package ro.davidmontei.loom.ui

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.ui.input.GamepadKeys
import ro.davidmontei.loom.ui.input.PadAction

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
}
