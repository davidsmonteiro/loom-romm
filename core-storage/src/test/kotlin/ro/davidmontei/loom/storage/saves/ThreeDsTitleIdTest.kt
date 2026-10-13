package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class ThreeDsTitleIdTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The first 0x200 bytes of a cartridge dump: signature, then the NCSD header. */
    private fun cartridge(name: String, titleId: Long, magic: String = "NCSD", length: Int = 0x200): Path {
        val bytes = ByteBuffer.allocate(length.coerceAtLeast(0x110)).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put(0x100, magic.toByteArray()[0]).put(0x101, magic.toByteArray()[1])
            .put(0x102, magic.toByteArray()[2]).put(0x103, magic.toByteArray()[3])
        bytes.putLong(0x108, titleId)
        return tmp.root.toPath().resolve(name).also { Files.write(it, bytes.array().copyOf(length)) }
    }

    /** The two games on the Thor, read there with the same offsets. */
    @Test fun readsTheTitleIdOfTheThorsGames() {
        val omegaRuby = NcsdReader.titleId(cartridge("Pokemon Omega Ruby.3ds", 0x000400000011C400L))!!
        assertThat(omegaRuby.toString()).isEqualTo("000400000011C400")
        assertThat(omegaRuby.high).isEqualTo("00040000")
        assertThat(omegaRuby.low).isEqualTo("0011c400")
        assertThat(omegaRuby.isGame).isTrue()

        val pokemonX = NcsdReader.titleId(cartridge("Pokemon X.3ds", 0x0004000000055D00L))!!
        assertThat(pokemonX.toString()).isEqualTo("0004000000055D00")
        assertThat(pokemonX.low).isEqualTo("00055d00")
    }

    @Test fun readsCciWhateverTheCaseOfItsExtension() {
        assertThat(NcsdReader.titleId(cartridge("Game.CCI", 0x000400000011C400L))).isEqualTo(ThreeDsTitleId(0x000400000011C400L))
        assertThat(NcsdReader.titleId(cartridge("Game.3DS", 0x000400000011C400L))).isEqualTo(ThreeDsTitleId(0x000400000011C400L))
    }

    @Test fun anythingButACartridgeImageIsNull() {
        assertThat(NcsdReader.titleId(cartridge("Game.cia", 0x000400000011C400L))).isNull()
        assertThat(NcsdReader.titleId(cartridge("Game.3dsx", 0x000400000011C400L))).isNull()
        assertThat(NcsdReader.titleId(cartridge("Game.zip", 0x000400000011C400L))).isNull()
        assertThat(NcsdReader.titleId(cartridge("NoMagic.3ds", 0x000400000011C400L, magic = "NCCH"))).isNull()
        assertThat(NcsdReader.titleId(cartridge("Short.3ds", 0x000400000011C400L, length = 0x10F))).isNull()
        assertThat(NcsdReader.titleId(tmp.root.toPath().resolve("Missing.3ds"))).isNull()
    }

    @Test fun parsesBothWaysRommWritesIt() {
        assertThat(ThreeDsTitleId.parse("000400000011C400")).isEqualTo(ThreeDsTitleId(0x000400000011C400L))
        assertThat(ThreeDsTitleId.parse("000400000011c400")).isEqualTo(ThreeDsTitleId(0x000400000011C400L))
        assertThat(ThreeDsTitleId.parse("00040000/0011c400")).isEqualTo(ThreeDsTitleId(0x000400000011C400L))
        assertThat(ThreeDsTitleId.parse("0011c400")).isNull()
        assertThat(ThreeDsTitleId.parse("ULUS10041")).isNull()
    }

    /** RomM's own test fixture uses an update's id (0004000e): updates keep no save of their own. */
    @Test fun onlyAGamesOwnTitleIsAGame() {
        assertThat(ThreeDsTitleId.parse("0004000e/0011c500")!!.isGame).isFalse()
        assertThat(ThreeDsTitleId.parse("0004008c00055d01")!!.isGame).isFalse() // DLC
    }
}
