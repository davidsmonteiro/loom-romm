package ro.davidmontei.loom.storage.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ro.davidmontei.loom.storage.plan.PartCheck
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Streams one ROM file to a `.part` file in the staging area, resuming from the last durable
 * byte with an HTTP Range request.
 *
 * - `Accept-Encoding: identity`, so byte offsets are offsets in the file on disk (OkHttp would
 *   otherwise negotiate gzip and drop Content-Length).
 * - 206 must start exactly at the requested offset and report the expected total; anything else
 *   restarts the part. A 200 to a ranged request (server or proxy ignored Range) truncates and
 *   keeps streaming the same response from byte 0.
 * - The file is fsync'd every [syncEveryBytes]/[syncEveryMs]; only then is the new
 *   `durableBytes` reported, so after a crash anything past it is truncated, never trusted.
 * - The hash named by the part's check is computed while streaming and re-primed from the
 *   durable prefix on resume.
 */
class RangeDownloader(
    private val client: OkHttpClient,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val syncEveryBytes: Long = 64L * 1024 * 1024,
    private val syncEveryMs: Long = 30_000,
    private val crash: CrashPoints = CrashPoints.None,
) {
    data class Result(val bytes: Long, val digest: String?, val validator: String?)

    /**
     * @param onDurable called after each fsync with the new durable length and validator.
     * @param onProgress called often (throttle in the UI) with bytes on disk so far.
     * @param isMounted re-checked when a local write fails, to tell "card removed" from "disk full".
     */
    suspend fun download(
        part: PartRecord,
        url: HttpUrl,
        file: Path,
        onDurable: suspend (durableBytes: Long, validator: String?) -> Unit,
        onProgress: (Long) -> Unit = {},
        isMounted: () -> Boolean = { true },
    ): Result = withContext(Dispatchers.IO) {
        val expected = part.plan.size
        val hashAlgo = (part.plan.check as? PartCheck.Hash)?.algo
        var restarts = 0
        var current = part
        var result: Result? = null
        while (result == null) {
            try {
                result = attempt(current, url, file, expected, hashAlgo, onDurable, onProgress)
            } catch (e: RestartPart) {
                if (++restarts > 2) throw EngineException.Transient("Server keeps returning inconsistent ranges")
                truncate(file, 0)
                onDurable(0, null)
                current = current.copy(durableBytes = 0, validator = null)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (e is LocalWriteFailure) throw (e.cause as IOException).classifyLocal(isMounted())
                throw EngineException.Transient(e.message ?: "Connection lost", e)
            }
        }
        result
    }

    private suspend fun attempt(
        part: PartRecord,
        url: HttpUrl,
        file: Path,
        expected: Long,
        hashAlgo: String?,
        onDurable: suspend (Long, String?) -> Unit,
        onProgress: (Long) -> Unit,
    ): Result {
        Files.createDirectories(file.parent)
        // Never trust bytes past the last fsync: a crash can leave a torn tail.
        val onDisk = if (Files.exists(file)) Files.size(file) else 0L
        var offset = minOf(onDisk, part.durableBytes)
        if (offset > expected && expected > 0) offset = 0
        if (onDisk != offset) truncate(file, offset)

        if (expected > 0 && offset == expected) {
            return Result(offset, hashAlgo?.let { hashPrefix(file, offset, it) }, part.validator)
        }

        val hasher = hashAlgo?.let { Hasher(it) }
        if (hasher != null && offset > 0) primeFrom(file, offset, hasher)

        val request = Request.Builder().url(url)
            .header("Accept-Encoding", "identity")
            .apply {
                if (offset > 0) {
                    header("Range", "bytes=$offset-")
                    part.validator?.let { header("If-Range", it) }
                }
            }
            .build()

        val call = client.newCall(request)
        val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                var position = offset
                when (response.code) {
                    206 -> {
                        val range = parseContentRange(response.header("Content-Range"))
                        if (range == null || range.first != offset || (expected > 0 && range.second != expected)) throw RestartPart()
                    }
                    200 -> if (offset > 0) {
                        // Range ignored: start over, reusing this response's body.
                        truncate(file, 0)
                        position = 0
                    }
                    416 -> {
                        val total = parseContentRange(response.header("Content-Range"))?.second
                        if (total != null && total == offset) return Result(offset, hashAlgo?.let { hashPrefix(file, offset, it) }, part.validator)
                        throw RestartPart()
                    }
                    else -> throw httpFailure(response)
                }
                checkBody(response, part)
                val validator = part.validator ?: response.header("Last-Modified") ?: strongEtag(response)
                val activeHasher = if (response.code == 200 && offset > 0) hashAlgo?.let { Hasher(it) } else hasher
                if (response.code == 200 && offset > 0) onDurable(0, validator)
                return stream(response, file, position, expected, activeHasher, validator, onDurable, onProgress)
            }
        } finally {
            cancelHook?.dispose()
        }
    }

    private suspend fun stream(
        response: Response,
        file: Path,
        start: Long,
        expected: Long,
        hasher: Hasher?,
        validator: String?,
        onDurable: suspend (Long, String?) -> Unit,
        onProgress: (Long) -> Unit,
    ): Result {
        val source = response.body.source()
        val buffer = ByteArray(256 * 1024)
        var written = start
        var lastSyncBytes = start
        var lastSyncAt = clockMs()
        val channel = try {
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            throw LocalWriteFailure(e)
        }
        channel.use { ch ->
            ch.position(start)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = source.read(buffer)
                if (n == -1) break
                try {
                    val bb = ByteBuffer.wrap(buffer, 0, n)
                    while (bb.hasRemaining()) ch.write(bb)
                } catch (e: IOException) {
                    throw LocalWriteFailure(e)
                }
                hasher?.update(buffer, 0, n)
                written += n
                if (expected > 0 && written > expected) throw RestartPart()
                onProgress(written)
                val now = clockMs()
                if (written - lastSyncBytes >= syncEveryBytes || now - lastSyncAt >= syncEveryMs) {
                    try { ch.force(false) } catch (e: IOException) { throw LocalWriteFailure(e) }
                    onDurable(written, validator)
                    lastSyncBytes = written
                    lastSyncAt = now
                    crash.hit(CrashPoint.AFTER_PART_BYTES)
                }
            }
            try { ch.force(true) } catch (e: IOException) { throw LocalWriteFailure(e) }
        }
        if (expected > 0 && written != expected) {
            throw EngineException.Transient("Connection closed after $written of $expected bytes")
        }
        onDurable(written, validator)
        return Result(written, hasher?.hex(), validator)
    }

    private fun checkBody(response: Response, part: PartRecord) {
        val encoding = response.header("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            throw EngineException.Fatal(Failure(FailureCode.PROXY_PAGE, "The server compressed the download ($encoding); check your proxy settings"))
        }
        val type = response.header("Content-Type").orEmpty().lowercase()
        val expectsHtml = part.plan.relPath.lowercase().let { it.endsWith(".html") || it.endsWith(".htm") }
        if (type.startsWith("text/html") && !expectsHtml) {
            throw EngineException.Fatal(Failure(FailureCode.PROXY_PAGE,
                "Got a web page instead of the game file. A proxy or login page (e.g. Cloudflare Access) is in the way."))
        }
    }

    private fun httpFailure(response: Response): EngineException = when (response.code) {
        401, 403 -> EngineException.Fatal(Failure(FailureCode.AUTH, "RomM refused the download (HTTP ${response.code}). Pair Loom again.", retryable = true))
        404 -> EngineException.Fatal(Failure(FailureCode.SERVER_FILE_MISSING, "RomM no longer has this file. Sync the library and try again."))
        408, 425, 429, in 500..599 -> EngineException.Transient("HTTP ${response.code}")
        else -> EngineException.Fatal(Failure(FailureCode.INTERNAL, "Unexpected HTTP ${response.code}"))
    }

    private fun strongEtag(response: Response): String? =
        response.header("ETag")?.takeUnless { it.startsWith("W/") }

    private fun truncate(file: Path, size: Long) {
        if (!Files.exists(file)) return
        FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(size) }
    }

    private fun primeFrom(file: Path, length: Long, hasher: Hasher) {
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1024 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                hasher.update(buf, 0, n)
                left -= n
            }
        }
    }

    private fun hashPrefix(file: Path, length: Long, algo: String): String =
        Hasher(algo).also { primeFrom(file, length, it) }.hex()

    private class RestartPart : IOException("restart part")
    private class LocalWriteFailure(cause: IOException) : IOException(cause.message, cause)

    companion object {
        private val CONTENT_RANGE = Regex("""bytes\s+(?:(\d+)-\d+|\*)/(\d+|\*)""")

        /** (start, total) from `bytes 100-199/200`, or start -1 for an unsatisfied range; -1 when unknown. */
        fun parseContentRange(header: String?): Pair<Long, Long>? {
            val m = CONTENT_RANGE.find(header ?: return null) ?: return null
            val start = m.groupValues[1].toLongOrNull() ?: -1
            val total = m.groupValues[2].toLongOrNull() ?: -1
            return start to total
        }
    }
}
