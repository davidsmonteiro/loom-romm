package ro.davidmontei.loom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    private var state by mutableStateOf(sample)

    private fun show(initial: QueueUiState = sample, focusJobId: Long? = null) {
        state = initial
        rule.setContent { LoomTestTheme { QueueContent(state, actions, focusJobId) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
    }

    /** Recompose with the new state first, then let delayed focus requests run. */
    private fun update(change: (QueueUiState) -> QueueUiState) {
        state = change(state)
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
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

    /** Found on the Thor: with only finished games, nothing had focus and the list couldn't be scrolled. */
    @Test fun finishedGamesCanBeScrolledWithThePad() {
        val done = (1..12).map { RecentInstall(100 + it, "Game $it", "psx/Game $it.chd", 1_000_000L * it, it.toLong(), "SHA1") }
        show(QueueUiState(active = emptyList(), failed = emptyList(), finished = done))
        rule.onNodeWithTag("done-101").assertIsFocused()
        repeat(11) { press(Key.DirectionDown) }
        rule.onNodeWithTag("done-112").assertIsFocused()
    }

    /** Found by audit: the first job finishing pulled focus to the new first job, so A paused that one. */
    @Test fun focusStaysOnItsJobWhenTheFirstOneFinishes() {
        show()
        press(Key.DirectionDown); press(Key.DirectionDown)
        rule.onNodeWithTag("job-3").assertIsFocused()
        update { it.copy(active = it.active.drop(1)) } // Final Fantasy VII is done
        rule.onNodeWithTag("job-3").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("pause:3")
    }

    /** Found by audit: closing the cancel question put focus on the first job, so a second X targeted it. */
    @Test fun keepGivesFocusBackToItsJob() {
        show()
        press(Key.DirectionDown); press(Key.DirectionDown) // Mario Kart DS
        press(Key.ButtonX)
        rule.onNodeWithText("Keep").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("job-3").assertIsFocused()
    }

    /** Found by audit: a job moving between "In progress" and "Needs attention" lost focus to the first job. */
    @Test fun aJobChangingSectionKeepsFocus() {
        show()
        repeat(3) { press(Key.DirectionDown) } // Chrono Cross, failed
        rule.onNodeWithTag("job-4").assertIsFocused()
        update { it.copy(active = it.active + item(4, "Chrono Cross (USA)", JobPhase.QUEUED), failed = emptyList()) } // retried
        rule.onNodeWithTag("job-4").assertIsFocused()
    }

    /** Found by audit: opened from a game in the library, the queue focused the first job, not that game's. */
    @Test fun opensOnTheJobItWasOpenedFor() {
        show(focusJobId = 3)
        rule.onNodeWithTag("job-3").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("pause:3")
    }

    @Test fun theJobItWasOpenedForIsScrolledTo() {
        val jobs = (1L..20L).map { item(it, "Game $it", JobPhase.QUEUED) }
        show(QueueUiState(active = jobs, failed = emptyList(), finished = emptyList()), focusJobId = 18)
        rule.onNodeWithTag("job-18").assertIsFocused()
    }

    @Test fun xIgnoresFinishedGames() {
        show()
        repeat(4) { press(Key.DirectionDown) } // past the jobs to the finished Chrono Trigger
        rule.onNodeWithTag("done-5").assertIsFocused()
        press(Key.ButtonX)
        rule.onNodeWithText("Keep").assertDoesNotExist()
        assertThat(calls).isEmpty()
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
