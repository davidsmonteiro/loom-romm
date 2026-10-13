package ro.davidmontei.loom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ro.davidmontei.loom.data.catalog.CatalogSyncStatus
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.InstallState
import ro.davidmontei.loom.ui.library.LibraryActions
import ro.davidmontei.loom.ui.library.LibraryContent
import ro.davidmontei.loom.ui.library.LibraryFilter
import ro.davidmontei.loom.ui.library.LibraryHeader
import ro.davidmontei.loom.ui.library.LibraryUiState

@OptIn(ExperimentalTestApi::class)
/**
 * Drives the library with a controller only: D-pad, A (DPAD_CENTER after the activity's remap),
 * X, Y, L1/R1, Start, Select. (Robolectric's native-graphics mode never grants window focus, so
 * screenshots live in [ScreenshotTest].)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = THOR_TOP_SCREEN)
class LibraryScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private val actions = object : LibraryActions {
        override fun selectPlatform(id: Int) { calls += "select:$id" }
        override fun cyclePlatform(step: Int) { calls += "cycle:$step" }
        override fun setFilter(filter: LibraryFilter) { calls += "filter:$filter" }
        override fun setQuery(query: String) { calls += "query:$query" }
        override fun toggleGrid() { calls += "grid" }
        override fun sync() { calls += "sync" }
        override fun download(game: GameItem) { calls += "download:${game.id}" }
        override fun offload(game: GameItem) { calls += "offload:${game.id}" }
        override fun retry(game: GameItem) { calls += "retry:${game.id}" }
        override fun cancel(game: GameItem) { calls += "cancel:${game.id}" }
        override fun openQueue() { calls += "queue" }
        override fun openSettings() { calls += "settings" }
    }

    private fun platform(id: Int, name: String, roms: Int, installed: Int, bytes: Long) =
        PlatformSummary(PlatformEntity(id, name.lowercase(), name.lowercase(), name, name, roms, "t"), roms, installed, bytes)

    private fun game(id: Int, title: String, state: InstallState, progress: Float? = null, detail: String? = null) = GameItem(
        id = id, title = title, sizeBytes = 1_500_000L * id, coverPath = null, regions = listOf("USA"), state = state,
        stateDetail = detail, progress = progress, jobId = if (state == InstallState.NOT_INSTALLED || state == InstallState.INSTALLED) null else id.toLong(),
        adopted = false, removedOnServer = false, installPath = if (state == InstallState.INSTALLED) "snes/$title.sfc" else null,
    )

    private val platforms = listOf(
        platform(1, "Super Nintendo", 412, 23, 61_000_000), platform(2, "PlayStation", 230, 4, 2_900_000_000),
        platform(3, "Game Boy Advance", 640, 0, 0), platform(4, "Nintendo DS", 310, 2, 120_000_000),
    )
    private val games = listOf(
        game(1, "Chrono Trigger", InstallState.INSTALLED),
        game(2, "EarthBound", InstallState.NOT_INSTALLED),
        game(3, "Super Metroid", InstallState.DOWNLOADING, progress = 0.42f),
        game(4, "The Legend of Zelda: A Link to the Past", InstallState.QUEUED, detail = "Queued"),
        game(5, "Final Fantasy VI", InstallState.FAILED, detail = "SHA1 doesn't match RomM's"),
        game(6, "Secret of Mana", InstallState.NOT_INSTALLED),
        game(7, "Super Mario World", InstallState.INSTALLED),
        game(8, "Donkey Kong Country", InstallState.NOT_INSTALLED),
    )

    internal var state by mutableStateOf(
        LibraryUiState(platforms, platforms[0], games, LibraryFilter.ALL, "", true,
            LibraryHeader(CatalogSyncStatus(false), System.currentTimeMillis() - 5 * 60_000, 2), coverBase = null),
    )

    private fun show() {
        rule.setContent { LoomTestTheme { LibraryContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
    }

    /** Recompose with the new state first, then let delayed focus requests run. */
    private fun update(change: (LibraryUiState) -> LibraryUiState) {
        state = change(state)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
    }

    // Key is a value class, so no vararg: press(a, b) is spelled press(listOf(a, b)).
    private fun press(key: Key) = press(listOf(key))
    private fun press(keys: List<Key>) = keys.forEach { key ->
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    /** What the activity does with B (BACK) once nothing in Compose has used it. */
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** Found on the Thor: B on the library, the root screen, closed Loom. */
    @Test fun bOnTheLibraryNeverClosesLoom() {
        show()
        calls.clear() // the search box reports its starting value when it appears
        back()
        assertThat(rule.activity.isFinishing).isFalse()
        assertThat(calls).isEmpty()
    }

    @Test fun bClearsASearchFirst() {
        state = state.copy(query = "Chrono")
        show()
        calls.clear()
        back()
        assertThat(calls).containsExactly("query:")
        assertThat(rule.activity.isFinishing).isFalse()
    }

    @Test fun rendersGridAndFocusesTheFirstGame() {
        show()
        rule.onNodeWithTag("game-1").assertIsFocused()
    }

    @Test fun dpadMovesThroughTheGridAndIntoTheRail() {
        show()
        press(Key.DirectionRight)
        rule.onNodeWithTag("game-2").assertIsFocused()
        press(listOf(Key.DirectionLeft, Key.DirectionLeft))
        // Left of the first column is the platform rail.
        rule.onNodeWithTag("platform-1").assertIsFocused()
        press(listOf(Key.DirectionDown, Key.DirectionCenter))
        assertThat(calls).contains("select:2")
    }

    @Test fun padButtonsDriveTheScreen() {
        show()
        press(Key.DirectionRight) // EarthBound, not installed
        press(Key.ButtonX)
        assertThat(calls).contains("download:2")
        press(listOf(Key.ButtonR1, Key.ButtonL1))
        assertThat(calls).containsAtLeast("cycle:1", "cycle:-1").inOrder()
        press(Key.ButtonStart)
        assertThat(calls).contains("queue")
        press(Key.ButtonSelect)
        assertThat(calls).contains("settings")
    }

    @Test fun xOnAnInstalledGameAsksBeforeOffloading() {
        show()
        press(Key.ButtonX) // Chrono Trigger is installed
        rule.onNodeWithText("Remove Chrono Trigger from this device?").assertExists()
        rule.onNodeWithText("Keep it").assertIsFocused() // the safe choice has focus
        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        assertThat(calls).contains("offload:1")
    }

    /** Found by audit: "Remove from device" in the details opened the question with nothing focused. */
    @Test fun removingFromTheDetailsAsksWithFocusOnKeepIt() {
        show()
        press(listOf(Key.DirectionCenter, Key.DirectionRight, Key.DirectionCenter)) // Chrono Trigger: details, Remove
        rule.onNodeWithText("Remove Chrono Trigger from this device?").assertExists()
        rule.onNodeWithText("Keep it").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls.filter { it.startsWith("offload") }).isEmpty()
    }

    /** Found on the Thor: Y only put the ring on the search box, and A was needed to type. */
    @Test fun yStartsTypingASearch() {
        show()
        press(Key.ButtonY)
        rule.onNode(hasSetTextAction()).assertIsFocused() // the field itself, so the keyboard is up
        assertThat(calls).doesNotContain("download:1")
    }

    /** Found on the Thor: Enter in the search box left focus there instead of on the results. */
    @Test fun enterInSearchGoesToTheFirstResult() {
        show()
        press(Key.ButtonY)
        rule.onNode(hasSetTextAction()).performTextInput("Chrono")
        press(Key.Enter)
        rule.onNodeWithTag("game-1").assertIsFocused()
    }

    /**
     * Found on the Thor: results that vanished and came back while typing pulled focus to the
     * first game, so the rest of the typing went to it (a space opened it, Enter downloaded it).
     */
    @Test fun typingASearchKeepsFocusInTheField() {
        show()
        press(Key.ButtonY)
        update { it.copy(query = "Alley M", games = emptyList()) }
        update { it.copy(query = "Alley", games = games.take(2)) }
        rule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test fun gamesThatLoadLateStillGetFocus() {
        state = state.copy(games = emptyList())
        show()
        update { it.copy(games = games) }
        rule.onNodeWithTag("game-1").assertIsFocused()
    }

    /** Found on the Thor: with the search box focused, X downloaded the game focused before it. */
    @Test fun xOnlyActsOnTheFocusedGame() {
        show()
        press(Key.DirectionUp) // from Chrono Trigger to the search row
        press(Key.ButtonX)
        rule.onNodeWithText("Remove Chrono Trigger from this device?").assertDoesNotExist()
        assertThat(calls.filter { it.startsWith("download") || it.startsWith("offload") }).isEmpty()
    }

    /** Found on the Thor: closing a dialog left focus on the platform rail, not the game. */
    @Test fun closingADialogGivesFocusBackToItsGame() {
        show()
        press(listOf(Key.DirectionRight, Key.DirectionCenter, Key.DirectionCenter)) // EarthBound: details, Download
        rule.onNodeWithTag("game-2").assertIsFocused()
        press(listOf(Key.DirectionLeft, Key.ButtonX)) // Chrono Trigger: asks before offloading
        press(Key.DirectionCenter) // "Keep it"
        rule.onNodeWithTag("game-1").assertIsFocused()
    }

    @Test fun aOpensDetailsWithTheRightPrimaryAction() {
        show()
        press(listOf(Key.DirectionRight, Key.DirectionCenter)) // EarthBound
        rule.onNodeWithText("Download").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("download:2")
    }

    @Test fun listModeRenders() {
        state = state.copy(gridMode = false)
        show()
        rule.onNodeWithTag("game-1").assertIsFocused()
    }

    @Test fun emptyLibraryOffersSync() {
        state = state.copy(platforms = emptyList(), selected = null, games = emptyList())
        show()
        rule.onNodeWithText("Sync now").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("sync")
    }
}
