package ro.davidmontei.loom.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.robolectric.annotation.GraphicsMode
import ro.davidmontei.loom.data.db.RecentInstall
import ro.davidmontei.loom.data.saves.ConflictChoice
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.ui.components.dateTime
import ro.davidmontei.loom.ui.queue.ConflictItem
import ro.davidmontei.loom.ui.queue.RecentSave
import ro.davidmontei.loom.ui.queue.RestorableSave
import ro.davidmontei.loom.ui.queue.QueueActions
import ro.davidmontei.loom.ui.queue.QueueContent
import ro.davidmontei.loom.ui.queue.QueueItem
import ro.davidmontei.loom.ui.queue.QueueUiState
import ro.davidmontei.loom.ui.queue.transferLine
import ro.davidmontei.loom.ui.saves.SaveFolderOption
import ro.davidmontei.loom.ui.saves.SaveFolderQuestion

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = THOR_TOP_SCREEN)
class QueueScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()
    private val actions = object : QueueActions {
        override fun pause(jobId: Long) { calls += "pause:$jobId" }
        override fun resume(jobId: Long) { calls += "resume:$jobId" }
        override fun retry(jobId: Long) { calls += "retry:$jobId" }
        override fun cancel(jobId: Long) { calls += "cancel:$jobId" }
        override fun resolveConflict(romId: Int, title: String, choice: ConflictChoice) { calls += "resolve:$romId:$choice" }
        override fun restoreSave(romId: Int, title: String) { calls += "restore:$romId" }
        override fun askSaveFolder(romId: Int, title: String, emulator: String?) { calls += "askSaveFolder:$romId:$emulator" }
        override fun chooseSaveFolder(id: String) { calls += "saveFolder:$id" }
        override fun dismissSaveFolderQuestion() { calls += "dismissSaveFolder" }
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

        val pokemonX = ConflictItem(
            7, "Pokemon X", "Both the Thor and RomM have changed this save since the last sync",
            thorModifiedMs = 1_792_000_000_000, thorBytes = 524_288, serverUpdatedMs = 1_791_990_000_000, serverEmulator = "azahar",
            serverBytes = 530_000, serverDevice = "Steam Deck", emulator = "azahar",
        )
        private val omegaRuby = RestorableSave(8, "Pokemon Omega Ruby", missing = false, emulator = "azahar")
        val withSaves = sample.copy(
            conflicts = listOf(pokemonX), restorable = listOf(omegaRuby),
            recentSaves = listOf(RecentSave(9, "Pokemon Y", System.currentTimeMillis() - 3 * 60_000)),
        )
    }

    private var state by mutableStateOf(sample)

    private fun show(initial: QueueUiState = sample, focusJobId: Long? = null, focusConflictRomId: Int? = null) {
        state = initial
        rule.setContent { LoomTestTheme { QueueContent(state, actions, focusJobId, focusConflictRomId = focusConflictRomId) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
    }

    /** Recompose with the new state first, then let delayed focus requests run. */
    private fun update(change: (QueueUiState) -> QueueUiState) {
        state = change(state)
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    private fun press(key: Key) { rule.onRoot().performKeyInput { pressKey(key) }; rule.mainClock.advanceTimeBy(100); rule.waitForIdle() }

    /** A button, not the hint of the same name ("X Cancel"). */
    private fun button(text: String) = rule.onNode(hasText(text) and hasClickAction())

    /** What the activity does with B (BACK) once nothing in Compose has used it. */
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
    }

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

    /** Found by audit: X on a job being moved into place asked to cancel it, and then nothing happened. */
    @Test fun xDoesNotOfferToCancelAJobBeingMovedIntoPlace() {
        show(sample.copy(active = listOf(item(1, "Final Fantasy VII (USA)", JobPhase.PROMOTING))))
        rule.onNodeWithTag("job-1").assertIsFocused()
        press(Key.ButtonX)
        rule.onNodeWithText("Keep").assertDoesNotExist()
    }

    /** On a job being moved into place A and X do nothing, so the hints don't offer them there. */
    @Test fun aJobBeingMovedIntoPlaceOffersNothingToPress() {
        show(sample.copy(active = listOf(item(1, "Final Fantasy VII (USA)", JobPhase.PROMOTING)) + sample.active.drop(1)))
        rule.onNodeWithTag("job-1").assertIsFocused()
        rule.onNodeWithText("Pause / resume / retry").assertDoesNotExist()
        rule.onNodeWithText("Cancel").assertDoesNotExist()
        rule.onNodeWithText("Back").assertExists()
        press(Key.DirectionDown) // Metroid Prime, paused
        rule.onNodeWithText("Pause / resume / retry").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
    }

    /**
     * Found by review: the question to cancel stayed as it was when its job reached the move into
     * place, and "Cancel download" then did nothing. It says what's happening instead.
     */
    @Test fun theCancelQuestionStopsOfferingCancelOnceTheJobIsMovedIntoPlace() {
        show()
        press(Key.ButtonX) // Final Fantasy VII, downloading
        press(Key.DirectionRight)
        button("Cancel download").assertIsFocused()
        update { it.copy(active = listOf(item(1, "Final Fantasy VII (USA)", JobPhase.PROMOTING)) + it.active.drop(1)) }
        button("Cancel download").assertDoesNotExist()
        rule.onNodeWithTag("cancel-state", useUnmergedTree = true).assertTextEquals("Finishing…")
        rule.onNodeWithText("Keep").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Keep").assertDoesNotExist()
        assertThat(calls).isEmpty()
    }

    @Test fun theCancelQuestionGoesWhenItsJobFinishes() {
        show()
        press(Key.DirectionDown); press(Key.DirectionDown) // Mario Kart DS
        press(Key.ButtonX)
        rule.onNodeWithText("Cancel downloading Mario Kart DS (USA)?").assertExists()
        update { it.copy(active = it.active.filterNot { job -> job.jobId == 3L }) } // installed meanwhile
        rule.onNodeWithText("Cancel downloading Mario Kart DS (USA)?").assertDoesNotExist()
        assertThat(calls).isEmpty()
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

    /** Found by review: when the focused job left the queue (cancelled, finished), focus jumped to the top job. */
    @Test fun focusGoesToTheJobInPlaceOfOneThatLeft() {
        var current by mutableStateOf(sample)
        rule.setContent { LoomTestTheme { QueueContent(current, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        press(Key.DirectionDown); press(Key.DirectionDown)
        rule.onNodeWithTag("job-3").assertIsFocused() // Mario Kart
        current = current.copy(active = current.active.filterNot { it.jobId == 3L }) // cancelled
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithTag("job-4").assertIsFocused() // the job now in its place, not job 1
    }

    // --- saves ---

    /** Opened from a save notification, the queue lands on what needs the user; the choice opens on Skip. */
    @Test fun aConflictComesFirstAndItsChoiceOpensOnSkip() {
        show(withSaves)
        rule.onNodeWithTag("conflict-7").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Pokemon X: which save to keep?").assertExists()
        rule.onNodeWithText("Skip").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("resolve:7:SKIP")
        rule.onNodeWithTag("conflict-7").assertIsFocused() // a skipped conflict stays listed
    }

    @Test fun bOnTheChoiceSkips() {
        show(withSaves)
        press(Key.DirectionCenter)
        back()
        assertThat(calls).containsExactly("resolve:7:SKIP")
        rule.onNodeWithText("Pokemon X: which save to keep?").assertDoesNotExist()
        rule.onNodeWithTag("conflict-7").assertIsFocused()
    }

    /** What needs the user comes first, under its own heading: why each conflict is one, and both sides in a line. */
    @Test fun aConflictRowSaysWhyAndShowsBothSides() {
        show(withSaves)
        fun inRow(text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("conflict-7")), useUnmergedTree = true)
        rule.onNodeWithText("SAVE CONFLICTS").assertExists()
        inRow("Both the Thor and RomM have changed this save since the last sync").assertExists()
        inRow("Thor: changed ${dateTime(pokemonX.thorModifiedMs!!)} · ${formatBytes(524_288)}    " +
            "RomM: uploaded ${dateTime(pokemonX.serverUpdatedMs!!)} · ${formatBytes(530_000)} · Azahar · from Steam Deck").assertExists()
        inRow("Choose").assertExists()
        rule.onNodeWithText("Choose / pause / resume / retry").assertExists()

        update { it.copy(conflicts = listOf(pokemonX.copy(skipped = true))) }
        inRow("Skipped").assertExists()
        update { it.copy(conflicts = listOf(pokemonX.copy(busy = true))) }
        inRow("Working…").assertExists()
        update { sample }
        rule.onNodeWithText("Pause / resume / retry").assertExists()
    }

    /** A newer save on RomM says what A does with it; a save gone from the Thor says it comes back. */
    @Test fun aSaveToRestoreSaysWhatADoes() {
        show(sample.copy(restorable = listOf(omegaRuby, RestorableSave(9, "Pokemon Y", missing = true, emulator = "azahar"))))
        fun inRow(id: Int, text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("restore-$id")), useUnmergedTree = true)
        rule.onNodeWithText("SAVES TO RESTORE FROM ROMM").assertExists()
        inRow(8, "Newer on RomM").assertExists()
        inRow(8, "A takes RomM's save, once Azahar is closed. The Thor's is backed up first.").assertExists()
        inRow(9, "Missing on the Thor").assertExists()
        inRow(9, "A puts RomM's save back on the Thor, once Azahar is closed.").assertExists()
    }

    @Test fun theChoiceShowsBothSides() {
        show(withSaves)
        press(Key.DirectionCenter)
        fun side(tag: String, text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        side("thor-side", "Changed ${dateTime(pokemonX.thorModifiedMs!!)}").assertExists()
        side("thor-side", formatBytes(524_288)).assertExists()
        side("server-side", "Uploaded ${dateTime(pokemonX.serverUpdatedMs!!)}").assertExists()
        side("server-side", "${formatBytes(530_000)} · Azahar").assertExists()
        side("server-side", "From Steam Deck").assertExists()
    }

    @Test fun keepThorAnswersAtOnce() {
        show(withSaves)
        press(Key.DirectionCenter); press(Key.DirectionRight)
        rule.onNodeWithText("Keep Thor").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("resolve:7:KEEP_THOR")
        rule.onNodeWithTag("conflict-7").assertIsFocused()
    }

    /** Keep server and Keep both replace the Thor's save, so they ask for the emulator to be closed first. */
    @Test fun keepingRommsSaveAsksToCloseTheEmulatorFirst() {
        show(withSaves)
        press(Key.DirectionCenter); press(Key.DirectionRight); press(Key.DirectionRight)
        rule.onNodeWithText("Keep server").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Put RomM's save of Pokemon X on the Thor?").assertExists()
        rule.onNodeWithText("Close Azahar completely first (swipe it away in Recents). Loom backs up the save on the Thor before replacing it.")
            .assertExists()
        button("Cancel").assertIsFocused()
        press(Key.DirectionCenter) // back to the choice, nothing decided
        rule.onNodeWithText("Skip").assertIsFocused()
        assertThat(calls).isEmpty()

        repeat(3) { press(Key.DirectionRight) }
        rule.onNodeWithText("Keep both").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Upload the Thor's save of Pokemon X as a copy, then take RomM's?").assertExists()
        press(Key.DirectionRight); press(Key.DirectionCenter)
        assertThat(calls).containsExactly("resolve:7:KEEP_BOTH")
        rule.onNodeWithTag("conflict-7").assertIsFocused()
    }

    @Test fun bOnTheCloseTheEmulatorQuestionGoesBackToTheChoice() {
        show(withSaves)
        press(Key.DirectionCenter); press(Key.DirectionRight); press(Key.DirectionRight); press(Key.DirectionCenter) // Keep server
        back()
        rule.onNodeWithText("Skip").assertIsFocused()
        assertThat(calls).isEmpty()
    }

    /** RomM's save from another emulator, and none on the Thor: there is no Thor save to keep, so the pad skips past it. */
    @Test fun aSideWithoutASaveCantBeKept() {
        show(sample.copy(conflicts = listOf(pokemonX.copy(thorModifiedMs = null, thorBytes = null))))
        press(Key.DirectionCenter)
        rule.onNodeWithText("Keep Thor").assertIsNotEnabled()
        rule.onNodeWithText("Keep both").assertIsNotEnabled()
        press(Key.DirectionRight)
        rule.onNodeWithText("Keep server").assertIsFocused()
    }

    /** RomM's save gone from the slot: there is nothing on RomM to keep, alone or beside the Thor's. */
    @Test fun aServerSideWithoutASaveCantBeKept() {
        show(sample.copy(conflicts = listOf(pokemonX.copy(serverUpdatedMs = null, serverEmulator = null, serverBytes = null, serverDevice = null))))
        press(Key.DirectionCenter)
        rule.onNodeWithText("Keep server").assertIsNotEnabled()
        rule.onNodeWithText("Keep both").assertIsNotEnabled()
        press(Key.DirectionRight)
        rule.onNodeWithText("Keep Thor").assertIsFocused()
    }

    /** Settled meanwhile (in the background, or from the library): the choice closes, and a later conflict doesn't reopen it. */
    @Test fun aConflictSettledWhileChoosingClosesTheChoice() {
        show(withSaves)
        press(Key.DirectionCenter)
        rule.onNodeWithText("Pokemon X: which save to keep?").assertExists()
        update { it.copy(conflicts = emptyList()) }
        rule.onNodeWithText("Pokemon X: which save to keep?").assertDoesNotExist()
        rule.onNodeWithTag("restore-8").assertIsFocused() // the row now in the conflict's place
        update { it.copy(conflicts = listOf(pokemonX.copy(reason = "RomM's newer save was made by another emulator"))) }
        rule.onNodeWithText("Pokemon X: which save to keep?").assertDoesNotExist()
        assertThat(calls).isEmpty()
    }

    @Test fun aNewerSaveOnRommIsRestoredOnceTheEmulatorIsClosed() {
        show(withSaves)
        press(Key.DirectionDown)
        rule.onNodeWithTag("restore-8").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Restore Pokemon Omega Ruby's save from RomM?").assertExists()
        rule.onNodeWithText("Close Azahar completely first (swipe it away in Recents). Loom backs up the save on the Thor before replacing it.")
            .assertExists()
        button("Cancel").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).isEmpty()
        rule.onNodeWithTag("restore-8").assertIsFocused()
        press(Key.DirectionCenter); press(Key.DirectionRight); press(Key.DirectionCenter)
        assertThat(calls).containsExactly("restore:8")
        rule.onNodeWithTag("restore-8").assertIsFocused()
    }

    @Test fun aSettledConflictHandsFocusToTheRowInItsPlace() {
        show(withSaves)
        rule.onNodeWithTag("conflict-7").assertIsFocused()
        update { it.copy(conflicts = emptyList()) }
        rule.onNodeWithTag("restore-8").assertIsFocused()
    }

    @Test fun aGameBeingWorkedOnIgnoresA() {
        show(withSaves.copy(conflicts = listOf(pokemonX.copy(busy = true))))
        press(Key.DirectionCenter)
        rule.onNodeWithText("Pokemon X: which save to keep?").assertDoesNotExist()
    }

    @Test fun aSaveBeingRestoredIgnoresA() {
        show(sample.copy(restorable = listOf(omegaRuby.copy(busy = true))))
        rule.onNodeWithTag("restore-8").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Restore Pokemon Omega Ruby's save from RomM?").assertDoesNotExist()
    }

    @Test fun xLeavesSavesAlone() {
        show(withSaves)
        press(Key.ButtonX)
        rule.onNodeWithText("Keep").assertDoesNotExist()
        assertThat(calls).isEmpty()
    }

    /** Found by review: "Choose which to keep" in a game's details landed on the oldest conflict, so A answered another game's. */
    @Test fun opensOnTheConflictOfTheGameItWasOpenedFor() {
        val conflicts = (1..12).map { pokemonX.copy(romId = 100 + it, title = "Game $it") }
        show(withSaves.copy(conflicts = conflicts), focusConflictRomId = 111)
        rule.onNodeWithTag("conflict-111").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Game 11: which save to keep?").assertExists()
    }

    @Test fun openedForAJobItStillStartsOnThatJob() {
        show(withSaves, focusJobId = 3)
        rule.onNodeWithTag("job-3").assertIsFocused()
    }

    /**
     * Found by review: a game in conflict was also listed as "in sync, 3 min ago". The list says when
     * each save last synced, an event, not where it stands now.
     */
    @Test fun aSaveSyncedLatelySaysWhenNotThatItIsInSync() {
        show(QueueUiState(emptyList(), emptyList(), emptyList(), conflicts = listOf(pokemonX),
            recentSaves = listOf(RecentSave(7, "Pokemon X", System.currentTimeMillis() - 3 * 60_000))))
        rule.onNode(hasText("synced 3 min ago") and hasAnyAncestor(hasTestTag("synced-7")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("in sync", substring = true), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun savesSyncedLatelyCanBeScrolledWithThePad() {
        val now = System.currentTimeMillis()
        show(QueueUiState(emptyList(), emptyList(), emptyList(), recentSaves = (1..10).map { RecentSave(it, "Game $it", now - it * 60_000L) }))
        rule.onNodeWithTag("synced-1").assertIsFocused()
        repeat(9) { press(Key.DirectionDown) }
        rule.onNodeWithTag("synced-10").assertIsFocused()
    }

    // --- a conflict over a save folder the user picked ---

    private val wonder = pokemonX.copy(romId = 30, title = "Super Mario Bros Wonder", serverEmulator = "eden", emulator = "eden",
        reason = "The Thor and RomM each have a different save, and they were never synced", folderChosen = true)
    private val wonderFolders = SaveFolderQuestion(30, "Super Mario Bros Wonder", "eden", listOf(
        SaveFolderOption("0100A0B00C0D0000", 2, 4_103, System.currentTimeMillis() - 60_000),
        SaveFolderOption("01002B300C1D2000", 2, 4_103, System.currentTimeMillis() - 3_600_000),
    ), current = "01002B300C1D2000")

    /**
     * A wrong pick of a Switch game's save folder ends in a conflict where neither save is the one to
     * keep, and the queue offered only that choice. Left of Skip, apart from the answers, is Change
     * save folder, which answers nothing: the conflict stays as it is until the folder question is settled.
     */
    @Test fun aConflictOverAPickedSaveFolderCanChangeTheFolder() {
        show(sample.copy(conflicts = listOf(wonder)))
        press(Key.DirectionCenter)
        rule.onNodeWithText("Skip").assertIsFocused()
        press(Key.DirectionLeft)
        button("Change save folder").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("askSaveFolder:30:eden")
        rule.onNodeWithText("Super Mario Bros Wonder: which save to keep?").assertDoesNotExist()
    }

    /**
     * On the bottom screen the five buttons wrap onto two lines: the pad still reaches every one. Native
     * graphics measure the words as on the device, so they wrap as there; such a window starts in touch
     * mode, so the pad's keyboard mode is provided.
     */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun everyConflictAnswerIsReachableWhenTheyWrap() {
        state = sample.copy(conflicts = listOf(wonder))
        val pad = object : InputModeManager {
            override val inputMode get() = InputMode.Keyboard
            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }
        rule.setContent { CompositionLocalProvider(LocalInputModeManager provides pad) { LoomTestTheme { QueueContent(state, actions) } } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        press(Key.DirectionCenter)
        val tops = listOf("Skip", "Keep both").map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertThat(tops.distinct()).hasSize(2) // two lines of buttons
        rule.onNodeWithText("Skip").assertIsFocused()
        val reached = mutableSetOf<String>()
        for (key in listOf(Key.DirectionRight, Key.DirectionDown, Key.DirectionLeft, Key.DirectionUp, Key.DirectionLeft, Key.DirectionLeft)) {
            press(key)
            rule.onAllNodes(isFocused()).fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.Text)?.let { reached += it.joinToString() }
        }
        assertThat(reached).containsAtLeast("Change save folder", "Keep Thor", "Keep server", "Keep both")
        assertThat(calls).isEmpty() // moving answers nothing
    }

    @Test fun onlyAPickedSaveFolderIsOfferedThere() {
        show(withSaves)
        press(Key.DirectionCenter)
        rule.onNodeWithText("Change save folder").assertDoesNotExist()
        press(Key.DirectionLeft)
        rule.onNodeWithText("Skip").assertIsFocused() // nothing left of it
    }

    /** The same question as in the game's details: focus on the newest folder, and A takes the one focused. */
    @Test fun theSaveFolderQuestionInTheQueueIsAnsweredByPad() {
        show(sample.copy(conflicts = listOf(wonder)))
        update { it.copy(saveFolderQuestion = wonderFolders) }
        rule.onNodeWithText("Which Eden save folder is Super Mario Bros Wonder's?").assertExists()
        rule.onNodeWithTag("save-folder-0100A0B00C0D0000", useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("saveFolder:0100A0B00C0D0000")
    }

    /** B closes the question, changing nothing, and focus is back on the conflict it was asked from. */
    @Test fun bClosesTheSaveFolderQuestionBackOnTheConflict() {
        show(sample.copy(conflicts = listOf(wonder)))
        press(Key.DirectionCenter); press(Key.DirectionLeft); press(Key.DirectionCenter) // Change save folder
        update { it.copy(saveFolderQuestion = wonderFolders) }
        back()
        assertThat(calls).containsExactly("askSaveFolder:30:eden", "dismissSaveFolder").inOrder()
        update { it.copy(saveFolderQuestion = null) }
        rule.onNodeWithText("Which Eden save folder is Super Mario Bros Wonder's?").assertDoesNotExist()
        rule.onNodeWithTag("conflict-30").assertIsFocused()
    }

    @Test fun aMessageSaysHowAnAnswerWent() {
        rule.setContent { LoomTestTheme { QueueContent(withSaves, actions, message = "RomM now has the Thor's save of Pokemon X") } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithText("RomM now has the Thor's save of Pokemon X").assertExists()
    }
}
