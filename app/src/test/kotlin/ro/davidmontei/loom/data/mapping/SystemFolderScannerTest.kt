package ro.davidmontei.loom.data.mapping

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.storage.mapping.SystemFolder
import java.io.File

class SystemFolderScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun system(name: String, fullName: String? = null, vararg games: String) = tmp.newFolder(name).also { dir ->
        fullName?.let { File(dir, "systeminfo.txt").writeText("System name:\n$name\n\nFull system name:\n$it\n\nSupported file extensions:\n.zip\n") }
        games.forEach { File(dir, it).writeText("rom") }
    }

    @Test fun readsGamesAndEsDeSystemNames() {
        system("arcade", "Arcade", "alleymas.zip", ".hidden")
        system("mame", "Multiple Arcade Machine Emulator")
        system("custom", null, "a.zip", "b.zip")
        tmp.newFolder(".loom-staging")
        assertThat(SystemFolderScanner.scan(tmp.root)).containsExactly(
            SystemFolder("arcade", 1, "Arcade"),
            SystemFolder("custom", 2, null),
            SystemFolder("mame", 0, "Multiple Arcade Machine Emulator"),
        ).inOrder()
    }

    /** On the Thor, Azahar keeps its user directory in ROMs/n3ds: its folders are not games. */
    @Test fun azaharsFoldersAreNotGames() {
        val n3ds = system("n3ds", null, "Pokemon X.3ds")
        listOf("sdmc", "nand", "config", "states", "sysdata", "log", "cheats", "shaders", "gpu_drivers").forEach { File(n3ds, it).mkdirs() }
        assertThat(SystemFolderScanner.scan(tmp.root).single()).isEqualTo(SystemFolder("n3ds", 1, null))
    }

    @Test fun theChooserSaysWhichOfAPlatformsFoldersAreOnTheCard() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.newFolder("prefs"), "p.preferences_pb") }
            val settings = SettingsStore(prefs)
            val chooser = FolderChooser(settings, MappingRepository(prefs))
            assertThat(chooser.onCard(listOf("mame"))).isNull() // no ROMs folder yet: Loom can't look
            val roms = tmp.newFolder("ROMs")
            File(roms, "MAME").mkdirs()
            File(roms, "MAME/alleymas.zip").writeText("rom")
            File(roms, "snes").mkdirs()
            settings.setRomsRoot(roms.path)
            assertThat(chooser.onCard(listOf("mame", "fba"))).containsExactly(SystemFolder("MAME", 1, null))
        } finally {
            scope.cancel()
        }
    }

    /** ROMs/saves holds emulators' saves (ROMs/saves/Eden), not a system's games: no scan lists it, whatever its case. */
    @Test fun theSavesFolderIsNoSystemFolder() {
        system("snes", null, "Chrono Trigger (USA).sfc")
        File(tmp.newFolder("Saves"), "Eden/nand").mkdirs()
        assertThat(SystemFolderScanner.scan(tmp.root).map { it.name }).containsExactly("snes")
        assertThat(SystemFolderScanner.scan(tmp.root, only = listOf("saves", "snes")).map { it.name }).containsExactly("snes")
    }

    /** "Another folder…" in the folder question lists every folder of the ROMs folder but that one. */
    @Test fun anotherFolderDoesntOfferTheSavesFolder() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.newFolder("prefs"), "p.preferences_pb") }
            val settings = SettingsStore(prefs)
            val roms = tmp.newFolder("ROMs")
            listOf("switch", "saves/Eden", "mame").forEach { File(roms, it).mkdirs() }
            settings.setRomsRoot(roms.path)
            assertThat(FolderChooser(settings, MappingRepository(prefs)).allFolders().map { it.name }).containsExactly("mame", "switch").inOrder()
        } finally {
            scope.cancel()
        }
    }

    @Test fun canLookAtJustSomeFoldersWhateverTheirCase() {
        system("MAME", "Multiple Arcade Machine Emulator")
        system("snes", "Nintendo SNES (Super Nintendo)")
        assertThat(SystemFolderScanner.scan(tmp.root, only = listOf("mame", "arcade")).map { it.name }).containsExactly("MAME")
    }
}
