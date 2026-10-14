package ro.davidmontei.loom.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueKickerTest {
    private val now = 1_000_000L

    private fun run(state: WorkInfo.State, nextScheduleAt: Long = now) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), nextScheduleTimeMillis = nextScheduleAt)

    @Test fun noWorkerStartsOne() {
        assertThat(kickAction(emptyList(), now)).isEqualTo(KickAction.APPEND)
        assertThat(kickAction(listOf(run(WorkInfo.State.SUCCEEDED)), now)).isEqualTo(KickAction.APPEND)
    }

    @Test fun aRunningWorkerGetsAFollowUp() {
        assertThat(kickAction(listOf(run(WorkInfo.State.RUNNING)), now)).isEqualTo(KickAction.APPEND)
    }

    @Test fun aWorkerAlreadyWaitingIsLeftAlone() {
        assertThat(kickAction(listOf(run(WorkInfo.State.ENQUEUED)), now)).isEqualTo(KickAction.NONE) // e.g. waiting for a network
        assertThat(kickAction(listOf(run(WorkInfo.State.RUNNING), run(WorkInfo.State.BLOCKED)), now)).isEqualTo(KickAction.NONE)
    }

    /** Found on the Thor: Loom killed 13 s into a download resumed 14 s after it was reopened. */
    @Test fun aWorkerHeldBackByItsBackOffStartsNow() {
        assertThat(kickAction(listOf(run(WorkInfo.State.ENQUEUED, nextScheduleAt = now + 17_000)), now)).isEqualTo(KickAction.START_NOW)
    }

    @Test fun aWorkerDueAnyMomentIsLeftAlone() {
        assertThat(kickAction(listOf(run(WorkInfo.State.ENQUEUED, nextScheduleAt = now + 500)), now)).isEqualTo(KickAction.NONE)
    }

    /** Never two workers draining at once: a held-back run beside a running one waits. */
    @Test fun nothingIsReplacedWhileAWorkerRuns() {
        val runs = listOf(run(WorkInfo.State.RUNNING), run(WorkInfo.State.ENQUEUED, nextScheduleAt = now + 17_000))
        assertThat(kickAction(runs, now)).isEqualTo(KickAction.NONE)
    }

    /** With WorkManager itself: the waiting run is swapped for one due now, under the same unique name. */
    @Test fun kickReplacesAHeldBackRunWithOneDueNow() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        val heldBack = OneTimeWorkRequestBuilder<DrainWorker>().setInitialDelay(30, TimeUnit.SECONDS).build()
        workManager.enqueueUniqueWork(DrainWorker.UNIQUE_NAME, androidx.work.ExistingWorkPolicy.KEEP, heldBack).result.get()

        QueueKicker(workManager).kick()

        val runs = workManager.getWorkInfosForUniqueWork(DrainWorker.UNIQUE_NAME).get(5, TimeUnit.SECONDS)
        val live = runs.filter { !it.state.isFinished }
        assertThat(live).hasSize(1)
        assertThat(live.single().id).isNotEqualTo(heldBack.id)
        assertThat(live.single().nextScheduleTimeMillis).isAtMost(System.currentTimeMillis() + 1_000)
    }
}
