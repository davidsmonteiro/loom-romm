package ro.davidmontei.loom.storage.engine

import com.google.common.truth.Truth.assertWithMessage
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.RecordedRequest
import okio.BufferedSink
import ro.davidmontei.loom.storage.plan.DownloadPlan
import ro.davidmontei.loom.storage.plan.EntryKind
import ro.davidmontei.loom.storage.plan.NameSanitizer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Serves ROM files like RomM's nginx: honours `Range: bytes=n-`, can misbehave on request. */
class FakeRommFiles : Dispatcher() {
    val files = mutableMapOf<Int, ByteArray>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile var ignoreRange = false
    /** Next response for this file drops the connection after this many bytes. */
    val dropAfter = mutableMapOf<Int, Int>()
    /** Serve these files with one flipped byte, this many more times. */
    val corruptTimes = mutableMapOf<Int, Int>()
    @Volatile var forcedStatus: Int? = null
    @Volatile var contentType = "application/octet-stream"
    @Volatile var contentEncoding: String? = null
    /** Bytes per 10 ms, to make downloads slow enough to pause mid-way. */
    @Volatile var throttleBytesPer10Ms: Long? = null

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        forcedStatus?.let { return MockResponse.Builder().code(it).body("""{"detail":"nope"}""").build() }
        val id = Regex("""/api/roms/(\d+)/files/content/""").find(request.url.encodedPath)?.groupValues?.get(1)?.toInt()
            ?: return MockResponse.Builder().code(404).build()
        var data = files[id] ?: return MockResponse.Builder().code(404).body("""{"detail":"File not found"}""").build()
        if ((corruptTimes[id] ?: 0) > 0) {
            corruptTimes[id] = corruptTimes.getValue(id) - 1
            data = data.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        }
        val start = request.headers["Range"]?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toLong() }
            ?.takeUnless { ignoreRange }
        val builder = MockResponse.Builder()
            .setHeader("Content-Type", contentType)
            .setHeader("Last-Modified", "Wed, 01 Oct 2026 10:00:00 GMT")
            .setHeader("Accept-Ranges", "bytes")
        contentEncoding?.let { builder.setHeader("Content-Encoding", it) }
        if (start != null && start >= data.size) {
            return builder.code(416).setHeader("Content-Range", "bytes */${data.size}").build()
        }
        val from = (start ?: 0L).toInt()
        val slice = data.copyOfRange(from, data.size)
        if (start != null) builder.code(206).setHeader("Content-Range", "bytes $from-${data.size - 1}/${data.size}") else builder.code(200)
        throttleBytesPer10Ms?.let { builder.throttleBody(it, 10, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val cut = dropAfter.remove(id)
        return builder.body(object : MockResponseBody {
            override val contentLength: Long = slice.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                if (cut == null) { sink.write(slice); return }
                sink.write(slice, 0, minOf(cut, slice.size))
                sink.flush()
                throw IOException("simulated connection drop")
            }
        }).build()
    }

    fun rangeHeaders(fileId: Int): List<String?> =
        requests.filter { it.url.encodedPath.contains("/roms/$fileId/") }.map { it.headers["Range"] }
}

/** JobStore in memory, with the same compare-and-set rules as the Room implementation. */
class InMemoryJobStore : JobStore {
    val jobs = mutableMapOf<Long, JobRecord>()
    val partsByJob = mutableMapOf<Long, MutableList<PartRecord>>()
    val installs = mutableMapOf<Int, InstallResult>()
    private var nextId = 1L

    @Synchronized fun add(job: (Long) -> JobRecord, parts: (Long) -> List<PartRecord>): Long {
        val id = nextId++
        jobs[id] = job(id)
        partsByJob[id] = parts(id).toMutableList()
        return id
    }

    override suspend fun load(jobId: Long) = synchronized(this) { jobs[jobId] }
    override suspend fun parts(jobId: Long) = synchronized(this) { partsByJob[jobId].orEmpty().toList() }

    override suspend fun savePart(part: PartRecord): Unit = synchronized(this) {
        val list = partsByJob[part.jobId] ?: return
        val i = list.indexOfFirst { it.romFileId == part.romFileId }
        if (i >= 0) list[i] = part
    }

