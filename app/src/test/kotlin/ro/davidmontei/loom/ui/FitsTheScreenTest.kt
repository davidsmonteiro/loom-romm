package ro.davidmontei.loom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ro.davidmontei.loom.data.catalog.CatalogSyncStatus
import ro.davidmontei.loom.storage.mapping.SystemFolder
import ro.davidmontei.loom.storage.saves.EdenProfile
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.saves.SaveSyncEngine
import ro.davidmontei.loom.data.settings.SaveSyncOutcome
import ro.davidmontei.loom.data.saves.conflictReason
import ro.davidmontei.loom.storage.saves.SyncDecision
import ro.davidmontei.loom.storage.saves.SyncOutcome
import ro.davidmontei.loom.data.saves.EdenSaves
import ro.davidmontei.loom.data.saves.SaveLook
import ro.davidmontei.loom.ui.library.FolderQuestion
import ro.davidmontei.loom.ui.saves.SaveFolderOption
import ro.davidmontei.loom.ui.saves.SaveFolderQuestion
import ro.davidmontei.loom.ui.library.GameSave
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.InstallState
import ro.davidmontei.loom.ui.library.LibraryContent
import ro.davidmontei.loom.ui.onboarding.OnboardingActions
import ro.davidmontei.loom.ui.onboarding.OnboardingContent
import ro.davidmontei.loom.ui.onboarding.OnboardingState
import ro.davidmontei.loom.ui.onboarding.OnboardingStep
import ro.davidmontei.loom.ui.onboarding.PairMode
import ro.davidmontei.loom.ui.queue.QueueContent
import ro.davidmontei.loom.ui.settings.SettingsActions
import ro.davidmontei.loom.ui.settings.SettingsContent
import ro.davidmontei.loom.ui.settings.SettingsSection

