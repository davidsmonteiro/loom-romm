package ro.davidmontei.loom.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import kotlinx.coroutines.delay

/**
 * Puts focus on a screen's primary element. Android starts most windows in touch mode, where
 * Compose won't focus clickables, so a request made at first composition is silently ignored on
 * the device. The request is therefore repeated the moment the window switches to keyboard mode
 * (the first D-pad or button press), which lands that first press on the primary action instead
 * of wherever a focus search from nothing would go.
 */
@Composable
fun InitialFocus(requester: FocusRequester, vararg keys: Any?, delayMs: Long = 50) {
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(*keys, keyboardMode) {
        if (delayMs > 0) delay(delayMs) // let lazy lists compose the target first
        runCatching { requester.requestFocus() }
    }
}
