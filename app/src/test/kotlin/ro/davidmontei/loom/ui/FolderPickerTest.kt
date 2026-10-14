package ro.davidmontei.loom.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val picked = mutableListOf<File>()

    private lateinit var sd: File

    private fun show(focusOnOpen: Boolean) {
        sd = tmp.newFolder("6365-3230")
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

    /** What the activity does with B (BACK) once nothing in Compose has used it. */
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
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

    /** Found by audit: B left the whole screen instead of undoing the A that opened a folder. */
    @Test fun bGoesBackUpThroughTheFoldersOpened() {
        show(focusOnOpen = true)
        press(Key.DirectionDown) // ROMs
        press(Key.DirectionCenter)
        press(Key.DirectionDown) // snes
        press(Key.DirectionCenter)
        rule.onNodeWithText(File(sd, "ROMs/snes").path).assertExists()
        back()
        rule.onNodeWithText("snes").assertIsFocused() // the folder just left, so A goes back into it
        back()
        rule.onNodeWithText("ROMs").assertIsFocused()
        rule.onNodeWithText(sd.path).assertExists()
        assertThat(picked).isEmpty()
    }

    /** A ROMs folder's `saves` holds emulators' saves (ROMs/saves/Eden): never counted as a system, whatever the screen knows. */
    @Test fun theSavesFolderDoesntLookLikeASystem() {
        val roms = tmp.newFolder("6365-3230", "ROMs")
        listOf("snes", "Saves/Eden").forEach { File(roms, it).mkdirs() }
        val volume = VolumeInfo(id = "6365-3230", root = roms.parentFile!!, label = "SD card", removable = true)
        rule.setContent { LoomTestTheme { FolderPicker(listOf(volume), roms, setOf("snes", "saves"), onPick = { picked += it }) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithText("1 folders here look like Cocoon systems (snes…)").assertExists()
    }
}
