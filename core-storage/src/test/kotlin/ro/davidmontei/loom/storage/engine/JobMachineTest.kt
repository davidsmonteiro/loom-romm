package ro.davidmontei.loom.storage.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import ro.davidmontei.loom.storage.engine.JobPhase.CANCELLING
import ro.davidmontei.loom.storage.engine.JobPhase.DONE
import ro.davidmontei.loom.storage.engine.JobPhase.DOWNLOADING
import ro.davidmontei.loom.storage.engine.JobPhase.EXTRACTING
import ro.davidmontei.loom.storage.engine.JobPhase.FAILED
import ro.davidmontei.loom.storage.engine.JobPhase.PAUSED
import ro.davidmontei.loom.storage.engine.JobPhase.PROMOTING
import ro.davidmontei.loom.storage.engine.JobPhase.QUEUED
import ro.davidmontei.loom.storage.engine.JobPhase.VERIFYING
import ro.davidmontei.loom.storage.engine.JobPhase.WAITING

class JobMachineTest {
    private val failure = Failure(FailureCode.NETWORK, "x")
    private val fatal = Failure(FailureCode.FILE_TOO_LARGE_FOR_SD, "x", retryable = false)

    private val events: List<JobEvent> = listOf(
        JobEvent.Claimed(DOWNLOADING), JobEvent.Claimed(PROMOTING), JobEvent.DownloadsComplete,
        JobEvent.Verified(extract = false), JobEvent.Verified(extract = true),
        JobEvent.ChecksumMismatch(firstTime = true), JobEvent.ChecksumMismatch(firstTime = false),
        JobEvent.Extracted, JobEvent.Promoted, JobEvent.Failed(failure), JobEvent.Wait(WaitReason.STORAGE),
        JobEvent.Interrupted, JobEvent.Pause, JobEvent.Resume, JobEvent.Cancel, JobEvent.Retry,
        JobEvent.ConditionRestored, JobEvent.CleanedUp,
    )

    private fun s(phase: JobPhase) = when (phase) {
        WAITING -> JobStatus(WAITING, waitReason = WaitReason.STORAGE)
        FAILED -> JobStatus(FAILED, failure = failure)
        else -> JobStatus(phase)
    }

    /** Every (phase, event) pair that is allowed, and where it leads. Everything else must throw. */
    private val legal: Map<Pair<JobPhase, JobEvent>, JobPhase> = buildMap {
        put(QUEUED to JobEvent.Claimed(DOWNLOADING), DOWNLOADING)
        put(QUEUED to JobEvent.Claimed(PROMOTING), PROMOTING)
        put(DOWNLOADING to JobEvent.DownloadsComplete, VERIFYING)
        put(VERIFYING to JobEvent.Verified(false), PROMOTING)
        put(VERIFYING to JobEvent.Verified(true), EXTRACTING)
        for (p in listOf(VERIFYING, EXTRACTING)) {
            put(p to JobEvent.ChecksumMismatch(true), DOWNLOADING)
            put(p to JobEvent.ChecksumMismatch(false), FAILED)
        }
        put(EXTRACTING to JobEvent.Extracted, PROMOTING)
        put(PROMOTING to JobEvent.Promoted, DONE)
        for (p in listOf(DOWNLOADING, VERIFYING, EXTRACTING, PROMOTING)) {
            put(p to JobEvent.Failed(failure), FAILED)
            put(p to JobEvent.Wait(WaitReason.STORAGE), WAITING)
            put(p to JobEvent.Interrupted, QUEUED)
        }
        for (p in listOf(QUEUED, WAITING, DOWNLOADING, VERIFYING, EXTRACTING, PAUSED)) put(p to JobEvent.Pause, PAUSED)
        put(PAUSED to JobEvent.Resume, QUEUED)
        for (p in listOf(QUEUED, WAITING, PAUSED, DOWNLOADING, VERIFYING, EXTRACTING, FAILED, CANCELLING)) put(p to JobEvent.Cancel, CANCELLING)
        put(FAILED to JobEvent.Retry, QUEUED)
        put(WAITING to JobEvent.ConditionRestored, QUEUED)
        put(CANCELLING to JobEvent.CleanedUp, DONE)
    }

    @Test fun everyPhaseEventPairIsEitherLegalOrRejected() {
        for (phase in JobPhase.entries) for (event in events) {
            val expected = legal[phase to event]
            if (expected != null) {
                assertWithMessage("$phase + $event").that(JobMachine.next(s(phase), event).phase).isEqualTo(expected)
            } else {
                assertWithMessage("$phase + $event must be rejected").that(JobMachine.canApply(s(phase), event)).isFalse()
            }
        }
    }

    @Test fun claimOnlyIntoActivePhases() {
        assertThrows(IllegalTransitionException::class.java) { JobMachine.next(JobStatus.Queued, JobEvent.Claimed(PAUSED)) }
    }

    @Test fun failuresAndWaitsCarryTheirReason() {
        val failed = JobMachine.next(JobStatus(DOWNLOADING), JobEvent.Failed(failure))
        assertThat(failed.failure).isEqualTo(failure)
        val waiting = JobMachine.next(JobStatus(EXTRACTING), JobEvent.Wait(WaitReason.APP_OPEN))
        assertThat(waiting.waitReason).isEqualTo(WaitReason.APP_OPEN)
        val checksum = JobMachine.next(JobStatus(VERIFYING), JobEvent.ChecksumMismatch(firstTime = false, detail = "SHA1"))
        assertThat(checksum.failure!!.code).isEqualTo(FailureCode.CHECKSUM)
    }

    @Test fun retryClearsTheFailure() {
        val retried = JobMachine.next(JobStatus(FAILED, failure = failure), JobEvent.Retry)
        assertThat(retried).isEqualTo(JobStatus.Queued)
    }

    @Test fun nonRetryableFailuresCannotBeRetried() {
        assertThat(JobMachine.canApply(JobStatus(FAILED, failure = fatal), JobEvent.Retry)).isFalse()
        assertThat(JobMachine.canApply(JobStatus(FAILED, failure = fatal), JobEvent.Cancel)).isTrue()
    }

    @Test fun promotingCannotBePausedOrCancelled() {
        // Moving the install into place takes milliseconds and must not be torn.
        assertThat(JobMachine.canApply(JobStatus(PROMOTING), JobEvent.Pause)).isFalse()
        assertThat(JobMachine.canApply(JobStatus(PROMOTING), JobEvent.Cancel)).isFalse()
    }

    @Test fun pauseAndCancelAreIdempotent() {
        assertThat(JobMachine.next(JobStatus(PAUSED), JobEvent.Pause).phase).isEqualTo(PAUSED)
        assertThat(JobMachine.next(JobStatus(CANCELLING), JobEvent.Cancel).phase).isEqualTo(CANCELLING)
    }
}
