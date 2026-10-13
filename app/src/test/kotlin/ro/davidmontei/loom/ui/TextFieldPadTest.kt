package ro.davidmontei.loom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.ui.components.InitialFocus
import ro.davidmontei.loom.ui.components.LoomTextField

/** The pad-driven text field: found on the Thor, where the keyboard and Enter misbehaved. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TextFieldPadTest {
    @get:Rule val rule = createComposeRule()
    private var primary = ""
    private var secondary = ""
    private var submits = 0

    private fun show() {
        rule.setContent {
            LoomTestTheme {
                var a by remember { mutableStateOf("") }
                var b by remember { mutableStateOf("") }
                val first = remember { FocusRequester() }
                InitialFocus(first)
                Column {
                    LoomTextField(a, { a = it; primary = it }, "Address", Modifier.focusRequester(first), imeAction = ImeAction.Next, tag = "a")
                    LoomTextField(b, { b = it; secondary = it }, "Second", onImeAction = { submits++ }, tag = "b")
                }
            }
        }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
    }

    private fun press(key: Key) { rule.onRoot().performKeyInput { pressKey(key) }; rule.mainClock.advanceTimeBy(100); rule.waitForIdle() }

    private fun innerFields() = rule.onAllNodes(hasSetTextAction())

    @Test fun dpadLandsOnTheFrameWithoutEditing() {
        show()
        rule.onNodeWithTag("a").assertIsFocused()
        innerFields()[0].assertIsNotFocused()
    }

    @Test fun aStartsEditingAndEnterMovesOnOnce() {
        show()
        press(Key.DirectionCenter) // A after the activity's remap
        innerFields()[0].assertIsFocused()
        innerFields()[0].performTextInput("http://100.64.0.7:8880")
        rule.waitForIdle()
        assertThat(primary).isEqualTo("http://100.64.0.7:8880") // nothing dropped
        press(Key.Enter)
        rule.onNodeWithTag("b").assertIsFocused() // "Next" moved on to the next frame…
        assertThat(submits).isEqualTo(0)          // …and did not also submit the second field
    }

    @Test fun enterOnTheLastFieldSubmitsExactlyOnce() {
        show()
        press(Key.DirectionDown)
        rule.onNodeWithTag("b").assertIsFocused()
        press(Key.DirectionCenter)
        innerFields()[1].performTextInput("https://romm.example.com")
        press(Key.Enter)
        assertThat(submits).isEqualTo(1)
        assertThat(secondary).isEqualTo("https://romm.example.com")
        rule.onNodeWithTag("b").assertIsFocused()
    }
}
