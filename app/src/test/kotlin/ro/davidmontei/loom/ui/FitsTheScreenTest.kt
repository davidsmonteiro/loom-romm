package ro.davidmontei.loom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ro.davidmontei.loom.data.catalog.CatalogSyncStatus
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import ro.davidmontei.loom.storage.mapping.PlatformMatch
import ro.davidmontei.loom.storage.mapping.SystemFolder
import ro.davidmontei.loom.ui.library.FolderQuestion
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.InstallState
import ro.davidmontei.loom.ui.library.LibraryContent
import ro.davidmontei.loom.ui.onboarding.OnboardingActions
import ro.davidmontei.loom.ui.onboarding.OnboardingContent
import ro.davidmontei.loom.ui.onboarding.OnboardingState
import ro.davidmontei.loom.ui.onboarding.OnboardingStep
import ro.davidmontei.loom.ui.onboarding.PairMode
import ro.davidmontei.loom.ui.settings.SettingsActions
import ro.davidmontei.loom.ui.settings.SettingsContent

/**
 * Screens that must fit the Thor's top screen without scrolling. Native graphics measure text with
 * the real font, so line wrapping (and so height) is as on the device; focus can't be tested here.
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

    /** Found by audit: the platform folder editor was taller than the screen. */
    @Test fun thePlatformFolderEditorFits() {
        val psx = SettingsScreenTest.sample.mapping.first()
        val state = SettingsScreenTest.sample.copy(mapping = listOf(psx.copy(override = PlatformMappingEntry(PlatformMatch(slug = "psx"), folder = "psx"))))
        val cocoon = listOf("psx", "ps2", "ps3", "psp", "pspminis", "pico8", "pokemini", "pc88", "pc98", "pcfx")
        val actions = object : SettingsActions by SettingsScreenTest.noop { override val cocoonFolders = cocoon }
        rule.setContent { LoomTestTheme { SettingsContent(state, actions) } }
        rule.onNodeWithTag("section-MAPPING").performClick()
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithText("PlayStation").performClick() // with Reset, and the other Cocoon folders under the field
        assertThat(overflow("Files to download")).isEqualTo(0f)
    }

    /** How far the scrolling node tagged [tag] could scroll, in px: 0 when everything fits. */
    private fun overflowOf(tag: String): Float {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        return rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
    }

    /** With Library sync added, the section list is seven rows: all of them in view, none below the fold. */
    @Test fun theSettingsSectionListFits() {
        rule.setContent { LoomTestTheme { SettingsContent(SettingsScreenTest.sample, SettingsScreenTest.noop) } }
        assertThat(overflowOf("settings-sections")).isEqualTo(0f)
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
