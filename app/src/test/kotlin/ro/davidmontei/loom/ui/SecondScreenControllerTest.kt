package ro.davidmontei.loom.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.provider.Settings
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
import ro.davidmontei.loom.ui.second.AYN_FOCUS_LOCK
import ro.davidmontei.loom.ui.second.CompanionHolder
import ro.davidmontei.loom.ui.second.FOCUS_AUTO_LOCK
import ro.davidmontei.loom.ui.second.FOCUS_BOTTOM
import ro.davidmontei.loom.ui.second.FOCUS_TOP
import ro.davidmontei.loom.ui.second.PadNotice
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
    private val displays get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(DisplayManager::class.java)
    private val resolver get() = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val focusModeUri get() = Settings.System.getUriFor(AYN_FOCUS_LOCK)
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

    /**
     * Stopping alone hides it. Android says Loom lost the top before it stops, so on the device the
     * panel has gone by then; this is what still hides it should that word not come.
     */
    @Test fun hidesWhenLoomStops() {
        val bottom = addBottomScreen()
        val activity = open()
        activity.pause().stop()
        idle()
        assertShownOn(null)
        activity.restart().start().resume()
        controller.onTopResumedChanged(true)
        idle()
        assertShownOn(bottom)
    }

    /** Told it's on top while stopped (out of order, as a vendor's screens might): it stays hidden until Loom starts. */
    @Test fun aStoppedLoomOnTopShowsNothing() {
        val bottom = addBottomScreen()
        val activity = open(top = false)
        activity.pause().stop()
        idle()
        controller.onTopResumedChanged(true)
        idle()
        assertShownOn(null)
        activity.restart().start().resume()
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

    // --- R3: the panel goes, so an app open on the bottom screen can be used, and comes back ---

    @Test fun r3HidesThePanelAndBringsItBack() {
        val bottom = addBottomScreen()
        open()
        controller.togglePanel()
        assertShownOn(null)
        controller.togglePanel()
        assertShownOn(bottom)
    }

    /**
     * The owner hides the panel to use the app under it, which takes the top, then turns back to Loom:
     * the panel stays hidden. Nor does anything else that shows it again (a change of screen, the
     * bottom screen off and on) bring it back.
     */
    @Test fun hiddenWithR3ItStaysHiddenWhenLoomIsBackOnTop() {
        val bottom = addBottomScreen()
        open()
        controller.togglePanel()
        controller.onTopResumedChanged(false) // the app on the bottom screen, touched
        controller.onTopResumedChanged(true) // and Loom again
        controller.onConfigurationChanged()
        shadowOf(displays.getDisplay(bottom)).setState(Display.STATE_OFF)
        idle()
        shadowOf(displays.getDisplay(bottom)).setState(Display.STATE_ON)
        idle()
        assertShownOn(null)
        controller.togglePanel()
        assertShownOn(bottom)
    }

    /** Left (Home, a game launched) and opened again, Loom shows the panel as it did before R3. */
    @Test fun reopeningLoomBringsThePanelBack() {
        val bottom = addBottomScreen()
        val activity = open()
        controller.togglePanel()
        controller.onTopResumedChanged(false)
        activity.pause().stop()
        idle()
        activity.restart().start().resume()
        controller.onTopResumedChanged(true)
        idle()
        assertShownOn(bottom)
    }

    /** With no panel to hide, R3 hides nothing for later: the panel shows once there's a screen for it, or the switch is on. */
    @Test fun r3DoesNothingWithoutASecondScreen() {
        open()
        controller.togglePanel()
        val bottom = addBottomScreen()
        assertShownOn(bottom)
        enabled.value = false
        idle()
        controller.togglePanel()
        enabled.value = true
        idle()
        assertShownOn(bottom)
    }

    // --- AYN's Focus Mode: which screen the pad's keys go to ---

    /** As AYN's Dual screen assistant does: the setting changes, and whoever watches it hears. */
    private fun setFocusMode(mode: Int) {
        Settings.System.putInt(resolver, AYN_FOCUS_LOCK, mode)
        resolver.notifyChange(focusModeUri, null)
        idle()
    }

    /** The pad works the bottom screen (Cocoon, a game): that must be in view, and Loom says why the pad does nothing. */
    @Test fun focusModeBottomHidesThePanelAndSaysSo() {
        val bottom = addBottomScreen()
        open()
        assertShownOn(bottom)
        setFocusMode(FOCUS_BOTTOM)
        assertShownOn(null)
        assertThat(holder.padNotice.value).isEqualTo(PadNotice.LOCKED_TO_BOTTOM)
    }

    @Test fun autoLockBringsThePanelBack() {
        val bottom = addBottomScreen()
        open()
        setFocusMode(FOCUS_BOTTOM)
        setFocusMode(FOCUS_AUTO_LOCK)
        assertShownOn(bottom)
        assertThat(holder.padNotice.value).isNull()
    }

    /** "Top screen" with Loom on top is the pad where it should be: the panel stays. */
    @Test fun focusModeTopKeepsThePanel() {
        val bottom = addBottomScreen()
        open()
        setFocusMode(FOCUS_TOP)
        assertShownOn(bottom)
        assertThat(holder.padNotice.value).isNull()
    }

    /** Cocoon opened Loom on the bottom screen while Focus Mode keeps the pad on top. */
    @Test fun focusModeTopWithLoomOnTheBottomSaysSo() {
        own = addBottomScreen()
        open()
        setFocusMode(FOCUS_TOP)
        assertShownOn(null)
        assertThat(holder.padNotice.value).isEqualTo(PadNotice.LOCKED_TO_TOP)
        own = Display.DEFAULT_DISPLAY // moved to the top screen, where the pad is
        controller.onConfigurationChanged()
        assertThat(holder.padNotice.value).isNull()
    }

    /** Focus Mode is set from AYN's panel, over whatever is open: one changed while Loom was away is read on its return. */
    @Test fun aFocusModeChangedWhileLoomWasAwayIsSeenOnItsReturn() {
        addBottomScreen()
        val activity = open()
        assertThat(shadowOf(resolver).getContentObservers(focusModeUri)).hasSize(1)
        controller.onTopResumedChanged(false)
        activity.pause().stop()
        idle()
        assertThat(shadowOf(resolver).getContentObservers(focusModeUri)).isEmpty() // not watched while away
        Settings.System.putInt(resolver, AYN_FOCUS_LOCK, FOCUS_BOTTOM)
        activity.restart().start().resume()
        controller.onTopResumedChanged(true)
        idle()
        assertShownOn(null)
        assertThat(holder.padNotice.value).isEqualTo(PadNotice.LOCKED_TO_BOTTOM)
    }
}
