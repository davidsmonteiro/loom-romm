package ro.davidmontei.loom.ui.input

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Face-button handling for the whole app.
 *
 * Compose's `clickable` only reacts to DPAD_CENTER / ENTER / SPACE, and back navigation to BACK.
 * The activity therefore rewrites the pad's confirm button to DPAD_CENTER and its back button to
 * BACK before Compose sees them, so every focusable element and back handler works with a pad.
 * Swapping A and B (Nintendo layouts) only changes which physical button is which.
 */
object GamepadKeys {
    fun remap(keyCode: Int, swapConfirmBack: Boolean): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> if (swapConfirmBack) KeyEvent.KEYCODE_BACK else KeyEvent.KEYCODE_DPAD_CENTER
        KeyEvent.KEYCODE_BUTTON_B -> if (swapConfirmBack) KeyEvent.KEYCODE_DPAD_CENTER else KeyEvent.KEYCODE_BACK
        else -> keyCode
    }

    /** The screen-level actions, from the remaining buttons. */
    fun action(keyCode: Int): PadAction? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_X -> PadAction.X
        KeyEvent.KEYCODE_BUTTON_Y -> PadAction.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> PadAction.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> PadAction.R1
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> PadAction.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> PadAction.SELECT
        else -> null
    }

    /**
     * Keys (after [remap]) that act on the focused element or its screen, so they do nothing in
     * touch mode. Back works without focus; the D-pad leaves touch mode on its own.
     */
    fun needsFocus(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || action(keyCode) != null

    fun remapEvent(event: KeyEvent, swapConfirmBack: Boolean): KeyEvent {
        val code = remap(event.keyCode, swapConfirmBack)
        if (code == event.keyCode) return event
        return KeyEvent(
            event.downTime, event.eventTime, event.action, code, event.repeatCount, event.metaState,
            event.deviceId, event.scanCode, event.flags, event.source,
        )
    }
}

enum class PadAction { X, Y, L1, R1, START, SELECT }

/**
 * After a tap the window is in touch mode, where Compose focuses nothing: pressing A would only
 * bring the focus ring back, and a second press would be needed. Instead a press that needs focus
 * leaves touch mode and is delivered [later], once the screen has put focus on its primary
 * element. Its real key-up is dropped, since the replay sends one.
 */
class TouchModePress(
    private val inTouchMode: () -> Boolean,
    private val leaveTouchMode: () -> Unit,
    private val later: (Runnable) -> Unit,
    private val deliver: (KeyEvent) -> Boolean,
) {
    private var replayedKey: Int? = null

    fun dispatch(key: KeyEvent): Boolean {
        if (!GamepadKeys.needsFocus(key.keyCode)) return deliver(key)
        if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0 && inTouchMode()) {
            leaveTouchMode()
            replayedKey = key.keyCode
            later {
                deliver(key)
                deliver(KeyEvent.changeAction(key, KeyEvent.ACTION_UP))
            }
            return true
        }
        if (key.action == KeyEvent.ACTION_UP && key.keyCode == replayedKey) {
            replayedKey = null
            return true
        }
        return deliver(key)
    }
}

/**
 * Handles X / Y / L1 / R1 / Start / Select for a screen. Attach to the screen's root: key events
 * tunnel from the root to the focused element, so the screen sees them whatever is focused.
 * Return true to consume.
 */
fun Modifier.onPadAction(handler: (PadAction) -> Boolean): Modifier = onPreviewKeyEvent { event ->
    val action = GamepadKeys.action(event.key.nativeKeyCode) ?: return@onPreviewKeyEvent false
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true // swallow the matching key-up
    if (event.nativeKeyEvent.repeatCount > 0 && action != PadAction.L1 && action != PadAction.R1) return@onPreviewKeyEvent true
    handler(action)
}
