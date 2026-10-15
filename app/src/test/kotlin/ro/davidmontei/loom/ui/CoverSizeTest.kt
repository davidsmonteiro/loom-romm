package ro.davidmontei.loom.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.size.Size
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ro.davidmontei.loom.data.catalog.CoverRef
import ro.davidmontei.loom.data.catalog.CoverStore
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.LibraryContent
import ro.davidmontei.loom.ui.library.LibraryUiState
import ro.davidmontei.loom.ui.second.CompanionContent
import ro.davidmontei.loom.ui.second.HERO_AFTER
import ro.davidmontei.loom.ui.second.SecondScreenContent
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Found on the Thor: covers were kept 256 px wide while a grid card draws them at 285 px, 297
 * focused, so they were enlarged and soft. They are kept at the size a focused card draws them, no
 * larger, measured here at the Thor's top screen exactly: 1920×1080 at 369 dpi. The bottom screen's
 * panel draws its cover larger, at the hero size, measured there.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = THOR_TOP_SCREEN)
class CoverSizeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** What the library asks Coil for, and at what size. */
    private val requested = CopyOnWriteArrayList<Pair<Any, Size>>()

    @Before fun recordCoverRequests() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components {
                add(Interceptor { chain ->
                    requested += chain.request.data to chain.size
                    ErrorResult(null, chain.request, IOException("no covers in this test"))
                })
            }.build(),
        )
    }

    @After fun forgetTheImageLoader() = SingletonImageLoader.reset()

    private fun show(state: LibraryUiState) {
        val withCovers = state.copy(games = state.games.map { it.copy(coverPath = "/assets/romm/resources/roms/1/${it.id}/cover/small.png") })
        rule.setContent { LoomTestTheme { LibraryContent(withCovers, NoopLibraryActions) } }
        rule.mainClock.advanceTimeBy(1_000) // the focused card's growth settles
        rule.waitForIdle()
    }

    private fun drawn(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun aFocusedCardDrawsItsCoverAtTheStoredSize() {
        show(LibraryScreenTest().state)
        rule.onNodeWithTag("game-1").assertIsFocused()
        val focused = drawn("cover-1")
        assertThat(focused.width).isWithin(1f).of(CoverStore.WIDTH.toFloat())
        assertThat(focused.height).isWithin(1f).of(CoverStore.HEIGHT.toFloat())
        val other = drawn("cover-2")
        assertThat(other.width).isLessThan(CoverStore.WIDTH.toFloat()) // 285 px: the stored cover is shrunk, never enlarged
    }

    @Test fun theListDrawsCoversSmallerStill() {
        show(LibraryScreenTest().state.copy(gridMode = false))
        val cover = drawn("cover-1")
        assertThat(cover.width).isLessThan(CoverStore.WIDTH.toFloat())
        assertThat(cover.height).isLessThan(CoverStore.HEIGHT.toFloat())
    }

    /** Decoded at the card's own size, a cover would be enlarged again when its card grows with the focus. */
    @Test fun coversAreDecodedAtTheStoredSize() {
        show(LibraryScreenTest().state)
        val covers = requested.filter { it.first is CoverRef }
        assertThat(covers).isNotEmpty()
        assertThat(covers.map { it.second }.toSet()).containsExactly(Size(CoverStore.WIDTH, CoverStore.HEIGHT))
    }

    // --- the bottom screen's panel: its cover, made from RomM's large art for the game the focus rests on ---

    /** A game with a cover and large art, as the panel shows it. */
    private fun withArt(game: GameItem) = game.copy(
        coverPath = "/assets/romm/resources/roms/1/${game.id}/cover/small.png", coverLarge = "/assets/romm/resources/roms/1/${game.id}/cover/big.png",
    )

    private fun heroes() = requested.mapNotNull { (data, size) -> (data as? CoverRef)?.takeIf { it.hero }?.let { it to size } }

    @Config(qualifiers = THOR_BOTTOM_PRESENTATION)
    @Test fun theBottomScreenDrawsItsCoverAtTheHeroSize() {
        val game = withArt(LibraryScreenTest().state.games[0])
        rule.setContent { LoomTestTheme { SecondScreenContent(CompanionContent.Game(game, "Super Nintendo", null, null)) } }
        rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle()
        val drawn = drawn("panel-cover")
        assertThat(drawn.width).isWithin(1f).of(CoverStore.HERO_WIDTH.toFloat())
        assertThat(drawn.height).isWithin(1f).of(CoverStore.HERO_HEIGHT.toFloat())
        assertThat(heroes().map { it.second }.toSet()).containsExactly(Size(CoverStore.HERO_WIDTH, CoverStore.HERO_HEIGHT))
    }

    /**
     * The panel draws the thumbnail at once, and asks for the hero cover only once the focus has rested on
     * the game for half a second: running through the grid fetches none for the games passed over.
     */
    @Config(qualifiers = THOR_BOTTOM_PRESENTATION)
    @Test fun aHeroIsAskedForOnlyOnceFocusRests() {
        val (first, second) = LibraryScreenTest().state.games.take(2).map(::withArt)
        var content by mutableStateOf(CompanionContent.Game(first, "Super Nintendo", null, null))
        rule.mainClock.autoAdvance = false
        rule.setContent { LoomTestTheme { SecondScreenContent(content) } }
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        assertThat(requested.map { (it.first as CoverRef).path }).contains(first.coverPath) // the thumbnail, at once
        assertThat(heroes()).isEmpty()

        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        content = CompanionContent.Game(second, "Super Nintendo", null, null) // on to the next game
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        assertThat(heroes()).isEmpty() // not the first's, though half a second has gone since it showed

        rule.mainClock.advanceTimeBy(HERO_AFTER.inWholeMilliseconds); rule.waitForIdle()
        assertThat(heroes().map { it.first.path }).containsExactly(second.coverPath)
        assertThat(heroes().single().first.large).isEqualTo(second.coverLarge)
    }
}
