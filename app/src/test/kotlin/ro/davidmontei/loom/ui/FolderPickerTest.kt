package ro.davidmontei.loom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.ui.components.FolderPicker
import ro.davidmontei.loom.ui.components.InitialFocus
import ro.davidmontei.loom.ui.components.LoomButton
import java.io.File

/** Found on the Thor: the picker took focus from onboarding's "Use …/ROMs", so A picked the card's root. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FolderPickerTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val picked = mutableListOf<File>()

    private fun show(focusOnOpen: Boolean) {
        val sd = tmp.newFolder("6365-3230")
        File(sd, "ROMs/snes").mkdirs()
        val volume = VolumeInfo(id = "6365-3230", root = sd, label = "SD card", removable = true)
        rule.setContent {
            LoomTestTheme {
                val first = remember { FocusRequester() }
                InitialFocus(first)
                Column {
                    LoomButton("Use suggested", onClick = {}, modifier = Modifier.focusRequester(first))
                    FolderPicker(listOf(volume), sd, setOf("snes"), onPick = { picked += it }, focusOnOpen = focusOnOpen)
                }
            }
        }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    /** Frame by frame, as on a device: one 200 ms jump can skip past a delayed focus request. */
    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        repeat(12) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
    }

    @Test fun theScreensSuggestionKeepsFocus() {
        show(focusOnOpen = false)
        rule.onNodeWithText("Use suggested").assertIsFocused()
    }

    @Test fun focusFollowsTheUserIntoTheFolderTheyOpen() {
        show(focusOnOpen = false)
        press(Key.DirectionDown) // Use this folder
        press(Key.DirectionDown) // ROMs
        press(Key.DirectionCenter)
        rule.onNodeWithText("Use this folder").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(picked.single().path).endsWith("6365-3230/ROMs")
    }

    @Test fun onItsOwnThePickerTakesFocus() {
        show(focusOnOpen = true)
        rule.onNodeWithText("Use this folder").assertIsFocused()
    }
}