    override suspend fun transition(jobId: Long, lease: String, from: JobPhase, to: JobStatus, checksumRetries: Int?): Boolean =
        synchronized(this) {
            val job = jobs[jobId] ?: return false
            if (job.lease != lease || job.status.phase != from) return false
            jobs[jobId] = job.copy(status = to, lease = if (to.phase.isActive) lease else null,
                checksumRetries = checksumRetries ?: job.checksumRetries)
            true
        }

    override suspend fun saveProgress(jobId: Long, bytesDone: Long): Unit = synchronized(this) {
        jobs[jobId]?.let { jobs[jobId] = it.copy(bytesDone = bytesDone) }
    }

    override suspend fun setFinalEntry(jobId: Long, relPath: String, kind: EntryKind): Unit = synchronized(this) {
        jobs[jobId]?.let { jobs[jobId] = it.copy(finalRelPath = relPath, finalKind = kind) }
    }

    override suspend fun complete(jobId: Long, lease: String, install: InstallResult): Boolean = synchronized(this) {
        val job = jobs[jobId] ?: return false
        if (job.lease != lease) return false
        installs[install.romId] = install
        jobs.remove(jobId); partsByJob.remove(jobId)
        true
    }

    override suspend fun isPathClaimed(volumeId: String, pathKey: String, exceptRomId: Int): Boolean = synchronized(this) {
        installs.values.any { it.romId != exceptRomId && it.volumeId == volumeId && NameSanitizer.key(it.relPath) == pathKey } ||
            jobs.values.any { it.romId != exceptRomId && it.volumeId == volumeId && it.finalRelPath?.let(NameSanitizer::key) == pathKey }
    }

    /** What the queue does: QUEUED → first unfinished phase, under a fresh lease. */
    @Synchronized fun claim(jobId: Long, resumeAt: JobPhase): String {
        val lease = UUID.randomUUID().toString()
        val job = jobs.getValue(jobId)
        jobs[jobId] = job.copy(status = JobMachine.next(job.status, JobEvent.Claimed(resumeAt)), lease = lease, attempt = job.attempt + 1)
        return lease
    }

    /** What the queue does on startup for a job whose worker died: back to QUEUED. */
    @Synchronized fun recoverStale(jobId: Long) {
        val job = jobs.getValue(jobId)
        if (job.status.phase.isActive) jobs[jobId] = job.copy(status = JobMachine.next(job.status, JobEvent.Interrupted), lease = null)
    }

    @Synchronized fun apply(jobId: Long, event: JobEvent) {
        val job = jobs.getValue(jobId)
        jobs[jobId] = job.copy(status = JobMachine.next(job.status, event), lease = null)
    }
}

object TestBytes {
    fun random(size: Int, seed: Int): ByteArray = java.util.Random(seed.toLong()).let { r -> ByteArray(size).also(r::nextBytes) }
    fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
    fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    fun crc(b: ByteArray) = "%08x".format(CRC32().apply { update(b) }.value)

    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
    }.toByteArray()
}

/**
 * The invariant behind the acceptance criterion "killing the app mid-download doesn't leave a file
 * Cocoon can see": the ROMs root only ever holds complete entries, nothing hidden or partial.
 */
fun assertRomsRootClean(romsRoot: Path, expected: Set<String>? = null) {
    if (!Files.exists(romsRoot)) return
    val all = Files.walk(romsRoot).use { s -> s.iterator().asSequence().filter { it != romsRoot }.map { romsRoot.relativize(it).toString() }.toList() }
    for (path in all) {
        val name = path.substringAfterLast('/')
        assertWithMessage("no hidden or partial file in the ROMs root: $path").that(
            name.startsWith(".") || name.endsWith(".part") || name.endsWith(".loomtmp"),
        ).isFalse()
    }
    if (expected != null) {
        val topEntries = all.filter { it.count { c -> c == '/' } == 1 }.toSet()
        assertWithMessage("entries in system folders").that(topEntries).containsExactlyElementsIn(expected)
    }
}

fun DownloadPlan.toParts(jobId: Long) = parts.map { PartRecord(jobId, it, "api/roms/${it.romFileId}/files/content/file") }
