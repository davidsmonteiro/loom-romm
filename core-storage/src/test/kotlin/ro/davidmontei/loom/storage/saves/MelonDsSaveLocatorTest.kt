package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class MelonDsSaveLocatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun path(name: String): Path = tmp.root.toPath().resolve(name)

    private val saveDir get() = path("ROMs/saves/melonDS")

    /** A DS ROM's first bytes: the game code at 0x0C and the DSi title ID at 0x230 (u64 LE). */
    private fun rom(name: String, gameCode: String = "AMCE", dsiTitleId: Long = 0, length: Int = 0x1000): Path {
        val bytes = ByteBuffer.allocate(0x1000).order(ByteOrder.LITTLE_ENDIAN)
        gameCode.toByteArray(Charsets.US_ASCII).forEachIndexed { i, b -> bytes.put(0x0C + i, b) }
        bytes.putLong(0x230, dsiTitleId)
        val file = path(name)
        Files.createDirectories(file.parent)
        return Files.write(file, bytes.array().copyOf(length))
    }

    private fun found(name: String) = MelonDsSaveLocation.Found(SaveUnit.SingleFile(saveDir.resolve(name)))

    @Test fun theSaveIsNamedAsMelonDsNamesIt() {
        assertThat(MelonDsSaveLocator.saveNameFor("Game (USA).nds")).isEqualTo("Game (USA).sav")
        assertThat(MelonDsSaveLocator.saveNameFor("a.b.nds")).isEqualTo("a.b.sav")
        assertThat(MelonDsSaveLocator.saveNameFor("Game.zip")).isEqualTo("Game.sav")
        assertThat(MelonDsSaveLocator.saveNameFor("Game")).isEqualTo("Game.sav")
    }

    /** The save may not exist yet: the game simply hasn't saved on this device. */
    @Test fun anInstalledRomIsItsOwnCartridge() {
        Files.createDirectories(saveDir)
        val installed = rom("ROMs/nds/Mario Kart DS (USA).nds")
        assertThat(MelonDsSaveLocator.locate(saveDir, installed)).isEqualTo(found("Mario Kart DS (USA).sav"))
        assertThat((MelonDsSaveLocator.locate(saveDir, installed) as MelonDsSaveLocation.Found).unit.hash()).isNull()
    }

    /** melonDS names a zipped ROM's save after the zip; Loom doesn't look inside it. */
    @Test fun aZippedRomIsNamedAfterTheZip() {
        Files.createDirectories(saveDir)
        val zip = SaveFixtures.zipOf(path("Game (USA).zip"), "Game (USA).nds" to ByteArray(0x300))
        assertThat(MelonDsSaveLocator.locate(saveDir, zip)).isEqualTo(found("Game (USA).sav"))
    }

    @Test fun aFolderInstallUsesItsOneCartridge() {
        Files.createDirectories(saveDir)
        rom("ROMs/nds/Game (USA)/Game (USA).nds")
        Files.write(path("ROMs/nds/Game (USA)/readme.txt"), byteArrayOf(1))
        Files.write(path("ROMs/nds/Game (USA)/Game (USA).sav"), byteArrayOf(2))
        assertThat(MelonDsSaveLocator.locate(saveDir, path("ROMs/nds/Game (USA)"))).isEqualTo(found("Game (USA).sav"))
    }

    @Test fun twoCartridgesInAFolderAreNotGuessed() {
        Files.createDirectories(saveDir)
        rom("ROMs/nds/Game/Game (USA).nds")
        rom("ROMs/nds/Game/Game (USA) (Rev 1).nds")
        assertThat(MelonDsSaveLocator.locate(saveDir, path("ROMs/nds/Game"))).isEqualTo(MelonDsSaveLocation.NoCartridge)
        Files.createDirectories(path("ROMs/nds/Empty"))
        assertThat(MelonDsSaveLocator.locate(saveDir, path("ROMs/nds/Empty"))).isEqualTo(MelonDsSaveLocation.NoCartridge)
        assertThat(MelonDsSaveLocator.locate(saveDir, path("ROMs/nds/Gone.nds"))).isEqualTo(MelonDsSaveLocation.NoCartridge)
    }

    @Test fun dsiWareIsNotACartridge() {
        Files.createDirectories(saveDir)
        val installed = rom("ROMs/nds/Flipnote.nds", gameCode = "KGUE", dsiTitleId = 0x0003_0004_4B47_5545L)
        assertThat(MelonDsSaveLocator.locate(saveDir, installed)).isEqualTo(MelonDsSaveLocation.DsiWare)
        assertThat(MelonDsSaveLocator.locate(path("nowhere"), installed)).isEqualTo(MelonDsSaveLocation.DsiWare)
    }

    @Test fun aDsiEnhancedCartridgeIsOne() {
        Files.createDirectories(saveDir)
        val installed = rom("ROMs/nds/Pokemon Black.nds", gameCode = "IRBO", dsiTitleId = 0x0003_0000_4952_424FL)
        assertThat(MelonDsSaveLocator.locate(saveDir, installed)).isEqualTo(found("Pokemon Black.sav"))
    }

    @Test fun aShortHeaderCountsAsACartridge() {
        Files.createDirectories(saveDir)
        val installed = rom("ROMs/nds/Tiny.nds", gameCode = "KGUE", dsiTitleId = 0x0003_0004_4B47_5545L, length = 0x200)
        assertThat(MelonDsSaveLocator.locate(saveDir, installed)).isEqualTo(found("Tiny.sav"))
        assertThat(MelonDsSaveLocator.locate(saveDir, rom("ROMs/nds/Empty.nds", length = 0))).isEqualTo(found("Empty.sav"))
    }

    @Test fun noFolderMeansNotSetUp() {
        val installed = rom("ROMs/nds/Game.nds")
        assertThat(MelonDsSaveLocator.locate(saveDir, installed)).isEqualTo(MelonDsSaveLocation.NoFolder)
        Files.createDirectories(saveDir.parent)
        Files.write(saveDir, byteArrayOf(1))
        assertThat(MelonDsSaveLocator.locate(saveDir, installed)).isEqualTo(MelonDsSaveLocation.NoFolder)
    }
}
