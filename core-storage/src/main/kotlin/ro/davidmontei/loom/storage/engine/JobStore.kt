package ro.davidmontei.loom.storage.engine

import ro.davidmontei.loom.storage.plan.DownloadPlan
import ro.davidmontei.loom.storage.plan.EntryKind
import ro.davidmontei.loom.storage.plan.PartPlan

enum class PartState { PENDING, PARTIAL, DOWNLOADED, VERIFIED, EXTRACTED }

/** One ROM file of a job, with what's known to be safely on disk. */
data class PartRecord(
    val jobId: Long,
    val plan: PartPlan,
    /** Relative API path, resolved against whichever server address is active. */
    val relUrl: String,
    /** Bytes known to be flushed to disk; a torn tail beyond this is truncated on resume. */
    val durableBytes: Long = 0,
    /** Last-Modified (or a strong ETag) of the first response, sent as If-Range on resume. */
    val validator: String? = null,
    /** Hex digest computed while downloading (for [ro.davidmontei.loom.storage.plan.PartCheck.Hash]). */
    val digest: String? = null,
    val state: PartState = PartState.PENDING,
) {
    val romFileId: Int get() = plan.romFileId
    val stagingName: String get() = "${plan.romFileId}.part"
}

/** A job as persisted. Paths are absolute; [volumeId] identifies the storage volume. */
data class JobRecord(
    val id: Long,
    val romId: Int,
    val platformId: Int,
    val title: String,
    val status: JobStatus,
    val lease: String?,
    val attempt: Int,
    /** Bumped when the plan is rebuilt, so stale staging from an older plan is never reused. */
    val generation: Int,
    val checksumRetries: Int,
    val plan: DownloadPlan,
    val volumeId: String,
    val stagingRoot: String,
    val romsRoot: String,
    /** Set once the final entry name is fixed (start of PROMOTING); needed to recover a promote. */
    val finalRelPath: String? = null,
    val finalKind: EntryKind? = null,
    val bytesDone: Long = 0,
)

/** What a finished job leaves behind. */
data class InstallResult(
    val romId: Int,
    val platformId: Int,
    val volumeId: String,
    /** Relative to the ROMs root, e.g. "psx/Final Fantasy VII.m3u". */
    val relPath: String,
    val kind: EntryKind,
    val bytes: Long,
    val verifiedBy: String,
    val fingerprint: String,
)

/**
 * Persistence the engine needs. Every write the runner makes is conditional on the job still
 * holding [JobRecord.lease] in the phase the runner expects, so a pause or cancel from the UI
 * (which clears the lease) always wins over a runner that hasn't noticed yet.
 */
interface JobStore {
    suspend fun load(jobId: Long): JobRecord?
    suspend fun parts(jobId: Long): List<PartRecord>
    suspend fun savePart(part: PartRecord)

    /** Moves the job to [to] iff it is still leased by [lease] and in [from]. */
    suspend fun transition(jobId: Long, lease: String, from: JobPhase, to: JobStatus, checksumRetries: Int? = null): Boolean

    suspend fun saveProgress(jobId: Long, bytesDone: Long)

    suspend fun setFinalEntry(jobId: Long, relPath: String, kind: EntryKind)

    /** Records the install and deletes the job and its parts, in one transaction, iff leased. */
    suspend fun complete(jobId: Long, lease: String, install: InstallResult): Boolean

    /**
     * True when [pathKey] (a [ro.davidmontei.loom.storage.plan.NameSanitizer.key] of a path
     * relative to the ROMs root) is used by an install or another job of a different ROM.
     */
    suspend fun isPathClaimed(volumeId: String, pathKey: String, exceptRomId: Int): Boolean
}
