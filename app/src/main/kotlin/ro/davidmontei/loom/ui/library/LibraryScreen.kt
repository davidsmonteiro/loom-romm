package ro.davidmontei.loom.ui.library

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ro.davidmontei.loom.ui.components.ScreenScaffold

@Composable
fun LibraryScreen(openQueue: () -> Unit, openSettings: () -> Unit) {
    ScreenScaffold(title = "LibraryScreen", hints = emptyList()) { Text("Coming next") }
}
