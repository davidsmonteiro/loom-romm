package ro.davidmontei.loom.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.ui.settings.azaharFolderSaved
import ro.davidmontei.loom.ui.settings.findAzaharFolder
import java.io.File

/** Settings' view of Azahar's folder: found in the 3DS system folder, or chosen. */
class AzaharFolderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun itIsFoundInTheSystemFolderHoldingAzaharsData() {
        val roms = tmp.newFolder("ROMs")
        File(roms, "3ds").mkdirs() // another frontend's name, empty
        File(roms, "n3ds/sdmc/Nintendo 3DS").mkdirs()
        assertThat(findAzaharFolder(roms.path, listOf("3ds", "n3ds"))).isEqualTo(File(roms, "n3ds"))
        assertThat(findAzaharFolder(roms.path, listOf("3ds"))).isNull()
        assertThat(findAzaharFolder(null, listOf("n3ds"))).isNull()
    }

    @Test fun choosingAFolderSaysWhetherAzaharsDataIsThere() {
        val azahar = tmp.newFolder("Azahar").apply { File(this, "sdmc/Nintendo 3DS").mkdirs() }
        val empty = tmp.newFolder("Empty")
        assertThat(azaharFolderSaved(azahar)).isEqualTo("Saved. Loom finds 3DS saves in ${azahar.path}")
        assertThat(azaharFolderSaved(empty)).startsWith("Saved, but Azahar's sdmc/Nintendo 3DS isn't in ${empty.path} yet.")
        assertThat(azaharFolderSaved(null)).isEqualTo("Loom looks for Azahar's data in the 3DS folder again")
    }
}
