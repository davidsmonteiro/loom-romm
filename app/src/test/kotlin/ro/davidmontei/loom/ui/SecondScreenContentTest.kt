package ro.davidmontei.loom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.GameSave
import ro.davidmontei.loom.ui.library.InstallState
import ro.davidmontei.loom.ui.second.CompanionContent
import ro.davidmontei.loom.ui.second.SecondScreenContent

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

    private fun show(shown: CompanionContent) {
        content = shown
        rule.setContent { LoomTestTheme { SecondScreenContent(content) } }
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
}
