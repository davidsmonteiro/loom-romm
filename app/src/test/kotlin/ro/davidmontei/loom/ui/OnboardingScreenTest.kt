package ro.davidmontei.loom.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ro.davidmontei.loom.ui.onboarding.OnboardingActions
import ro.davidmontei.loom.ui.onboarding.OnboardingContent
import ro.davidmontei.loom.ui.onboarding.OnboardingState
import ro.davidmontei.loom.ui.onboarding.OnboardingStep
import ro.davidmontei.loom.ui.onboarding.PairMode
import java.io.File

/** Drives the onboarding steps with the pad only, as on the Thor. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = THOR_TOP_SCREEN)
class OnboardingScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(OnboardingState())

    /** Records what the steps ask for; moving between steps and modes changes the state as the ViewModel does. */
    private val actions = object : OnboardingActions {
        override val knownSystemFolders = setOf("snes", "psx")
        override fun go(step: OnboardingStep) { calls += "go:$step"; state = state.copy(step = step) }
        override fun setAddress(text: String) { calls += "address:$text" }
        override fun connect() { calls += "connect" }
        override fun setPairMode(mode: PairMode) { calls += "mode:$mode"; state = state.copy(pairMode = mode, pairInput = "", pairError = null) }
        override fun setPairInput(text: String) { calls += "input:$text" }
        override fun submitPairInput() { calls += "submit" }
        override fun startDeviceAuth() { calls += "deviceAuth" }
        override fun pairAgain() { calls += "pairAgain" }
        override fun refreshStorage() = Unit
        override fun pickRoot(dir: File) { calls += "root:$dir" }
        override fun createSuggestedRoot() { calls += "create" }
        override fun finish() { calls += "finish" }
    }

    private fun show(initial: OnboardingState) {
        state = initial
        rule.setContent { LoomTestTheme { OnboardingContent(state, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
    }

    /** Lets the press recompose first, so delayed focus requests it starts have run when it returns. */
    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
    }

    /** What the activity does with B (BACK) once nothing in Compose has used it. */
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
    }

    @Test fun aMovesOnFromWelcome() {
        show(OnboardingState())
        rule.onNodeWithText("Get started").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("go:SERVER")
        back()
        assertThat(calls).contains("go:WELCOME")
    }
}
