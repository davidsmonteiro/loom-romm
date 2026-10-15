package ro.davidmontei.loom.ui

import android.app.Dialog
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import ro.davidmontei.loom.R
import ro.davidmontei.loom.ui.second.LoomPresentation
import ro.davidmontei.loom.ui.second.PresentationWindow
import java.lang.reflect.Proxy

/** The window the second screen is drawn in: one the pad never reaches, laid out for its own screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = THOR_TOP_SCREEN)
class LoomPresentationTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The Thor's bottom screen beside Loom's, as `dumpsys display` lists it. */
    private fun bottomScreen(): Display {
        val displays = activity.getSystemService(DisplayManager::class.java)
        val id = ShadowDisplayManager.addDisplay(THOR_BOTTOM_PRESENTATION, "Screen-2")
        shadowOf(displays.getDisplay(id)).setFlags(Display.FLAG_PRESENTATION or Display.FLAG_SECURE)
        return displays.getDisplay(id)
    }

    @Test fun thePanelNeverTakesThePadsFocus() {
        val panel = LoomPresentation(activity, bottomScreen()) { Text("Chrono Trigger") }
        panel.show()
        idle()
        val flags = panel.window!!.attributes.flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).isNotEqualTo(0)
        // Still touchable: a tap passed through would reach Cocoon's bottom screen, which would take the pad.
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        panel.dismiss()
    }

    /**
     * Its content is drawn with the presentation's context, on the bottom screen, so it measures that
     * screen: 1240 px wide at 369 dpi, 538 dp. (Robolectric gives such a context the display's metrics
     * but not its configuration, from which Compose takes the window's size on the device.)
     */
    @Test fun thePanelIsLaidOutForItsOwnScreen() {
        val bottom = bottomScreen()
        var context: android.content.Context? = null
        val panel = LoomPresentation(activity, bottom) {
            context = LocalContext.current
            Text("Chrono Trigger")
        }
        panel.show()
        idle()
        assertThat(context!!.display!!.displayId).isEqualTo(bottom.displayId)
        val metrics = context!!.resources.displayMetrics
        assertThat(metrics.widthPixels / metrics.density).isWithin(1f).of(538f)
        panel.dismiss()
    }

    /** One asked for there would be another copy of the activity's: the panel is only ever handed state. */
    @Test fun aViewModelCantBeMadeOnThePanel() {
        val panel = LoomPresentation(activity, bottomScreen()) { Text("Chrono Trigger") }
        panel.show()
        idle()
        val view = panel.findViewById<ComposeView>(R.id.loom_second_screen)
        assertThat(view.findViewTreeLifecycleOwner()).isSameInstanceAs(activity)
        assertThat(view.findViewTreeViewModelStoreOwner()).isNull()
        panel.dismiss()
    }

    /** How many display listeners the app has: a presentation adds one while it shows. */
    private fun displayListeners(): Int {
        val global = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.hardware.display.DisplayManagerGlobal"), "getInstance")
        return ReflectionHelpers.getField<Collection<*>>(global, "mDisplayListeners").size
    }

    /** As the window manager does when the display went after it was picked: the window can't be added. */
    private fun refuseWindows(panel: Presentation) {
        val real = ReflectionHelpers.getField<WindowManager>(panel, "mWindowManager")
        val refusing = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
            if (method.name == "addView") throw WindowManager.InvalidDisplayException("The display went")
            method.invoke(real, *(args ?: emptyArray()))
        }
        ReflectionHelpers.setField(Dialog::class.java, panel, "mWindowManager", refusing)
    }

    /** Found by review: a show that failed left the panel watching the displays, holding the activity until Loom's process ended. */
    @Test fun aFailedShowLetsGoOfTheActivity() {
        val panel = LoomPresentation(activity, bottomScreen()) { Text("Chrono Trigger") }
        val before = displayListeners()
        refuseWindows(panel)
        assertThrows(WindowManager.InvalidDisplayException::class.java) { panel.show() }
        idle()
        assertThat(displayListeners()).isEqualTo(before)
    }

    /**
     * What the controller hears of the panel: only the system taking it away (its screen gone) is news.
     * Loom's own dismiss isn't, nor is an earlier panel's dismissal, which arrives later, once the
     * panel is dismissed and shown again in one go (the setting switched off and on).
     */
    @Test fun onlyTheSystemTakingThePanelAwayIsReported() {
        var gone = 0
        val window = PresentationWindow(activity) { Text("Chrono Trigger") }
        val bottom = bottomScreen()
        assertThat(window.show(bottom) { gone++ }).isTrue()
        idle()
        window.dismiss()
        idle()
        assertThat(gone).isEqualTo(0)
        assertThat(window.show(bottom) { gone++ }).isTrue()
        window.dismiss()
        assertThat(window.show(bottom) { gone++ }).isTrue()
        idle()
        assertThat(gone).isEqualTo(0)
        ShadowDisplayManager.removeDisplay(bottom.displayId)
        idle()
        assertThat(gone).isEqualTo(1)
    }

    /** A tap on the panel does nothing there, but it is someone using Loom: it lights the panel again. */
    @Test fun aTapOnThePanelCountsAsInput() {
        var touches = 0
        val panel = LoomPresentation(activity, bottomScreen(), onTouch = { touches++ }) { Text("Chrono Trigger") }
        panel.show()
        idle()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 0, action, 100f, 100f, 0)
            panel.dispatchTouchEvent(event)
            event.recycle()
        }
        assertThat(touches).isEqualTo(1) // once per tap
        panel.dismiss()
    }
}
