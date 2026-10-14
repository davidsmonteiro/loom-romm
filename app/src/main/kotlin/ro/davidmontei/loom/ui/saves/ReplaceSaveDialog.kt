package ro.davidmontei.loom.ui.saves

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomDialog
import ro.davidmontei.loom.ui.theme.LoomColors

/**
 * Asked before Loom replaces a save on the Thor (Restore server save, Keep server, Keep both).
 * Loom can't see whether the emulator is still running, and one left in the background writes
 * over the new save the next time the game saves, so the user closes it first. Cancel has focus,
 * and B cancels.
 */
@Composable
fun ReplaceSaveDialog(title: String, emulator: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    LoomDialog(
        title = title,
        onDismiss = onDismiss,
        body = {
            Text(
                "Close $emulator completely first (swipe it away in Recents). Loom backs up the save on the Thor before replacing it.",
                style = MaterialTheme.typography.bodyLarge, color = LoomColors.TextMuted,
            )
        },
        buttons = { first ->
            LoomButton("Cancel", onDismiss, Modifier.focusRequester(first))
            LoomButton(confirm, onConfirm, primary = true)
        },
    )
}
