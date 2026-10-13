package ro.davidmontei.loom.data.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.DownloadDao
import ro.davidmontei.loom.data.db.DownloadJobEntity
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.NotPairedException
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.RommUrls
import ro.davidmontei.loom.romm.dto.RomDto
import ro.davidmontei.loom.storage.engine.JobEvent
import ro.davidmontei.loom.storage.engine.JobMachine
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.storage.engine.JobStatus
import ro.davidmontei.loom.storage.engine.PartRecord
import ro.davidmontei.loom.storage.engine.WaitReason
import ro.davidmontei.loom.storage.plan.ArchiveMember
import ro.davidmontei.loom.storage.plan.DownloadPlan
import ro.davidmontei.loom.storage.plan.DownloadPlanner
import ro.davidmontei.loom.storage.plan.NothingToDownloadException
import ro.davidmontei.loom.storage.plan.RomFileSnapshot
import ro.davidmontei.loom.storage.plan.RomSnapshot

/** Live transfer numbers for the queue screen; not persisted. */
data class LiveProgress(val bytesDone: Long, val bytesPerSecond: Long, val updatedAtMs: Long)

sealed interface EnqueueResult {
    data class Queued(val jobId: Long) : EnqueueResult
    data object AlreadyQueued : EnqueueResult
    data object AlreadyInstalled : EnqueueResult
    data class Failed(val message: String) : EnqueueResult
}

/**
 * What the UI and the headless entry points do to the queue. State changes go through
 * [JobMachine] and clear the job's lease, so a running worker notices and stops.
 */
