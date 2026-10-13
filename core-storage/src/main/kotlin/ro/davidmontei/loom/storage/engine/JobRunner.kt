package ro.davidmontei.loom.storage.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import ro.davidmontei.loom.storage.plan.EntryKind
import ro.davidmontei.loom.storage.plan.LayoutDecider
import ro.davidmontei.loom.storage.plan.NameSanitizer
import ro.davidmontei.loom.storage.plan.PartCheck
import ro.davidmontei.loom.storage.plan.PayloadFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/** How a run ended, from the queue's point of view. */
sealed interface RunOutcome {
    data class Installed(val install: InstallResult) : RunOutcome
    /** The job moved to [status] (failed, waiting, back in the queue after a checksum retry…). */
    data class Parked(val status: JobStatus) : RunOutcome
    /** Someone else changed the job (pause, cancel) or it no longer exists. */
    data object Released : RunOutcome
}

/** Replayable record of how the staged files become the final entry (written before any move). */
@Serializable
internal data class AssemblyManifest(
    val entryName: String,
    val kind: EntryKind,
    val moves: List<Move>,
    val generated: List<Generated>,
) {
    @Serializable data class Move(val from: String, val to: String)
    @Serializable data class Generated(val to: String, val content: String)
}

/**
 * Drives one claimed job through DOWNLOADING → VERIFYING → (EXTRACTING) → PROMOTING. Each phase
 * is idempotent and derives what's left from persisted part states and the staging folder, so
 * after process death the queue just claims the job again and calls [run].
 *
 * Staging layout, per job: `<stagingRoot>/jobs/<id>-<generation>/` with `dl/` (parts), `x/<file>/`
 * (unpacked archives), `out/<entry>` (the install being assembled) and `assembly.json`.
 */
