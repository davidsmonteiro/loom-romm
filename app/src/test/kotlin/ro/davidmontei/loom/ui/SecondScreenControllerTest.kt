package ro.davidmontei.loom.ui

import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import ro.davidmontei.loom.ui.second.CompanionHolder
import ro.davidmontei.loom.ui.second.SecondScreenController
import ro.davidmontei.loom.ui.second.SecondScreenWindow

/**
 * When the panel shows on the second screen: only while Loom is in front, on a screen that's on,
 * never the one Loom is on. The window is a stand-in: these are the controller's decisions.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = THOR_TOP_SCREEN)
class SecondScreenControllerTest {
    private class FakeWindow : SecondScreenWindow {
        var shownOn: Int? = null
        var refuses = false
        var gone: (() -> Unit)? = null
        override fun show(display: Display, onGone: () -> Unit): Boolean {
            if (refuses) return false
            shownOn = display.displayId
            gone = onGone
            return true
        }
        override fun dismiss() { shownOn = null; gone = null }

        /** As a presentation does when its display goes: it cancels itself. */
        fun systemTakesItAway() {
            shownOn = null
            gone?.invoke()
        }
    }

    private val window = FakeWindow()
    private val holder = CompanionHolder()
    private val enabled = MutableStateFlow(true)
    private var own = Display.DEFAULT_DISPLAY
    private val displays get() = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(DisplayManager::class.java)
    private lateinit var controller: SecondScreenController

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The Thor's bottom screen, as `dumpsys display` lists it: built in, secure, for presentations. */
    private fun addBottomScreen(): Int {
        val id = ShadowDisplayManager.addDisplay(THOR_BOTTOM_PRESENTATION, "Screen-2")
        shadowOf(displays.getDisplay(id)).setFlags(Display.FLAG_PRESENTATION or Display.FLAG_SECURE or Display.FLAG_SUPPORTS_PROTECTED_BUFFERS)
        idle()
        return id
    }

    /** Loom opened and in front: created, started, resumed, then told it's the top resumed activity. */
    private fun open(top: Boolean = true): ActivityController<ComponentActivity> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create()
        // Robolectric's displays have no type, which the system's list of presentation displays goes by: all of them, in order.
        controller = SecondScreenController(activity.get(), holder, enabled, window, ownDisplay = { own },
            presentationDisplays = { displays.displays.toList() })
        activity.get().lifecycle.addObserver(controller)
        activity.start().resume()
        if (top) controller.onTopResumedChanged(true)
        idle()
        return activity
    }

    private fun assertShownOn(id: Int?) {
        assertThat(window.shownOn).isEqualTo(id)
        assertThat(holder.presenting.value).isEqualTo(id != null)
    }

    @Test fun showsOnceStartedAndOnTop() {
        val bottom = addBottomScreen()
        open(top = false)
        assertShownOn(null)
        controller.onTopResumedChanged(true)
        assertShownOn(bottom)
    }

    @Test fun hidesWhenLoomStops() {
        val bottom = addBottomScreen()
        val activity = open()
        controller.onTopResumedChanged(false)
        activity.pause().stop()
        idle()
        assertShownOn(null)
        activity.restart().start().resume()
        controller.onTopResumedChanged(true)
        idle()
        assertShownOn(bottom)
    }

    /** With a screen each, a game Cocoon starts on the other screen is resumed beside Loom and takes the top. */
    @Test fun hidesWhenAnotherActivityTakesTheTop() {
        val bottom = addBottomScreen()
        open()
        controller.onTopResumedChanged(false)
        assertShownOn(null)
        controller.onTopResumedChanged(true)
        assertShownOn(bottom)
    }

    @Test fun hidesWhileTheBottomScreenIsOffAndReturns() {
        val bottom = addBottomScreen()
        open()
        shadowOf(displays.getDisplay(bottom)).setState(Display.STATE_OFF)
        idle()
        assertShownOn(null)
        shadowOf(displays.getDisplay(bottom)).setState(Display.STATE_ON)
        idle()
        assertShownOn(bottom)
    }

    @Test fun hidesWhenTheDisplayIsRemoved() {
        val bottom = addBottomScreen()
        open()
        ShadowDisplayManager.removeDisplay(bottom)
        idle()
        assertShownOn(null)
        val again = addBottomScreen()
        assertShownOn(again)
    }

    /** A presentation whose display goes cancels itself, maybe before Loom hears the display went. */
    @Test fun aPanelTheSystemTookAwayIsNotThoughtShown() {
        addBottomScreen()
        open()
        window.systemTakesItAway()
        assertShownOn(null)
    }

    @Test fun usesADisplayAddedLater() {
        open()
        assertShownOn(null)
        val bottom = addBottomScreen()
        assertShownOn(bottom)
    }

    @Test fun turningTheSettingOffHidesIt() {
        val bottom = addBottomScreen()
        open()
        enabled.value = false
        idle()
        assertShownOn(null)
        enabled.value = true
        idle()
        assertShownOn(bottom)
    }

    @Test fun aFailedShowLeavesNothingShown() {
        addBottomScreen()
        window.refuses = true
        open()
        assertShownOn(null)
    }

    /** Cocoon or AYN moved Loom to the bottom screen: there is no other screen for the panel. */
    @Test fun movingToTheBottomScreenHidesIt() {
        val bottom = addBottomScreen()
        open()
        own = bottom
        controller.onConfigurationChanged()
        assertShownOn(null)
        own = Display.DEFAULT_DISPLAY
        controller.onConfigurationChanged()
        assertShownOn(bottom)
    }

    /** A display that isn't secure is an app's (a screen recording): never the second screen. */
    @Test fun aScreenCaptureIsIgnored() {
        open()
        val capture = ShadowDisplayManager.addDisplay(THOR_TOP_SCREEN, "WebRTC_ScreenCapture")
        shadowOf(displays.getDisplay(capture)).setFlags(Display.FLAG_PRESENTATION)
        idle()
        assertShownOn(null)
    }
}
