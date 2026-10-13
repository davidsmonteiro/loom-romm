package ro.davidmontei.loom.ui

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.ui.input.TouchModePress

/** Found on the Thor: after launch, A on "Get started" only showed the focus ring; a second A was needed. */
@RunWith(AndroidJUnit4::class)
class TouchModePressTest {
    private var touchMode = true
    private var left = 0
    private val pending = mutableListOf<Runnable>()
    private val delivered = mutableListOf<Pair<Int, Int>>() // action to key code
    private val press = TouchModePress(
        inTouchMode = { touchMode },
        leaveTouchMode = { left++; touchMode = false },
        later = { pending += it },
        deliver = { delivered += it.action to it.keyCode; true },
    )

    private fun down(code: Int) = press.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, code))
    private fun up(code: Int) = press.dispatch(KeyEvent(KeyEvent.ACTION_UP, code))
    private fun focusSettles() { pending.toList().forEach(Runnable::run); pending.clear() }

    @Test fun firstConfirmInTouchModeActsOnceFocusHasLanded() {
        assertThat(down(KeyEvent.KEYCODE_DPAD_CENTER)).isTrue()
        assertThat(left).isEqualTo(1)
        assertThat(delivered).isEmpty() // held until the screen has focused its primary element
        up(KeyEvent.KEYCODE_DPAD_CENTER)
        focusSettles()
        assertThat(delivered).containsExactly(
            KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.ACTION_UP to KeyEvent.KEYCODE_DPAD_CENTER,
        ).inOrder() // exactly one press: the real key-up was dropped
    }

    @Test fun aKeyUpAfterTheReplayIsDroppedToo() {
        down(KeyEvent.KEYCODE_DPAD_CENTER)
        focusSettles() // the button is still held
        up(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(delivered.count { it.first == KeyEvent.ACTION_UP }).isEqualTo(1)
    }

    @Test fun outsideTouchModePressesGoStraightThrough() {
        touchMode = false
        down(KeyEvent.KEYCODE_DPAD_CENTER); up(KeyEvent.KEYCODE_DPAD_CENTER)
        down(KeyEvent.KEYCODE_DPAD_CENTER); up(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(delivered).hasSize(4)
        assertThat(left).isEqualTo(0)
    }

    @Test fun screenButtonsAreHeldBackLikeConfirm() {
        down(KeyEvent.KEYCODE_BUTTON_R1)
        assertThat(delivered).isEmpty()
        focusSettles()
        assertThat(delivered.first()).isEqualTo(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_BUTTON_R1)
    }

    @Test fun backAndTheDpadAreLeftToAndroid() {
        down(KeyEvent.KEYCODE_BACK); up(KeyEvent.KEYCODE_BACK)
        down(KeyEvent.KEYCODE_DPAD_DOWN)
        assertThat(delivered).hasSize(3)
        assertThat(left).isEqualTo(0)
        assertThat(pending).isEmpty()
    }
}
