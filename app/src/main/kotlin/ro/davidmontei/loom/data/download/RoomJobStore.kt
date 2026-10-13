package ro.davidmontei.loom.data.download

import kotlinx.serialization.json.Json
import ro.davidmontei.loom.data.db.DownloadDao
import ro.davidmontei.loom.data.db.DownloadJobEntity
import ro.davidmontei.loom.data.db.DownloadPartEntity
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.InstallKind
import ro.davidmontei.loom.data.db.InstallSource
import ro.davidmontei.loom.data.db.VerifiedBy
import ro.davidmontei.loom.storage.engine.Failure
import ro.davidmontei.loom.storage.engine.FailureCode
import ro.davidmontei.loom.storage.engine.InstallResult
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.storage.engine.JobRecord
import ro.davidmontei.loom.storage.engine.JobStatus
import ro.davidmontei.loom.storage.engine.JobStore
import ro.davidmontei.loom.storage.engine.PartRecord
import ro.davidmontei.loom.storage.engine.PartState
import ro.davidmontei.loom.storage.engine.WaitReason
import ro.davidmontei.loom.storage.plan.DownloadPlan
import ro.davidmontei.loom.storage.plan.EntryKind
import ro.davidmontei.loom.storage.plan.NameSanitizer
import ro.davidmontei.loom.storage.plan.PartPlan

internal val EngineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** The engine's [JobStore] on Room. Every runner write is a compare-and-set on lease + phase. */
class RoomJobStore(
    private val dao: DownloadDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : JobStore {

    override suspend fun load(jobId: Long): JobRecord? = dao.job(jobId)?.toRecord()

    override suspend fun parts(jobId: Long): List<PartRecord> = dao.parts(jobId).map { it.toRecord() }

    override suspend fun savePart(part: PartRecord) = dao.upsertPart(part.toEntity())

    override suspend fun transition(jobId: Long, lease: String, from: JobPhase, to: JobStatus, checksumRetries: Int?): Boolean =
        dao.casPhase(
            id = jobId, lease = lease, fromPhase = from.name, toPhase = to.phase.name,
            waitReason = to.waitReason?.name, failureCode = to.failure?.code?.name, failureDetail = to.failure?.detail,
            retryable = to.failure?.retryable ?: true, keepLease = to.phase.isActive,
            checksumRetries = checksumRetries, now = nowMs(),
        ) == 1

    override suspend fun saveProgress(jobId: Long, bytesDone: Long) = dao.saveProgress(jobId, bytesDone)

    override suspend fun setFinalEntry(jobId: Long, relPath: String, kind: EntryKind) =
        dao.setFinalEntry(jobId, relPath, NameSanitizer.key(relPath), kind.name)

    override suspend fun complete(jobId: Long, lease: String, install: InstallResult): Boolean {
        val romsRoot = dao.job(jobId)?.romsRoot ?: return false
        return dao.completeJob(jobId, lease, install.toEntity(romsRoot, nowMs()))
    }

    override suspend fun isPathClaimed(volumeId: String, pathKey: String, exceptRomId: Int): Boolean =
        dao.installsClaimingPath(volumeId, pathKey, exceptRomId) > 0 || dao.jobsClaimingPath(volumeId, pathKey, exceptRomId) > 0
}

fun DownloadJobEntity.status(): JobStatus = JobStatus(
    phase = JobPhase.valueOf(phase),
    waitReason = waitReason?.let(WaitReason::valueOf),
    failure = failureCode?.let { Failure(FailureCode.valueOf(it), failureDetail, failureRetryable) },
)

fun DownloadJobEntity.plan(): DownloadPlan = EngineJson.decodeFromString(DownloadPlan.serializer(), planJson)

fun DownloadJobEntity.toRecord() = JobRecord(
    id = id, romId = romId, platformId = platformId, title = title, status = status(), lease = leaseId,
    attempt = attempt, generation = generation, checksumRetries = checksumRetries, plan = plan(),
    volumeId = volumeUuid, stagingRoot = stagingRoot, romsRoot = romsRoot, finalRelPath = finalRelPath,
    finalKind = finalKind?.let(EntryKind::valueOf), bytesDone = bytesDone,
)

fun DownloadPartEntity.toRecord() = PartRecord(
    jobId = jobId,
    plan = EngineJson.decodeFromString(PartPlan.serializer(), planJson),
    relUrl = relUrl, durableBytes = durableBytes, validator = validator, digest = digest,
    state = PartState.valueOf(state),
)

fun PartRecord.toEntity() = DownloadPartEntity(
    jobId = jobId, romFileId = romFileId, planJson = EngineJson.encodeToString(PartPlan.serializer(), plan),
    relUrl = relUrl, durableBytes = durableBytes, validator = validator, digest = digest, state = state.name,
)

fun InstallResult.toEntity(romsRoot: String, now: Long) = InstallEntity(
    romId = romId,
    volumeUuid = volumeId,
    romsRoot = romsRoot,
    relPath = relPath,
    pathKey = NameSanitizer.key(relPath),
    platformId = platformId,
    kind = if (kind == EntryKind.DIRECTORY) InstallKind.DIRECTORY else InstallKind.FILE,
    bytes = bytes,
    verifiedBy = runCatching { VerifiedBy.valueOf(verifiedBy) }.getOrDefault(VerifiedBy.SIZE),
    source = InstallSource.LOOM,
    romFingerprint = fingerprint,
    installedAtEpochMs = now,
)
