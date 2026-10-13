package ro.davidmontei.loom.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ro.davidmontei.loom.data.auth.Credentials
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.storage.mapping.MappingJson
import ro.davidmontei.loom.storage.mapping.PlatformMapper
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import ro.davidmontei.loom.storage.mapping.PlatformMatch
import ro.davidmontei.loom.ui.settings.MappingRow
import ro.davidmontei.loom.ui.settings.SettingsActions
import ro.davidmontei.loom.ui.settings.SettingsContent
import ro.davidmontei.loom.ui.settings.SettingsUiState
import ro.davidmontei.loom.ui.settings.StorageInfo
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = THOR_TOP_SCREEN)
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()

    companion object {
        private val mapper = PlatformMapper(MappingJson.shippedDefaults())
        private fun row(id: Int, name: String, slug: String, fsSlug: String, installed: Int = 0, bytes: Long = 0) =
            MappingRow(PlatformSummary(PlatformEntity(id, slug, fsSlug, name, name, 100, "t"), 100, installed, bytes),
                mapper.resolve(slug, fsSlug), null)

        val noop: SettingsActions get() = Recording(mutableListOf())

        val sample = SettingsUiState(
            settings = Settings(ServerConfig("http://100.64.0.7:8880/".toHttpUrl()),
                "5.3.1", "/storage/9C33-6BBD/ROMs", true, false, 1, null, null),
            credentials = Credentials("rmm_x", "david", "dev-1", null),
            mapping = listOf(
                row(1, "PlayStation", "psx", "psx", 4, 2_900_000_000), row(2, "Super Nintendo", "snes", "snes", 23, 61_000_000),
                row(3, "Arcade", "arcade", "fbneo"), row(4, "HyperScan", "hyperscan", "hyperscan"),
            ),
            storage = StorageInfo("/storage/9C33-6BBD/ROMs", "SD card", 112_000_000_000, 512_000_000_000),
            batteryUnrestricted = false, allFilesAccess = true,
        )
    }

    private val actions = Recording(calls)

    class Recording(private val calls: MutableList<String>) : SettingsActions {
        override fun updateAddress(address: String) { calls += "address:$address" }
        override fun repair() { calls += "repair" }
        override fun signOut() { calls += "signout" }
        override fun fullResync() { calls += "resync" }
        override fun setRomsRoot(dir: File) { calls += "root:$dir" }
        override fun volumes() = listOf(VolumeInfo("9C33-6BBD", File("/storage/9C33-6BBD"), "SD card", true))
        override fun saveOverride(entry: PlatformMappingEntry) { calls += "save:${entry.match.slug}:${entry.folder}" }
        override fun resetOverride(match: PlatformMatch) { calls += "reset" }
        override fun exportMapping() { calls += "export" }
        override fun importMapping() { calls += "import" }
        override val exportPath = "/storage/emulated/0/Download/loom-mapping.json"
        override val cocoonFolders = listOf("psx", "snes", "gc")
        override fun setConcurrency(n: Int) { calls += "concurrency:$n" }
        override fun setSwap(swap: Boolean) { calls += "swap:$swap" }
        override fun openBatterySettings() { calls += "battery" }
        override fun licenseText() = "GNU GENERAL PUBLIC LICENSE"
        override fun fontLicense() = "SIL OPEN FONT LICENSE"
    }

    private fun show() {
        rule.setContent { LoomTestTheme { SettingsContent(sample, actions) } }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
    }

    private fun press(key: Key) { rule.onRoot().performKeyInput { pressKey(key) }; rule.mainClock.advanceTimeBy(100); rule.waitForIdle() }

    @Test fun sectionsAreReachableAndOpenWithA() {
        show()
        rule.onNodeWithTag("section-SERVER").assertIsFocused()
        repeat(4) { press(Key.DirectionDown) }
        rule.onNodeWithTag("section-CONTROLS").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertThat(calls).contains("swap:true")
    }

    @Test fun mappingEditorSavesAFolderOverride() {
        show()
        press(Key.DirectionDown); press(Key.DirectionDown)
        rule.onNodeWithTag("section-MAPPING").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionRight) // Export
        rule.onNodeWithText("Export").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).contains("export")
        press(Key.DirectionDown) // first platform row
        press(Key.DirectionCenter)
        rule.onNodeWithText("Cancel").assertIsFocused() // editor opens on the safe choice
        press(Key.DirectionRight); press(Key.DirectionCenter) // Save
        assertThat(calls).contains("save:psx:psx")
    }
}