class JobRunner(
    private val store: JobStore,
    private val downloader: RangeDownloader,
    private val baseUrl: suspend () -> HttpUrl,
    private val space: SpaceProbe,
    private val files: FileOps = NioFileOps(),
    private val archives: Archives = Archives(),
    private val crash: CrashPoints = CrashPoints.None,
    private val isMounted: (JobRecord) -> Boolean = { Files.isDirectory(Paths.get(it.romsRoot)) },
    private val onProgress: (jobId: Long, bytesDone: Long) -> Unit = { _, _ -> },
    private val maxTransientRetries: Int = 5,
    private val backoffMs: (attempt: Int) -> Long = { (2_000L shl (it - 1)).coerceAtMost(60_000) },
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val promoter = Promoter(files, crash)
    private val json = Json { ignoreUnknownKeys = true }

    /** The first phase whose work isn't done yet, judged from persisted state. */
    suspend fun resumePhase(job: JobRecord): JobPhase {
        val parts = store.parts(job.id)
        return when {
            job.finalRelPath != null || Files.exists(manifestPath(job)) -> JobPhase.PROMOTING
            parts.any { it.state < PartState.DOWNLOADED } -> JobPhase.DOWNLOADING
            parts.any { it.state == PartState.DOWNLOADED } -> JobPhase.VERIFYING
            parts.any { it.plan.extract && it.state != PartState.EXTRACTED } -> JobPhase.EXTRACTING
            else -> JobPhase.PROMOTING
        }
    }

    suspend fun run(jobId: Long, lease: String): RunOutcome {
        while (true) {
            val job = store.load(jobId) ?: return RunOutcome.Released
            if (job.lease != lease || !job.status.phase.isActive) return RunOutcome.Released
            val phase = job.status.phase
            val event: JobEvent = try {
                when (phase) {
                    JobPhase.DOWNLOADING -> { download(job); JobEvent.DownloadsComplete }
                    JobPhase.VERIFYING -> JobEvent.Verified(extract = verify(job))
                    JobPhase.EXTRACTING -> { extract(job); JobEvent.Extracted }
                    JobPhase.PROMOTING -> return promote(job, lease)
                    else -> return RunOutcome.Released
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException.Corrupt) {
                resetPart(job, e.romFileId)
                JobEvent.ChecksumMismatch(firstTime = job.checksumRetries == 0, detail = e.message)
            } catch (e: EngineException.Fatal) {
                JobEvent.Failed(e.failure)
            } catch (e: EngineException.StorageGone) {
                JobEvent.Wait(WaitReason.STORAGE)
            } catch (e: EngineException.Transient) {
                JobEvent.Failed(Failure(FailureCode.NETWORK, e.message, retryable = true))
            } catch (e: Exception) {
                JobEvent.Failed(Failure(FailureCode.INTERNAL, e.message ?: e.javaClass.simpleName))
            }
            val next = JobMachine.next(job.status, event)
            val retries = if (event is JobEvent.ChecksumMismatch) job.checksumRetries + 1 else null
            if (!store.transition(job.id, lease, phase, next, retries)) return RunOutcome.Released
            if (!next.phase.isActive) return RunOutcome.Parked(next)
        }
    }

    /** Deletes a job's staging folder (cancel, or a re-plan after the server's files changed). */
    fun discardStaging(job: JobRecord) = files.deleteRecursively(jobDir(job))

    // --- DOWNLOADING ---

    private suspend fun download(job: JobRecord) {
        checkSpace(job, store.parts(job.id), includeDownload = true)
        val dl = jobDir(job).resolve("dl")
        files.createDirectories(dl)
        files.writeText(Paths.get(job.stagingRoot).resolve(".nomedia"), "")
        for (initial in store.parts(job.id).filter { it.state < PartState.DOWNLOADED }) {
            var part = initial
            val doneElsewhere = store.partsDoneBytes(job.id, except = part.romFileId)
            var attempt = 0
            while (true) {
                try {
                    val url = baseUrl().resolve(part.relUrl) ?: error("Bad part URL ${part.relUrl}")
                    val result = downloader.download(
                        part, url, dl.resolve(part.stagingName),
                        onDurable = { bytes, validator ->
                            part = part.copy(durableBytes = bytes, validator = validator, state = PartState.PARTIAL)
                            store.savePart(part)
                            store.saveProgress(job.id, doneElsewhere + bytes)
                        },
                        onProgress = { onProgress(job.id, doneElsewhere + it) },
                        isMounted = { isMounted(job) },
                    )
                    part = part.copy(durableBytes = result.bytes, validator = result.validator, digest = result.digest,
                        state = PartState.DOWNLOADED)
                    store.savePart(part)
                    break
                } catch (e: EngineException.Transient) {
                    if (!isMounted(job)) throw EngineException.StorageGone(e)
                    if (++attempt > maxTransientRetries) throw e
                    delay(backoffMs(attempt))
                    part = store.parts(job.id).first { it.romFileId == part.romFileId }
                }
            }
        }
    }

    // --- VERIFYING ---

    /** Verifies every downloaded part; returns true when an archive still has to be unpacked. */
    private suspend fun verify(job: JobRecord): Boolean {
        val dl = jobDir(job).resolve("dl")
        for (part in store.parts(job.id).filter { it.state == PartState.DOWNLOADED }) {
            val file = dl.resolve(part.stagingName)
            if (!files.exists(file)) throw EngineException.Corrupt(part.romFileId, "Downloaded file disappeared")
            val size = Files.size(file)
            if (part.plan.size > 0 && size != part.plan.size) {
                throw EngineException.Corrupt(part.romFileId, "Size is $size, RomM says ${part.plan.size}")
            }
            when (val check = part.plan.check) {
                is PartCheck.Hash -> if (!check.hex.equals(part.digest, ignoreCase = true)) {
                    throw EngineException.Corrupt(part.romFileId, "${check.algo.uppercase()} doesn't match RomM's")
                }
                is PartCheck.ArchiveMembers, PartCheck.ArchiveIntegrity ->
                    // Unpacked archives are checked entry by entry while extracting.
                    if (!part.plan.extract) archives.verify(file, check, part.romFileId)
                PartCheck.SizeOnly -> Unit
            }
            store.savePart(part.copy(state = PartState.VERIFIED))
        }
        return store.parts(job.id).any { it.plan.extract && it.state != PartState.EXTRACTED }
    }

    // --- EXTRACTING ---

    private suspend fun extract(job: JobRecord) {
        val parts = store.parts(job.id)
        checkSpace(job, parts, includeDownload = false)
        for (part in parts.filter { it.plan.extract && it.state == PartState.VERIFIED }) {
            val archive = jobDir(job).resolve("dl").resolve(part.stagingName)
            val dest = extractDir(job, part)
            files.deleteRecursively(dest)
            try {
                archives.extract(archive, dest, part.plan.check, part.romFileId)
            } catch (e: java.io.IOException) {
                throw e.classifyLocal(isMounted(job))
            }
            files.deleteRecursively(archive) // frees the space before the next archive
            store.savePart(part.copy(state = PartState.EXTRACTED))
        }
    }

    // --- PROMOTING ---

    private suspend fun promote(job: JobRecord, lease: String): RunOutcome {
        val event: JobEvent
        try {
            val manifest = loadOrWriteManifest(job)
            val out = jobDir(job).resolve("out")
            var moved = 0
            for (m in manifest.moves) {
                val from = Paths.get(m.from); val to = Paths.get(m.to)
                if (files.exists(from) && !files.exists(to)) {
                    files.createDirectories(to.parent)
                    files.atomicMove(from, to)
                    if (++moved == 1) crash.hit(CrashPoint.MID_ASSEMBLY)
                }
            }
            manifest.generated.forEach { files.writeText(Paths.get(it.to), it.content) }

            val relPath = "${job.plan.systemFolder}/${manifest.entryName}"
            val staged = out.resolve(manifest.entryName)
            val target = Paths.get(job.romsRoot).resolve(job.plan.systemFolder).resolve(manifest.entryName)
            when (promoter.promote(staged, target)) {
                PromoteOutcome.PROMOTED, PromoteOutcome.ALREADY_PROMOTED -> {
                    val install = InstallResult(
                        romId = job.romId, platformId = job.platformId, volumeId = job.volumeId, relPath = relPath,
                        kind = manifest.kind, bytes = files.sizeOf(target), verifiedBy = verifiedBy(job),
                        fingerprint = job.plan.fingerprint,
                    )
                    if (!store.complete(job.id, lease, install)) return RunOutcome.Released
                    files.deleteRecursively(jobDir(job))
                    return RunOutcome.Installed(install)
                }
                PromoteOutcome.DEST_EXISTS -> event = JobEvent.Failed(Failure(FailureCode.DEST_EXISTS,
                    "$relPath already exists on the SD card and wasn't installed by Loom. Remove it, or sync to let Loom adopt it."))
                PromoteOutcome.MISSING -> {
                    store.parts(job.id).forEach { store.savePart(it.copy(state = PartState.PENDING, durableBytes = 0, digest = null)) }
                    files.deleteRecursively(jobDir(job))
                    event = JobEvent.Failed(Failure(FailureCode.INTERNAL, "The staged files were lost; retry to download again"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            return park(job, lease, if (!isMounted(job)) JobEvent.Wait(WaitReason.STORAGE) else JobEvent.Failed(
                Failure(FailureCode.INTERNAL, e.message ?: "Couldn't move the game into place"),
            ))
        }
        return park(job, lease, event)
    }

    private suspend fun park(job: JobRecord, lease: String, event: JobEvent): RunOutcome {
        val next = JobMachine.next(job.status, event)
        return if (store.transition(job.id, lease, job.status.phase, next)) RunOutcome.Parked(next) else RunOutcome.Released
    }

    private suspend fun loadOrWriteManifest(job: JobRecord): AssemblyManifest {
        val path = manifestPath(job)
        if (Files.exists(path)) {
            val manifest = json.decodeFromString(AssemblyManifest.serializer(), String(Files.readAllBytes(path), Charsets.UTF_8))
            if (job.finalRelPath == null) store.setFinalEntry(job.id, "${job.plan.systemFolder}/${manifest.entryName}", manifest.kind)
            return manifest
        }
        val parts = store.parts(job.id)
        val sources = mutableMapOf<String, Path>()
        val payload = parts.flatMap { part ->
            if (part.state == PartState.EXTRACTED) {
                val dir = extractDir(job, part)
                Files.walk(dir).use { s -> s.iterator().asSequence().filter { it.isRegularFile() }.toList() }.map { file ->
                    val rel = file.relativeTo(dir).toString().replace('\\', '/')
                    val key = if (parts.size > 1) "${part.romFileId}/$rel" else rel
                    sources[key] = file
                    PayloadFile(key, Files.size(file), unpacked = true)
                }
            } else {
                sources[part.plan.relPath] = jobDir(job).resolve("dl").resolve(part.stagingName)
                listOf(PayloadFile(part.plan.relPath, part.plan.size))
            }
        }
        val layout = LayoutDecider.decide(job.plan.entryStem, payload, job.plan.multiDisc, job.plan.systemFolder) { rel ->
            sources[rel]?.takeIf { Files.size(it) <= 1_048_576 }?.let { String(Files.readAllBytes(it), Charsets.ISO_8859_1) }
        }
        val entryName = resolveCollision(job, layout.entryName, layout.kind)
        val entryRoot = jobDir(job).resolve("out").resolve(entryName)
        val manifest = AssemblyManifest(
            entryName = entryName,
            kind = layout.kind,
            moves = layout.placements.map { p ->
                val to = if (p.to.isEmpty()) entryRoot else entryRoot.resolve(p.to)
                AssemblyManifest.Move(sources.getValue(p.from).toString(), to.toString())
            },
            generated = layout.generated.map { AssemblyManifest.Generated(entryRoot.resolve(it.to).toString(), it.content) },
        )
        files.createDirectories(path.parent)
        val tmp = path.resolveSibling("assembly.json.tmp")
        Files.write(tmp, json.encodeToString(AssemblyManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        crash.hit(CrashPoint.AFTER_ASSEMBLY_MANIFEST)
        store.setFinalEntry(job.id, "${job.plan.systemFolder}/$entryName", layout.kind)
        return manifest
    }

    /** Another RomM ROM already installed under this name gets a stable "(romm-<id>)" suffix. */
    private suspend fun resolveCollision(job: JobRecord, name: String, kind: EntryKind): String {
        val key = NameSanitizer.key("${job.plan.systemFolder}/$name")
        return if (store.isPathClaimed(job.volumeId, key, job.romId)) {
            NameSanitizer.withSuffix(name, job.romId, kind == EntryKind.DIRECTORY)
        } else name
    }

    private suspend fun verifiedBy(job: JobRecord): String {
        val checks = store.parts(job.id).map { it.plan.check }
        return when {
            checks.isEmpty() -> "NONE"
            checks.all { it is PartCheck.Hash } -> (checks.first() as PartCheck.Hash).algo.uppercase().let { if (it == "CRC32") "CRC32" else it }
            checks.any { it is PartCheck.ArchiveMembers } -> "ARCHIVE_MEMBERS"
            checks.any { it is PartCheck.ArchiveIntegrity } -> "ARCHIVE_CRC"
            checks.any { it is PartCheck.Hash } -> (checks.first { it is PartCheck.Hash } as PartCheck.Hash).algo.uppercase()
            else -> "SIZE"
        }
    }

    // --- helpers ---

    private suspend fun checkSpace(job: JobRecord, parts: List<PartRecord>, includeDownload: Boolean) {
        val remainingDownload = if (includeDownload) parts.filter { it.state < PartState.DOWNLOADED }.sumOf { it.plan.size - it.durableBytes } else 0
        val unpack = parts.filter { it.plan.extract && it.state != PartState.EXTRACTED }.sumOf { part ->
            (part.plan.check as? PartCheck.ArchiveMembers)?.members?.sumOf { it.size } ?: (part.plan.size * 3)
        }
        val needed = remainingDownload + unpack
        if (needed <= 0) return
        val free = try { space.freeBytes(Paths.get(job.stagingRoot)) } catch (e: Exception) { throw EngineException.StorageGone(e) }
        val margin = maxOf(256L * 1024 * 1024, needed / 100)
        if (free < needed + margin) {
            throw EngineException.Fatal(Failure(FailureCode.INSUFFICIENT_SPACE,
                "Needs ${formatBytes(needed + margin)} free on the SD card; ${formatBytes(free)} available. Offload something and retry."))
        }
    }

    private suspend fun resetPart(job: JobRecord, romFileId: Int) {
        val part = store.parts(job.id).firstOrNull { it.romFileId == romFileId } ?: return
        files.deleteRecursively(jobDir(job).resolve("dl").resolve(part.stagingName))
        files.deleteRecursively(extractDir(job, part))
        store.savePart(part.copy(state = PartState.PENDING, durableBytes = 0, digest = null, validator = null))
    }

    private suspend fun JobStore.partsDoneBytes(jobId: Long, except: Int): Long =
        parts(jobId).filter { it.romFileId != except }.sumOf { if (it.state >= PartState.DOWNLOADED) it.plan.size else it.durableBytes }

    private fun jobDir(job: JobRecord): Path = Paths.get(job.stagingRoot).resolve("jobs").resolve("${job.id}-${job.generation}")
    private fun manifestPath(job: JobRecord): Path = jobDir(job).resolve("assembly.json")
    private fun extractDir(job: JobRecord, part: PartRecord): Path = jobDir(job).resolve("x").resolve(part.romFileId.toString())
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return if (value >= 100) "%.0f %s".format(value, units[i]) else "%.1f %s".format(value, units[i])
}
