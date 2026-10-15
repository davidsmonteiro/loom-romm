package ro.davidmontei.loom.ui

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.ui.input.GamepadKeys
import ro.davidmontei.loom.ui.input.PadAction

@RunWith(AndroidJUnit4::class)
class GamepadKeysTest {
    @Test fun confirmAndBackFollowXboxLayoutByDefault() {
        assertThat(GamepadKeys.remap(KeyEvent.KEYCODE_BUTTON_A, swapConfirmBack = false)).isEqualTo(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(GamepadKeys.remap(KeyEvent.KEYCODE_BUTTON_B, swapConfirmBack = false)).isEqualTo(KeyEvent.KEYCODE_BACK)
    }

    @Test fun swapGivesNintendoLayout() {
        assertThat(GamepadKeys.remap(KeyEvent.KEYCODE_BUTTON_A, swapConfirmBack = true)).isEqualTo(KeyEvent.KEYCODE_BACK)
        assertThat(GamepadKeys.remap(KeyEvent.KEYCODE_BUTTON_B, swapConfirmBack = true)).isEqualTo(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test fun otherKeysPassThrough() {
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER)) {
            assertThat(GamepadKeys.remap(code, swapConfirmBack = false)).isEqualTo(code)
            assertThat(GamepadKeys.remap(code, swapConfirmBack = true)).isEqualTo(code)
        }
    }

    @Test fun screenActionsFromTheRemainingButtons() {
        assertThat(GamepadKeys.action(KeyEvent.KEYCODE_BUTTON_X)).isEqualTo(PadAction.X)
        assertThat(GamepadKeys.action(KeyEvent.KEYCODE_BUTTON_Y)).isEqualTo(PadAction.Y)
        assertThat(GamepadKeys.action(KeyEvent.KEYCODE_BUTTON_L1)).isEqualTo(PadAction.L1)
        assertThat(GamepadKeys.action(KeyEvent.KEYCODE_BUTTON_R1)).isEqualTo(PadAction.R1)
        assertThat(GamepadKeys.action(KeyEvent.KEYCODE_BUTTON_START)).isEqualTo(PadAction.START)
        assertThat(GamepadKeys.action(KeyEvent.KEYCODE_BUTTON_A)).isNull()
        assertThat(GamepadKeys.action(KeyEvent.KEYCODE_DPAD_LEFT)).isNull()
    }

    private fun usedWhenNothingTakesIt(code: Int, swap: Boolean = false) =
        GamepadKeys.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, code), swap) { false }

    /** Found by audit: with A and B swapped, confirm on a row with nothing to press went back. */
    @Test fun padButtonsNeverFallBackToOtherKeys() {
        val delivered = mutableListOf<Int>()
        val used = GamepadKeys.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B), swapConfirmBack = true) {
            delivered += it.keyCode
            false
        }
        assertThat(delivered).containsExactly(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(used).isTrue() // so Android doesn't send its fallback, BACK
        assertThat(usedWhenNothingTakesIt(KeyEvent.KEYCODE_BUTTON_Y)).isTrue()
        assertThat(usedWhenNothingTakesIt(KeyEvent.KEYCODE_BUTTON_START)).isTrue()
        assertThat(usedWhenNothingTakesIt(KeyEvent.KEYCODE_BUTTON_MODE)).isFalse() // Home keeps its fallback
        assertThat(usedWhenNothingTakesIt(KeyEvent.KEYCODE_DPAD_DOWN)).isFalse() // Android still moves focus
    }

    private fun rightShift(action: Int, deviceId: Int) =
        KeyEvent(0, 0, action, KeyEvent.KEYCODE_SHIFT_RIGHT, 0, 0, deviceId, 0, 0, InputDevice.SOURCE_KEYBOARD)

    /** AYN runs `input keyevent 60` at every focus-mode change: a right Shift from the virtual keyboard. */
    @Test fun theRightShiftAynSendsOnAFocusModeChangeIsDropped() {
        val delivered = mutableListOf<KeyEvent>()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val used = GamepadKeys.dispatch(rightShift(action, KeyCharacterMap.VIRTUAL_KEYBOARD), swapConfirmBack = false) {
                delivered += it
                false
            }
            assertThat(used).isTrue()
        }
        assertThat(delivered).isEmpty()
    }

    @Test fun aKeyboardsRightShiftStillReachesLoom() {
        val delivered = mutableListOf<Int>()
        GamepadKeys.dispatch(rightShift(KeyEvent.ACTION_DOWN, deviceId = 7), swapConfirmBack = false) {
            delivered += it.keyCode
            true
        }
        assertThat(delivered).containsExactly(KeyEvent.KEYCODE_SHIFT_RIGHT)
    }
}