/**
 * Screens that must fit the Thor's top screen without scrolling. Native graphics measure text with
 * the real font, so line wrapping (and so height) is as on the device. Focus isn't tested here: these
 * windows start in touch mode (SettingsScreenTest's folder editor tests switch them to the pad's).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = THOR_TOP_SCREEN)
class FitsTheScreenTest {
    @get:Rule val rule = createComposeRule()

    private object NoopOnboardingActions : OnboardingActions {
        override val knownSystemFolders = emptySet<String>()
        override fun go(step: OnboardingStep) = Unit
        override fun setAddress(text: String) = Unit
        override fun connect() = Unit
        override fun setPairMode(mode: PairMode) = Unit
        override fun setPairInput(text: String) = Unit
        override fun submitPairInput() = Unit
        override fun startDeviceAuth() = Unit
        override fun pairAgain() = Unit
        override fun refreshStorage() = Unit
        override fun pickRoot(dir: File) = Unit
        override fun createSuggestedRoot() = Unit
        override fun finish() = Unit
    }

    /** How far the screen's scrolling pane (the one holding [text]) could scroll, in px: 0 when everything fits. */
    private fun overflow(text: String? = null): Float {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        val scrolls = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        val pane = rule.onNode(if (text == null) scrolls else scrolls and hasText(text)).fetchSemanticsNode()
        return pane.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
    }

    /** How far Settings' section pane could scroll, in px: 0 when the open section fits. */
    private fun settingsOverflow(): Float {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        return rule.onNodeWithTag("settings-content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
    }

    /** How many lines the text holding [text] takes. */
    private fun lineCount(text: String): Int {
        val node = rule.onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().lineCount
    }

    /** Whether the one-line text holding [text] ends in an ellipsis, wherever the ellipsis falls. */
    private fun ellipsized(text: String): Boolean {
        val node = rule.onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().let { it.isLineEllipsized(it.lineCount - 1) }
    }

    /** Whether the text holding [text] is cut short: an ellipsis, or lines left out. */
    private fun cutShort(text: String): Boolean {
        val node = rule.onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().hasVisualOverflow
    }

    /** Found by audit: with the plain-HTTP warning showing, the Server step scrolled and cut off its title. */
    @Test fun theServerStepFitsWithTheHttpWarning() {
        val state = OnboardingState(
            step = OnboardingStep.SERVER, addressInput = "203.0.113.5:8080", serverSummary = "RomM 5.3.1 at http://203.0.113.5:8080/",
            insecureWarning = "203.0.113.5 is a public address over plain HTTP: your token would travel unencrypted. Use https:// if you can.",
        )
        rule.setContent { LoomTestTheme { OnboardingContent(state, NoopOnboardingActions) } }
        assertThat(overflow()).isEqualTo(0f)
        rule.saveScreenshot("onboarding-server-http-warning")
    }

    /** Every section is in the list without scrolling it: a section that has to be scrolled to is easily missed. */
    @Test fun theSettingsSectionListFits() {
        rule.setContent { LoomTestTheme { SettingsContent(SettingsScreenTest.sample, SettingsScreenTest.noop) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        val list = rule.onNodeWithTag("settings-sections").fetchSemanticsNode()
        assertThat(list.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()).isEqualTo(0f)
        val last = rule.onNodeWithTag("section-${SettingsSection.entries.last().name}").fetchSemanticsNode()
        assertThat(last.boundsInRoot.bottom).isAtMost(list.boundsInRoot.bottom)
    }

    /**
     * Found by review: Downloads & saves scrolled by over 400 px, with every save control below the
     * fold. Downloads and Saves are two sections now, and each fits.
     */
    @Test fun theDownloadsSectionFits() {
        var state by mutableStateOf(SettingsScreenTest.sample)
        rule.setContent { LoomTestTheme { SettingsContent(state, SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-DOWNLOADS").performClick()
        assertThat(settingsOverflow()).isEqualTo(0f) // battery-optimised, with the button to allow background downloads
        state = state.copy(batteryUnrestricted = true)
        assertThat(settingsOverflow()).isEqualTo(0f)
    }

    /**
     * Saves at its tallest: a failed last sync whose reason, the longest, sits beside Pair again; a
     * chosen Azahar folder (with the button back to the 3DS one); and a chosen Eden folder without
     * user/save, no prod.keys, and two profiles to choose between.
     */
    @Test fun theSavesSectionFits() {
        val settings = SettingsScreenTest.sample.settings!!.copy(
            azaharUserDir = "/storage/6365-3230/Emulation/Azahar/user",
            edenSaveDir = "/storage/6365-3230/Emulation/Eden/nand",
            lastSaveSync = SaveSyncOutcome(System.currentTimeMillis(), SaveSyncEngine.NO_DEVICE, failed = true, needsPairing = true),
        )
        val storage = SettingsScreenTest.sample.storage.copy(
            edenProfiles = listOf(EdenProfile("0123456789ABCDEF0123456789ABCDEF", 12, null), EdenProfile("4E431822D6DEFD527268BA35B5C09533", 3, null)),
        )
        rule.setContent { LoomTestTheme { SettingsContent(SettingsScreenTest.sample.copy(settings = settings, storage = storage), SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-SAVES").performClick()
        assertThat(settingsOverflow()).isEqualTo(0f)
        rule.onNodeWithText("Pair again").assertExists()
        rule.onNodeWithText("Use 3DS folder").assertExists()
        rule.onNodeWithText("Use saves/Eden").assertExists()
        rule.onNodeWithTag("eden-profile").assertExists()
        // How Loom found each folder, in full, on one line. (Labels with letter spacing report a width
        // overflow they don't have, so the ellipsis and the line count are what's checked.)
        for (line in listOf("Chosen here.", "Chosen, but it has no user/save.")) {
            assertWithMessage(line).that(ellipsized(line)).isFalse()
            assertWithMessage(line).that(lineCount(line)).isEqualTo(1)
        }
        assertThat(cutShort("Saves from RomM always wait for you.")).isFalse() // the switch's explanation, in full
        rule.saveScreenshot("settings-saves-tallest")
    }

    /** Eight Eden profiles: one chip in Saves, and a list of them all that fits beside its explanation. */
    @Test fun manyEdenProfilesFit() {
        val profiles = (1..8).map { i -> EdenProfile("4E431822D6DEFD527268BA35B5C095%02X".format(i), 40 + i, java.time.Instant.parse("2026-10-14T12:00:00Z").minusSeconds(86_400L * i)) }
        val settings = SettingsScreenTest.sample.settings!!.copy(
            azaharUserDir = "/storage/6365-3230/Emulation/Azahar/user", edenSaveDir = "/storage/6365-3230/Emulation/Eden/nand",
            lastSaveSync = SaveSyncOutcome(System.currentTimeMillis(), SaveSyncEngine.NO_DEVICE, failed = true, needsPairing = true),
        )
        val state = SettingsScreenTest.sample.copy(settings = settings, storage = SettingsScreenTest.sample.storage.copy(edenProfiles = profiles))
        rule.setContent { LoomTestTheme { SettingsContent(state, SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-SAVES").performClick()
        assertThat(settingsOverflow()).isEqualTo(0f)
        rule.onNodeWithTag("eden-profile").performClick()
        assertThat(overflow("Whose Switch saves does Loom sync?")).isEqualTo(0f)
        assertThat(cutShort("Yours is likely the one whose saves changed last.")).isFalse()
        rule.saveScreenshot("settings-saves-eden-profiles")
    }

    /** Saves as on the Thor: both folders found where Loom looks, shown in full, and Loom able to read title IDs. */
    @Test fun theSavesSectionShowsTheThorsFoldersInFull() {
        val storage = SettingsScreenTest.sample.storage.copy(
            romsRoot = "/storage/6365-3230/ROMs", azaharFound = "/storage/6365-3230/ROMs/n3ds", edenFound = "/storage/6365-3230/ROMs/saves/Eden",
            edenProfiles = listOf(EdenProfile("4E431822D6DEFD527268BA35B5C09533", 2, null)), prodKeys = "/storage/6365-3230/ROMs/prod.keys",
        )
        rule.setContent { LoomTestTheme { SettingsContent(SettingsScreenTest.sample.copy(storage = storage), SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-SAVES").performClick()
        assertThat(settingsOverflow()).isEqualTo(0f)
        rule.saveScreenshot("settings-saves-thor")
        for (line in listOf("/storage/6365-3230/ROMs/n3ds", "/storage/6365-3230/ROMs/saves/Eden", "Found in ROMs/saves/Eden.")) {
            assertWithMessage(line).that(ellipsized(line)).isFalse()
        }
    }

    /**
     * Found by audit: the platform folder editor was taller than the screen. With Reset, and the longest hint
     * under the field for a platform without other folders of its own: eight Cocoon folders.
     */
    @Test fun thePlatformFolderEditorFits() {
        val state = SettingsScreenTest.sample.copy(mapping = listOf(SettingsScreenTest.chosenRow(1, "PlayStation 2", "ps2", "ps2", "ps2")))
        val cocoon = listOf("psx", "ps2", "ps3", "psp", "pspminis", "pico8", "pokemini", "pc88", "pc98", "pcfx")
        val actions = object : SettingsActions by SettingsScreenTest.noop { override val cocoonFolders = cocoon }
        rule.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.onNodeWithTag("section-MAPPING").performClick()
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithText("PlayStation 2").performClick()
        rule.onNodeWithText("Other Cocoon folders: psx, ps3, psp, pspminis, pico8, pokemini, pc88, pc98", useUnmergedTree = true).assertExists()
        assertThat(overflow("Files to download")).isEqualTo(0f)
    }

    /**
     * A 3DS game's details at their tallest: a title and install path long enough to wrap, the
     * save's line at its longest, and a row of save buttons above the dialog's own.
     */
    @Test fun aGamesDetailsWithItsSaveFit() {
        val title = "Pokémon Omega Ruby (USA) (En,Ja,Fr,De,Es,It,Ko) (Rev 2) [Special Demo Edition]"
        val game = GameItem(30, title, 3_900_000_000, null, listOf("USA"), InstallState.INSTALLED, null, null, null, adopted = true,
            removedOnServer = false, installPath = "n3ds/$title.3ds", save = GameSave(true, SaveSyncState.SERVER_NEWER, null, "azahar"))
        var state by mutableStateOf(LibraryScreenTest().state.copy(games = listOf(game)))
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("game-30").performClick()
        assertThat(overflow(title)).isEqualTo(0f)
        rule.saveScreenshot("library-details-save")
        val failed = GameSave(true, SaveSyncState.ERROR, "Couldn't read the save: /storage/6365-3230/ROMs/n3ds/sdmc/Nintendo 3DS/" +
            "00000000000000000000000000000000/00000000000000000000000000000000/title/00040000/0011c400/data (Permission denied)", "azahar")
        state = state.copy(games = listOf(game.copy(save = failed)))
        assertThat(overflow(title)).isEqualTo(0f)
        // A look that couldn't ask RomM says so in full beside the buttons.
        state = state.copy(games = listOf(game.copy(save = failed.copy(look = SaveLook.ThorOnly("RomM rejected Loom's token")))))
        assertThat(overflow(title)).isEqualTo(0f)
        assertThat(cutShort("checked the Thor's side only")).isFalse()
        rule.saveScreenshot("library-details-save-look")
    }

    /**
     * A Switch game's details at their tallest: a long title and path, the folder question's line
     * at its longest, then a chosen folder with all three save buttons in one row.
     */
    @Test fun aSwitchGamesDetailsWithItsSaveFit() {
        val title = "The Legend of Zelda: Tears of the Kingdom (World) (En,Ja,Fr,De,Es,It,Nl,Ru,Ko,Zh) (v1.2.1)"
        val ask = GameSave(true, SaveSyncState.NEEDS_FOLDER, EdenSaves.WRONG_KEY, "eden")
        val game = GameItem(40, title, 16_300_000_000, null, listOf("World"), InstallState.INSTALLED, null, null, null, adopted = true,
            removedOnServer = false, installPath = "switch/$title.xci", save = ask)
        var state by mutableStateOf(LibraryScreenTest().state.copy(games = listOf(game)))
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("game-40").performClick()
        assertThat(overflow(title)).isEqualTo(0f)
        assertThat(cutShort(EdenSaves.WRONG_KEY)).isFalse()
        rule.saveScreenshot("library-details-switch-folder")
        // Missing on the Thor, with automatic upload off: Upload save, Restore server save and Change save folder.
        state = state.copy(games = listOf(game.copy(save = GameSave(true, SaveSyncState.LOCAL_MISSING, null, "eden", autoUpload = false, folderChosen = true))))
        assertThat(overflow(title)).isEqualTo(0f)
        rule.saveScreenshot("library-details-switch-buttons")
    }

    /** Which Eden save folder is a game's: the explanation in full, and the folders in a list of their own. */
    @Test fun theSaveFolderQuestionFits() {
        val title = "The Legend of Zelda: Tears of the Kingdom (World) (En,Ja,Fr,De,Es,It,Nl,Ru,Ko,Zh) (v1.2.1)"
        val game = GameItem(40, title, 16_300_000_000, null, listOf("World"), InstallState.INSTALLED, null, null, null, adopted = true,
            removedOnServer = false, installPath = "switch/$title.xci", save = GameSave(true, SaveSyncState.NEEDS_FOLDER, EdenSaves.NO_KEYS, "eden"))
        val options = (1..12).map { SaveFolderOption("0100%012X".format(it * 0x1000L), 4, 1_200_000, System.currentTimeMillis() - it * 3_600_000L) }
        val state = LibraryScreenTest().state.copy(games = listOf(game), saveFolderQuestion = SaveFolderQuestion(40, title, "eden", options, current = null))
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        assertThat(overflow("Which Eden save folder is $title's?")).isEqualTo(0f)
        rule.saveScreenshot("library-save-folder-question")
    }

    /** Which of a game's two Eden folders Loom syncs: the question in full beside both folders, from details that fit too. */
    @Test fun theSyncedFolderQuestionFits() {
        val title = "The Legend of Zelda: Tears of the Kingdom (World) (En,Ja,Fr,De,Es,It,Nl,Ru,Ko,Zh) (v1.2.1)"
        val moved = GameSave(true, SaveSyncState.MOVED, "Eden now keeps this game's save in its profile folder", "eden")
        val game = GameItem(40, title, 16_300_000_000, null, listOf("World"), InstallState.INSTALLED, null, null, null, adopted = true,
            removedOnServer = false, installPath = "switch/$title.xci", save = moved)
        var state by mutableStateOf(LibraryScreenTest().state.copy(games = listOf(game)))
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("game-40").performClick()
        assertThat(overflow(title)).isEqualTo(0f)
        assertThat(cutShort("Choose which one Loom syncs.")).isFalse()

        val eden = "/storage/6365-3230/ROMs/saves/Eden/user/save/0000000000000000"
        val question = SaveFolderQuestion(40, title, "eden", listOf(
            SaveFolderOption("$eden/4E431822D6DEFD527268BA35B5C09533/0100F2C0115B6000", 12, 41_000_000, System.currentTimeMillis() - 60_000,
                name = "Profile folder"),
            SaveFolderOption("$eden/${"0".repeat(32)}/0100F2C0115B6000", 3, 9_000_000, System.currentTimeMillis() - 86_400_000,
                name = "Console-wide folder"),
        ), current = "$eden/${"0".repeat(32)}/0100F2C0115B6000", kind = SaveFolderQuestion.Kind.SYNCED)
        rule.onNodeWithText("Close").performClick() // as Choose save folder does
        state = state.copy(saveFolderQuestion = question)
        assertThat(overflow("Which Eden folder should Loom sync $title's save from?")).isEqualTo(0f)
        rule.saveScreenshot("library-synced-folder-question")
        // When it changed, then what it holds: a line each, in full.
        assertThat(lineCount("synced so far")).isEqualTo(2)
        assertThat(ellipsized("synced so far")).isFalse()
    }

    /** The longest reason a save conflict can give. */
    private val longestConflictReason = SyncDecision.entries.filter { it.outcome == SyncOutcome.CONFLICT }
        .map { conflictReason(it.name) }.maxBy { it.length }

    /** A save conflict's choice at its tallest: a long title, the longest reason, and RomM's side in full. */
    @Test fun theSaveConflictChoiceFits() {
        val title = "Pokémon Omega Ruby (USA) (En,Ja,Fr,De,Es,It,Ko) (Rev 2)"
        val conflict = QueueScreenTest.pokemonX.copy(title = title, reason = longestConflictReason,
            serverDevice = "AYN Odin 2 Portal (Argosy)")
        rule.setContent { LoomTestTheme { QueueContent(QueueScreenTest.sample.copy(conflicts = listOf(conflict)), NoopQueueActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("conflict-7").performClick()
        assertThat(overflow("$title: which save to keep?")).isEqualTo(0f)
        rule.saveScreenshot("queue-conflict-choice")
    }

    /** The same, for a Switch game whose save folder the user picked: Change save folder fits beside the answers. */
    @Test fun theSaveConflictChoiceWithChangeSaveFolderFits() {
        val title = "The Legend of Zelda: Tears of the Kingdom (World) (En,Ja,Fr,De,Es,It,Nl,Ru,Ko,Zh) (v1.2.1)"
        val conflict = QueueScreenTest.pokemonX.copy(title = title, reason = longestConflictReason,
            serverEmulator = "eden", emulator = "eden", serverDevice = "AYN Odin 2 Portal (Argosy)", folderChosen = true)
        rule.setContent { LoomTestTheme { QueueContent(QueueScreenTest.sample.copy(conflicts = listOf(conflict)), NoopQueueActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("conflict-7").performClick()
        rule.onNodeWithText("Change save folder").assertExists()
        assertThat(overflow("$title: which save to keep?")).isEqualTo(0f)
        assertThat(cutShort("Change save folder picks again.")).isFalse()
        rule.saveScreenshot("queue-conflict-choice-change-folder")
    }

    /** The editor with arcade's other folders under the field, and what the card holds of each. */
    @Test fun thePlatformFolderEditorFitsWithThePlatformsOwnFolders() {
        val state = SettingsScreenTest.sample.copy(mapping = listOf(SettingsScreenTest.chosenRow(1, "Arcade", "arcade", "arcade", "arcade")))
        val actions = object : SettingsActions by SettingsScreenTest.noop {
            override suspend fun foldersOnCard(names: List<String>) = listOf(SystemFolder("mame", 1234), SystemFolder("fbneo", 0))
        }
        rule.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.onNodeWithTag("section-MAPPING").performClick()
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithText("Arcade").performClick()
        rule.onNodeWithText("Other folders for Arcade: mame (1234 games), fbneo (no games), fba (not on the card)", useUnmergedTree = true)
            .assertExists()
        assertThat(overflow("Files to download")).isEqualTo(0f)
        rule.saveScreenshot("settings-mapping-editor-arcade")
    }

    /** How far the scrolling node tagged [tag] could scroll, in px: 0 when everything fits. */
    private fun overflowOf(tag: String): Float {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        return rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
    }

    /** Library sync at its tallest: a failed sync's reason beside Full resync. */
    @Test fun theLibrarySyncSectionFits() {
        val state = SettingsScreenTest.sample.copy(
            settings = SettingsScreenTest.sample.settings!!.copy(lastCatalogSyncEpochMs = System.currentTimeMillis() - 3 * 3_600_000L),
            sync = CatalogSyncStatus(running = false, error = "RomM rejected Loom's token. Pair again in Settings.", needsPairing = true),
        )
        rule.setContent { LoomTestTheme { SettingsContent(state, SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-LIBRARY").performClick()
        val overflow = overflowOf("settings-content")
        rule.saveScreenshot("settings-library-sync")
        assertThat(overflow).isEqualTo(0f)
    }

    /**
     * The note that RomM can't be reached sits in the header beside "Synced …", in full: the title,
     * the buttons, the platforms and the games stay exactly where they were.
     */
    @Test fun theOfflineNoteFitsTheHeaderAndMovesNothing() {
        var state by mutableStateOf(LibraryScreenTest().state)
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        fun layout(): List<Any> {
            rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
            val tagged = listOf("search", "platform-1", "game-1", "game-8").map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
            val buttons = listOf("Sync library", "Grid or list", "Settings").map { rule.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot }
            val title = rule.onAllNodesWithText("Super Nintendo", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
            return tagged + buttons + title
        }
        val online = layout()
        state = state.copy(header = state.header.copy(offline = true))
        assertThat(layout()).isEqualTo(online)
        for (line in listOf("Offline · showing your saved library", "Synced 5 min ago")) {
            assertWithMessage(line).that(ellipsized(line)).isFalse()
            assertWithMessage(line).that(lineCount(line)).isEqualTo(1)
        }
        rule.saveScreenshot("library-offline")
    }

    /** The question at a platform's first download, with its few options and with every folder of the Thor's ES-DE template. */
    @Test fun theFolderQuestionFits() {
        val options = listOf(SystemFolder("mame", 0, "Multiple Arcade Machine Emulator"), SystemFolder("arcade", 2, "Arcade"),
            SystemFolder("fbneo", 5, "FinalBurn Neo"), SystemFolder("fba", 0, "FinalBurn Alpha"))
        val game = GameItem(1, "Alley Master", 101_385, null, emptyList(), InstallState.NOT_INSTALLED, null, null, null, false, false, null)
        val question = FolderQuestion(game, "Arcade", "arcade", "arcade", options, "mame")
        var state by mutableStateOf(LibraryScreenTest().state.copy(folderQuestion = question))
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        assertThat(overflow("Where does Cocoon look for Arcade games?")).isEqualTo(0f)
        rule.saveScreenshot("library-folder-question")
        state = state.copy(folderQuestion = question.copy(allFolders = (1..178).map { SystemFolder("system$it", 0, "Some System $it") }))
        assertThat(overflow("Where does Cocoon look for Arcade games?")).isEqualTo(0f)
    }
}
