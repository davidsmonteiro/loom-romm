package ro.davidmontei.loom.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
        override fun connect() { calls += "connect"; state = state.copy(probing = true, serverError = null, serverSummary = null) }
        override fun setPairMode(mode: PairMode) { calls += "mode:$mode"; state = state.copy(pairMode = mode, pairInput = "", pairError = null) }
        override fun setPairInput(text: String) { calls += "input:$text" }
        override fun submitPairInput() { calls += "submit"; state = state.copy(pairing = true, pairError = null) }
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

    /** Recompose with the new state first, then let delayed focus requests run. */
    private fun update(change: (OnboardingState) -> OnboardingState) {
        state = change(state)
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

    /** Found by audit: B on the pairing screen opened from Settings walked back through setup, never to Settings. */
    @Test fun bLeavesPairingAgain() {
        show(OnboardingState(step = OnboardingStep.PAIR, pairAgain = true))
        rule.onNodeWithText("Pair again").assertExists() // the screen's title, not "Set up Loom"
        back()
        assertThat(calls).contains("finish")
        assertThat(calls.filter { it.startsWith("go:") }).isEmpty()
    }

    @Test fun pairingAgainClosesOnceTheNewTokenWorks() {
        show(OnboardingState(step = OnboardingStep.PAIR, pairAgain = true))
        assertThat(calls).doesNotContain("finish")
        update { it.copy(pairedAs = "david") }
        assertThat(calls).contains("finish")
    }

    @Test fun bOnTheSetupsPairingStepGoesBackAStep() {
        show(OnboardingState(step = OnboardingStep.PAIR))
        back()
        assertThat(calls).contains("go:SERVER")
        assertThat(calls).doesNotContain("finish")
    }

    /** Found by audit: choosing "Enter a pair code" put focus back on "Scan a code", so the next A switched back. */
    @Test fun choosingAWayToPairFocusesItsField() {
        show(OnboardingState(step = OnboardingStep.PAIR))
        rule.onNodeWithText("Scan a code").assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionCenter) // Enter a pair code
        rule.onNodeWithTag("pair-input").assertIsFocused()
        press(Key.DirectionUp); press(Key.DirectionRight)
        rule.onNodeWithText("Paste a token").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("pair-input").assertIsFocused()
        assertThat(calls).containsNoneOf("mode:DEVICE_CODE", "deviceAuth")
    }

    /** Found by audit: the busy Pair button lost focus to "Scan a code", so after a rejected code A switched modes. */
    @Test fun pairingKeepsFocusAndARejectedCodeGoesBackToTheField() {
        show(OnboardingState(step = OnboardingStep.PAIR, pairMode = PairMode.PAIR_CODE, pairInput = "ABCD2345"))
        rule.onNodeWithTag("pair-input").assertIsFocused()
        press(Key.DirectionDown)
        press(Key.DirectionCenter) // Pair
        rule.onNodeWithText("Pairing…").assertIsFocused()
        update { it.copy(pairing = false, pairError = "That code is invalid or expired (codes last 60 seconds). Create a new one in RomM.") }
        rule.onNodeWithTag("pair-input").assertIsFocused()
        assertThat(calls).containsNoneOf("mode:DEVICE_CODE", "deviceAuth")
    }

    /** Found by audit: while an address was being checked, the disabled button dropped focus to the top of the step. */
    @Test fun checkingAnAddressKeepsFocusOnTheButton() {
        show(OnboardingState(step = OnboardingStep.SERVER, addressInput = "romm.example.org"))
        press(Key.DirectionDown)
        press(Key.DirectionCenter) // Check address
        rule.onNodeWithText("Checking…").assertIsFocused()
        update { it.copy(probing = false, serverError = "Couldn't reach RomM: timeout") }
        rule.onNodeWithText("Check address").assertIsFocused()
    }

    /** Found by audit: back on the Server step with the address checked, focus went to the field instead of Continue. */
    @Test fun aCheckedAddressStartsOnContinue() {
        show(OnboardingState(step = OnboardingStep.SERVER, addressInput = "romm.example.org", serverSummary = "RomM 5.3.1 at https://romm.example.org/"))
        rule.onNodeWithText("Continue").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("go:PAIR")
    }
}
