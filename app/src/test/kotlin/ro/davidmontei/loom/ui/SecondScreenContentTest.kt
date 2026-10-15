package ro.davidmontei.loom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ro.davidmontei.loom.data.catalog.CatalogSyncer
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.saves.conflictReason
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.storage.saves.SyncDecision
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.GameSave
import ro.davidmontei.loom.ui.library.InstallState
import ro.davidmontei.loom.ui.library.RomDetails
import ro.davidmontei.loom.ui.queue.transferLine
import ro.davidmontei.loom.ui.second.CompanionContent
import ro.davidmontei.loom.ui.second.CompanionHolder
import ro.davidmontei.loom.ui.second.DIM_AFTER
import ro.davidmontei.loom.ui.second.QueueStrip
import ro.davidmontei.loom.ui.second.SecondScreenContent
import ro.davidmontei.loom.ui.second.SecondScreenPanel

/**
 * The second screen as the Thor's bottom one shows it beside Loom: 538×444 dp, what Compose measures
 * there. Native graphics measure text as on the device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = THOR_BOTTOM_PRESENTATION)
class SecondScreenContentTest {
    @get:Rule val rule = createComposeRule()

    private var content by mutableStateOf<CompanionContent>(CompanionContent.Idle)

    private var strip by mutableStateOf<QueueStrip?>(null)

    /** The buttons and touches that have reached Loom, as the activity counts them. */
    private var inputs by mutableLongStateOf(0)

    private fun show(shown: CompanionContent, queue: QueueStrip? = null) {
        content = shown
        strip = queue
        rule.setContent { LoomTestTheme { SecondScreenContent(content, strip, inputs) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    private val title = "Pokémon Omega Ruby (USA) (En,Ja,Fr,De,Es,It,Ko) (Rev 2) [Special Demo Edition]"

    /** A 3DS game at its tallest: a title and path long enough to wrap, and a failed save sync's long reason. */
    private val tallest = GameItem(30, title, 3_900_000_000, null, listOf("USA", "Europe", "Japan"), InstallState.INSTALLED, null, null, null,
        adopted = true, removedOnServer = false, installPath = "n3ds/$title.3ds", matchedByName = true,
        save = GameSave(true, SaveSyncState.ERROR, "Couldn't read the save: /storage/6365-3230/ROMs/n3ds/sdmc/Nintendo 3DS/" +
            "00000000000000000000000000000000/00000000000000000000000000000000/title/00040000/0011c400/data (Permission denied)", "azahar"))

    @Test fun theFocusedGameFitsTheBottomScreen() {
        show(CompanionContent.Game(tallest, "Nintendo 3DS", null))
        rule.assertFitsIn("second-screen")
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText("On device").assertExists()
        val cover = rule.onNodeWithTag("panel-cover").fetchSemanticsNode().boundsInRoot
        assertThat(with(rule.density) { cover.height.toDp().value }).isAtLeast(240f) // beside the words, and large
        rule.saveScreenshot("second-screen-game")
    }

    // --- what RomM says of the game: its year, genres, companies and summary ---

    /** A 3DS game as most are: a title on one line, on the device, its save in sync. */
    private val pokemonX = GameItem(31, "Pokémon X", 1_700_000_000, null, listOf("Europe"), InstallState.INSTALLED, null, null, null,
        adopted = false, removedOnServer = false, installPath = "n3ds/Pokémon X.3ds", save = GameSave(true, SaveSyncState.IN_SYNC, null, "azahar"))

    /** What RomM says of it at its longest: three long genres, two companies, and a summary cut at a thousand characters. */
    private val pokemonXDetails = RomDetails(31,
        ("Pokémon X and Pokémon Y introduce a new generation of Pokémon to be discovered in a stunning 3D world. Players begin their " +
            "journey in the Kalos region, a land of breathtaking beauty, as they set out to become Pokémon Trainers. ").repeat(5)
            .take(CatalogSyncer.SUMMARY_LENGTH - 1).trimEnd() + "…",
        2013, listOf("Role-playing (RPG)", "Turn-based strategy (TBS)", "Adventure"), listOf("Game Freak", "The Pokémon Company"))

    /** The year, genres and companies, then the summary in the room left: all of it on the bottom screen, with the queue's strip. */
    @Test fun everythingFitsTheBottomScreenAtOnce() {
        val downloading = QueueScreenTest.item(1, "The Legend of Zelda: Tears of the Kingdom (World) (En,Ja,Fr,De,Es,It,Nl,Ru,Ko,Zh) (v1.2.1)",
            JobPhase.DOWNLOADING, done = 1_300_000_000, speed = 11_800_000)
        show(CompanionContent.Game(pokemonX, "Nintendo 3DS", null, pokemonXDetails), QueueStrip(downloading, waiting = 2, failed = 0, conflicts = 1))
        rule.assertFitsIn("second-screen")
        rule.onNodeWithText("2013 · Role-playing (RPG), Turn-based strategy (TBS), Adventure").assertExists()
        rule.onNodeWithText("Game Freak, The Pokémon Company").assertExists()
        rule.onNodeWithText("Nintendo 3DS · ${formatBytes(1_700_000_000)} · Europe").assertExists() // the year isn't said twice
        assertThat(lines("summary")).isAtLeast(2)
        val cover = rule.onNodeWithTag("panel-cover").fetchSemanticsNode().boundsInRoot
        assertThat(with(rule.density) { cover.height.toDp().value }).isAtLeast(240f) // still beside the words, and large
        rule.saveScreenshot("second-screen-game-details")
        strip = null // without the strip, the summary takes its room
        rule.assertFitsIn("second-screen")
        rule.saveScreenshot("second-screen-game-details-alone")
    }

    /** The summary runs on as far as the room lets it, then ends in an ellipsis. */
    @Test fun aLongSummaryEndsInAnEllipsis() {
        show(CompanionContent.Game(pokemonX, "Nintendo 3DS", null, pokemonXDetails))
        rule.assertFitsIn("second-screen")
        val layout = layout("summary")
        assertThat(layout.isLineEllipsized(layout.lineCount - 1)).isTrue()
        // A short one is there in full.
        content = CompanionContent.Game(pokemonX, "Nintendo 3DS", null, pokemonXDetails.copy(summary = "Catch them all in Kalos."))
        rule.assertFitsIn("second-screen")
        assertThat(layout("summary").let { it.isLineEllipsized(it.lineCount - 1) }).isFalse()
    }

    /**
     * Under the game at its tallest there is little room: what RomM says takes what's left, a line at a time,
     * and what has no room is left out rather than cut through.
     */
    @Test fun rommsDetailsGiveWayToTheGamesOwnLines() {
        show(CompanionContent.Game(tallest, "Nintendo 3DS", null, pokemonXDetails.copy(romId = 30)))
        rule.assertFitsIn("second-screen")
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithTag("save-line", useUnmergedTree = true).assertExists()
        rule.saveScreenshot("second-screen-game-tallest-details")
    }

    /** Another game's details, still there as the focus moves on, aren't shown as this one's. */
    @Test fun anotherGamesDetailsArentShown() {
        show(CompanionContent.Game(tallest, "Nintendo 3DS", null, pokemonXDetails))
        rule.onNodeWithTag("about-the-game").assertDoesNotExist()
        rule.onNodeWithText("Game Freak, The Pokémon Company").assertDoesNotExist()
    }

    private fun layout(tag: String): TextLayoutResult {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    private fun lines(tag: String) = layout(tag).lineCount

    @Test fun aDownloadsProgressAndFinishingShow() {
        val downloading = tallest.copy(state = InstallState.DOWNLOADING, progress = 0.42f, jobId = 30, installPath = null, save = null,
            adopted = false, matchedByName = false)
        show(CompanionContent.Game(downloading, "Nintendo 3DS", null))
        rule.onNodeWithText("42%").assertExists()
        rule.onNodeWithText("Downloading").assertExists()
        rule.assertFitsIn("second-screen")
        rule.saveScreenshot("second-screen-downloading")
        content = CompanionContent.Game(downloading.copy(progress = 1f, stateDetail = "Finishing…", finishing = true), "Nintendo 3DS", null)
        rule.onNodeWithText("Finishing…").assertExists() // not "100%"
        rule.onNodeWithText("Moving it into your ROMs folder").assertExists()
        rule.assertFitsIn("second-screen")
    }

    /** Under the game at its tallest, the queue's strip at its longest: every line of both in full, or ellipsized. */
    @Test fun theQueueStripFitsUnderTheGame() {
        val downloading = QueueScreenTest.item(1, "The Legend of Zelda: Tears of the Kingdom (World) (En,Ja,Fr,De,Es,It,Nl,Ru,Ko,Zh) (v1.2.1)",
            JobPhase.DOWNLOADING, done = 1_300_000_000, speed = 11_800_000)
        show(CompanionContent.Game(tallest, "Nintendo 3DS", null), QueueStrip(downloading, waiting = 12, failed = 3, conflicts = 2))
        rule.assertFitsIn("second-screen")
        rule.onNodeWithText("12 more in the queue · 3 failed · 2 save conflicts").assertExists()
        val cover = rule.onNodeWithTag("panel-cover").fetchSemanticsNode().boundsInRoot
        assertThat(cover.bottom).isAtMost(rule.onNodeWithTag("queue-strip").fetchSemanticsNode().boundsInRoot.top)
        assertWithMessage("the counts").that(ellipsized("12 more in the queue · 3 failed · 2 save conflicts")).isFalse()
        rule.saveScreenshot("second-screen-game-queue")
        strip = QueueStrip(null, waiting = 0, failed = 0, conflicts = 1) // nothing downloading: one line
        rule.assertFitsIn("second-screen")
        rule.onNodeWithText("1 save conflict").assertExists()
    }

    /**
     * Found by review: beside a download's bar the counts had half the line, and the save conflicts,
     * which come last, were cut off. The bar takes what the counts leave.
     */
    @Test fun theStripCountsSaveConflictsWhileADownloadRuns() {
        val downloading = QueueScreenTest.item(1, "Final Fantasy VII (USA)", JobPhase.DOWNLOADING, done = 1_300_000_000, speed = 11_800_000)
        show(CompanionContent.Game(tallest, "Nintendo 3DS", null), QueueStrip(downloading, waiting = 1, failed = 0, conflicts = 1))
        for (queue in listOf(QueueStrip(downloading, 1, 0, 1), QueueStrip(downloading, 2, 1, 1), QueueStrip(downloading, 12, 3, 2))) {
            strip = queue
            rule.assertFitsIn("second-screen")
            assertWithMessage(queue.counts).that(ellipsized(queue.counts)).isFalse()
        }
    }

    /** Whether the one-line text [text] ends in an ellipsis. */
    private fun ellipsized(text: String): Boolean {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().isLineEllipsized(0)
    }

    /** The queue's conflict at its longest: a long title and reason, and RomM's side in full beside the Thor's. */
    @Test fun aConflictsTwoSidesFitTheBottomScreen() {
        val conflict = QueueScreenTest.pokemonX.copy(
            title = "The Legend of Zelda: Tears of the Kingdom (World) (En,Ja,Fr,De,Es,It,Nl,Ru,Ko,Zh) (v1.2.1)",
            reason = conflictReason(SyncDecision.SERVER_WENT_BACK.name), serverEmulator = "eden", serverDevice = "AYN Odin 2 Portal (Argosy)",
        )
        show(CompanionContent.Conflict(conflict))
        rule.assertFitsIn("second-screen")
        val thor = rule.onNodeWithTag("panel-thor-side").fetchSemanticsNode().boundsInRoot
        val server = rule.onNodeWithTag("panel-server-side").fetchSemanticsNode().boundsInRoot
        assertThat(server.left).isAtLeast(thor.right) // beside each other
        assertThat(server.top).isEqualTo(thor.top)
        rule.onNodeWithText("From AYN Odin 2 Portal (Argosy)").assertExists()
        rule.saveScreenshot("second-screen-conflict")
    }

    @Test fun aDownloadFitsTheBottomScreen() {
        val failed = QueueScreenTest.sample.failed[0]
        show(CompanionContent.Job(QueueScreenTest.sample.active[0]))
        rule.assertFitsIn("second-screen")
        rule.onNodeWithText(transferLine(QueueScreenTest.sample.active[0])).assertExists()
        rule.saveScreenshot("second-screen-download")
        content = CompanionContent.Job(failed.copy(retryable = false))
        rule.assertFitsIn("second-screen")
        rule.onNodeWithText("Retrying won't help until this is fixed.").assertExists()
    }

    /** Pairing's code, larger than the top screen has room for, with the code and how long it lasts under it. */
    @Test fun thePairingCodeFitsTheBottomScreen() {
        show(CompanionContent.Pairing(OnboardingScreenTest.session, System.currentTimeMillis()))
        rule.assertFitsIn("second-screen")
        rule.onNodeWithText("ABCD-2345").assertExists()
        rule.onNodeWithText("Waiting for approval · code expires in 10:00").assertExists()
        val qr = rule.onNodeWithContentDescription("QR code", substring = true).fetchSemanticsNode().boundsInRoot
        assertThat(qr.bottom).isAtMost(rule.onNodeWithTag("second-screen").fetchSemanticsNode().boundsInRoot.bottom)
        assertThat(with(rule.density) { qr.height.toDp().value }).isAtLeast(232f + 28f + 30f) // the top screen's, and more
        rule.saveScreenshot("second-screen-pairing")
    }

    /** It takes no input: the pad stays with Loom on the other screen. */
    @Test fun nothingOnTheBottomScreenTakesFocus() {
        show(CompanionContent.Game(tallest, "Nintendo 3DS", null))
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun idleShowsOnlyTheLoomMark() {
        show(CompanionContent.Idle)
        rule.onNodeWithContentDescription("Loom").assertExists()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).assertCountEquals(0)
        rule.saveScreenshot("second-screen-idle")
    }

    // --- dark after three minutes without a button or a touch: an OLED panel keeps the mark of what it shows for long ---

    /** [millis] go by. A change made before it takes effect at the next frame, so dark is checked a second after. */
    private fun waitFor(millis: Long) {
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(millis); rule.waitForIdle()
    }

    private val dark get() = rule.onNodeWithTag("second-screen-dark")

    @Test fun theBottomScreenGoesDarkAfterThreeMinutes() {
        show(CompanionContent.Game(tallest, "Nintendo 3DS", null))
        waitFor(DIM_AFTER.inWholeMilliseconds - 1_000)
        rule.onNodeWithTag("game-panel").assertExists()
        waitFor(1_000)
        dark.assertExists()
        rule.onNodeWithTag("second-screen").assertDoesNotExist() // nothing drawn: all black
        rule.saveScreenshot("second-screen-dark")
    }

    @Test fun aButtonPressLightsItAgain() {
        show(CompanionContent.Game(tallest, "Nintendo 3DS", null))
        waitFor(DIM_AFTER.inWholeMilliseconds + 1_000)
        dark.assertExists()
        inputs++
        rule.waitForIdle()
        rule.onNodeWithTag("game-panel").assertExists()
        dark.assertDoesNotExist()
        waitFor(DIM_AFTER.inWholeMilliseconds - 60_000)
        inputs++ // another press: three minutes from this one
        waitFor(DIM_AFTER.inWholeMilliseconds - 1_000)
        dark.assertDoesNotExist()
        waitFor(2_000)
        dark.assertExists()
    }

    /**
     * The panel as the activity draws it, from what it hands the second screen: a button or a touch
     * reaching Loom (the activity tells the holder of each) lights it again.
     */
    @Test fun inputReachingLoomLightsThePanelAgain() {
        val holder = CompanionHolder()
        holder.publish(this, CompanionContent.Game(tallest, "Nintendo 3DS", null))
        rule.setContent { LoomTestTheme { SecondScreenPanel(holder) } }
        waitFor(DIM_AFTER.inWholeMilliseconds + 1_000)
        dark.assertExists()
        holder.poke()
        rule.waitForIdle()
        rule.onNodeWithTag("game-panel").assertExists()
        dark.assertDoesNotExist()
    }

    /** A download moving on changes what the screen shows, but nobody is looking: it goes dark all the same. */
    @Test fun progressAloneDoesntKeepItLit() {
        val downloading = tallest.copy(state = InstallState.DOWNLOADING, progress = 0f, jobId = 30, installPath = null, save = null)
        show(CompanionContent.Game(downloading, "Nintendo 3DS", null))
        for (step in 1..6) {
            waitFor(30_000)
            content = CompanionContent.Game(downloading.copy(progress = step / 10f), "Nintendo 3DS", null)
        }
        waitFor(1_000)
        dark.assertExists()
    }

    /** The pairing code is read off it with a phone, which takes as long as it takes: it stays lit, then dims once it's gone. */
    @Test fun thePairingCodeStaysLit() {
        show(CompanionContent.Pairing(OnboardingScreenTest.session, System.currentTimeMillis()))
        waitFor(DIM_AFTER.inWholeMilliseconds * 2)
        rule.onNodeWithTag("pairing-panel").assertExists()
        content = CompanionContent.Idle // paired
        waitFor(DIM_AFTER.inWholeMilliseconds - 1_000)
        rule.onNodeWithContentDescription("Loom").assertExists()
        waitFor(2_000)
        dark.assertExists()
    }
}
