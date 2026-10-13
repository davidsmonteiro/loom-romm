package ro.davidmontei.loom.data.mapping

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

    @Test fun canLookAtJustSomeFoldersWhateverTheirCase() {
        system("MAME", "Multiple Arcade Machine Emulator")
        system("snes", "Nintendo SNES (Super Nintendo)")
        assertThat(SystemFolderScanner.scan(tmp.root, only = listOf("mame", "arcade")).map { it.name }).containsExactly("MAME")
    }
}
