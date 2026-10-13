package ro.davidmontei.loom.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ro.davidmontei.loom.data.db.RecentInstall
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.ui.queue.QueueActions
import ro.davidmontei.loom.ui.queue.QueueContent
import ro.davidmontei.loom.ui.queue.QueueItem
import ro.davidmontei.loom.ui.queue.QueueUiState
import ro.davidmontei.loom.ui.queue.transferLine

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = THOR_TOP_SCREEN)
class QueueScreenTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()
    private val actions = object : QueueActions {
        override fun pause(jobId: Long) { calls += "pause:$jobId" }
        override fun resume(jobId: Long) { calls += "resume:$jobId" }
        override fun retry(jobId: Long) { calls += "retry:$jobId" }
        override fun cancel(jobId: Long) { calls += "cancel:$jobId" }
    }

    companion object {
        fun item(id: Long, title: String, phase: JobPhase, done: Long = 0, total: Long = 4_000_000_000, speed: Long = 0,
                 detail: String? = null, retryable: Boolean = true) =
            QueueItem(id, title, phase, phase.name.lowercase().replaceFirstChar { it.uppercase() }, detail, done, total, speed, retryable)

        val sample = QueueUiState(
            active = listOf(
                item(1, "Final Fantasy VII (USA)", JobPhase.DOWNLOADING, done = 1_300_000_000, speed = 11_800_000),
                item(2, "Metroid Prime (USA)", JobPhase.PAUSED, done = 300_000_000, total = 1_400_000_000),
                item(3, "Mario Kart DS (USA)", JobPhase.QUEUED, total = 60_000_000),
            ),
            failed = listOf(item(4, "Chrono Cross (USA)", JobPhase.FAILED, detail = "Needs 1.4 GB free on the SD card; 900 MB available. Offload something and retry.")),
            finished = listOf(RecentInstall(5, "Chrono Trigger (USA)", "snes/Chrono Trigger (USA).sfc", 4_194_304, 0, "SHA1")),
        )
    }

    private fun show(state: QueueUiState = sample) {
        rule.setContent { LoomTestTheme { QueueContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
    }

    private fun press(key: Key) { rule.onRoot().performKeyInput { pressKey(key) }; rule.mainClock.advanceTimeBy(100); rule.waitForIdle() }

    @Test fun aPausesResumesAndRetriesByRow() {
        show()
        rule.onNodeWithTag("job-1").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown); press(Key.DirectionCenter)
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionCenter)
        assertThat(calls).containsExactly("pause:1", "resume:2", "retry:4").inOrder()
    }

    @Test fun xCancelsAfterConfirmation() {
        show()
        press(Key.ButtonX)
        rule.onNodeWithText("Keep").assertIsFocused()
        press(Key.DirectionRight); press(Key.DirectionCenter)
        assertThat(calls).containsExactly("cancel:1")
    }

    @Test fun emptyQueueExplainsHowToAdd() {
        show(QueueUiState(emptyList(), emptyList(), emptyList()))
        rule.onNodeWithText("Nothing downloading", substring = true).assertExists()
    }

    @Test fun transferLineShowsSpeedAndEta() {
        val line = transferLine(item(1, "x", JobPhase.DOWNLOADING, done = 1_000_000_000, total = 2_000_000_000, speed = 10_000_000))
        assertThat(line).isEqualTo("954 MB of 1.9 GB · 9.5 MB/s · 1 min left")
    }
}
