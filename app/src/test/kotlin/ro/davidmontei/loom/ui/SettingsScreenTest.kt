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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ro.davidmontei.loom.data.auth.Credentials
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.storage.mapping.MappingJson
import ro.davidmontei.loom.storage.mapping.PlatformMapper
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import ro.davidmontei.loom.storage.mapping.PlatformMatch
import ro.davidmontei.loom.storage.mapping.ROM_FILE_CATEGORIES
import ro.davidmontei.loom.ui.settings.MappingRow
import ro.davidmontei.loom.ui.settings.SettingsActions
import ro.davidmontei.loom.ui.settings.SettingsContent
import ro.davidmontei.loom.ui.settings.SettingsUiState
import ro.davidmontei.loom.ui.settings.StorageInfo

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = THOR_TOP_SCREEN)
class SettingsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(sample)

    companion object {
        private val mapper = PlatformMapper(MappingJson.shippedDefaults())
        private fun row(id: Int, name: String, slug: String, fsSlug: String, installed: Int = 0, bytes: Long = 0) =
            MappingRow(PlatformSummary(PlatformEntity(id, slug, fsSlug, name, name, 100, "t"), 100, installed, bytes),
                mapper.resolve(slug, fsSlug), null)

        val noop: SettingsActions get() = Recording(mutableListOf())

        val sample = SettingsUiState(
            settings = Settings(ServerConfig("http://100.64.0.7:8880/".toHttpUrl()),
                "5.3.1", "/storage/9C33-6BBD/ROMs", true, false, 1, null, null),
            credentials = Credentials("rmm_x", "david", "dev-1", null),
            mapping = listOf(
                row(1, "PlayStation", "psx", "psx", 4, 2_900_000_000), row(2, "Super Nintendo", "snes", "snes", 23, 61_000_000),
                row(3, "Arcade", "arcade", "fbneo"), row(4, "HyperScan", "hyperscan", "hyperscan"),
            ),
            storage = StorageInfo("/storage/9C33-6BBD/ROMs", "SD card", 112_000_000_000, 512_000_000_000),
            batteryUnrestricted = false, allFilesAccess = true,
        )
    }

    private val actions = Recording(calls)

    class Recording(private val calls: MutableList<String>) : SettingsActions {
        override fun updateAddress(address: String) { calls += "address:$address" }
        override fun repair() { calls += "repair" }
        override fun signOut() { calls += "signout" }
        override fun fullResync() { calls += "resync" }
        override fun setRomsRoot(dir: File) { calls += "root:$dir" }
        override fun volumes() = listOf(VolumeInfo("9C33-6BBD", File("/storage/9C33-6BBD"), "SD card", true))
        override fun saveOverride(entry: PlatformMappingEntry) { calls += "save:${entry.match.fsSlug ?: entry.match.slug}:${entry.folder}" }
        override fun resetOverride(match: PlatformMatch) { calls += "reset" }
        override fun exportMapping() { calls += "export" }
        override fun importMapping() { calls += "import" }
        override val exportPath = "/storage/emulated/0/Download/loom-mapping.json"
        override val cocoonFolders = listOf("psx", "snes", "gc")
        override fun setConcurrency(n: Int) { calls += "concurrency:$n" }
        override fun setSwap(swap: Boolean) { calls += "swap:$swap" }
        override fun openBatterySettings() { calls += "battery" }
        override fun licenseText() = (1..120).joinToString("\n\n") { "GNU GENERAL PUBLIC LICENSE, paragraph $it" }
        override fun fontLicense() = "SIL OPEN FONT LICENSE"
    }

    private fun show() {
        rule.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
    }

    /** Lets the press recompose first, so delayed focus requests it starts have run when it returns. */
    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
    }

    /** What the activity does with B (BACK) once nothing in Compose has used it. */
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
    }

    /** Recompose with the new state first, then let delayed focus requests run. */
    private fun update(change: (SettingsUiState) -> SettingsUiState) {
        state = change(state)
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
    }

    @Test fun sectionsAreReachableAndOpenWithA() {
        show()
        rule.onNodeWithTag("section-SERVER").assertIsFocused()
        repeat(4) { press(Key.DirectionDown) }
        rule.onNodeWithTag("section-CONTROLS").assertIsFocused()
        press(Key.DirectionCenter) // opens Controls on its switch
        press(Key.DirectionCenter)
        assertThat(calls).contains("swap:true")
    }

    /** Found on the Thor: A selected a section but left focus in the list; the next press did nothing new. */
    @Test fun aOnASectionMovesIntoIt() {
        show()
        press(Key.DirectionDown) // Storage
        press(Key.DirectionCenter)
        rule.onNodeWithText("Change folder").assertIsFocused()
    }

    /** Found on the Thor: Left from a section's control went to the section level with it, not the open one. */
    @Test fun leftReturnsToTheOpenSection() {
        show()
        press(Key.DirectionDown) // Storage
        press(Key.DirectionCenter) // Change folder, level with "Platform folders"
        press(Key.DirectionLeft)
        rule.onNodeWithTag("section-STORAGE").assertIsFocused()
    }

    /** Found by audit: B inside a section left Settings instead of going back to the section list. */
    @Test fun bInsideASectionReturnsToTheSectionList() {
        show()
        press(Key.DirectionDown); press(Key.DirectionDown) // Platform folders
        press(Key.DirectionCenter) // Export
        press(Key.DirectionDown); press(Key.DirectionDown) // a platform row
        back()
        rule.onNodeWithTag("section-MAPPING").assertIsFocused()
        assertThat(rule.activity.isFinishing).isFalse()
    }

    /** Found on the Thor: the licence text scrolled by touch only. */
    @Test fun licenceTextScrollsWithThePad() {
        show()
        repeat(5) { press(Key.DirectionDown) } // About
        press(Key.DirectionCenter) // into the section: the GPL button
        press(Key.DirectionCenter) // open the licence; Close has focus
        press(Key.DirectionUp)
        rule.onNodeWithTag("license-text", useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionDown) // scrolls rather than leaving
        rule.onNodeWithTag("license-text", useUnmergedTree = true).assertIsFocused()
        repeat(40) { press(Key.DirectionDown) } // to the end, then on to Close
        rule.onNodeWithText("Close").assertIsFocused()
    }

    @Test fun mappingEditorSavesAFolderOverride() {
        show()
        press(Key.DirectionDown); press(Key.DirectionDown)
        rule.onNodeWithTag("section-MAPPING").assertIsFocused()
        press(Key.DirectionCenter) // opens the section on its first control, Export
        rule.onNodeWithText("Export").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("export")
        press(Key.DirectionDown) // first platform row
        press(Key.DirectionCenter)
        rule.onNodeWithText("Cancel").assertIsFocused() // editor opens on the safe choice
        press(Key.DirectionRight); press(Key.DirectionCenter) // Save
        // Saved for its server folder; the folder untouched isn't pinned, so Loom still looks on the card.
        assertThat(calls).contains("save:psx:null")
    }

    @Test fun aFolderTheUserTypesIsSaved() {
        show()
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionCenter) // Platform folders: Export
        press(Key.DirectionDown); press(Key.DirectionCenter) // the first platform's editor
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[0].performTextReplacement("ps1")
        rule.waitForIdle()
        rule.onNodeWithText("Save", useUnmergedTree = true).performClick()
        assertThat(calls).contains("save:psx:ps1")
    }

    /** Found by audit: closing a dialog left focus on the section list, not on what opened it. */
    @Test fun closingTheFolderEditorGivesFocusBackToItsRow() {
        show()
        press(Key.DirectionDown); press(Key.DirectionDown) // Platform folders
        press(Key.DirectionCenter) // Export
        press(Key.DirectionDown); press(Key.DirectionDown) // Super Nintendo, the second platform
        press(Key.DirectionCenter)
        rule.onNodeWithText("Cancel").assertIsFocused()
        back()
        rule.onNodeWithText("Super Nintendo").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionCenter) // Cancel
        rule.onNodeWithText("Super Nintendo").assertIsFocused()
    }

    @Test fun closingTheLicenceGivesFocusBackToItsButton() {
        show()
        repeat(5) { press(Key.DirectionDown) } // About
        press(Key.DirectionCenter) // GNU GPL v3.0
        press(Key.DirectionRight)
        press(Key.DirectionCenter) // Inter font licence; Close has focus
        rule.onNodeWithText("Close").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Inter font licence").assertIsFocused()
    }

    @Test fun stayingSignedInGivesFocusBackToSignOut() {
        show()
        press(Key.DirectionCenter) // Server & pairing: the address
        press(Key.DirectionDown) // the pairing buttons, on the one nearest the address's centre
        rule.onNodeWithText("Sign out").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Stay signed in").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Sign out").assertIsFocused()
        assertThat(calls).doesNotContain("signout")
    }

    @Test fun allowingBackgroundDownloadsKeepsFocusInTheSection() {
        show()
        repeat(3) { press(Key.DirectionDown) } // Downloads
        press(Key.DirectionCenter) // the first concurrency chip
        press(Key.DirectionDown)
        rule.onNodeWithText("Allow background downloads").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("battery")
        update { it.copy(batteryUnrestricted = true) } // back from Android's dialog, allowed
        rule.onNodeWithText("1").assertIsFocused()
    }

    @Test fun pickingAFolderGivesFocusBackToChangeFolder() {
        show()
        press(Key.DirectionDown) // Storage
        press(Key.DirectionCenter) // Change folder
        press(Key.DirectionCenter) // the browser opens; the SD card isn't there in tests, so it lists volumes
        rule.onNodeWithText("SD card").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Use this folder").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("root:/storage/9C33-6BBD")
        rule.onNodeWithText("Change folder").assertIsFocused()
    }

    /** Found by audit: B in the folder browser left Settings instead of going up a folder or closing it. */
    @Test fun bGoesUpAFolderThenClosesTheBrowser() {
        show()
        press(Key.DirectionDown) // Storage
        press(Key.DirectionCenter) // Change folder
        press(Key.DirectionCenter) // the browser lists volumes
        press(Key.DirectionCenter) // into the SD card
        rule.onNodeWithText("Use this folder").assertIsFocused()
        back()
        rule.onNodeWithText("SD card").assertIsFocused()
        back()
        rule.onNodeWithText("Change folder").assertIsFocused()
        back()
        rule.onNodeWithTag("section-STORAGE").assertIsFocused()
        assertThat(calls.filter { it.startsWith("root:") }).isEmpty()
    }

    /** Found by audit: the browser opened at the card's root on "Use this folder", so A, A made the root the ROMs folder. */
    @Test fun changeFolderStartsOnTheCurrentRomsFolder() {
        val card = tmp.newFolder("9C33-6BBD")
        listOf("Android", "DCIM", "Download", "Music", "Pictures", "ROMs/snes").forEach { File(card, it).mkdirs() }
        val roms = File(card, "ROMs")
        state = sample.copy(storage = sample.storage.copy(romsRoot = roms.path))
        show()
        press(Key.DirectionDown) // Storage
        press(Key.DirectionCenter) // Change folder
        press(Key.DirectionCenter) // the browser opens beside the ROMs folder
        rule.onNodeWithText("ROMs").assertIsFocused()
        press(Key.DirectionCenter) // A goes into it
        rule.onNodeWithText("snes").assertExists()
        assertThat(calls.filter { it.startsWith("root:") }).isEmpty()
    }

    /** The top of the settings pane: anything above it has scrolled out of view. */
    private fun paneTop() = rule.onNodeWithTag("settings-sections").getBoundsInRoot().top

    /** Found by audit: back on a section's first control, the text above it stayed scrolled out of view. */
    @Test fun aSectionsFirstControlsShowItsHeading() {
        state = sample.copy(mapping = sample.mapping + sample.mapping.map { it.copy(platform = it.platform.copy(
            platform = it.platform.platform.copy(id = it.platform.platform.id + 10, displayName = it.platform.platform.displayName + " 2"))) })
        show()
        press(Key.DirectionDown); press(Key.DirectionDown) // Platform folders
        press(Key.DirectionCenter) // Export
        repeat(8) { press(Key.DirectionDown) } // down the platforms, scrolling the pane
        repeat(8) { press(Key.DirectionUp) } // to Export / Import
        assertThat(rule.onNodeWithText("WHERE EACH PLATFORM'S GAMES GO").getBoundsInRoot().top).isAtLeast(paneTop())
    }

    @Test fun theFolderBrowsersToggleShowsTheRomsFolder() {
        show()
        press(Key.DirectionDown) // Storage
        press(Key.DirectionCenter) // Change folder
        press(Key.DirectionCenter) // open the browser, on the SD card
        repeat(2) { press(Key.DirectionDown) } // down the games on the device, scrolling the pane
        repeat(3) { press(Key.DirectionUp) }
        rule.onNodeWithText("Close folder browser").assertIsFocused()
        assertThat(rule.onNodeWithText("ROMS FOLDER").getBoundsInRoot().top).isAtLeast(paneTop())
    }

    /** Found by audit: the editor was taller than the screen; its title and the folder's label scrolled out of reach. */
    @Test fun theFolderEditorFitsTheScreen() {
        show()
        press(Key.DirectionDown); press(Key.DirectionDown) // Platform folders
        press(Key.DirectionCenter) // Export
        press(Key.DirectionDown) // PlayStation
        press(Key.DirectionCenter)
        rule.onNodeWithText("Cancel").assertIsFocused()
        val editor = rule.onNode(hasText("Files to download")).fetchSemanticsNode()
        assertThat(editor.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()).isEqualTo(0f)
    }

    /**
     * Opens PlayStation's folder editor laid out as on the Thor: run with native graphics, text is measured
     * with the real font, so the chips wrap and line up as on the device. Robolectric starts such a window in
     * touch mode, where nothing takes focus, so the pad's keyboard mode is provided around the dialogs too.
     */
    private fun openFolderEditorAsOnTheThor() {
        val pad = object : InputModeManager {
            override val inputMode get() = InputMode.Keyboard
            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }
        rule.setContent { CompositionLocalProvider(LocalInputModeManager provides pad) { LoomTestTheme { SettingsContent(state, actions) } } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionCenter) // Platform folders: Export
        press(Key.DirectionDown); press(Key.DirectionCenter) // PlayStation's editor
        rule.onNodeWithText("Cancel").assertIsFocused()
    }

    private fun chip(text: String) = rule.onNode(hasText(text) and hasClickAction())

    /** The label of the focused chip or button; null for a control without one (the folder field). */
    private fun focusedText(): String? =
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString()

    private fun assertFolderFieldFocused() = rule.onNodeWithTag("folder-field", useUnmergedTree = true).assertIsFocused()

    /**
     * Found on the Thor: Left from "Game.m3u folder" went down to the "walkthrough" chip, not to the folder
     * field. Down from the field went across to "Game.m3u folder" too, past the files below it.
     */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun leftFromTheFolderEditorsChoicesGoesToTheFolderField() {
        openFolderEditorAsOnTheThor()
        repeat(4) { if (focusedText() != "Game.m3u folder" && focusedText() != "Plain folder") press(Key.DirectionUp) }
        if (focusedText() == "Plain folder") press(Key.DirectionLeft)
        chip("Game.m3u folder").assertIsFocused()
        press(Key.DirectionLeft)
        assertFolderFieldFocused()
        press(Key.DirectionRight) // the archive choice beside the field
        chip("Keep as is").assertIsFocused()
        press(Key.DirectionLeft)
        assertFolderFieldFocused()
        press(Key.DirectionDown) // the files below
        chip("game").assertIsFocused()
    }

    /**
     * Found on the Thor: Up from the first row of file chips reached the folder field only from "game"; from
     * the others under the field ("walkthrough"…) it went to the right column. Chips under the right column
     * still go to the choice above them.
     */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun upFromTheFirstRowOfFilesReachesTheFolderField() {
        openFolderEditorAsOnTheThor()
        val bounds = ROM_FILE_CATEGORIES.associateWith { chip(it).getBoundsInRoot() }
        val firstRow = ROM_FILE_CATEGORIES.filter { bounds.getValue(it).top == bounds.getValue("game").top }
        val rightColumn = chip("Keep as is").getBoundsInRoot().left
        val underField = firstRow.filter { bounds.getValue(it).let { b -> (b.left + b.right) / 2 } < rightColumn }
        assertThat(underField.size).isGreaterThan(1) // as on the Thor: more than "game"…
        assertThat(firstRow - underField).isNotEmpty() // …and some under the right column
        repeat(4) { if (focusedText() !in firstRow) press(Key.DirectionUp) } // from Cancel, up over the files
        for (c in firstRow) {
            repeat(firstRow.size) {
                val at = firstRow.indexOf(focusedText())
                if (at != firstRow.indexOf(c)) press(if (at < firstRow.indexOf(c)) Key.DirectionRight else Key.DirectionLeft)
            }
            chip(c).assertIsFocused()
            press(Key.DirectionUp)
            if (c in underField) assertFolderFieldFocused() else assertThat(focusedText()).isAnyOf("Game.m3u folder", "Plain folder")
            press(Key.DirectionDown) // back to the first row
        }
    }

    /**
     * After a tap on a section the pad's first A (replayed once focus is back) belongs to that section, not
     * to Server & pairing: focus entering the section list goes to the open section. Robolectric can't enter
     * touch mode, so the test switches Compose's own input mode, which InitialFocus and focusability read.
     */
    @Test fun afterATapThePadStartsOnTheOpenSection() {
        var mode by mutableStateOf(InputMode.Keyboard)
        val inputModes = object : InputModeManager {
            override val inputMode get() = mode
            override fun requestInputMode(inputMode: InputMode): Boolean { mode = inputMode; return true }
        }
        rule.setContent {
            LoomTestTheme { CompositionLocalProvider(LocalInputModeManager provides inputModes) { SettingsContent(state, actions) } }
        }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        mode = InputMode.Touch
        rule.onNodeWithTag("section-CONTROLS").performClick()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        mode = InputMode.Keyboard // a pad press leaves touch mode
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        rule.onNodeWithTag("section-CONTROLS").assertIsFocused()
        press(Key.DirectionCenter) // the replayed A opens it…
        press(Key.DirectionCenter) // …and the next acts on it
        assertThat(calls).contains("swap:true")
    }

    /** Found by audit: Pair again threw the working token away at once, without asking. */
    @Test fun pairAgainAsksFirst() {
        show()
        press(Key.DirectionCenter) // Server & pairing: the address
        press(Key.DirectionDown); press(Key.DirectionLeft); press(Key.DirectionLeft) // the first pairing button
        rule.onNodeWithText("Pair again").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Cancel").assertIsFocused() // the dialog opens on the safe choice
        press(Key.DirectionCenter)
        assertThat(calls).doesNotContain("repair")
        rule.onNodeWithText("Pair again").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionRight); press(Key.DirectionCenter) // Pair again, in the dialog
        assertThat(calls).contains("repair")
    }

    /** Found by review: back from pairing again, Settings came back with focus on its first section, not on Pair again. */
    @Test fun backFromPairingAgainFocusesPairAgain() {
        val restore = StateRestorationTester(rule)
        restore.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        press(Key.DirectionCenter) // Server & pairing: the address
        press(Key.DirectionDown); press(Key.DirectionLeft); press(Key.DirectionLeft) // Pair again
        press(Key.DirectionCenter); press(Key.DirectionRight); press(Key.DirectionCenter) // confirmed
        assertThat(calls).contains("repair")
        // Pairing opens on top and Settings leaves composition; coming back recreates it from saved state.
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithText("Pair again").assertIsFocused()
    }
}
