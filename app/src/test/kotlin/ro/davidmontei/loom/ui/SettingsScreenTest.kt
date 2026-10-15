package ro.davidmontei.loom.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.test.assertTextEquals
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
import ro.davidmontei.loom.data.catalog.CatalogSyncStatus
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.data.saves.SaveSyncEngine
import ro.davidmontei.loom.data.sent.SendToDeviceState
import ro.davidmontei.loom.data.settings.SaveSyncOutcome
import ro.davidmontei.loom.data.settings.CatalogRefresh
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.storage.mapping.MappingJson
import ro.davidmontei.loom.storage.mapping.PlatformMapper
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import ro.davidmontei.loom.storage.mapping.PlatformMatch
import ro.davidmontei.loom.storage.mapping.ROM_FILE_CATEGORIES
import ro.davidmontei.loom.storage.mapping.SystemFolder
import ro.davidmontei.loom.storage.saves.EdenProfile
import ro.davidmontei.loom.ui.settings.MappingRow
import ro.davidmontei.loom.ui.settings.SettingsActions
import ro.davidmontei.loom.ui.settings.SettingsContent
import ro.davidmontei.loom.ui.settings.SettingsSection
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
        fun row(id: Int, name: String, slug: String, fsSlug: String, installed: Int = 0, bytes: Long = 0) =
            MappingRow(PlatformSummary(PlatformEntity(id, slug, fsSlug, name, name, 100, "t"), 100, installed, bytes),
                mapper.resolve(slug, fsSlug), null)

        /** A platform whose folder the user chose, so the shipped mapping no longer names it. */
        fun chosenRow(id: Int, name: String, slug: String, fsSlug: String, folder: String): MappingRow {
            val chosen = PlatformMappingEntry(PlatformMatch(fsSlug = fsSlug), folder = folder)
            return row(id, name, slug, fsSlug).copy(resolved = PlatformMapper(MappingJson.shippedDefaults(), listOf(chosen)).resolve(slug, fsSlug),
                override = chosen)
        }

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
        override fun setCatalogRefresh(refresh: CatalogRefresh) { calls += "refresh:$refresh" }
        override fun setRefreshOnWifiOnly(wifiOnly: Boolean) { calls += "wifi:$wifiOnly" }
        override fun setRomsRoot(dir: File) { calls += "root:$dir" }
        override fun volumes() = listOf(VolumeInfo("9C33-6BBD", File("/storage/9C33-6BBD"), "SD card", true))
        override fun saveOverride(entry: PlatformMappingEntry) { calls += "save:${entry.match.fsSlug ?: entry.match.slug}:${entry.folder}" }
        override fun resetOverride(match: PlatformMatch) { calls += "reset" }
        override fun exportMapping() { calls += "export" }
        override fun importMapping() { calls += "import" }
        override val exportPath = "/storage/emulated/0/Download/loom-mapping.json"
        override val cocoonFolders = listOf("psx", "snes", "gc")
        /** The ROMs folder's system folders; null when Loom can't look. */
        var onCard: List<SystemFolder>? = null
        override suspend fun foldersOnCard(names: List<String>) = onCard?.filter { f -> names.any { it.equals(f.name, ignoreCase = true) } }
        override fun setConcurrency(n: Int) { calls += "concurrency:$n" }
        override fun setReceiveFromRomm(on: Boolean) { calls += "receive:$on" }
        override fun setAutoUploadSaves(on: Boolean) { calls += "autoUpload:$on" }
        override fun setAzaharFolder(dir: File?) { calls += "azahar:$dir" }
        override fun setEdenFolder(dir: File?) { calls += "eden:$dir" }
        override fun setEdenProfile(profile: String) { calls += "edenProfile:$profile" }
        override fun setMelonDsFolder(dir: File?) { calls += "melonds:$dir" }
        override fun setPpssppFolder(dir: File?) { calls += "ppsspp:$dir" }
        override fun setSwap(swap: Boolean) { calls += "swap:$swap" }
        override fun setSecondScreen(on: Boolean) { calls += "secondScreen:$on" }
        override fun openBatterySettings() { calls += "battery" }
        override fun licenseText() = (1..120).joinToString("\n\n") { "GNU GENERAL PUBLIC LICENSE, paragraph $it" }
        override fun fontLicense() = "SIL OPEN FONT LICENSE"
    }

    private fun show(actions: SettingsActions = this.actions) {
        rule.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
    }

    /** Down the section list from Server & pairing, where Settings opens, to [section]. */
    private fun goTo(section: SettingsSection) = repeat(section.ordinal) { press(Key.DirectionDown) }

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
        goTo(SettingsSection.CONTROLS)
        rule.onNodeWithTag("section-CONTROLS").assertIsFocused()
        press(Key.DirectionCenter) // opens Controls on its switch
        press(Key.DirectionCenter)
        assertThat(calls).contains("swap:true")
    }

    /** Under Swap A and B, on until turned off: A turns it off. */
    @Test fun theSecondScreenSwitchIsInControlsAndOn() {
        show()
        goTo(SettingsSection.CONTROLS)
        rule.onNodeWithText("Controls & screens").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        rule.onNode(hasText("Use the second screen") and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("secondScreen:false")
    }

    /**
     * Found by review: the panel covers an app open on the other screen whenever Loom is in front, and
     * Loom can't tell it is there. The switch says that turning it off leaves that app in view.
     */
    @Test fun theSecondScreenSwitchSaysOffLeavesAnotherAppInView() {
        show()
        goTo(SettingsSection.CONTROLS)
        press(Key.DirectionCenter)
        rule.onNodeWithText("Off, an app open on the other screen stays in view while Loom is in front.", substring = true).assertExists()
    }

    /** Found on the Thor: A selected a section but left focus in the list; the next press did nothing new. */
    @Test fun aOnASectionMovesIntoIt() {
        show()
        goTo(SettingsSection.STORAGE)
        press(Key.DirectionCenter)
        rule.onNodeWithText("Change folder").assertIsFocused()
    }

    /** Found on the Thor: Left from a section's control went to the section level with it, not the open one. */
    @Test fun leftReturnsToTheOpenSection() {
        show()
        goTo(SettingsSection.STORAGE)
        press(Key.DirectionCenter) // Change folder, level with "Platform folders"
        press(Key.DirectionLeft)
        rule.onNodeWithTag("section-STORAGE").assertIsFocused()
    }

    /** Found by audit: B inside a section left Settings instead of going back to the section list. */
    @Test fun bInsideASectionReturnsToTheSectionList() {
        show()
        goTo(SettingsSection.MAPPING)
        press(Key.DirectionCenter) // Export
        press(Key.DirectionDown); press(Key.DirectionDown) // a platform row
        back()
        rule.onNodeWithTag("section-MAPPING").assertIsFocused()
        assertThat(rule.activity.isFinishing).isFalse()
    }

    /** Found on the Thor: the licence text scrolled by touch only. */
    @Test fun licenceTextScrollsWithThePad() {
        show()
        goTo(SettingsSection.ABOUT)
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
        goTo(SettingsSection.MAPPING)
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
        goTo(SettingsSection.MAPPING); press(Key.DirectionCenter) // Export
        press(Key.DirectionDown); press(Key.DirectionCenter) // the first platform's editor
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[0].performTextReplacement("ps1")
        rule.waitForIdle()
        rule.onNodeWithText("Save", useUnmergedTree = true).performClick()
        assertThat(calls).contains("save:psx:ps1")
    }

    /**
     * Found on the Thor: under Arcade's folder ("arcade") the hint listed Cocoon folders that only start with
     * the same letter (amiga, amstradcpc…). It lists arcade's own other folders, and what the card holds of
     * each. Only a platform without any falls back to Cocoon's folders.
     */
    @Test fun theFolderHintListsThePlatformsOwnFolders() {
        state = sample.copy(mapping = listOf(chosenRow(5, "Arcade", "arcade", "arcade", "arcade"), row(6, "PlayStation 2", "ps2", "ps2")))
        actions.onCard = listOf(SystemFolder("arcade", 3), SystemFolder("MAME", 52), SystemFolder("fbneo", 0), SystemFolder("ps2", 9))
        show()
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionCenter) // Platform folders: Export
        press(Key.DirectionDown); press(Key.DirectionCenter) // Arcade
        rule.onNodeWithText("Other folders for Arcade: MAME (52 games), fbneo (no games), fba (not on the card)", useUnmergedTree = true)
            .assertExists()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[0].performTextReplacement("mame")
        rule.waitForIdle()
        rule.onNodeWithText("Other folders for Arcade: arcade (3 games), fbneo (no games), fba (not on the card)", useUnmergedTree = true)
            .assertExists()
        back()
        press(Key.DirectionDown); press(Key.DirectionCenter) // PlayStation 2: no other folders of its own
        rule.onNodeWithText("Other Cocoon folders: psx", useUnmergedTree = true).assertExists()
    }

    /** Found by audit: closing a dialog left focus on the section list, not on what opened it. */
    @Test fun closingTheFolderEditorGivesFocusBackToItsRow() {
        show()
        goTo(SettingsSection.MAPPING)
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
        goTo(SettingsSection.ABOUT)
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
        goTo(SettingsSection.DOWNLOADS)
        press(Key.DirectionCenter) // the first concurrency chip
        press(Key.DirectionDown)
        rule.onNodeWithText("Allow background downloads").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("battery")
        update { it.copy(batteryUnrestricted = true) } // back from Android's dialog, allowed
        rule.onNodeWithText("1").assertIsFocused()
    }

    @Test fun theRommSwitchTurnsReceivingOff() {
        state = state.copy(sendToDevice = SendToDeviceState.Ready("dev-1"))
        show()
        goTo(SettingsSection.DOWNLOADS)
        press(Key.DirectionCenter) // the first concurrency chip
        press(Key.DirectionDown); press(Key.DirectionDown) // past Allow background downloads
        rule.onNodeWithText("Receive games sent from RomM").assertIsFocused()
        rule.onNodeWithText("Pair again").assertDoesNotExist()
        press(Key.DirectionCenter)
        assertThat(calls).contains("receive:false")
    }

    /** RomM refuses the device: the switch says to pair again, and Pair again is beside it, the row keeping its height. */
    @Test fun pairAgainSitsBesideTheSwitchWhenRommCantSendHere() {
        state = state.copy(sendToDevice = SendToDeviceState.NeedsPairing)
        show()
        goTo(SettingsSection.DOWNLOADS)
        press(Key.DirectionCenter) // the first concurrency chip
        rule.onNodeWithText("Pair again, with the code Loom shows, to receive games from RomM.", useUnmergedTree = true).assertExists()
        val switch = rule.onNodeWithText("Receive games sent from RomM").getBoundsInRoot()
        val pairAgain = rule.onNodeWithText("Pair again").getBoundsInRoot()
        assertThat(pairAgain.left).isAtLeast(switch.right)
        assertThat(pairAgain.top).isAtLeast(switch.top)
        assertThat(pairAgain.bottom).isAtMost(switch.bottom)
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionRight)
        rule.onNodeWithText("Pair again").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("repair") // nothing to confirm: RomM already refuses the device
    }

    /** Pairing again from Downloads comes back to its Pair again, and to the switch once the new pairing works. */
    @Test fun backFromPairingFocusLandsOnPairAgainOrTheSwitch() {
        state = state.copy(sendToDevice = SendToDeviceState.NeedsPairing)
        val restore = StateRestorationTester(rule)
        restore.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        goTo(SettingsSection.DOWNLOADS)
        press(Key.DirectionCenter); press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionRight) // Pair again
        press(Key.DirectionCenter)
        assertThat(calls).contains("repair")
        // Pairing opens on top and Settings leaves composition; coming back recreates it from saved state.
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithText("Pair again").assertIsFocused()
        update { it.copy(sendToDevice = SendToDeviceState.Ready("dev-2")) }
        rule.onNodeWithText("Pair again").assertDoesNotExist()
        rule.onNodeWithText("Receive games sent from RomM").assertIsFocused()

        // Paired while away, the button gone by the time Settings is back: the switch.
        update { it.copy(sendToDevice = SendToDeviceState.NeedsPairing) }
        press(Key.DirectionRight); press(Key.DirectionCenter)
        state = state.copy(sendToDevice = SendToDeviceState.Ready("dev-3"))
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithText("Receive games sent from RomM").assertIsFocused()
    }

    @Test fun librarySyncOpensOnTheChosenIntervalAndAChoosesAnother() {
        show()
        goTo(SettingsSection.LIBRARY)
        press(Key.DirectionCenter)
        rule.onNodeWithText("Every 12 h").assertIsFocused() // the default: a second A changes nothing
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("refresh:EVERY_24_HOURS")
        update { it.copy(settings = it.settings!!.copy(catalogRefresh = CatalogRefresh.EVERY_24_HOURS)) }
        rule.onNodeWithText("Every 24 h").assertIsFocused()
        repeat(3) { press(Key.DirectionLeft) }
        press(Key.DirectionCenter)
        assertThat(calls).contains("refresh:OFF")
    }

    @Test fun librarySyncTogglesWifiOnlyAndResyncsWithThePad() {
        show()
        goTo(SettingsSection.LIBRARY)
        press(Key.DirectionCenter) // the interval
        press(Key.DirectionDown)
        rule.onNodeWithText("Only on Wi-Fi").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("wifi:true")
        press(Key.DirectionDown)
        rule.onNodeWithText("Full resync").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("resync")
        back()
        rule.onNodeWithTag("section-LIBRARY").assertIsFocused()
    }

    @Test fun librarySyncShowsWhenTheLibraryLastSynced() {
        val threeHoursAgo = System.currentTimeMillis() - (3 * 60 + 5) * 60_000L
        state = sample.copy(settings = sample.settings!!.copy(lastCatalogSyncEpochMs = threeHoursAgo))
        show()
        goTo(SettingsSection.LIBRARY)
        press(Key.DirectionCenter)
        rule.onNodeWithText("Last synced 3 h ago").assertExists()
        update { it.copy(sync = CatalogSyncStatus(running = true, fetched = 1200, total = 23144)) }
        rule.onNodeWithText("Syncing 1200 of 23144").assertExists()
        update { it.copy(sync = CatalogSyncStatus(running = false, error = "Can't reach the RomM server")) }
        rule.onNodeWithText("Last synced 3 h ago").assertExists()
        rule.onNodeWithText("The last sync failed: Can't reach the RomM server").assertExists()
    }

    private val rejectedToken = CatalogSyncStatus(running = false, error = "RomM rejected Loom's token. Pair again in Settings.", needsPairing = true)

    /** Found by review: a rejected token's line here said "Pair again in Settings", with no Pair again in the section. */
    @Test fun aRejectedTokenInLibrarySyncLeadsToPairing() {
        state = sample.copy(sync = rejectedToken)
        show()
        goTo(SettingsSection.LIBRARY)
        press(Key.DirectionCenter) // the interval
        rule.onNodeWithText("The last sync failed: RomM rejected Loom's token.").assertExists()
        press(Key.DirectionDown); press(Key.DirectionDown) // past Only on Wi-Fi
        rule.onNodeWithText("Pair again").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("repair")
    }

    /** Pairing again from Library sync comes back to its Pair again, and to the interval once the new token's sync starts. */
    @Test fun backFromPairingAgainInLibrarySyncFocusesItsPairAgain() {
        state = sample.copy(sync = rejectedToken)
        val restore = StateRestorationTester(rule)
        restore.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        goTo(SettingsSection.LIBRARY)
        press(Key.DirectionCenter); press(Key.DirectionDown); press(Key.DirectionDown) // Pair again
        press(Key.DirectionCenter)
        assertThat(calls).contains("repair")
        // Pairing opens on top and Settings leaves composition; coming back recreates it from saved state.
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithText("Pair again").assertIsFocused()
        update { it.copy(sync = CatalogSyncStatus(running = true)) } // the new token works: its sync replaces the failure
        rule.onNodeWithText("Every 12 h").assertIsFocused()
    }

    @Test fun backFromPairingAgainInLibrarySyncWithTheSyncAlreadyStartedFocusesTheInterval() {
        state = sample.copy(sync = rejectedToken)
        val restore = StateRestorationTester(rule)
        restore.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        goTo(SettingsSection.LIBRARY)
        press(Key.DirectionCenter); press(Key.DirectionDown); press(Key.DirectionDown) // Pair again
        press(Key.DirectionCenter)
        state = state.copy(sync = CatalogSyncStatus(running = true)) // paired while away: the sync is under way on return
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithText("Every 12 h").assertIsFocused()
    }

    @Test fun pickingAFolderGivesFocusBackToChangeFolder() {
        show()
        goTo(SettingsSection.STORAGE)
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
        goTo(SettingsSection.STORAGE)
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
        goTo(SettingsSection.STORAGE)
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
        goTo(SettingsSection.MAPPING)
        press(Key.DirectionCenter) // Export
        repeat(8) { press(Key.DirectionDown) } // down the platforms, scrolling the pane
        repeat(8) { press(Key.DirectionUp) } // to Export / Import
        assertThat(rule.onNodeWithText("WHERE EACH PLATFORM'S GAMES GO").getBoundsInRoot().top).isAtLeast(paneTop())
    }

    @Test fun theFolderBrowsersToggleShowsTheRomsFolder() {
        show()
        goTo(SettingsSection.STORAGE)
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
        goTo(SettingsSection.MAPPING)
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
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionCenter) // Platform folders: Export
        press(Key.DirectionDown); press(Key.DirectionCenter) // PlayStation's editor
        rule.onNodeWithText("Cancel").assertIsFocused()
    }

    private fun chip(text: String) = rule.onNode(hasText(text) and hasClickAction())

    /** The label of the focused chip or button; null for a control without one (the folder field). */
    private fun focusedTag(): String? =
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { null }

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

    // --- a narrow screen: the list, then one section at a time ---

    @Config(qualifiers = PHONE_PORTRAIT)
    @Test fun onANarrowScreenAOpensTheSectionAlone() {
        show()
        rule.onNodeWithTag("section-SERVER").assertIsFocused()
        rule.onNodeWithTag("settings-content").assertDoesNotExist()
        goTo(SettingsSection.STORAGE)
        press(Key.DirectionCenter)
        rule.onNodeWithText("Settings · Storage").assertExists()
        rule.onNodeWithTag("settings-sections").assertDoesNotExist()
        rule.onNodeWithText("Change folder").assertIsFocused()
    }

    @Config(qualifiers = PHONE_PORTRAIT)
    @Test fun bFromASectionReturnsToItInTheList() {
        show()
        goTo(SettingsSection.MAPPING)
        press(Key.DirectionCenter) // Export
        press(Key.DirectionDown)
        back()
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        rule.onNodeWithTag("settings-content").assertDoesNotExist()
        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithTag("section-MAPPING").assertIsFocused()
        assertThat(rule.activity.isFinishing).isFalse()
        press(Key.DirectionCenter) // and A opens it again
        rule.onNodeWithText("Settings · Platform folders").assertExists()
    }

    /** Pairing again from a section on a narrow screen comes back to that section, open, on its Pair again. */
    @Config(qualifiers = PHONE_PORTRAIT)
    @Test fun pairAgainComesBackToItsSection() {
        val restore = StateRestorationTester(rule)
        restore.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        press(Key.DirectionCenter) // Server & pairing: the address
        repeat(4) { if (focusedText() != "Pair again") press(if (focusedText() == "Sign out") Key.DirectionLeft else Key.DirectionDown) }
        rule.onNodeWithText("Pair again").assertIsFocused()
        press(Key.DirectionCenter); press(Key.DirectionRight); press(Key.DirectionCenter) // confirmed
        assertThat(calls).contains("repair")
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithText("Settings · Server & pairing").assertExists()
        rule.onNodeWithText("Pair again").assertIsFocused()
        back()
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        rule.onNodeWithTag("section-SERVER").assertIsFocused()
    }

    // --- moving to the other screen: the activity stays, and Settings is laid out again in place ---

    private val topScreen = DpSize(832.dp, 468.dp)
    private val bottomScreen = DpSize(538.dp, 468.dp)

    /** The size of Loom's window as Settings reads it, which a move to the other screen changes. */
    private var window by mutableStateOf(topScreen)

    private fun showMovable() {
        rule.setContent {
            val density = LocalDensity.current
            val size = window
            val info = remember(size) {
                object : WindowInfo {
                    override val isWindowFocused = true
                    override val containerSize get() = with(density) { IntSize(size.width.roundToPx(), size.height.roundToPx()) }
                    override val containerDpSize get() = size
                }
            }
            CompositionLocalProvider(LocalWindowInfo provides info) {
                Box(Modifier.size(size)) { LoomTestTheme { SettingsContent(state, actions) } }
            }
        }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
    }

    private fun moveTo(size: DpSize) {
        window = size
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    /** Found by review: moved to the bottom screen, Settings lost focus, and the first A did nothing. */
    @Test fun movedToTheBottomScreenTheListKeepsItsSection() {
        showMovable()
        goTo(SettingsSection.CONTROLS)
        moveTo(bottomScreen)
        rule.onNodeWithTag("section-CONTROLS").assertIsFocused()
        press(Key.DirectionCenter) // opens it…
        rule.onNodeWithText("Settings · Controls & screens").assertExists()
        press(Key.DirectionCenter) // …and the next acts on it
        assertThat(calls).contains("swap:true")
    }

    /** Found by review: a section opened on the top screen, then left for the list, opened on its own on the bottom one. */
    @Test fun movedToTheBottomScreenFromTheListItShowsTheList() {
        showMovable()
        goTo(SettingsSection.STORAGE)
        press(Key.DirectionCenter) // Change folder
        press(Key.DirectionLeft) // back to Storage in the list
        press(Key.DirectionDown) // Platform folders
        moveTo(bottomScreen)
        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithTag("section-MAPPING").assertIsFocused()
    }

    @Test fun movedToTheBottomScreenASectionKeepsItsControl() {
        showMovable()
        goTo(SettingsSection.CONTROLS)
        press(Key.DirectionCenter); press(Key.DirectionDown) // Use the second screen
        moveTo(bottomScreen)
        rule.onNodeWithText("Settings · Controls & screens").assertExists()
        rule.onNode(hasText("Use the second screen") and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("secondScreen:false")
    }

    /** Found by review: moved to the top screen from a section, nothing had focus, and A did nothing. */
    @Test fun movedToTheTopScreenASectionKeepsItsControl() {
        window = bottomScreen
        showMovable()
        goTo(SettingsSection.STORAGE)
        press(Key.DirectionCenter)
        rule.onNodeWithText("Change folder").assertIsFocused()
        moveTo(topScreen)
        rule.onNodeWithTag("settings-sections").assertExists()
        rule.onNodeWithText("Change folder").assertIsFocused()
        back() // B goes back to the section in the list, as anywhere on the top screen
        rule.onNodeWithTag("section-STORAGE").assertIsFocused()
        assertThat(rule.activity.isFinishing).isFalse()
    }

    @Test fun movedToTheTopScreenTheListKeepsItsSection() {
        window = bottomScreen
        showMovable()
        goTo(SettingsSection.CONTROLS)
        moveTo(topScreen)
        rule.onNodeWithTag("section-CONTROLS").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        assertThat(calls).contains("swap:true")
    }

    /** Found by audit: Pair again threw the working token away at once, without asking. */
    @Test fun pairAgainAsksFirst() {
        show()
        press(Key.DirectionCenter) // Server & pairing: the address
        press(Key.DirectionDown); press(Key.DirectionLeft) // the first pairing button
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
        press(Key.DirectionDown); press(Key.DirectionLeft) // Pair again
        press(Key.DirectionCenter); press(Key.DirectionRight); press(Key.DirectionCenter) // confirmed
        assertThat(calls).contains("repair")
        // Pairing opens on top and Settings leaves composition; coming back recreates it from saved state.
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithText("Pair again").assertIsFocused()
    }

    // --- save sync, in Saves ---

    /** A on Saves opens it on the automatic upload switch. */
    private fun toTheSaveSwitch() {
        goTo(SettingsSection.SAVES)
        press(Key.DirectionCenter)
        rule.onNodeWithText("Upload saves automatically").assertIsFocused()
    }

    @Test fun aTurnsAutomaticUploadOff() {
        show()
        toTheSaveSwitch()
        // Off, nothing happens in the background: no uploads, and no word of newer saves or conflicts either.
        rule.onNodeWithText("Off, Loom only looks when you press Upload save", substring = true).assertExists()
        press(Key.DirectionCenter)
        assertThat(calls).contains("autoUpload:false")
    }

    @Test fun aTurnsAutomaticUploadBackOn() {
        state = sample.copy(settings = sample.settings!!.copy(autoUploadSaves = false))
        show()
        toTheSaveSwitch()
        press(Key.DirectionCenter)
        assertThat(calls).contains("autoUpload:true")
    }

    @Test fun theLastSaveSyncSaysWhenAndHowItWent() {
        state = sample.copy(settings = sample.settings!!.copy(lastSaveSync = SaveSyncOutcome(System.currentTimeMillis() - 5 * 60_000, "2 uploaded · 1 conflict", false)))
        show()
        toTheSaveSwitch()
        rule.onNodeWithTag("last-save-sync").assertTextEquals("Last sync 5 min ago: 2 uploaded · 1 conflict")
        update { it.copy(settings = it.settings!!.copy(lastSaveSync = SaveSyncOutcome(System.currentTimeMillis(), "Can't reach the RomM server", true))) }
        rule.onNodeWithTag("last-save-sync").assertTextEquals("Last sync just now: Can't reach the RomM server")
        rule.onNodeWithText("Pair again").assertDoesNotExist() // pairing again wouldn't help
    }

    /** The last save sync failed until Loom pairs again, as [summary] says. */
    private fun needingPairing(summary: String) = sample.copy(settings = sample.settings!!.copy(
        lastSaveSync = SaveSyncOutcome(System.currentTimeMillis() - 5 * 60_000, summary, failed = true, needsPairing = true)))

    /**
     * Found by review: a save sync that needed pairing again said "Pair again in Settings" in Settings,
     * with nothing to press. Pair again is beside it now, and asks nothing first: pairing is already broken.
     */
    @Test fun aSaveSyncThatNeedsPairingLeadsToPairing() {
        state = needingPairing(SaveSyncEngine.NO_DEVICE)
        show()
        toTheSaveSwitch()
        rule.onNodeWithTag("last-save-sync").assertTextEquals("Last sync 5 min ago: RomM doesn't have Loom as a device, so saves can't sync.")
        press(Key.DirectionDown)
        rule.onNodeWithText("Pair again").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("eden-card").assertIsFocused() // the rest of the section is still reachable, below it
        press(Key.DirectionUp)
        rule.onNodeWithText("Pair again").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("repair")

        update { needingPairing("RomM rejected Loom's token. Pair again in Settings.") }
        rule.onNodeWithTag("last-save-sync").assertTextEquals("Last sync 5 min ago: RomM rejected Loom's token.")
        rule.onNodeWithText("Pair again").assertExists()
    }

    /** Sync switched off for the Thor on RomM's Devices page: the line says where to switch it on, and pairing again wouldn't help. */
    @Test fun syncSwitchedOffShowsNoPairAgain() {
        state = sample.copy(settings = sample.settings!!.copy(
            lastSaveSync = SaveSyncOutcome(System.currentTimeMillis() - 5 * 60_000, SaveSyncEngine.SYNC_OFF, failed = true)))
        show()
        toTheSaveSwitch()
        rule.onNodeWithTag("last-save-sync").assertTextEquals("Last sync 5 min ago: ${SaveSyncEngine.SYNC_OFF}")
        rule.onNodeWithText("Pair again").assertDoesNotExist()
        press(Key.DirectionDown)
        rule.onNodeWithTag("azahar-card").assertIsFocused()
    }

    /** Pairing again from Saves comes back to its Pair again, and to the switch once the save sync it starts has run. */
    @Test fun backFromPairingAgainInSavesFocusesItsPairAgain() {
        state = needingPairing(SaveSyncEngine.NO_DEVICE)
        val restore = StateRestorationTester(rule)
        restore.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        toTheSaveSwitch()
        press(Key.DirectionDown) // Pair again
        press(Key.DirectionCenter)
        assertThat(calls).contains("repair")
        // Pairing opens on top and Settings leaves composition; coming back recreates it from saved state.
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithText("Pair again").assertIsFocused()
        update { it.copy(settings = it.settings!!.copy(lastSaveSync = SaveSyncOutcome(System.currentTimeMillis(), "1 uploaded", false))) }
        rule.onNodeWithText("Pair again").assertDoesNotExist()
        rule.onNodeWithText("Upload saves automatically").assertIsFocused()
    }

    @Test fun backFromPairingAgainInSavesWithTheSyncAlreadyRunFocusesTheSwitch() {
        state = needingPairing(SaveSyncEngine.NO_DEVICE)
        val restore = StateRestorationTester(rule)
        restore.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        toTheSaveSwitch()
        press(Key.DirectionDown) // Pair again
        press(Key.DirectionCenter)
        // Paired while away: the save sync it started has run by the time Settings is back.
        state = state.copy(settings = state.settings!!.copy(lastSaveSync = SaveSyncOutcome(System.currentTimeMillis(), "no changes", false)))
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithText("Upload saves automatically").assertIsFocused()
    }

    // --- each emulator's card, its details and its folder browser ---

    /** The Thor's card with Azahar's data in ROMs/n3ds, and Eden's, melonDS's and PPSSPP's saves in ROMs/saves. */
    private fun thorCard(): Pair<File, File> {
        val card = tmp.newFolder("6365-3230")
        val n3ds = File(card, "ROMs/n3ds").apply { File(this, "sdmc/Nintendo 3DS").mkdirs(); File(this, "nand").mkdirs() }
        val eden = File(card, "ROMs/saves/Eden").apply { File(this, "user/save/0000000000000000").mkdirs() }
        val melonDs = File(card, "ROMs/saves/melonDS").apply { mkdirs(); File(this, "Mario.sav").writeBytes(ByteArray(512)) }
        val ppsspp = File(card, "ROMs/saves/PPSSPP").apply {
            File(this, "PSP/SAVEDATA").mkdirs(); File(this, "PSP/SYSTEM").mkdirs(); File(this, "PSP/SYSTEM/ppsspp.ini").writeText("")
        }
        File(card, "ROMs/snes").mkdirs()
        File(card, "ROMs/saves/Other").mkdirs()
        state = sample.copy(storage = sample.storage.copy(
            romsRoot = File(card, "ROMs").path, azaharFound = n3ds.path, edenFound = eden.path, melonDsFound = melonDs.path, ppssppFound = ppsspp.path,
        ))
        return n3ds to eden
    }

    /** From the switch down to [tag]'s card, and A on it: its details open on Change folder. */
    private fun openDetails(tag: String) {
        press(Key.DirectionDown)
        if (tag == "eden") press(Key.DirectionRight)
        if (tag == "melonds" || tag == "ppsspp") press(Key.DirectionDown)
        if (tag == "ppsspp") press(Key.DirectionRight)
        rule.onNodeWithTag("$tag-card").assertIsFocused()
        press(Key.DirectionCenter)
    }

    /** Each emulator has a card saying where its saves are; A opens its details, with its folder in full, and B goes back to the card. */
    @Test fun aCardOpensItsEmulatorsDetails() {
        val (n3ds, _) = thorCard()
        state = state.copy(storage = state.storage.copy(edenFound = null))
        show()
        toTheSaveSwitch()
        rule.onNodeWithTag("azahar-card").assertTextEquals("Azahar · 3DS", "Found in the 3DS folder")
        rule.onNodeWithTag("eden-card").assertTextEquals("Eden · Switch", "Not found yet")
        openDetails("azahar")
        rule.onNodeWithText("Azahar's saves").assertExists()
        rule.onNodeWithTag("azahar-folder-path", useUnmergedTree = true).assertTextEquals(n3ds.path)
        rule.onNodeWithTag("azahar-folder-status", useUnmergedTree = true).assertTextEquals("Found in the 3DS folder.")
        rule.onNodeWithText("sdmc and nand", substring = true).assertExists()
        rule.onNodeWithTag("azahar-change-folder").assertIsFocused()
        rule.onNodeWithTag("azahar-use-default").assertDoesNotExist() // nothing chosen to go back from
        back()
        rule.onNodeWithText("Azahar's saves").assertDoesNotExist()
        rule.onNodeWithTag("azahar-card").assertIsFocused()
        back()
        rule.onNodeWithTag("section-SAVES").assertIsFocused()
    }

    @Test fun theCardsMoveWithTheDpad() {
        show()
        toTheSaveSwitch()
        press(Key.DirectionDown)
        rule.onNodeWithTag("azahar-card").assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag("eden-card").assertIsFocused()
        press(Key.DirectionLeft)
        rule.onNodeWithTag("azahar-card").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("melonds-card").assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag("ppsspp-card").assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag("eden-card").assertIsFocused()
        press(Key.DirectionLeft)
        rule.onNodeWithTag("azahar-card").assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithText("Upload saves automatically").assertIsFocused()
    }

    /** Change folder: the browser takes the section's place, opening beside the folder in use. */
    @Test fun changeFolderOpensTheBrowserInPlaceOfTheCards() {
        thorCard()
        show()
        toTheSaveSwitch()
        openDetails("azahar")
        press(Key.DirectionCenter)
        rule.onNodeWithText("Azahar's saves").assertDoesNotExist()
        rule.onNodeWithTag("save-folder-browser").assertExists()
        rule.onNodeWithText("Upload saves automatically").assertDoesNotExist()
        rule.onNodeWithTag("last-save-sync").assertDoesNotExist()
        rule.onNodeWithTag("azahar-card").assertDoesNotExist()
        rule.onNodeWithText("Azahar's folder".uppercase()).assertExists()
        rule.onNodeWithText("n3ds").assertIsFocused()
        rule.onNodeWithText("Azahar's folder holds sdmc and nand; they aren't here.").assertExists() // ROMs itself
    }

    /** Left out of the open browser to the section list, A on Saves goes back into the browser, as it goes into any section. */
    @Test fun aOnSavesGoesBackIntoItsOpenBrowser() {
        thorCard()
        show()
        toTheSaveSwitch()
        openDetails("azahar")
        press(Key.DirectionCenter) // Change folder: the browser opens, on the folder in use
        rule.onNodeWithText("n3ds").assertIsFocused()
        press(Key.DirectionLeft) // the browser's left edge
        press(Key.DirectionLeft)
        rule.onNodeWithTag("section-SAVES").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("n3ds").assertIsFocused()
    }

    /** Azahar's folder browser says whether the open folder holds Azahar's data; picking one puts focus back on the card. */
    @Test fun pickingAFolderGivesFocusBackToItsCard() {
        val (n3ds, _) = thorCard()
        show()
        toTheSaveSwitch()
        openDetails("azahar")
        press(Key.DirectionCenter) // Change folder: the browser opens, on the folder in use
        rule.onNodeWithText("n3ds").assertIsFocused()
        press(Key.DirectionCenter) // A goes into it
        rule.onNodeWithText("Azahar's data is here (nand, sdmc).").assertExists()
        rule.onNodeWithText("Use this folder").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("azahar:${n3ds.path}")
        rule.onNodeWithTag("save-folder-browser").assertDoesNotExist()
        rule.onNodeWithTag("azahar-card").assertIsFocused()
    }

    /** B goes up through the folders opened, then closes the browser, back on its card; the next B leaves the section. */
    @Test fun bClosesTheBrowserBackToItsCard() {
        thorCard()
        show()
        toTheSaveSwitch()
        openDetails("azahar")
        press(Key.DirectionCenter)
        press(Key.DirectionCenter) // into n3ds
        rule.onNodeWithText("Use this folder").assertIsFocused()
        back()
        rule.onNodeWithText("n3ds").assertIsFocused() // up, on the folder just left
        back()
        rule.onNodeWithTag("save-folder-browser").assertDoesNotExist()
        rule.onNodeWithTag("azahar-card").assertIsFocused()
        back()
        rule.onNodeWithTag("section-SAVES").assertIsFocused()
        assertThat(calls.filter { it.startsWith("azahar") }).isEmpty()
    }

    /** Without the card in the list of storages, the browser starts there; B still closes it. */
    @Test fun bClosesTheBrowserFromTheListOfStorages() {
        show()
        toTheSaveSwitch()
        openDetails("azahar")
        press(Key.DirectionCenter) // the browser lists volumes: the card isn't there in tests
        rule.onNodeWithText("SD card").assertIsFocused()
        back()
        rule.onNodeWithTag("azahar-card").assertIsFocused()
    }

    /**
     * A chosen folder's details offer the way back to the 3DS folder. Pressing it closes them, and
     * focus is back on the card.
     */
    @Test fun aChosenFolderCanGoBackToThe3dsFolder() {
        state = sample.copy(settings = sample.settings!!.copy(azaharUserDir = "/storage/6365-3230/Azahar"))
        show(object : SettingsActions by actions {
            override fun setAzaharFolder(dir: File?) {
                actions.setAzaharFolder(dir)
                state = state.copy(settings = state.settings!!.copy(azaharUserDir = dir?.path))
            }
        })
        toTheSaveSwitch()
        rule.onNodeWithTag("azahar-card").assertTextEquals("Azahar · 3DS", "Your chosen folder")
        openDetails("azahar")
        rule.onNodeWithTag("azahar-folder-path", useUnmergedTree = true).assertTextEquals("/storage/6365-3230/Azahar")
        rule.onNodeWithTag("azahar-folder-status", useUnmergedTree = true).assertTextEquals("You chose this folder.")
        rule.onNodeWithTag("azahar-change-folder").assertIsFocused()
        press(Key.DirectionLeft)
        rule.onNodeWithText("Use 3DS folder").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("azahar:null")
        rule.onNodeWithText("Azahar's saves").assertDoesNotExist()
        rule.onNodeWithTag("azahar-card").assertIsFocused()
        rule.onNodeWithTag("azahar-card").assertTextEquals("Azahar · 3DS", "Not found yet")
        back()
        rule.onNodeWithTag("section-SAVES").assertIsFocused()
    }

    @Test fun withoutAzaharsFolderItSaysWhatToDo() {
        show()
        toTheSaveSwitch()
        rule.onNodeWithTag("last-save-sync").assertTextEquals("No save sync has run yet.")
        rule.onNodeWithTag("azahar-card").assertTextEquals("Azahar · 3DS", "Not found yet")
        openDetails("azahar")
        rule.onNodeWithTag("azahar-folder-path", useUnmergedTree = true).assertTextEquals("No folder")
        rule.onNodeWithTag("azahar-folder-status", useUnmergedTree = true).assertTextEquals("Not found: run a game in Azahar once, or choose the folder holding its sdmc and nand.")
        rule.onNodeWithText("Use 3DS folder").assertDoesNotExist()
    }

    /** Eden's card is beside Azahar's; its browser opens beside Eden's folder, saying whether it holds user/save. */
    @Test fun edensFolderIsChosenWithTheFolderBrowser() {
        val (_, eden) = thorCard()
        show()
        toTheSaveSwitch()
        rule.onNodeWithTag("eden-card").assertTextEquals("Eden · Switch", "Found in ROMs/saves/Eden")
        openDetails("eden")
        rule.onNodeWithTag("eden-folder-path", useUnmergedTree = true).assertTextEquals(eden.path)
        rule.onNodeWithTag("eden-folder-status", useUnmergedTree = true).assertTextEquals("Found in ROMs/saves/Eden.")
        press(Key.DirectionCenter) // Change folder: the browser opens in saves, on Eden
        rule.onNodeWithText("Eden").assertIsFocused()
        rule.onNodeWithText("Eden's save folder holds user/save; it isn't here.").assertExists()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Eden's saves are here (user/save).").assertExists()
        rule.onNodeWithText("Use this folder").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("eden:${eden.path}")
        assertThat(calls.filter { it.startsWith("azahar") }).isEmpty()
        rule.onNodeWithTag("eden-card").assertIsFocused()
    }

    /** As with Azahar's: going back to saves/Eden closes the details, back on Eden's card. */
    @Test fun aChosenEdenFolderCanGoBackToSavesEden() {
        state = sample.copy(settings = sample.settings!!.copy(edenSaveDir = "/storage/6365-3230/Emulation/Eden"))
        show(object : SettingsActions by actions {
            override fun setEdenFolder(dir: File?) {
                actions.setEdenFolder(dir)
                state = state.copy(settings = state.settings!!.copy(edenSaveDir = dir?.path))
            }
        })
        toTheSaveSwitch()
        rule.onNodeWithTag("eden-card").assertTextEquals("Eden · Switch", "Chosen, but it has no user/save")
        openDetails("eden")
        rule.onNodeWithTag("eden-folder-path", useUnmergedTree = true).assertTextEquals("/storage/6365-3230/Emulation/Eden")
        rule.onNodeWithTag("eden-folder-status", useUnmergedTree = true).assertTextEquals("You chose this folder, but it has no user/save.")
        press(Key.DirectionLeft)
        rule.onNodeWithText("Use saves/Eden").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("eden:null")
        rule.onNodeWithText("Eden's saves").assertDoesNotExist()
        rule.onNodeWithTag("eden-card").assertIsFocused()
    }

    /**
     * Several Eden profiles: Eden's card says to choose one, and its details open on Choose profile.
     * That lists them all, each with its saves' count and newest change, however many there are;
     * A on one chooses it, back in Eden's details.
     */
    @Test fun whoseEdenSavesIsChosenFromEdensDetails() {
        val profiles = (1..8).map { i -> EdenProfile("%032X".format(i), i, java.time.Instant.parse("2026-10-14T12:00:00Z").minusSeconds(3600L * i)) }
        state = sample.copy(storage = sample.storage.copy(edenFound = "/storage/6365-3230/ROMs/saves/Eden", edenProfiles = profiles))
        show()
        toTheSaveSwitch()
        rule.onNodeWithTag("eden-card").assertTextEquals("Eden · Switch", "Several profiles: choose one")
        openDetails("eden")
        rule.onNodeWithTag("eden-profile-line", useUnmergedTree = true).assertTextEquals("Eden has 8 profiles. Whose saves does Loom sync? Choose profile picks one.")
        rule.onNodeWithTag("eden-profile").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Whose Switch saves does Loom sync?").assertExists()
        rule.onNodeWithTag("profile-${profiles[0].id}").assertIsFocused() // the latest, likely the owner's
        rule.onNodeWithText("1 save · changed 14 Oct", substring = true, useUnmergedTree = true).assertExists()
        repeat(7) { press(Key.DirectionDown) }
        rule.onNodeWithTag("profile-${profiles[7].id}").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("edenProfile:${profiles[7].id}")
        rule.onNodeWithText("Whose Switch saves does Loom sync?").assertDoesNotExist()
        rule.onNodeWithText("Eden's saves").assertExists()
        rule.onNodeWithTag("eden-profile").assertIsFocused()
        update { it.copy(settings = it.settings!!.copy(edenProfile = profiles[7].id)) }
        rule.onNodeWithTag("eden-profile-line", useUnmergedTree = true).assertTextEquals("Loom syncs the saves of Eden's profile 0000…0008 (8 saves).")
        back()
        rule.onNodeWithTag("eden-card").assertIsFocused()
        rule.onNodeWithTag("eden-card").assertTextEquals("Eden · Switch", "Found in ROMs/saves/Eden")
    }

    @Test fun bLeavesTheProfileListChoosingNothing() {
        val profiles = listOf(EdenProfile("0123456789ABCDEF0123456789ABCDEF", 2, null), EdenProfile("4E431822D6DEFD527268BA35B5C09533", 3, null))
        state = sample.copy(storage = sample.storage.copy(edenFound = "/storage/6365-3230/ROMs/saves/Eden", edenProfiles = profiles))
        show()
        toTheSaveSwitch()
        openDetails("eden")
        press(Key.DirectionCenter)
        rule.onNodeWithText("Whose Switch saves does Loom sync?").assertExists()
        back()
        rule.onNodeWithText("Whose Switch saves does Loom sync?").assertDoesNotExist()
        assertThat(calls.filter { it.startsWith("edenProfile") }).isEmpty()
        rule.onNodeWithTag("eden-profile").assertIsFocused() // back in Eden's details, where it was
    }

    /** Eden's browser opens beside Eden's folder, even right after Azahar's was open: each starts at its own folder. */
    @Test fun eachBrowserStartsAtItsOwnFolder() {
        val (_, eden) = thorCard()
        show()
        toTheSaveSwitch()
        openDetails("azahar")
        press(Key.DirectionCenter) // Azahar's browser, in ROMs
        rule.onNodeWithText("n3ds").assertIsFocused()
        press(Key.DirectionDown) // the next folder, and into it
        press(Key.DirectionCenter)
        back(); back() // up to ROMs, then closed
        rule.onNodeWithTag("azahar-card").assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionCenter) // Eden's details
        press(Key.DirectionCenter) // Eden's browser
        rule.onNodeWithText(eden.parentFile!!.path).assertExists() // the browser is in saves, beside Eden
        rule.onNodeWithText("Eden").assertIsFocused()
        rule.onNodeWithText("n3ds").assertDoesNotExist()
    }

    /** melonDS's card is below Azahar's; its browser says whether the open folder holds melonDS's saves. */
    @Test fun melonDsFolderIsChosenWithTheFolderBrowser() {
        val (_, eden) = thorCard()
        val melonDs = File(eden.parentFile, "melonDS")
        show()
        toTheSaveSwitch()
        rule.onNodeWithTag("melonds-card").assertTextEquals("melonDS · DS", "Found in ROMs/saves/melonDS")
        openDetails("melonds")
        rule.onNodeWithText("melonDS's saves").assertExists()
        rule.onNodeWithTag("melonds-folder-path", useUnmergedTree = true).assertTextEquals(melonDs.path)
        rule.onNodeWithTag("melonds-folder-status", useUnmergedTree = true).assertTextEquals("Found in ROMs/saves/melonDS.")
        rule.onNodeWithText("turn off Save next to ROM file", substring = true).assertExists()
        press(Key.DirectionCenter) // Change folder: the browser opens in saves, on melonDS
        rule.onNodeWithText("melonDS").assertIsFocused()
        rule.onNodeWithText("No .sav files here: melonDS makes one for each game it runs.").assertExists()
        press(Key.DirectionCenter)
        rule.onNodeWithText("melonDS's saves are here (1 .sav file).").assertExists()
        rule.onNodeWithText("Use this folder").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("melonds:${melonDs.path}")
        rule.onNodeWithTag("melonds-card").assertIsFocused()
    }

    /** PPSSPP's card is beside melonDS's; its browser says whether the open folder is PPSSPP's memory stick. */
    @Test fun ppssppsMemoryStickIsChosenWithTheFolderBrowser() {
        val (_, eden) = thorCard()
        val stick = File(eden.parentFile, "PPSSPP")
        show()
        toTheSaveSwitch()
        rule.onNodeWithTag("ppsspp-card").assertTextEquals("PPSSPP · PSP", "Found in ROMs/saves/PPSSPP")
        openDetails("ppsspp")
        rule.onNodeWithText("PPSSPP's saves").assertExists()
        rule.onNodeWithTag("ppsspp-folder-path", useUnmergedTree = true).assertTextEquals(stick.path)
        rule.onNodeWithTag("ppsspp-folder-status", useUnmergedTree = true).assertTextEquals("Found in ROMs/saves/PPSSPP.")
        rule.onNodeWithText("set the Memory Stick folder to ROMs/saves/PPSSPP", substring = true).assertExists()
        press(Key.DirectionCenter) // Change folder: the browser opens in saves, on PPSSPP
        rule.onNodeWithText("PPSSPP").assertIsFocused()
        rule.onNodeWithText("PPSSPP's memory stick holds PSP/SYSTEM/ppsspp.ini; it isn't here.").assertExists()
        press(Key.DirectionCenter)
        rule.onNodeWithText("PPSSPP's memory stick is here (PSP/SYSTEM/ppsspp.ini).").assertExists()
        rule.onNodeWithText("Use this folder").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("ppsspp:${stick.path}")
        rule.onNodeWithTag("ppsspp-card").assertIsFocused()
    }

    /** A chosen memory stick PPSSPP hasn't used says so; going back to saves/PPSSPP closes the details, back on its card. */
    @Test fun aChosenMemoryStickCanGoBackToSavesPpsspp() {
        state = sample.copy(settings = sample.settings!!.copy(ppssppMemoryStick = "/storage/6365-3230/Emulation/PPSSPP"))
        show(object : SettingsActions by actions {
            override fun setPpssppFolder(dir: File?) {
                actions.setPpssppFolder(dir)
                state = state.copy(settings = state.settings!!.copy(ppssppMemoryStick = dir?.path))
            }
        })
        toTheSaveSwitch()
        rule.onNodeWithTag("ppsspp-card").assertTextEquals("PPSSPP · PSP", "Chosen, but no ppsspp.ini")
        openDetails("ppsspp")
        rule.onNodeWithTag("ppsspp-folder-status", useUnmergedTree = true).assertTextEquals("You chose this folder, but PPSSPP hasn't used it as its memory stick yet.")
        press(Key.DirectionLeft)
        rule.onNodeWithText("Use saves/PPSSPP").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("ppsspp:null")
        rule.onNodeWithTag("ppsspp-card").assertIsFocused()
        rule.onNodeWithTag("ppsspp-card").assertTextEquals("PPSSPP · PSP", "Not found yet")
    }

    /** A chosen melonDS folder that isn't there says so; going back to saves/melonDS closes the details, back on its card. */
    @Test fun aChosenMelonDsFolderCanGoBackToSavesMelonDs() {
        state = sample.copy(settings = sample.settings!!.copy(melonDsSaveDir = "/storage/6365-3230/Emulation/melonDS"))
        show(object : SettingsActions by actions {
            override fun setMelonDsFolder(dir: File?) {
                actions.setMelonDsFolder(dir)
                state = state.copy(settings = state.settings!!.copy(melonDsSaveDir = dir?.path))
            }
        })
        toTheSaveSwitch()
        rule.onNodeWithTag("melonds-card").assertTextEquals("melonDS · DS", "Chosen, but it isn't there")
        openDetails("melonds")
        rule.onNodeWithTag("melonds-folder-status", useUnmergedTree = true).assertTextEquals("You chose this folder, but it isn't there.")
        press(Key.DirectionLeft)
        rule.onNodeWithText("Use saves/melonDS").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("melonds:null")
        rule.onNodeWithText("melonDS's saves").assertDoesNotExist()
        rule.onNodeWithTag("melonds-card").assertIsFocused()
        rule.onNodeWithTag("melonds-card").assertTextEquals("melonDS · DS", "Not found yet")
    }

    /** Whether Loom can read Switch title IDs, in Eden's details, without a word of the key itself. */
    @Test fun theSectionSaysWhetherLoomCanReadSwitchTitleIds() {
        show()
        toTheSaveSwitch()
        openDetails("eden")
        rule.onNodeWithTag("prod-keys", useUnmergedTree = true).assertTextEquals("No prod.keys in the ROMs folder: each Switch game asks which save folder is its.")
        update { it.copy(storage = it.storage.copy(prodKeys = "/storage/6365-3230/ROMs/prod.keys")) }
        rule.onNodeWithTag("prod-keys", useUnmergedTree = true).assertTextEquals("Switch title IDs are read with the header_key in your prod.keys, which never leaves the Thor.")
        update { it.copy(storage = it.storage.copy(prodKeys = null, prodKeysWithoutKey = true)) }
        rule.onNodeWithTag("prod-keys", useUnmergedTree = true).assertTextEquals("Your prod.keys has no header_key: each Switch game asks which save folder is its.")
    }
}
