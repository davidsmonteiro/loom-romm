package ro.davidmontei.loom

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.util.ReflectionHelpers
import ro.davidmontei.loom.ui.THOR_TOP_SCREEN

/**
 * What counts as someone using Loom, for the second screen to stay lit or light up again: a button
 * pressed or a screen touched. Robolectric builds [MainActivity] with the app's real Hilt graph, so
 * this is one test: each would build that graph again.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = THOR_TOP_SCREEN)
class MainActivityInputTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun joystick(axis: Int, value: Float): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(axis, value) })
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_JOYSTICK, 0)
    }

    /**
     * Found by review: Android tells an activity of every joystick move as user interaction, so a
     * stick resting a little off centre, or a trigger, could keep the bottom screen lit for good.
     * And after a tap, X only brings focus back (see TouchModePress) and isn't delivered: it was
     * missed, and the dark bottom screen needed another press.
     */
    @Test fun onlyButtonsAndTouchesAreInput() {
        ShadowEnvironment.addExternalDir(tmp.newFolder("sdcard").path) // Android's all-files check needs a volume
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val inputs = ViewModelProvider(activity)[AppViewModel::class.java].companion.inputs
        fun counts(what: String, input: Boolean, send: () -> Unit) {
            val before = inputs.value
            send()
            assertWithMessage(what).that(inputs.value > before).isEqualTo(input)
        }
        val now = SystemClock.uptimeMillis()
        try {
            counts("a stick", input = false) {
                activity.dispatchGenericMotionEvent(joystick(MotionEvent.AXIS_X, 0.02f))
                activity.dispatchGenericMotionEvent(joystick(MotionEvent.AXIS_RZ, -0.03f))
            }
            counts("a trigger", input = false) { activity.dispatchGenericMotionEvent(joystick(MotionEvent.AXIS_GAS, 0.01f)) }
            // AYN's focus-mode Shift isn't a press of the user's: it is dropped before anything sees it.
            counts("AYN's Shift", input = false) {
                activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
            }
            counts("A", input = true) { activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }
            // R3 is used up even with no second screen to hide, as here: nothing else ever sees it.
            counts("R3", input = true) {
                assertWithMessage("R3 used").that(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBR))).isTrue()
            }
            counts("a touch", input = true) { activity.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0)) }
            // In touch mode, as a tap leaves it (Robolectric's taps don't go through the window).
            val window = ReflectionHelpers.callInstanceMethod<Any>(activity.window.decorView, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Boolean>(window, "ensureTouchModeLocally",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
            assertWithMessage("touch mode").that(activity.window.decorView.isInTouchMode).isTrue()
            counts("X after a tap", input = true) { activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X)) }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
