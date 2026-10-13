package ro.davidmontei.loom.ui.queue

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ro.davidmontei.loom.ui.components.ScreenScaffold

@Composable
fun QueueScreen(onBack: () -> Unit) {
    ScreenScaffold(title = "QueueScreen", hints = emptyList()) { Text("Coming next") }
}
