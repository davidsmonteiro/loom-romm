package ro.davidmontei.loom.storage.engine

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ro.davidmontei.loom.storage.plan.PartCheck
import java.io.IOException
import java.io.InputStream
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
 * - The hash named by the part's check is computed while streaming. On resume the durable
 *   prefix is read back and hashed alongside the request, not before it, and the bytes that
 *   arrive meanwhile are hashed after it, so the digest still covers the file in order.
 */
class RangeDownloader(
    private val client: OkHttpClient,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val syncEveryBytes: Long = 64L * 1024 * 1024,
    private val syncEveryMs: Long = 30_000,
    private val crash: CrashPoints = CrashPoints.None,
    /** Opens a staged file to hash what is already in it. */
    private val readBack: (Path) -> InputStream = { Files.newInputStream(it) },
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
    ): Result = coroutineScope {
        Files.createDirectories(file.parent)
        // Never trust bytes past the last fsync: a crash can leave a torn tail.
        val onDisk = if (Files.exists(file)) Files.size(file) else 0L
        var offset = minOf(onDisk, part.durableBytes)
        if (offset > expected && expected > 0) offset = 0
        if (onDisk != offset) truncate(file, offset)

        if (expected > 0 && offset == expected) {
            return@coroutineScope Result(offset, hashAlgo?.let { hashPrefix(file, offset, it) }, part.validator)
        }

        // What is already on the card is hashed while RomM is asked for the rest, not before: read back
        // at the card's speed (about 90 MB/s on the Thor), it held a resumed download back by a second
        // for every 90 MB already downloaded.
        val hasher = hashAlgo?.let { Hasher(it) }
        val prefix = if (hasher != null && offset > 0) async { hashRange(file, 0, offset, hasher) } else null

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
                var activeHasher = hasher
                var pending = prefix
                when (response.code) {
                    206 -> {
                        val range = parseContentRange(response.header("Content-Range"))
                        if (range == null || range.first != offset || (expected > 0 && range.second != expected)) throw RestartPart()
                    }
                    200 -> if (offset > 0) {
                        // Range ignored: start over, reusing this response's body.
                        prefix?.cancelAndJoin()
                        truncate(file, 0)
                        position = 0
                        activeHasher = hashAlgo?.let { Hasher(it) }
                        pending = null
                    }
                    416 -> {
                        val total = parseContentRange(response.header("Content-Range"))?.second
                        if (total != null && total == offset) {
                            prefix?.await()
                            return@coroutineScope Result(offset, hasher?.hex(), part.validator)
                        }
                        throw RestartPart()
                    }
                    else -> throw httpFailure(response)
                }
                checkBody(response, part)
                val validator = part.validator ?: response.header("Last-Modified") ?: strongEtag(response)
                if (response.code == 200 && offset > 0) onDurable(0, validator)
                stream(response, file, position, expected, activeHasher, pending, validator, onDurable, onProgress)
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
        prefix: Deferred<Unit>?,
        validator: String?,
        onDurable: suspend (Long, String?) -> Unit,
        onProgress: (Long) -> Unit,
    ): Result {
        // Where [hasher] has got to: -1 while [prefix], the bytes before [start], is still being hashed.
        // What arrives meanwhile is only written, and read back from the file once the prefix is done.
        var hashedTo = if (prefix == null) start else -1L
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
                if (hasher != null) {
                    if (hashedTo < 0 && prefix!!.isCompleted) {
                        prefix.await()
                        hashedTo = start
                    }
                    if (hashedTo >= 0) {
                        if (hashedTo < written) hashRange(file, hashedTo, written, hasher)
                        hasher.update(buffer, 0, n)
                        hashedTo = written + n
                    }
                }
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
        if (hasher != null) {
            if (hashedTo < 0) {
                prefix!!.await()
                hashedTo = start
            }
            if (hashedTo < written) hashRange(file, hashedTo, written, hasher)
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

    /** Feeds bytes [from] to [to] of [file] to [hasher], stopping as soon as the download is. */
    private suspend fun hashRange(file: Path, from: Long, to: Long, hasher: Hasher) {
        readBack(file).use { input ->
            var skipped = 0L
            while (skipped < from) {
                val n = input.skip(from - skipped)
                if (n <= 0) throw IOException("${file.fileName} is shorter than the $from bytes already downloaded")
                skipped += n
            }
            val buf = ByteArray(1024 * 1024)
            var left = to - from
            while (left > 0) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("${file.fileName} is shorter than the $to bytes already downloaded")
                hasher.update(buf, 0, n)
                left -= n
            }
        }
    }

    private suspend fun hashPrefix(file: Path, length: Long, algo: String): String =
        Hasher(algo).also { hashRange(file, 0, length, it) }.hex()

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
