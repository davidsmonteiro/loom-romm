package ro.davidmontei.loom.ui

import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ro.davidmontei.loom.ui.layout.LoomLayout
import ro.davidmontei.loom.ui.layout.WidthClass
import ro.davidmontei.loom.ui.layout.loomLayout
import ro.davidmontei.loom.ui.layout.rememberLoomLayout

/** The layout each screen Loom runs on gets, from its size in dp as the devices report it. */
@RunWith(AndroidJUnit4::class)
class LoomLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theThorsTopScreenIsMedium() {
        assertThat(loomLayout(832.5f, 468.3f)).isEqualTo(LoomLayout(WidthClass.MEDIUM, tallEnough = false))
    }

    @Test fun theThorsBottomScreenIsCompact() {
        assertThat(loomLayout(537.7f, 468.3f)).isEqualTo(LoomLayout(WidthClass.COMPACT, tallEnough = false)) // Loom opened there
        assertThat(loomLayout(538f, 444f)).isEqualTo(LoomLayout(WidthClass.COMPACT, tallEnough = false)) // the second screen beside it
    }

    @Test fun aPhoneInPortraitIsCompact() {
        assertThat(loomLayout(411f, 891f)).isEqualTo(LoomLayout(WidthClass.COMPACT, tallEnough = true))
    }

    @Test fun aPhoneInLandscapeIsExpanded() {
        assertThat(loomLayout(891f, 411f)).isEqualTo(LoomLayout(WidthClass.EXPANDED, tallEnough = false))
    }

    @Test fun aTabletIsExpanded() {
        assertThat(loomLayout(1280f, 800f)).isEqualTo(LoomLayout(WidthClass.EXPANDED, tallEnough = true))
        assertThat(loomLayout(800f, 1280f)).isEqualTo(LoomLayout(WidthClass.MEDIUM, tallEnough = true)) // in portrait
    }

    /** The window's size and layout, as a screen drawn at the test's qualifiers sees them. */
    private fun window(): Pair<DpSize, LoomLayout> {
        var seen: Pair<DpSize, LoomLayout>? = null
        rule.setContent { seen = LocalWindowInfo.current.containerDpSize to rememberLoomLayout() }
        rule.waitForIdle()
        return seen!!
    }

    /** The tests' sizes reach Compose as the window's: what the layout tests at each size rely on. */
    @Config(qualifiers = THOR_TOP_SCREEN)
    @Test fun theLayoutFollowsTheWindow() {
        val (size, layout) = window()
        assertThat(size.width.value).isWithin(1f).of(832.5f)
        assertThat(size.height.value).isWithin(1f).of(468.3f)
        assertThat(layout).isEqualTo(LoomLayout(WidthClass.MEDIUM, tallEnough = false))
    }

    @Config(qualifiers = THOR_BOTTOM_ACTIVITY)
    @Test fun theLayoutFollowsTheWindowOnTheBottomScreen() {
        val (size, layout) = window()
        assertThat(size.width.value).isWithin(1f).of(537.7f)
        assertThat(layout).isEqualTo(LoomLayout(WidthClass.COMPACT, tallEnough = false))
    }

    @Config(qualifiers = PHONE_PORTRAIT)
    @Test fun theLayoutFollowsTheWindowOnAPhone() {
        val (size, layout) = window()
        assertThat(size.width.value).isWithin(1f).of(411f)
        assertThat(layout).isEqualTo(LoomLayout(WidthClass.COMPACT, tallEnough = true))
    }

    @Config(qualifiers = TABLET_LANDSCAPE)
    @Test fun theLayoutFollowsTheWindowOnATablet() {
        assertThat(window().second).isEqualTo(LoomLayout(WidthClass.EXPANDED, tallEnough = true))
    }
}
