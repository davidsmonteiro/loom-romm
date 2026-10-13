package ro.davidmontei.loom.ui.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ro.davidmontei.loom.ui.components.ScreenScaffold

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    ScreenScaffold(title = "OnboardingScreen", hints = emptyList()) { Text("Coming next") }
}
