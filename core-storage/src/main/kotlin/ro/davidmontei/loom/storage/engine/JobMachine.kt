package ro.davidmontei.loom.storage.engine

import kotlinx.serialization.Serializable

/** Where a download job is. Success is not a phase: a finished job becomes an install and is deleted. */
@Serializable
enum class JobPhase {
    QUEUED, WAITING, PAUSED,
    DOWNLOADING, VERIFYING, EXTRACTING, PROMOTING,
    FAILED, CANCELLING,
    /** Terminal: the job row is deleted (installed, or cancelled and cleaned up). */
    DONE;

    val isActive: Boolean get() = this == DOWNLOADING || this == VERIFYING || this == EXTRACTING || this == PROMOTING
}

/** Why a job is parked until something outside Loom changes. */
@Serializable
enum class WaitReason {
    /** The SD card holding the ROMs root is unmounted or unwritable. */
    STORAGE,

    /** Android refused to start the foreground service from the background; opening Loom resumes. */
    APP_OPEN,
}

@Serializable
enum class FailureCode {
    NETWORK, AUTH, SERVER_FILE_MISSING, PROXY_PAGE, CHECKSUM, CORRUPT_ARCHIVE,
    INSUFFICIENT_SPACE, FILE_TOO_LARGE_FOR_SD, DEST_EXISTS, NOTHING_TO_DOWNLOAD, INTERNAL,
}

@Serializable
data class Failure(val code: FailureCode, val detail: String? = null, val retryable: Boolean = true)

@Serializable
data class JobStatus(
    val phase: JobPhase,
    val waitReason: WaitReason? = null,
    val failure: Failure? = null,
) {
    companion object {
        val Queued = JobStatus(JobPhase.QUEUED)
    }
}

sealed interface JobEvent {
    /** A worker took the job; [resumeAt] is the first phase whose work isn't done yet. */
    data class Claimed(val resumeAt: JobPhase) : JobEvent
    data object DownloadsComplete : JobEvent
    data class Verified(val extract: Boolean) : JobEvent
    /** A part failed its check. The first time it's downloaded again; the second time the job fails. */
    data class ChecksumMismatch(val firstTime: Boolean, val detail: String? = null) : JobEvent
    data object Extracted : JobEvent
    data object Promoted : JobEvent
    data class Failed(val failure: Failure) : JobEvent
    data class Wait(val reason: WaitReason) : JobEvent
    /** The worker stopped (process death, system stop) while the job was active. */
    data object Interrupted : JobEvent
    data object Pause : JobEvent
    data object Resume : JobEvent
    data object Cancel : JobEvent
    data object Retry : JobEvent
    data object ConditionRestored : JobEvent
    data object CleanedUp : JobEvent
}

class IllegalTransitionException(from: JobStatus, event: JobEvent) :
    IllegalStateException("No transition from ${from.phase} on $event")

/**
 * The download job state machine, as a pure function. Every state change the runner, the queue
 * or the UI makes goes through [next], so the legal moves are listed in one place:
 *
 * ```
 * QUEUED ──Claimed──▶ DOWNLOADING ─▶ VERIFYING ─▶ (EXTRACTING) ─▶ PROMOTING ─▶ DONE
 *    ▲                    │  ▲            │checksum (1st)              │
 *    │                    │  └────────────┘                            │ no pause/cancel while
 *    ├──Interrupted───────┤ (any active phase)                         │ the install is being
 *    ├──Resume── PAUSED ◀─┤ Pause (not PROMOTING)                      │ moved into place
 *    ├──Restored─ WAITING ◀┤ Wait(storage | app open)
 *    └──Retry─── FAILED ◀─┘ Failed / checksum (2nd)
 * any non-active, non-terminal ──Cancel──▶ CANCELLING ──CleanedUp──▶ DONE
 * ```
 */
object JobMachine {
    fun next(from: JobStatus, event: JobEvent): JobStatus {
        val p = from.phase
        val to: JobStatus? = when (event) {
            is JobEvent.Claimed -> if (p == JobPhase.QUEUED && event.resumeAt.isActive) JobStatus(event.resumeAt) else null
            JobEvent.DownloadsComplete -> if (p == JobPhase.DOWNLOADING) JobStatus(JobPhase.VERIFYING) else null
            is JobEvent.Verified -> if (p == JobPhase.VERIFYING) {
                JobStatus(if (event.extract) JobPhase.EXTRACTING else JobPhase.PROMOTING)
            } else null
            is JobEvent.ChecksumMismatch -> if (p == JobPhase.VERIFYING || p == JobPhase.EXTRACTING) {
                if (event.firstTime) JobStatus(JobPhase.DOWNLOADING)
                else JobStatus(JobPhase.FAILED, failure = Failure(FailureCode.CHECKSUM, event.detail, retryable = true))
            } else null
            JobEvent.Extracted -> if (p == JobPhase.EXTRACTING) JobStatus(JobPhase.PROMOTING) else null
            JobEvent.Promoted -> if (p == JobPhase.PROMOTING) JobStatus(JobPhase.DONE) else null
            is JobEvent.Failed -> if (p.isActive) JobStatus(JobPhase.FAILED, failure = event.failure) else null
            is JobEvent.Wait -> if (p.isActive) JobStatus(JobPhase.WAITING, waitReason = event.reason) else null
            JobEvent.Interrupted -> if (p.isActive) JobStatus.Queued else null
            JobEvent.Pause -> when (p) {
                JobPhase.QUEUED, JobPhase.WAITING, JobPhase.DOWNLOADING, JobPhase.VERIFYING, JobPhase.EXTRACTING ->
                    JobStatus(JobPhase.PAUSED)
                JobPhase.PAUSED -> from
                else -> null
            }
            JobEvent.Resume -> if (p == JobPhase.PAUSED) JobStatus.Queued else null
            JobEvent.Cancel -> when (p) {
                JobPhase.PROMOTING, JobPhase.DONE -> null
                JobPhase.CANCELLING -> from
                else -> JobStatus(JobPhase.CANCELLING)
            }
            JobEvent.Retry -> if (p == JobPhase.FAILED && from.failure?.retryable != false) JobStatus.Queued else null
            JobEvent.ConditionRestored -> if (p == JobPhase.WAITING) JobStatus.Queued else null
            JobEvent.CleanedUp -> if (p == JobPhase.CANCELLING) JobStatus(JobPhase.DONE) else null
        }
        return to ?: throw IllegalTransitionException(from, event)
    }

    fun canApply(from: JobStatus, event: JobEvent): Boolean =
        runCatching { next(from, event) }.isSuccess
}