class DownloadRepository(
    private val dao: DownloadDao,
    private val catalog: CatalogDao,
    private val server: ActiveServer,
    private val mapping: MappingRepository,
    private val settings: SettingsStore,
    private val volumes: StorageVolumes,
    private val kicker: QueueKicker,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val live = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, LiveProgress>> = live.asStateFlow()

    val jobs = dao.observeJobs()
    val recentInstalls = dao.observeRecentInstalls()

    suspend fun enqueue(romId: Int): EnqueueResult {
        dao.jobForRom(romId)?.let { existing ->
            if (existing.phase == JobPhase.FAILED.name) { retry(existing.id); return EnqueueResult.Queued(existing.id) }
            return EnqueueResult.AlreadyQueued
        }
        if (catalog.install(romId) != null) return EnqueueResult.AlreadyInstalled
        val romsRoot = settings.snapshot().romsRoot ?: return EnqueueResult.Failed("Choose a ROMs folder in Settings first")
        if (!volumes.hasAllFilesAccess()) return EnqueueResult.Failed("Loom needs all-files access to write to your ROMs folder")
        val volume = volumes.forPath(romsRoot) ?: return EnqueueResult.Failed("The storage holding $romsRoot isn't mounted")

        val rom = try {
            server.call { it.rom(romId) }
        } catch (e: NotPairedException) {
            return EnqueueResult.Failed("Pair Loom with your RomM server first")
        } catch (e: RommException) {
            return EnqueueResult.Failed(e.message ?: "Couldn't reach RomM")
        }
        val plan = try {
            planFor(rom)
        } catch (e: NothingToDownloadException) {
            return EnqueueResult.Failed(e.message ?: "Nothing to download")
        }
        val title = catalog.rom(romId)?.title ?: rom.name ?: rom.fsNameNoTags
        val now = nowMs()
        val id = dao.insertJobWithParts(
            DownloadJobEntity(
                romId = romId, platformId = rom.platformId, title = title, phase = JobPhase.QUEUED.name,
                planJson = EngineJson.encodeToString(DownloadPlan.serializer(), plan), fingerprint = plan.fingerprint,
                volumeUuid = volume.id, stagingRoot = volume.stagingRoot.path, romsRoot = romsRoot,
                bytesTotal = plan.totalBytes, queueOrder = now, updatedAt = now,
            ),
        ) { jobId -> partsFor(jobId, plan).map { it.toEntity() } }
        kicker.kick()
        return EnqueueResult.Queued(id)
    }

    suspend fun planFor(rom: RomDto): DownloadPlan =
        DownloadPlanner.plan(rom.toSnapshot(), mapping.currentMapper().resolve(rom.platformSlug, rom.platformFsSlug))

    fun partsFor(jobId: Long, plan: DownloadPlan) =
        plan.parts.map { PartRecord(jobId, it, RommUrls.romFileContentPath(it.romFileId)) }

    suspend fun pause(jobId: Long) = apply(jobId, JobEvent.Pause)

    suspend fun resume(jobId: Long) { if (apply(jobId, JobEvent.Resume)) kicker.kick() }

    suspend fun retry(jobId: Long) { if (apply(jobId, JobEvent.Retry)) kicker.kick() }

    /** Marks the job for cleanup; the worker removes its staging files and the row. */
    suspend fun cancel(jobId: Long) { if (apply(jobId, JobEvent.Cancel)) kicker.kick() }

    suspend fun pauseAll() = dao.observeJobsSnapshot().forEach { pause(it.id) }

    /** Called when the SD card comes back or the app opens: parked jobs rejoin the queue. */
    suspend fun resumeWaiting(reason: WaitReason) {
        var any = false
        for (job in dao.jobsIn(JobPhase.WAITING.name)) {
            if (job.waitReason == reason.name && apply(job.id, JobEvent.ConditionRestored)) any = true
        }
        if (any || dao.pendingWorkCount() > 0) kicker.kick()
    }

    /** Parks every queued job until the app is opened (Android refused a background start). */
    suspend fun parkQueued(reason: WaitReason) {
        for (job in dao.jobsIn(JobPhase.QUEUED.name)) {
            val to = JobStatus(JobPhase.WAITING, waitReason = reason)
            dao.setPhase(job.id, job.phase, to.phase.name, reason.name, null, null, true, nowMs())
        }
    }

    private suspend fun apply(jobId: Long, event: JobEvent): Boolean {
        val job = dao.job(jobId) ?: return false
        val from = job.status()
        if (!JobMachine.canApply(from, event)) return false
        val to = JobMachine.next(from, event)
        if (to == from) return true
        return dao.setPhase(
            job.id, job.phase, to.phase.name, to.waitReason?.name, to.failure?.code?.name, to.failure?.detail,
            to.failure?.retryable ?: true, nowMs(),
        ) == 1
    }

    /** Called by the worker with bytes on disk; keeps a smoothed speed for the UI. */
    fun reportProgress(jobId: Long, bytesDone: Long) {
        val now = nowMs()
        live.update { map ->
            val prev = map[jobId]
            if (prev != null && now - prev.updatedAtMs < 400) return@update map
            val instant = if (prev == null || now <= prev.updatedAtMs) 0L
            else ((bytesDone - prev.bytesDone).coerceAtLeast(0) * 1000) / (now - prev.updatedAtMs)
            val speed = if (prev == null || prev.bytesPerSecond == 0L) instant else (prev.bytesPerSecond * 7 + instant * 3) / 10
            map + (jobId to LiveProgress(bytesDone, speed, now))
        }
    }

    fun clearProgress(jobId: Long) = live.update { it - jobId }

    private suspend fun DownloadDao.observeJobsSnapshot(): List<DownloadJobEntity> =
        JobPhase.entries.flatMap { jobsIn(it.name) }
}

fun RomDto.toSnapshot() = RomSnapshot(
    id = id,
    fsName = fsName,
    fsNameNoExt = fsNameNoExt,
    fullPath = fullPath,
    hasMultipleFiles = hasMultipleFiles,
    hasNestedSingleFile = hasNestedSingleFile,
    updatedAt = updatedAt,
    files = files.map { f ->
        RomFileSnapshot(
            id = f.id, fileName = f.fileName, filePath = f.filePath, size = f.fileSizeBytes,
            crc = f.crcHash, md5 = f.md5Hash, sha1 = f.sha1Hash, category = f.category,
            archiveMembers = f.archiveMembers?.map { ArchiveMember(it.name, it.size, it.crcHash, it.md5Hash, it.sha1Hash) },
        )
    },
)
