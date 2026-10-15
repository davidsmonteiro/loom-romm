package ro.davidmontei.loom.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.storage.mapping.SystemFolder
import ro.davidmontei.loom.storage.saves.EdenProfile
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.saves.SaveSyncEngine
import ro.davidmontei.loom.data.sent.SendToDeviceState
import ro.davidmontei.loom.data.settings.SaveSyncOutcome
import ro.davidmontei.loom.data.saves.conflictReason
import ro.davidmontei.loom.storage.saves.SyncDecision
import ro.davidmontei.loom.storage.saves.SyncOutcome
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.data.saves.EdenSaves
import ro.davidmontei.loom.data.saves.SaveLook
import ro.davidmontei.loom.ui.folders.FolderQuestion
import ro.davidmontei.loom.ui.saves.SaveFolderOption
import ro.davidmontei.loom.ui.saves.SaveFolderQuestion
import ro.davidmontei.loom.ui.library.GameSave
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.InstallState
import ro.davidmontei.loom.ui.library.LibraryContent
import ro.davidmontei.loom.ui.library.LibraryFilter
import okhttp3.HttpUrl.Companion.toHttpUrl
import ro.davidmontei.loom.romm.dto.DeviceAuthInitResponse
import ro.davidmontei.loom.romm.pairing.DeviceAuthSession
import ro.davidmontei.loom.ui.onboarding.OnboardingActions
import ro.davidmontei.loom.ui.onboarding.OnboardingContent
import ro.davidmontei.loom.ui.onboarding.OnboardingState
import ro.davidmontei.loom.ui.onboarding.OnboardingStep
import ro.davidmontei.loom.ui.onboarding.PairMode
import ro.davidmontei.loom.ui.queue.QueueContent
import ro.davidmontei.loom.ui.second.CompanionHolder
import ro.davidmontei.loom.ui.second.LocalCompanion
import ro.davidmontei.loom.ui.second.PadNotice
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
    private fun lineCount(text: String): Int =
        lineCount(rule.onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode())

    /** How many lines the text [node] takes. */
    private fun lineCount(node: SemanticsNode): Int {
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

    /** The Server step at its tallest: a checked address, with the plain-HTTP warning. */
    private val serverStepWithTheHttpWarning = OnboardingState(
        step = OnboardingStep.SERVER, addressInput = "203.0.113.5:8080", serverSummary = "RomM 5.3.1 at http://203.0.113.5:8080/",
        insecureWarning = "203.0.113.5 is a public address over plain HTTP: your token would travel unencrypted. Use https:// if you can.",
    )

    /** The Pair step with a code to approve, its QR code and how long it lasts. */
    private val pairStepWithItsCode = OnboardingState(
        step = OnboardingStep.PAIR, sessionStartedAt = System.currentTimeMillis(),
        session = DeviceAuthSession(
            "https://romm.example.com/".toHttpUrl(), "ABCD2345", "https://romm.example.com/pair/device".toHttpUrl(),
            "https://romm.example.com/pair/device?user_code=ABCD2345".toHttpUrl(), 600,
            DeviceAuthInitResponse("dev-code", "ABCD2345", "/pair/device", "/pair/device?user_code=ABCD2345", expiresIn = 600, interval = 5),
        ),
    )

    /** Found by audit: with the plain-HTTP warning showing, the Server step scrolled and cut off its title. */
    @Test fun theServerStepFitsWithTheHttpWarning() {
        rule.setContent { LoomTestTheme { OnboardingContent(serverStepWithTheHttpWarning, NoopOnboardingActions) } }
        assertThat(overflow()).isEqualTo(0f)
        rule.saveScreenshot("onboarding-server-http-warning")
    }

    /** On the bottom screen the explanation goes above the form, and the step still fits at its tallest. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theServerStepFitsTheBottomScreen() {
        rule.setContent { LoomTestTheme { OnboardingContent(serverStepWithTheHttpWarning, NoopOnboardingActions) } }
        assertThat(overflow()).isEqualTo(0f)
        onScreen("Check address", "Continue")
        rule.saveScreenshot("onboarding-server-bottom-screen")
    }

    /** On the bottom screen the chips stay on one line, and a smaller QR code sits beside the code. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun thePairStepWithItsCodeFitsTheBottomScreen() {
        rule.setContent { LoomTestTheme { OnboardingContent(pairStepWithItsCode, NoopOnboardingActions) } }
        assertThat(overflow()).isEqualTo(0f)
        onScreen("Scan a code", "Enter a pair code", "Paste a token", "ABCD-2345")
        val qr = rule.onNodeWithContentDescription("QR code", substring = true).fetchSemanticsNode().boundsInRoot
        assertThat(qr.bottom).isAtMost(rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        assertThat(lineCount("Waiting for approval")).isAtMost(2)
        rule.saveScreenshot("onboarding-pair-bottom-screen")
    }

    /**
     * Found by review: on a window shorter than the Thor's, a phone in landscape, the QR code beside
     * the whole step left the chips two lines, and where to enter the code and how long it lasts went
     * below the hints, where the pad can't scroll. The QR code goes beside the code instead.
     */
    @Config(qualifiers = PHONE_LANDSCAPE)
    @Test fun thePairStepWithItsCodeFitsAPhoneInLandscape() = thePairStepWithItsCodeFitsAShortScreen("onboarding-pair-phone-landscape")

    @Config(qualifiers = SMALL_PHONE_LANDSCAPE)
    @Test fun thePairStepWithItsCodeFitsASmallPhoneInLandscape() =
        thePairStepWithItsCodeFitsAShortScreen("onboarding-pair-small-phone-landscape")

    private fun thePairStepWithItsCodeFitsAShortScreen(screenshot: String) {
        rule.setContent { LoomTestTheme { OnboardingContent(pairStepWithItsCode, NoopOnboardingActions) } }
        rule.saveScreenshot(screenshot)
        assertThat(overflow()).isEqualTo(0f)
        onScreen("Scan a code", "Enter a pair code", "Paste a token", "ABCD-2345")
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (line in listOf("Or open", "Waiting for approval")) {
            val bounds = rule.onNodeWithText(line, substring = true).fetchSemanticsNode().boundsInRoot
            assertWithMessage(line).that(bounds.bottom).isAtMost(root.bottom)
        }
        val qr = rule.onNodeWithContentDescription("QR code", substring = true).fetchSemanticsNode().boundsInRoot
        assertThat(qr.bottom).isAtMost(root.bottom)
        assertThat(lineCount("Scan a code")).isEqualTo(1)
        assertThat(rule.onNodeWithText("Paste a token").fetchSemanticsNode().boundsInRoot.top)
            .isEqualTo(rule.onNodeWithText("Scan a code").fetchSemanticsNode().boundsInRoot.top) // the chips on one line
    }

    /**
     * Found by review: when waiting for approval failed, the error went under the code, below the
     * bottom screen's hints, where the pad couldn't scroll. The code goes, and the error shows under
     * Get a new code.
     */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun aPairingErrorFitsTheBottomScreen() {
        val failed = OnboardingState(step = OnboardingStep.PAIR, pairError = "RomM didn't answer. Check the server, then get a new code.")
        rule.setContent { LoomTestTheme { OnboardingContent(failed, NoopOnboardingActions) } }
        assertThat(overflow()).isEqualTo(0f)
        onScreen("Get a new code", "RomM didn't answer. Check the server, then get a new code.")
        rule.saveScreenshot("onboarding-pair-error-bottom-screen")
    }

    /** On a phone in portrait every step fits, one column each, the QR code under the pairing code. */
    @Config(qualifiers = PHONE_PORTRAIT)
    @Test fun everyStepFitsAPhoneInPortrait() {
        var state by mutableStateOf(OnboardingState())
        rule.setContent { LoomTestTheme { OnboardingContent(state, NoopOnboardingActions) } }
        val steps = listOf(
            OnboardingState(), serverStepWithTheHttpWarning, pairStepWithItsCode, OnboardingState(step = OnboardingStep.STORAGE),
            OnboardingState(step = OnboardingStep.BACKGROUND), OnboardingState(step = OnboardingStep.SYNC),
        )
        for (step in steps) {
            state = step
            assertWithMessage(step.step.name).that(overflow()).isEqualTo(0f)
            if (step.step == OnboardingStep.PAIR) onScreen("Scan a code", "Enter a pair code", "Paste a token", "ABCD-2345")
            rule.saveScreenshot("onboarding-phone-${step.step.name.lowercase()}")
        }
        // The ROMs step's folder list scrolls in its own pane, under the suggestion.
        state = romsStep
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        onScreen("Create /storage/9C33-6BBD/ROMs", "Or browse", "SD card")
        rule.saveScreenshot("onboarding-phone-roms_root")
    }

    /** The ROMs step, with a folder to suggest and the SD card to browse. */
    private val romsStep = File("/storage/9C33-6BBD").let { card ->
        OnboardingState(step = OnboardingStep.ROMS_ROOT, suggestedRoot = File(card, "ROMs"), volumes = listOf(VolumeInfo("9C33-6BBD", card, "SD card", true)))
    }

    /** On the bottom screen the folder list goes under the suggestion, with room to browse in its own pane. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theRomsStepFitsTheBottomScreen() {
        rule.setContent { LoomTestTheme { OnboardingContent(romsStep, NoopOnboardingActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        onScreen("Which folder does Cocoon scan?", "Create /storage/9C33-6BBD/ROMs", "Or browse", "SD card")
        rule.saveScreenshot("onboarding-roms-bottom-screen")
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

    /** On the bottom screen the list takes the screen's width, every section in it without scrolling. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theSettingsSectionListFitsTheBottomScreen() {
        theSettingsSectionListFits()
        rule.onNodeWithTag("settings-content").assertDoesNotExist() // the sections open on their own
        rule.saveScreenshot("settings-bottom-screen")
    }

    /** On a phone in portrait each section opens on its own, and fits. */
    @Config(qualifiers = PHONE_PORTRAIT)
    @Test fun everySectionFitsAPhoneInPortrait() {
        // A new Settings for each section, as from the library: the list, then A on the section.
        var shown by mutableStateOf(SettingsSection.SERVER)
        rule.setContent { LoomTestTheme { key(shown) { SettingsContent(SettingsScreenTest.sample, SettingsScreenTest.noop) } } }
        for (section in SettingsSection.entries) {
            shown = section
            rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
            rule.onNodeWithTag("section-${section.name}").performClick()
            rule.onNodeWithText("Settings · ${section.title}").assertExists()
            assertWithMessage(section.name).that(settingsOverflow()).isEqualTo(0f)
            rule.saveScreenshot("settings-phone-${section.name.lowercase()}")
        }
    }

    /**
     * Found by review: Downloads & saves scrolled by over 400 px, with every save control below the
     * fold. Downloads and Saves are two sections now, and each fits.
     */
    @Test fun theDownloadsSectionFits() = theDownloadsSectionFits("settings-downloads-pair-again")

    /** The same, open on its own on the bottom screen: Pair again and the line beside it still fit, in full. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theDownloadsSectionFitsTheBottomScreen() = theDownloadsSectionFits("settings-downloads-pair-again-bottom-screen")

    private fun theDownloadsSectionFits(screenshot: String) {
        var state by mutableStateOf(SettingsScreenTest.sample.copy(sendToDevice = SendToDeviceState.Ready("dev-1")))
        rule.setContent { LoomTestTheme { SettingsContent(state, SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-DOWNLOADS").performClick()
        assertThat(settingsOverflow()).isEqualTo(0f) // battery-optimised, with the button to allow background downloads
        state = state.copy(batteryUnrestricted = true)
        assertThat(settingsOverflow()).isEqualTo(0f)
        // At its tallest: battery-optimised, and RomM refusing the device, with Pair again beside the switch.
        state = state.copy(batteryUnrestricted = false, sendToDevice = SendToDeviceState.NeedsPairing)
        assertThat(settingsOverflow()).isEqualTo(0f)
        assertThat(ellipsized("Pair again, with the code Loom shows")).isFalse()
        rule.saveScreenshot(screenshot)
    }

    /** Saves with every folder chosen, a failed sync and two Eden profiles to choose between. */
    private fun tallestSaves(lastSaveSync: SaveSyncOutcome): ro.davidmontei.loom.ui.settings.SettingsUiState {
        val settings = SettingsScreenTest.sample.settings!!.copy(
            azaharUserDir = "/storage/6365-3230/Emulation/Azahar/user",
            edenSaveDir = "/storage/6365-3230/Emulation/Eden/nand",
            melonDsSaveDir = "/storage/6365-3230/Emulation/melonDS",
            ppssppMemoryStick = "/storage/6365-3230/Emulation/PPSSPP",
            lastSaveSync = lastSaveSync,
        )
        val storage = SettingsScreenTest.sample.storage.copy(
            edenProfiles = listOf(EdenProfile("0123456789ABCDEF0123456789ABCDEF", 12, null), EdenProfile("4E431822D6DEFD527268BA35B5C09533", 3, null)),
        )
        return SettingsScreenTest.sample.copy(settings = settings, storage = storage)
    }

    /**
     * Saves at its tallest: a failed last sync whose reason, the longest, sits beside Pair again, and
     * a card per emulator, each saying where its folder is on one line.
     */
    @Test fun theSavesSectionFits() = theSavesSectionFits("settings-saves-tallest")

    /** The same Saves, open on its own on the bottom screen: it fits there too, each card's line in full. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theSavesSectionFitsTheBottomScreen() = theSavesSectionFits("settings-saves-bottom-screen")

    private fun theSavesSectionFits(screenshot: String) {
        val state = tallestSaves(SaveSyncOutcome(System.currentTimeMillis(), SaveSyncEngine.NO_DEVICE, failed = true, needsPairing = true))
        rule.setContent { LoomTestTheme { SettingsContent(state, SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-SAVES").performClick()
        assertThat(settingsOverflow()).isEqualTo(0f)
        rule.onNodeWithText("Pair again").assertExists()
        rule.onNodeWithTag("azahar-card").assertExists()
        rule.onNodeWithTag("eden-card").assertExists()
        rule.onNodeWithTag("melonds-card").assertExists()
        rule.onNodeWithTag("ppsspp-card").assertExists()
        // Each card's line in full. (Labels with letter spacing report a width overflow they don't
        // have, so the ellipsis and the line count are what's checked.)
        for (line in listOf("Your chosen folder", "Chosen, but it has no user/save", "Chosen, but it isn't there", "Chosen, but no ppsspp.ini")) {
            assertWithMessage(line).that(ellipsized(line)).isFalse()
            assertWithMessage(line).that(lineCount(line)).isEqualTo(1)
        }
        assertThat(cutShort("Saves from RomM always wait for you.")).isFalse() // the switch's explanation, in full
        rule.saveScreenshot(screenshot)
    }

    /** The same with sync switched off on RomM, days ago: the longest last-sync line, across the section's width. */
    @Test fun theSavesSectionFitsWithSyncSwitchedOffOnRomm() = theSavesSectionFitsWithSyncSwitchedOffOnRomm("settings-saves-sync-off")

    /** The same on the bottom screen, Saves open on its own. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theSavesSectionFitsWithSyncSwitchedOffOnRommOnTheBottomScreen() =
        theSavesSectionFitsWithSyncSwitchedOffOnRomm("settings-saves-sync-off-bottom-screen")

    private fun theSavesSectionFitsWithSyncSwitchedOffOnRomm(screenshot: String) {
        val state = tallestSaves(SaveSyncOutcome(System.currentTimeMillis() - 10 * 86_400_000L, SaveSyncEngine.SYNC_OFF, failed = true))
        rule.setContent { LoomTestTheme { SettingsContent(state, SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-SAVES").performClick()
        assertThat(settingsOverflow()).isEqualTo(0f)
        assertThat(lineCount("pairing again won't help.")).isEqualTo(2)
        rule.saveScreenshot(screenshot)
    }

    /**
     * Each emulator's details at their tallest, with every button: a long chosen folder, and for
     * Eden two profiles to choose between and no prod.keys. All of it shows, and the buttons sit
     * on one line inside the dialog.
     */
    @Test fun eachEmulatorsDialogFits() {
        val tallest = tallestSaves(SaveSyncOutcome(System.currentTimeMillis(), "no changes", failed = false))
        val long = "/storage/6365-3230/Emulation/Some Emulator Front-end/Collections/Nintendo/Handhelds and Consoles"
        val state = tallest.copy(settings = tallest.settings!!.copy(
            azaharUserDir = "$long/Azahar/user", edenSaveDir = "$long/Eden/nand", melonDsSaveDir = "$long/melonDS", ppssppMemoryStick = "$long/PPSSPP",
        ))
        rule.setContent { LoomTestTheme { SettingsContent(state, SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-SAVES").performClick()
        for ((tag, name) in listOf("azahar" to "Azahar", "eden" to "Eden", "melonds" to "melonDS", "ppsspp" to "PPSSPP")) {
            rule.onNodeWithTag("$tag-card").performClick()
            assertWithMessage(name).that(overflow("$name's saves")).isEqualTo(0f)
            rule.saveScreenshot("settings-saves-$tag-details")
            assertWithMessage(name).that(cutShort("$long/$name")).isFalse()
            val dialog = rule.onNode(hasText("$name's saves")).fetchSemanticsNode().boundsInRoot
            val close = rule.onNodeWithTag("$tag-close").fetchSemanticsNode().boundsInRoot
            assertWithMessage(name).that(close.right).isAtMost(dialog.right)
            assertWithMessage(name).that(lineCount("Close")).isEqualTo(1)
            if (tag == "eden") assertThat(lineCount("each Switch game asks which save folder is its.")).isEqualTo(1)
            rule.onNodeWithTag("$tag-close").performClick()
            rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        }
    }

    /** Eight Eden profiles: a list of them all, from Eden's details, that fits beside its explanation. */
    @Test fun manyEdenProfilesFit() {
        val profiles = (1..8).map { i -> EdenProfile("4E431822D6DEFD527268BA35B5C095%02X".format(i), 40 + i, java.time.Instant.parse("2026-10-14T12:00:00Z").minusSeconds(86_400L * i)) }
        val tallest = tallestSaves(SaveSyncOutcome(System.currentTimeMillis(), SaveSyncEngine.NO_DEVICE, failed = true, needsPairing = true))
        val state = tallest.copy(storage = tallest.storage.copy(edenProfiles = profiles))
        rule.setContent { LoomTestTheme { SettingsContent(state, SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-SAVES").performClick()
        assertThat(settingsOverflow()).isEqualTo(0f)
        rule.onNodeWithTag("eden-card").performClick()
        rule.onNodeWithTag("eden-profile").performClick()
        assertThat(overflow("Whose Switch saves does Loom sync?")).isEqualTo(0f)
        assertThat(cutShort("Yours is likely the one whose saves changed last.")).isFalse()
        rule.saveScreenshot("settings-saves-eden-profiles")
    }

    /** Controls & screens: both switches, each explanation in full, the second screen's with what R3 does. */
    @Test fun theControlsSectionFits() = theControlsSectionFits("settings-controls")

    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theControlsSectionFitsTheBottomScreen() = theControlsSectionFits("settings-controls-bottom-screen")

    private fun theControlsSectionFits(screenshot: String) {
        rule.setContent { LoomTestTheme { SettingsContent(SettingsScreenTest.sample, SettingsScreenTest.noop) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("section-CONTROLS").performClick()
        assertThat(settingsOverflow()).isEqualTo(0f)
        for (line in listOf("go back with the bottom one.", "stays in view while Loom is in front.", "or next open Loom.")) {
            assertWithMessage(line).that(cutShort(line)).isFalse()
        }
        rule.saveScreenshot(screenshot)
    }

    /** Saves as on the Thor: both folders found where Loom looks, shown in full, and Loom able to read title IDs. */
    @Test fun theSavesSectionShowsTheThorsFoldersInFull() = theSavesSectionShowsTheThorsFoldersInFull("settings-saves-thor")

    /** The same Saves, open on its own on the bottom screen: the folders found there in full too. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theSavesSectionShowsTheThorsFoldersInFullOnTheBottomScreen() =
        theSavesSectionShowsTheThorsFoldersInFull("settings-saves-thor-bottom-screen")

    private fun theSavesSectionShowsTheThorsFoldersInFull(screenshot: String) {
        val storage = SettingsScreenTest.sample.storage.copy(
            romsRoot = "/storage/6365-3230/ROMs", azaharFound = "/storage/6365-3230/ROMs/n3ds", edenFound = "/storage/6365-3230/ROMs/saves/Eden",
            edenProfiles = listOf(EdenProfile("4E431822D6DEFD527268BA35B5C09533", 2, null)), prodKeys = "/storage/6365-3230/ROMs/prod.keys",
            melonDsFound = "/storage/6365-3230/ROMs/saves/melonDS", ppssppFound = "/storage/6365-3230/ROMs/saves/PPSSPP",
        )
        rule.setContent { LoomTestTheme { SettingsContent(SettingsScreenTest.sample.copy(storage = storage), SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-SAVES").performClick()
        assertThat(settingsOverflow()).isEqualTo(0f)
        rule.saveScreenshot(screenshot)
        for (line in listOf("Found in the 3DS folder", "Found in ROMs/saves/Eden", "Found in ROMs/saves/melonDS", "Found in ROMs/saves/PPSSPP")) {
            assertWithMessage(line).that(ellipsized(line)).isFalse()
        }
        rule.onNodeWithTag("eden-card").performClick()
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        assertThat(lineCount("/storage/6365-3230/ROMs/saves/Eden")).isEqualTo(1)
    }

    /**
     * The folder browser in Saves, in a folder with more folders than fit: it takes the section's
     * place, and its list scrolls in its own pane, so the section itself never does.
     */
    @Test fun theSavesFolderBrowserFits() {
        val card = File(System.getProperty("java.io.tmpdir"), "loom-fits-${System.nanoTime()}/6365-3230")
        val roms = File(card, "ROMs")
        try {
            (1..30).forEach { File(roms, "system%02d".format(it)).mkdirs() }
            val n3ds = File(roms, "n3ds").apply { File(this, "sdmc/Nintendo 3DS").mkdirs() }
            val state = SettingsScreenTest.sample.copy(storage = SettingsScreenTest.sample.storage.copy(romsRoot = roms.path, azaharFound = n3ds.path))
            rule.setContent { LoomTestTheme { SettingsContent(state, SettingsScreenTest.noop) } }
            rule.onNodeWithTag("section-SAVES").performClick()
            rule.onNodeWithTag("azahar-card").performClick()
            rule.onNodeWithTag("azahar-change-folder").performClick()
            rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
            rule.onNodeWithTag("save-folder-browser").assertExists()
            assertThat(settingsOverflow()).isEqualTo(0f)
            rule.onNodeWithText("Use this folder").assertExists()
            rule.saveScreenshot("settings-saves-browser")
        } finally {
            card.parentFile!!.deleteRecursively()
        }
    }

    /**
     * Found by audit: the platform folder editor was taller than the screen. With Reset, and the longest hint
     * under the field for a platform without other folders of its own: eight Cocoon folders.
     */
    @Test fun thePlatformFolderEditorFits() = thePlatformFolderEditorFits("settings-mapping-editor-longest-hint")

    /** On the bottom screen the editor takes the screen's width, and still fits. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun thePlatformFolderEditorFitsTheBottomScreen() = thePlatformFolderEditorFits("settings-mapping-editor-bottom-screen")

    private fun thePlatformFolderEditorFits(screenshot: String) {
        val state = SettingsScreenTest.sample.copy(mapping = listOf(SettingsScreenTest.chosenRow(1, "PlayStation 2", "ps2", "ps2", "ps2")))
        val cocoon = listOf("psx", "ps2", "ps3", "psp", "pspminis", "pico8", "pokemini", "pc88", "pc98", "pcfx")
        val actions = object : SettingsActions by SettingsScreenTest.noop { override val cocoonFolders = cocoon }
        rule.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.onNodeWithTag("section-MAPPING").performClick()
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithText("PlayStation 2").performClick()
        rule.onNodeWithText("Other Cocoon folders: psx, ps3, psp, pspminis, pico8, pokemini, pc88, pc98", useUnmergedTree = true).assertExists()
        assertThat(overflow("Files to download")).isEqualTo(0f)
        rule.saveScreenshot(screenshot)
    }

    /** Whether every node holding [texts] lies wholly on the screen: buttons that wrap must not wrap off it. */
    private fun onScreen(vararg texts: String) {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (text in texts) {
            val bounds = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            assertWithMessage(text).that(bounds.left).isAtLeast(root.left)
            assertWithMessage(text).that(bounds.right).isAtMost(root.right)
            assertWithMessage(text).that(bounds.top).isAtLeast(root.top)
            assertWithMessage(text).that(bounds.bottom).isAtMost(root.bottom)
        }
    }

    /**
     * A 3DS game's details at their tallest: a title and install path long enough to wrap, the
     * save's line at its longest, and a row of save buttons above the dialog's own.
     */
    @Test fun aGamesDetailsWithItsSaveFit() = aGamesDetailsWithItsSaveFit("library-details-save")

    /** The same on the bottom screen: the dialog takes its width, and the look's words go under the save's line. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun aGamesDetailsWithItsSaveFitTheBottomScreen() {
        aGamesDetailsWithItsSaveFit("library-details-save-bottom-screen")
        onScreen("Upload save", "Close", "Remove from device")
    }

    private fun aGamesDetailsWithItsSaveFit(screenshot: String) {
        val title = "Pokémon Omega Ruby (USA) (En,Ja,Fr,De,Es,It,Ko) (Rev 2) [Special Demo Edition]"
        val game = GameItem(30, title, 3_900_000_000, null, listOf("USA"), InstallState.INSTALLED, null, null, null, adopted = true,
            removedOnServer = false, installPath = "n3ds/$title.3ds", save = GameSave(true, SaveSyncState.SERVER_NEWER, null, "azahar"))
        var state by mutableStateOf(LibraryScreenTest().state.copy(games = listOf(game)))
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("game-30").performClick()
        assertThat(overflow(title)).isEqualTo(0f)
        rule.saveScreenshot(screenshot)
        val failed = GameSave(true, SaveSyncState.ERROR, "Couldn't read the save: /storage/6365-3230/ROMs/n3ds/sdmc/Nintendo 3DS/" +
            "00000000000000000000000000000000/00000000000000000000000000000000/title/00040000/0011c400/data (Permission denied)", "azahar")
        state = state.copy(games = listOf(game.copy(save = failed)))
        assertThat(overflow(title)).isEqualTo(0f)
        // A look that couldn't ask RomM says so in full beside the buttons (under the save's line on a narrow screen).
        state = state.copy(games = listOf(game.copy(save = failed.copy(look = SaveLook.ThorOnly("RomM rejected Loom's token")))))
        assertThat(overflow(title)).isEqualTo(0f)
        // In full: on one line, a text shorter than its room can report a width overflow it doesn't have.
        assertThat(ellipsized("checked the Thor's side only")).isFalse()
        rule.saveScreenshot("$screenshot-look")
    }

    /**
     * A Switch game's details at their tallest: a long title and path, the folder question's line
     * at its longest, then a chosen folder with all three save buttons in one row.
     */
    @Test fun aSwitchGamesDetailsWithItsSaveFit() = aSwitchGamesDetailsWithItsSaveFit("library-details-switch")

    /** On the bottom screen the three save buttons wrap, all of them on the screen. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun aSwitchGamesSaveButtonsWrapOnTheBottomScreen() {
        aSwitchGamesDetailsWithItsSaveFit("library-details-switch-bottom-screen")
        onScreen("Upload save", "Restore server save", "Change save folder", "Close", "Remove from device")
        val saveButtons = listOf("Upload save", "Restore server save", "Change save folder").map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertThat(saveButtons.distinct().size).isGreaterThan(1)
    }

    private fun aSwitchGamesDetailsWithItsSaveFit(screenshot: String) {
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
        rule.saveScreenshot("$screenshot-folder")
        // Missing on the Thor, with automatic upload off: Upload save, Restore server save and Change save folder.
        state = state.copy(games = listOf(game.copy(save = GameSave(true, SaveSyncState.LOCAL_MISSING, null, "eden", autoUpload = false, folderChosen = true))))
        assertThat(overflow(title)).isEqualTo(0f)
        rule.saveScreenshot("$screenshot-buttons")
    }

    /**
     * A DS or PSP game's details with its save's reason at its longest, under a long title and path:
     * what to set in melonDS or PPSSPP, where there are no save buttons, and why Loom asks for a PSP
     * game's save folders, above the button that asks, in full.
     */
    @Test fun aDsOrPspGamesSaveReasonFitsInFull() {
        val title = "Monster Hunter Freedom Unite (USA) (En,Fr,De,Es,It) (Rev 1) (Greatest Hits) (v1.01)"
        val roms = "/storage/6365-3230/ROMs"
        val saves = listOf(
            GameSave(true, SaveSyncState.UNSUPPORTED, "melonDS's saves aren't in $roms/saves/melonDS: in melonDS's Save Files settings, " +
                "turn off Save next to ROM file and set the Save file directory there, or choose its folder in Settings", "melonds"),
            GameSave(true, SaveSyncState.UNSUPPORTED, "PPSSPP's memory stick isn't $roms/saves/PPSSPP: in PPSSPP's System settings, set the " +
                "Memory Stick folder there, or choose PPSSPP's folder in Settings", "ppsspp"),
            GameSave(true, SaveSyncState.UNSUPPORTED, "New Super Mario Bros. (USA, Australia) (Rev 1) has the same melonDS save file " +
                "(New Super Mario Bros. (USA, Australia) (Rev 1).sav), and Loom syncs a save for one game only", "melonds"),
            GameSave(true, SaveSyncState.UNSUPPORTED, "Monster Hunter Freedom Unite (USA) (En,Fr,De,Es,It) (Rev 1) has the same PSP disc ID " +
                "(ULUS10391), and Loom syncs a game's save folders for one game only", "ppsspp"),
            GameSave(true, SaveSyncState.NEEDS_FOLDER, "Its image says it is ULUS10084, not ULUS10041 as you chose", "ppsspp"),
            GameSave(true, SaveSyncState.NEEDS_FOLDER, "Its image names two disc IDs: ULUS10084 in UMD_DATA.BIN, ULJM05500 in PARAM.SFO", "ppsspp"),
            GameSave(true, SaveSyncState.NEEDS_FOLDER, "RomM says it is ULUS10084, its image ULJM05500", "ppsspp"),
            GameSave(true, SaveSyncState.NEEDS_FOLDER, "RomM didn't read its disc ID, and Loom can't read a CHD on the Thor", "ppsspp"),
            GameSave(true, SaveSyncState.NEEDS_FOLDER, "RomM didn't read its disc ID, and Loom can't: the PBP's PARAM.SFO names no disc ID", "ppsspp"),
        )
        val game = GameItem(40, title, 1_600_000_000, null, listOf("USA"), InstallState.INSTALLED, null, null, null, adopted = true,
            removedOnServer = false, installPath = "psp/$title.chd", save = saves.first())
        var state by mutableStateOf(LibraryScreenTest().state.copy(games = listOf(game)))
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("game-40").performClick()
        for (save in saves) {
            state = state.copy(games = listOf(game.copy(save = save)))
            assertWithMessage(save.detail).that(overflow(title)).isEqualTo(0f)
            assertWithMessage(save.detail).that(cutShort(save.detail!!.takeLast(20))).isFalse()
        }
        rule.saveScreenshot("library-details-psp-reason")
    }

    /**
     * A conflict's details give its own reason, with both save buttons below: each of the queue's
     * reasons fits, and one Loom can't restore at its longest fits the dialog, cut short if need be.
     */
    @Test fun aConflictsReasonFitsItsDetails() {
        val title = "Monster Hunter Freedom Unite (USA) (En,Fr,De,Es,It) (Rev 1) (Greatest Hits) (v1.01)"
        val conflict = GameSave(true, SaveSyncState.CONFLICT, null, "ppsspp", folderChosen = true)
        val game = GameItem(40, title, 1_600_000_000, null, listOf("USA"), InstallState.INSTALLED, null, null, null, adopted = true,
            removedOnServer = false, installPath = "psp/$title.chd", save = conflict)
        var state by mutableStateOf(LibraryScreenTest().state.copy(games = listOf(game)))
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("game-40").performClick()
        for (reason in SyncDecision.entries.filter { it.outcome == SyncOutcome.CONFLICT }.map { conflictReason(it.name) }) {
            state = state.copy(games = listOf(game.copy(save = conflict.copy(detail = reason))))
            assertWithMessage(reason).that(overflow(title)).isEqualTo(0f)
            // A one-line text can measure as overflowing its width: an ellipsis is what tells it is cut.
            assertWithMessage(reason).that(ellipsized(reason.takeLast(20))).isFalse()
        }
        val unrestorable = "RomM's save is over 256 MB, more than Loom downloads: it likely holds data the game installed; nothing was replaced"
        state = state.copy(games = listOf(game.copy(save = conflict.copy(detail = unrestorable))))
        assertThat(overflow(title)).isEqualTo(0f)
        rule.saveScreenshot("library-details-conflict-reason")
    }

    /** Which Eden save folder is a game's: the explanation in full, and the folders in a list of their own. */
    @Test fun theSaveFolderQuestionFits() {
        val title = "The Legend of Zelda: Tears of the Kingdom (World) (En,Ja,Fr,De,Es,It,Nl,Ru,Ko,Zh) (v1.2.1)"
        val game = GameItem(40, title, 16_300_000_000, null, listOf("World"), InstallState.INSTALLED, null, null, null, adopted = true,
            removedOnServer = false, installPath = "switch/$title.xci", save = GameSave(true, SaveSyncState.NEEDS_FOLDER, EdenSaves.NO_KEYS, "eden"))
        val options = (1..12).map { SaveFolderOption("0100%012X".format(it * 0x1000L), 4, 1_200_000, System.currentTimeMillis() - it * 3_600_000L) }
        val state = LibraryScreenTest().state.copy(games = listOf(game),
            saveFolderQuestion = SaveFolderQuestion(40, title, "eden", options, current = options.first().id))
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        assertThat(overflow("Which Eden save folder is $title's?")).isEqualTo(0f)
        assertThat(ellipsized("4 files · ${formatBytes(1_200_000)} · chosen now")).isFalse()
        rule.saveScreenshot("library-save-folder-question")
    }

    /** Which disc ID's PPSSPP folders are a PSP game's: the explanation in full, and the disc IDs in a list of their own. */
    @Test fun thePspSaveFolderQuestionFits() {
        val title = "Monster Hunter Freedom Unite (USA) (En,Fr,De,Es,It) (Rev 1) (Greatest Hits) (v1.01)"
        val game = GameItem(40, title, 1_600_000_000, null, listOf("USA"), InstallState.INSTALLED, null, null, null, adopted = true,
            removedOnServer = false, installPath = "psp/$title.chd",
            save = GameSave(true, SaveSyncState.NEEDS_FOLDER, "RomM didn't read this game's disc ID, and Loom can't read a CHD on the Thor", "ppsspp"))
        val options = (1..12).map { SaveFolderOption("ULUS%05d".format(10_000 + it), 9, 12_400_000, System.currentTimeMillis() - it * 3_600_000L, folderCount = 3) }
        // Asked again from its details: the folders chosen now say so.
        val question = SaveFolderQuestion(40, title, "ppsspp", options, current = options.first().id, kind = SaveFolderQuestion.Kind.PREFIX)
        val state = LibraryScreenTest().state.copy(games = listOf(game), saveFolderQuestion = question)
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        assertThat(overflow("Which PPSSPP save folders are $title's?")).isEqualTo(0f)
        assertThat(cutShort("Its details can change this later.")).isFalse()
        // What each holds and how much, beside when it changed: a line each.
        assertThat(ellipsized("3 folders · ${formatBytes(12_400_000)} · chosen now")).isFalse()
        rule.saveScreenshot("library-psp-save-folder-question")
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
    @Test fun theSaveConflictChoiceWithChangeSaveFolderFits() = theSaveConflictsAnswersFitWhole("queue-conflict-choice-change-folder")

    /** On the bottom screen the five buttons wrap, every one of them on the screen. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theSaveConflictChoiceFitsTheBottomScreen() = theSaveConflictsAnswersFitWhole("queue-conflict-choice-bottom-screen")

    /**
     * Found by review: a window from 600 dp to about 830 kept the Thor's single line of buttons, and
     * the last answers were squeezed, Keep both to "Keep", or to nothing at 600. They wrap instead.
     */
    @Config(qualifiers = TABLET_PORTRAIT)
    @Test fun theSaveConflictsAnswersFitATabletInPortrait() = theSaveConflictsAnswersFitWhole("queue-conflict-choice-tablet-portrait")

    @Config(qualifiers = SMALL_TABLET_PORTRAIT)
    @Test fun theSaveConflictsAnswersFitASmallTabletInPortrait() = theSaveConflictsAnswersFitWhole("queue-conflict-choice-small-tablet")

    private fun theSaveConflictsAnswersFitWhole(screenshot: String) {
        theSaveConflictChoiceWithChangeSaveFolderFits(screenshot)
        val answers = arrayOf("Change save folder", "Skip", "Keep Thor", "Keep server", "Keep both")
        onScreen(*answers)
        for (answer in answers) {
            val label = rule.onNodeWithText(answer, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            label.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            // A button's label is one line: squeezed, the rest of it is left out.
            assertWithMessage(answer).that(layouts.single().multiParagraph.didExceedMaxLines).isFalse()
        }
    }

    private fun theSaveConflictChoiceWithChangeSaveFolderFits(screenshot: String) {
        val title = "The Legend of Zelda: Tears of the Kingdom (World) (En,Ja,Fr,De,Es,It,Nl,Ru,Ko,Zh) (v1.2.1)"
        val conflict = QueueScreenTest.pokemonX.copy(title = title, reason = longestConflictReason,
            serverEmulator = "eden", emulator = "eden", serverDevice = "AYN Odin 2 Portal (Argosy)", folderChosen = true)
        rule.setContent { LoomTestTheme { QueueContent(QueueScreenTest.sample.copy(conflicts = listOf(conflict)), NoopQueueActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("conflict-7").performClick()
        rule.onNodeWithText("Change save folder").assertExists()
        assertThat(overflow("$title: which save to keep?")).isEqualTo(0f)
        assertThat(cutShort("Change save folder picks again.")).isFalse()
        rule.saveScreenshot(screenshot)
    }

    /**
     * RomM's save Loom can't restore, for a Switch game whose folder the user picked: the longest
     * reason Loom gives, which names both title IDs, with Change save folder beside the answers.
     */
    @Test fun anUnreadableSaveConflictChoiceFits() {
        val title = "The Legend of Zelda: Tears of the Kingdom (World) (En,Ja,Fr,De,Es,It,Nl,Ru,Ko,Zh) (v1.2.1)"
        val why = "RomM's save is for title 0100A0B00C0D0000 and 0100F2C0115B6000, not 01002B300C1D2000, the save folder this game uses. " +
            "If that folder is the wrong one, change it in the game's details; nothing was replaced"
        val conflict = QueueScreenTest.pokemonX.copy(title = title, reason = why, serverUnreadable = true,
            serverEmulator = "eden", emulator = "eden", serverDevice = "AYN Odin 2 Portal (Argosy)", folderChosen = true)
        rule.setContent { LoomTestTheme { QueueContent(QueueScreenTest.sample.copy(conflicts = listOf(conflict)), NoopQueueActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("conflict-7").performClick()
        assertThat(overflow("$title: which save to keep?")).isEqualTo(0f)
        assertThat(cutShort("Change save folder picks again.")).isFalse()
        rule.saveScreenshot("queue-conflict-choice-unreadable")
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
    @Test fun theLibrarySyncSectionFits() = theLibrarySyncSectionFits("settings-library-sync")

    /**
     * Found by review: at 600 dp Settings put the section beside the list in 282 dp, and the failed
     * sync's reason beside Pair again and Full resync took a character a line, the section 1000 dp
     * tall. Below 800 dp a section opens on its own instead, as on a narrow screen.
     */
    @Config(qualifiers = SMALL_TABLET_PORTRAIT)
    @Test fun theLibrarySyncSectionFitsASmallTabletInPortrait() {
        theLibrarySyncSectionFits("settings-library-sync-small-tablet")
        rule.onNodeWithText("Settings · ${SettingsSection.LIBRARY.title}").assertExists()
        rule.onNodeWithTag("settings-sections").assertDoesNotExist()
    }

    /** A tablet in portrait has room for the section beside the list, a little more than the Thor's bottom screen gives it alone. */
    @Config(qualifiers = TABLET_PORTRAIT)
    @Test fun theLibrarySyncSectionFitsBesideTheListOnATabletInPortrait() {
        theLibrarySyncSectionFits("settings-library-sync-tablet-portrait")
        rule.onNodeWithTag("settings-sections").assertExists()
    }

    private fun theLibrarySyncSectionFits(screenshot: String) {
        val state = SettingsScreenTest.sample.copy(
            settings = SettingsScreenTest.sample.settings!!.copy(lastCatalogSyncEpochMs = System.currentTimeMillis() - 3 * 3_600_000L),
            sync = CatalogSyncStatus(running = false, error = "RomM rejected Loom's token. Pair again in Settings.", needsPairing = true),
        )
        rule.setContent { LoomTestTheme { SettingsContent(state, SettingsScreenTest.noop) } }
        rule.onNodeWithTag("section-LIBRARY").performClick()
        val overflow = overflowOf("settings-content")
        rule.saveScreenshot(screenshot)
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

    /**
     * The library's hints, the most any screen shows, each checked to be whole: one line, inside the
     * screen's [margin] (narrower on a narrow screen). Returns how many lines of hints there are.
     */
    private fun libraryHintLines(margin: Dp = Space.xl): Int {
        rule.setContent { LoomTestTheme { LibraryContent(LibraryScreenTest().state, NoopLibraryActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        val end = rule.onRoot().fetchSemanticsNode().boundsInRoot.right - with(rule.density) { margin.toPx() }
        val hints = listOf("Details", "Download / offload", "Search", "Platform", "Queue", "Settings").map { label ->
            label to rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("button-hints")), useUnmergedTree = true).fetchSemanticsNode()
        }
        for ((label, hint) in hints) {
            assertWithMessage(label).that(lineCount(hint)).isEqualTo(1)
            assertWithMessage(label).that(hint.boundsInRoot.right).isAtMost(end)
        }
        return hints.map { it.second.boundsInRoot.top }.distinct().size
    }

    /**
     * At the Thor's exact size, 832 dp wide, the six hints 24 dp apart took 781 of the 784 dp
     * between the margins: a little more text and Settings would have been squeezed. 16 dp apart
     * they leave about 40 dp, on one line.
     */
    @Test fun theLibrarysHintsFitOnOneLine() {
        assertThat(libraryHintLines()).isEqualTo(1)
    }

    /** On a narrower screen, such as the Thor's bottom one, the hints wrap rather than squeeze the last one. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theHintsWrapToTwoLinesAtMost() {
        assertThat(libraryHintLines(margin = Space.l)).isEqualTo(2)
        rule.saveScreenshot("library-hints-bottom-screen")
    }

    /** A phone in portrait is narrower still: its narrower margins keep the hints to two lines. */
    @Config(qualifiers = PHONE_PORTRAIT)
    @Test fun theHintsWrapToTwoLinesAtMostOnAPhone() {
        assertThat(libraryHintLines(margin = Space.l)).isEqualTo(2)
    }

    /**
     * While AYN's Focus Mode has the pad on the other screen, its [notice] takes the hints' place in
     * full, inside the screen's [margin], in no more [lines] than the hints took there.
     */
    private fun theFocusModeNoticeFits(notice: PadNotice, lines: Int, margin: Dp, screenshot: String) {
        val companion = CompanionHolder().apply { setPadNotice(notice) }
        rule.setContent {
            CompositionLocalProvider(LocalCompanion provides companion) { LoomTestTheme { LibraryContent(LibraryScreenTest().state, NoopLibraryActions) } }
        }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("button-hints").assertDoesNotExist()
        assertThat(lineCount(notice.text)).isAtMost(lines) // and no limit to cut it short
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val shown = rule.onNodeWithTag("pad-notice").fetchSemanticsNode().boundsInRoot
        assertThat(shown.right).isAtMost(root.right - with(rule.density) { margin.toPx() })
        assertThat(shown.bottom).isAtMost(root.bottom)
        // One line is the hints' height, so the screen above keeps the room it was laid out for.
        if (lines == 1) assertThat(shown.height).isAtMost(with(rule.density) { 26.dp.roundToPx() }.toFloat())
        rule.saveScreenshot(screenshot)
    }

    /** Loom on the top screen, the pad on the bottom one: one line, as the hints were. */
    @Test fun theFocusModeNoticeFitsInPlaceOfTheHints() =
        theFocusModeNoticeFits(PadNotice.LOCKED_TO_BOTTOM, lines = 1, Space.xl, "library-focus-mode-notice")

    /** Loom on the bottom screen, the pad on the top one. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theFocusModeNoticeFitsInPlaceOfTheHintsOnTheBottomScreen() =
        theFocusModeNoticeFits(PadNotice.LOCKED_TO_TOP, lines = 2, Space.l, "library-focus-mode-notice-bottom-screen")

    /**
     * The library's header on a narrow screen: the platform's name and where the sync stands in full,
     * with the sign that RomM can't be reached too, the four buttons inside the margin, and the filter
     * chip on one line, whichever filter.
     */
    private fun theLibraryHeaderFits(screenshot: String) {
        var state by mutableStateOf(LibraryScreenTest().state)
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("platform-rail").assertDoesNotExist()
        val end = rule.onRoot().fetchSemanticsNode().boundsInRoot.right - with(rule.density) { Space.l.toPx() }
        fun fits(vararg lines: String) {
            rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
            for (line in lines) {
                assertWithMessage(line).that(ellipsized(line)).isFalse()
                assertWithMessage(line).that(lineCount(line)).isEqualTo(1)
            }
            for (button in listOf("Sync library", "Grid or list", "Settings")) {
                assertWithMessage(button).that(rule.onNodeWithContentDescription(button).fetchSemanticsNode().boundsInRoot.right).isAtMost(end)
            }
            assertThat(lineCount(rule.onNode(hasAnyAncestor(hasTestTag("filter")), useUnmergedTree = true).fetchSemanticsNode())).isEqualTo(1)
            // The search box keeps room to show what's typed.
            assertThat(with(rule.density) { rule.onNodeWithTag("search").fetchSemanticsNode().boundsInRoot.width.toDp() }).isAtLeast(160.dp)
        }
        fits("Super Nintendo", "Synced 5 min ago")
        rule.saveScreenshot(screenshot)
        state = state.copy(header = state.header.copy(offline = true), filter = LibraryFilter.NOT_ON_DEVICE)
        fits("Super Nintendo", "Synced 5 min ago")
        rule.onNodeWithContentDescription("Offline · showing your saved library").assertExists() // beside it
    }

    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theLibraryHeaderFitsTheBottomScreen() = theLibraryHeaderFits("library-header-bottom-screen")

    @Config(qualifiers = PHONE_PORTRAIT)
    @Test fun theLibraryHeaderFitsAPhoneInPortrait() = theLibraryHeaderFits("library-header-phone")

    /** The platforms to pick from on the bottom screen: many of them scroll in their own pane, the dialog in full. */
    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun thePlatformPickerFitsTheBottomScreen() {
        val library = LibraryScreenTest().state
        val platforms = (1..40).map { i -> library.platforms[0].let { it.copy(platform = it.platform.copy(id = i, displayName = "Platform $i")) } }
        rule.setContent { LoomTestTheme { LibraryContent(library.copy(platforms = platforms, selected = platforms[30]), NoopLibraryActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithTag("platform-title").performClick()
        assertThat(overflow("Choose a platform")).isEqualTo(0f)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (node in listOf(rule.onNodeWithTag("platform-31"), rule.onNodeWithText("Cancel"))) {
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertThat(bounds.top).isAtLeast(root.top)
            assertThat(bounds.bottom).isAtMost(root.bottom)
        }
        rule.saveScreenshot("library-platform-picker")
    }

    /** The pad's keyboard mode, for a native-graphics window, which starts in touch mode where nothing takes focus. */
    private val pad = object : InputModeManager {
        override val inputMode get() = InputMode.Keyboard
        override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
    }

    /**
     * The focused game's details beside the games at their tallest: a title and path long enough to wrap,
     * a failed save sync's long reason, then a download being moved into place.
     */
    private fun theDetailsBesideFit(screenshot: String) {
        val title = "Pokémon Omega Ruby (USA) (En,Ja,Fr,De,Es,It,Ko) (Rev 2) [Special Demo Edition]"
        val failed = GameSave(true, SaveSyncState.ERROR, "Couldn't read the save: /storage/6365-3230/ROMs/n3ds/sdmc/Nintendo 3DS/" +
            "00000000000000000000000000000000/00000000000000000000000000000000/title/00040000/0011c400/data (Permission denied)", "azahar")
        val game = GameItem(30, title, 3_900_000_000, null, listOf("USA", "Europe", "Japan"), InstallState.INSTALLED, null, null, null,
            adopted = true, removedOnServer = false, installPath = "n3ds/$title.3ds", save = failed, matchedByName = true)
        var state by mutableStateOf(LibraryScreenTest().state.copy(games = listOf(game) + LibraryScreenTest().state.games))
        rule.setContent { CompositionLocalProvider(LocalInputModeManager provides pad) { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } } }
        rule.assertFitsIn("details-beside")
        rule.onNode(hasText(title) and hasAnyAncestor(hasTestTag("details-beside"))).assertExists()
        rule.saveScreenshot(screenshot)
        val finishing = game.copy(state = InstallState.DOWNLOADING, stateDetail = "Finishing…", progress = 1f, jobId = 30, installPath = null,
            save = null, finishing = true, adopted = false, matchedByName = false)
        state = state.copy(games = listOf(finishing) + state.games.drop(1))
        rule.assertFitsIn("details-beside")
        rule.onNode(hasText("Finishing…") and hasAnyAncestor(hasTestTag("details-beside"))).assertExists()
        rule.saveScreenshot("$screenshot-finishing")
    }

    /** A phone on its side has the width for the details beside the games, but little height: a small cover beside the title. */
    @Config(qualifiers = PHONE_LANDSCAPE)
    @Test fun theDetailsBesideFitAPhoneInLandscape() = theDetailsBesideFit("library-details-beside-phone")

    @Config(qualifiers = TABLET_LANDSCAPE)
    @Test fun theDetailsBesideFitATablet() {
        theDetailsBesideFit("library-details-beside-tablet")
        val cover = rule.onNodeWithTag("panel-cover").fetchSemanticsNode().boundsInRoot
        assertThat(with(rule.density) { cover.height.toDp() }).isAtLeast(240.dp) // above the words, and large
    }

    /** The question at a platform's first download, with its few options and with every folder of the Thor's ES-DE template. */
    @Test fun theFolderQuestionFits() = theFolderQuestionFits("library-folder-question")

    /** On a phone, two folders to a row, each name in full. */
    @Config(qualifiers = PHONE_PORTRAIT)
    @Test fun theFolderQuestionFitsAPhone() {
        theFolderQuestionFits("library-folder-question-phone")
        onScreen("Cancel")
    }

    private fun theFolderQuestionFits(screenshot: String) {
        val options = listOf(SystemFolder("mame", 0, "Multiple Arcade Machine Emulator"), SystemFolder("arcade", 2, "Arcade"),
            SystemFolder("fbneo", 5, "FinalBurn Neo"), SystemFolder("fba", 0, "FinalBurn Alpha"))
        val game = GameItem(1, "Alley Master", 101_385, null, emptyList(), InstallState.NOT_INSTALLED, null, null, null, false, false, null)
        val question = FolderQuestion(game.id, game.title, 1, "Arcade", "arcade", "arcade", options, "mame")
        var state by mutableStateOf(LibraryScreenTest().state.copy(folderQuestion = question))
        rule.setContent { LoomTestTheme { LibraryContent(state, NoopLibraryActions) } }
        assertThat(overflow("Where does Cocoon look for Arcade games?")).isEqualTo(0f)
        for (folder in options) {
            assertWithMessage(folder.name).that(ellipsized("${folder.name}/")).isFalse()
            assertWithMessage(folder.name).that(lineCount("${folder.name}/")).isEqualTo(1)
        }
        rule.saveScreenshot(screenshot)
        state = state.copy(folderQuestion = question.copy(allFolders = (1..178).map { SystemFolder("system$it", 0, "Some System $it") }))
        assertThat(overflow("Where does Cocoon look for Arcade games?")).isEqualTo(0f)
    }

    /** The same question asked in the queue, for a game sent from RomM waiting for it, with the longest platform name. */
    @Test fun theFolderQuestionFitsInTheQueue() {
        val question = QueueScreenTest.megaDriveFolders.copy(
            platformName = "Sega Mega Drive/Genesis 32X CD (Sega Mega-CD 32X)",
            options = listOf(SystemFolder("megadrive", 12, "Sega Mega Drive"), SystemFolder("genesis", 3, "Sega Genesis"),
                SystemFolder("sega32x", 0, "Sega 32X"), SystemFolder("megacd", 0, "Sega Mega-CD")),
        )
        var state by mutableStateOf(QueueScreenTest.withAFolderToChoose.copy(folderQuestion = question))
        rule.setContent { LoomTestTheme { QueueContent(state, NoopQueueActions) } }
        assertThat(overflow("Where does Cocoon look for ${question.platformName} games?")).isEqualTo(0f)
        rule.saveScreenshot("queue-folder-question")
        state = state.copy(folderQuestion = question.copy(allFolders = (1..178).map { SystemFolder("system$it", 0, "Some System $it") }))
        assertThat(overflow("Where does Cocoon look for ${question.platformName} games?")).isEqualTo(0f)
    }

    /**
     * A game sent from RomM with a long title, waiting for its folder (the longest badge), under the note that
     * RomM can't send more until Loom pairs again: "From RomM" and the badge keep their place and the title
     * gives way, so the row stays one line high; the note fits on its line.
     */
    @Test fun aLongTitleSentFromRommStaysOnOneLine() {
        val title = "Shin Megami Tensei: Digital Devil Saga 2 - Avatar Tuner (USA) (Rev 1) (Limited Collector's Edition)"
        val sent = QueueScreenTest.sonic.copy(title = title, fromRomm = true)
        val state = QueueScreenTest.sample.copy(active = listOf(sent) + QueueScreenTest.sample.active, rommNeedsPairing = true)
        rule.setContent { LoomTestTheme { QueueContent(state, NoopQueueActions) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        assertThat(lineCount(title)).isEqualTo(1)
        assertThat(lineCount("From RomM")).isEqualTo(1)
        assertThat(ellipsized("From RomM")).isFalse()
        assertThat(ellipsized("RomM can't send games here")).isFalse()
        rule.saveScreenshot("queue-from-romm")
        // As tall as a game picked in the library, to well under a pixel (the Thor's 2.30625 px a dp leaves float noise).
        assertThat(rule.onNodeWithTag("job-${sent.jobId}").getBoundsInRoot().let { it.bottom - it.top }.value)
            .isWithin(0.01f).of(rule.onNodeWithTag("job-1").getBoundsInRoot().let { it.bottom - it.top }.value)
    }
}
