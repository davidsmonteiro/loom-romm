package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_MAIN
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_METADATA
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_UNIT_HASH
import ro.davidmontei.loom.storage.saves.SaveFixtures.folderOf
import java.nio.file.Files
import java.nio.file.Path

class AzaharSaveLocatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zeros = "0".repeat(32)
    private val omegaRuby = ThreeDsTitleId(0x000400000011C400L)
    private val pokemonX = ThreeDsTitleId(0x0004000000055D00L)

    /** `ROMs/n3ds` on the Thor: the games, Azahar's own folders, and its SD card with one save. */
    private fun thorN3ds(): Path {
        val n3ds = tmp.newFolder("ROMs", "n3ds").toPath()
        Files.write(n3ds.resolve("Pokemon Omega Ruby.3ds"), ByteArray(16))
        Files.write(n3ds.resolve("Pokemon X.3ds"), ByteArray(16))
        listOf("config", "log", "nand", "states", "sysdata", "cheats", "shaders", "gpu_drivers").forEach {
            Files.createDirectories(n3ds.resolve(it))
        }
        val tree = n3ds.resolve("sdmc/Nintendo 3DS/$zeros/$zeros")
        folderOf(tree.resolve("title/00040000/0011c400/data"), "00000001/main" to AZAHAR_MAIN, "00000001.metadata" to AZAHAR_METADATA)
        folderOf(tree.resolve("extdata/00000000/000011C5"), "user/progress" to byteArrayOf(1), "icon" to byteArrayOf(2))
        // A console's SD card also holds Private/<id> folders, which are not an id0/id1 tree.
        Files.createDirectories(n3ds.resolve("sdmc/Nintendo 3DS/Private/00020400"))
        return n3ds
    }

    @Test fun findsAGamesSaveAndOnlyItsDataFolder() {
        val n3ds = thorN3ds()
        val found = AzaharSaveLocator(n3ds).locate(omegaRuby) as AzaharSaveLocation.Found
        assertThat(found.unit.name).isEqualTo("data")
        assertThat(found.unit.folder).isEqualTo(n3ds.resolve("sdmc/Nintendo 3DS/$zeros/$zeros/title/00040000/0011c400/data"))
        assertThat(SaveZipper.entries(listOf(found.unit)).map { it.name })
            .containsExactly("data/00000001.metadata", "data/00000001/main").inOrder()
        assertThat(SaveHasher.hashUnit(listOf(found.unit))).isEqualTo(AZAHAR_UNIT_HASH)
    }

    /** No save yet on this device: the folder a download would create. */
    @Test fun pointsWhereANewSaveGoes() {
        val n3ds = thorN3ds()
        val found = AzaharSaveLocator(n3ds).locate(pokemonX) as AzaharSaveLocation.Found
        assertThat(found.unit.folder).isEqualTo(n3ds.resolve("sdmc/Nintendo 3DS/$zeros/$zeros/title/00040000/00055d00/data"))
        assertThat(Files.exists(found.unit.folder)).isFalse()
        assertThat(SaveHasher.hashUnit(listOf(found.unit))).isNull()
    }

    @Test fun withoutAnSdCardTreeThereIsNoPlaceForSaves() {
        val n3ds = tmp.newFolder("n3ds").toPath()
        assertThat(AzaharSaveLocator(n3ds).locate(omegaRuby)).isEqualTo(AzaharSaveLocation.NoSdCard)
        Files.createDirectories(n3ds.resolve("sdmc/Nintendo 3DS/$zeros"))
        assertThat(AzaharSaveLocator(n3ds).locate(omegaRuby)).isEqualTo(AzaharSaveLocation.NoSdCard)
    }

    @Test fun ofSeveralTreesTheOneHoldingTheSaveWins() {
        val n3ds = thorN3ds()
        val other = n3ds.resolve("sdmc/Nintendo 3DS/${"a".repeat(32)}/${"b".repeat(32)}")
        Files.createDirectories(other.resolve("title/00040000"))
        val found = AzaharSaveLocator(n3ds).locate(omegaRuby) as AzaharSaveLocation.Found
        assertThat(found.unit.folder.startsWith(n3ds.resolve("sdmc/Nintendo 3DS/$zeros/$zeros"))).isTrue()
    }

    @Test fun ofSeveralTreesWithNoOrTwoSavesNoneIsGuessed() {
        val n3ds = thorN3ds()
        val other = n3ds.resolve("sdmc/Nintendo 3DS/${"a".repeat(32)}/${"b".repeat(32)}")
        Files.createDirectories(other)
        val none = AzaharSaveLocator(n3ds).locate(pokemonX) as AzaharSaveLocation.Ambiguous
        assertThat(none.trees).hasSize(2)
        Files.createDirectories(other.resolve("title/00040000/0011c400/data"))
        assertThat(AzaharSaveLocator(n3ds).locate(omegaRuby)).isInstanceOf(AzaharSaveLocation.Ambiguous::class.java)
    }

    @Test fun updatesAndDlcHaveNoSaveOfTheirOwn() {
        val n3ds = thorN3ds()
        assertThat(AzaharSaveLocator(n3ds).locate(ThreeDsTitleId.parse("0004000e/0011c400")!!)).isEqualTo(AzaharSaveLocation.NotAGame)
    }
}
