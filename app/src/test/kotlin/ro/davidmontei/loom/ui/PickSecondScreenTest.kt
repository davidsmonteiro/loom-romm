package ro.davidmontei.loom.ui

import android.view.Display
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.ui.second.DisplayCandidate
import ro.davidmontei.loom.ui.second.pickSecondScreen

/** Which display the panel goes on, from the displays a presentation can go on, in the system's order. */
class PickSecondScreenTest {
    private val screen = Display.FLAG_PRESENTATION or Display.FLAG_SECURE or Display.FLAG_SUPPORTS_PROTECTED_BUFFERS

    /** The Thor's bottom screen, display 4: built in, secure, for presentations (`dumpsys display`). */
    private val thorBottom = DisplayCandidate(4, screen, Display.STATE_ON, valid = true)

    @Test fun theThorsBottomScreenIsPicked() {
        assertThat(pickSecondScreen(Display.DEFAULT_DISPLAY, listOf(thorBottom))).isEqualTo(4)
    }

    /** The top screen isn't one a presentation can go on, so it isn't listed: Loom there is on its own. */
    @Test fun loomOnTheBottomScreenHasNoSecondScreen() {
        assertThat(pickSecondScreen(4, listOf(thorBottom))).isNull()
    }

    /** Discord sharing the screen makes a presentation display, but an app's can't be secure. */
    @Test fun aScreenCaptureDisplayIsNeverPicked() {
        val capture = DisplayCandidate(7, Display.FLAG_PRESENTATION, Display.STATE_ON, valid = true)
        assertThat(pickSecondScreen(Display.DEFAULT_DISPLAY, listOf(capture))).isNull()
        assertThat(pickSecondScreen(Display.DEFAULT_DISPLAY, listOf(capture, thorBottom))).isEqualTo(4)
    }

    /** An emulator's own hidden display (Azahar's) is private: the system wouldn't put Loom's panel there anyway. */
    @Test fun aPrivateDisplayIsNeverPicked() {
        val hidden = DisplayCandidate(8, screen or Display.FLAG_PRIVATE, Display.STATE_ON, valid = true)
        assertThat(pickSecondScreen(Display.DEFAULT_DISPLAY, listOf(hidden))).isNull()
    }

    /** A long press on the chin button turns the bottom screen off without removing it. */
    @Test fun aScreenThatIsOffIsNotPicked() {
        assertThat(pickSecondScreen(Display.DEFAULT_DISPLAY, listOf(thorBottom.copy(state = Display.STATE_OFF)))).isNull()
        assertThat(pickSecondScreen(Display.DEFAULT_DISPLAY, listOf(thorBottom.copy(valid = false)))).isNull()
        assertThat(pickSecondScreen(Display.DEFAULT_DISPLAY, listOf(thorBottom.copy(state = Display.STATE_DOZE)))).isEqualTo(4)
    }

    /** The system lists a monitor (external) before the built-in screens: the built-in one is the second screen. */
    @Test fun theBuiltInScreenWinsOverAMonitor() {
        val monitor = DisplayCandidate(5, screen, Display.STATE_ON, valid = true)
        assertThat(pickSecondScreen(Display.DEFAULT_DISPLAY, listOf(monitor, thorBottom))).isEqualTo(4)
        assertThat(pickSecondScreen(Display.DEFAULT_DISPLAY, listOf(monitor))).isEqualTo(5) // else the monitor
    }

    @Test fun nothingWhenSwitchedOff() {
        assertThat(pickSecondScreen(Display.DEFAULT_DISPLAY, listOf(thorBottom), enabled = false)).isNull()
    }
}
