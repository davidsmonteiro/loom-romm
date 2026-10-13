package ro.davidmontei.loom.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ro.davidmontei.loom.ui.components.ScreenScaffold

@Composable
fun SettingsScreen(onBack: () -> Unit, onRepair: () -> Unit) {
    ScreenScaffold(title = "SettingsScreen", hints = emptyList()) { Text("Coming next") }
}
