package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class NdsHeaderTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The start of a DS ROM: the game code at 0x0C, the unit code at 0x12, the DSi title ID at 0x230 (u64 LE). */
    private fun rom(gameCode: String, unitCode: Int = 0, dsiTitleId: Long = 0, length: Int = 0x1000): Path {
        val bytes = ByteBuffer.allocate(0x1000).order(ByteOrder.LITTLE_ENDIAN)
        gameCode.toByteArray(Charsets.US_ASCII).forEachIndexed { i, b -> bytes.put(0x0C + i, b) }
        bytes.put(0x12, unitCode.toByte())
        bytes.putLong(0x230, dsiTitleId)
        return Files.write(tmp.newFile().toPath(), bytes.array().copyOf(length))
    }

    /** A DSiWare title's ID is its category, 0x00030004, then its game code. */
    @Test fun readsTheGameCodeAndTheDsiCategory() {
        assertThat(NdsHeader.read(rom("KQ9E", unitCode = 3, dsiTitleId = 0x0003_0004_4B51_3945L)))
            .isEqualTo(NdsHeader("KQ9E", 0x0003_0004L))
    }

    @Test fun dsiWareIsToldAsMelonDsTellsIt() {
        assertThat(NdsHeader.read(rom("KQ9E", unitCode = 3, dsiTitleId = 0x0003_0004_4B51_3945L))!!.isDsiWare).isTrue()
        assertThat(NdsHeader.read(rom("HNGE", unitCode = 3, dsiTitleId = 0x0003_0004_484E_4745L))!!.isDsiWare).isTrue()
    }

    /** Pokémon Black is DSi-enhanced: a cartridge, category 0x00030000, saving to a .sav like any other. */
    @Test fun aDsiEnhancedCartridgeIsNotDsiWare() {
        assertThat(NdsHeader.read(rom("IRBO", unitCode = 2, dsiTitleId = 0x0003_0000_4952_424FL))!!.isDsiWare).isFalse()
        assertThat(NdsHeader.read(rom("AMCE"))!!.isDsiWare).isFalse()
    }

    /** melonDS checks the game code before the category: a cartridge's code with DSiWare's category is still a cartridge. */
    @Test fun theCategoryAloneIsNotDsiWare() {
        assertThat(NdsHeader.read(rom("IRBO", unitCode = 3, dsiTitleId = 0x0003_0004_4952_424FL))!!.isDsiWare).isFalse()
    }

    /** A DS-only ROM may end before the DSi fields; melonDS then reads no category and takes it for a cartridge. */
    @Test fun aHeaderEndingBeforeTheCategoryHasNone() {
        val short = NdsHeader.read(rom("KQ9E", unitCode = 3, dsiTitleId = 0x0003_0004_4B51_3945L, length = 0x237))!!
        assertThat(short.dsiCategory).isNull()
        assertThat(short.isDsiWare).isFalse()
        assertThat(NdsHeader.read(rom("KQ9E", length = 0x0F))).isNull()
        assertThat(NdsHeader.read(rom("KQ9E", length = 0))).isNull()
    }
}
