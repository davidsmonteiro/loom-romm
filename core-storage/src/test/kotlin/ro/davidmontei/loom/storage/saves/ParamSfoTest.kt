package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.storage.saves.SaveFixtures.sfoOf
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ParamSfoTest {
    /** A game's PARAM.SFO, with the keys a UMD game's has. */
    private val disc = sfoOf(
        "BOOTABLE" to 1,
        "CATEGORY" to "UG",
        "DISC_ID" to "ULUS10064",
        "DISC_NUMBER" to 1,
        "DISC_TOTAL" to 1,
        "DISC_VERSION" to "1.00",
        "PARENTAL_LEVEL" to 3,
        "PSP_SYSTEM_VER" to "1.50",
        "REGION" to 32768,
        "TITLE" to "Loom Test Game",
    )

    /** [this] with the u32 at [at] set to [value]. */
    private fun ByteArray.withInt(at: Int, value: Int) = copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value) }

    @Test fun readsDiscIdAndTitle() {
        val sfo = ParamSfo.parse(disc)!!
        assertThat(sfo.string("DISC_ID")).isEqualTo("ULUS10064")
        assertThat(sfo.string("TITLE")).isEqualTo("Loom Test Game")
        assertThat(sfo.string("CATEGORY")).isEqualTo("UG")
        assertThat(sfo.int("REGION")).isEqualTo(32768L)
        assertThat(sfo.string("SAVEDATA_PARAMS")).isNull()
    }

    /** What PPSSPP's savedata dialog writes: the file list and params are raw bytes, which only count as keys. */
    @Test fun aSavesRawValuesAreKeysToo() {
        val sfo = ParamSfo.parse(
            sfoOf(
                "CATEGORY" to "MS",
                "PARENTAL_LEVEL" to 1,
                "SAVEDATA_DETAIL" to "Chapter 2",
                "SAVEDATA_DIRECTORY" to "ULUS10064DATA00",
                "SAVEDATA_FILE_LIST" to ByteArray(3168) { it.toByte() },
                "SAVEDATA_PARAMS" to ByteArray(128) { 7 },
                "SAVEDATA_TITLE" to "Loom Test Game",
                "TITLE" to "Loom Test Game",
            ),
        )!!
        assertThat(sfo.keys).containsAtLeast("SAVEDATA_FILE_LIST", "SAVEDATA_PARAMS", "SAVEDATA_DIRECTORY")
        assertThat(sfo.string("SAVEDATA_PARAMS")).isNull()
        assertThat(sfo.string("SAVEDATA_DIRECTORY")).isEqualTo("ULUS10064DATA00")
    }

    @Test fun anIntegerIsNotAString() {
        val sfo = ParamSfo.parse(sfoOf("DISC_ID" to 10064, "DISC_NUMBER" to 1))!!
        assertThat(sfo.string("DISC_ID")).isNull()
        assertThat(sfo.int("DISC_ID")).isEqualTo(10064L)
        assertThat(ParamSfo.parse(disc)!!.int("DISC_ID")).isNull()
    }

    /** A text value is its length's bytes, cut at the first NUL. */
    @Test fun textStopsAtItsLengthOrItsFirstNul() {
        val sfo = ParamSfo.parse(sfoOf("TITLE" to "Loom\u0000Test", "DISC_ID" to "ULUS10064"))!!
        assertThat(sfo.string("TITLE")).isEqualTo("Loom")
        // DISC_ID is the second entry: its length (at 0x24 + 4) cut to five bytes.
        assertThat(ParamSfo.parse(sfoOf("TITLE" to "Loom", "DISC_ID" to "ULUS10064").withInt(0x24 + 4, 5))!!.string("DISC_ID")).isEqualTo("ULUS1")
    }

    @Test fun aCutShortTableGivesNothing() {
        val indexEnd = 0x14 + 16 * 10
        assertThat(ParamSfo.parse(disc.copyOf(indexEnd - 1))).isNull()
        assertThat(ParamSfo.parse(disc.copyOf(0x13))).isNull()
        assertThat(ParamSfo.parse(ByteArray(0))).isNull()
        assertThat(ParamSfo.parse(disc.withInt(0x10, 5000))).isNull()
        assertThat(ParamSfo.parse(disc.withInt(0x10, -1))).isNull()
    }

    @Test fun offsetsPastTheEndGiveNothing() {
        assertThat(ParamSfo.parse(disc.withInt(0x08, disc.size + 1))).isNull()
        assertThat(ParamSfo.parse(disc.withInt(0x0C, disc.size + 1))).isNull()
        assertThat(ParamSfo.parse(disc.withInt(0x14 + 12, disc.size))).isNull() // the first entry's data
        assertThat(ParamSfo.parse(disc.withInt(0x14 + 16 * 2 + 12, -16))).isNull() // DISC_ID's data, as a u32 far past the end
    }

    /** A value running past the end is left out, as PPSSPP leaves it out; the rest still reads. */
    @Test fun aValueRunningPastTheEndIsLeftOut() {
        val sfo = ParamSfo.parse(disc.withInt(0x14 + 16 * 9 + 4, 4096))!! // TITLE's length
        assertThat(sfo.string("TITLE")).isNull()
        assertThat(sfo.string("DISC_ID")).isEqualTo("ULUS10064")
    }

    @Test fun aWrongMagicIsNoSfo() {
        assertThat(ParamSfo.parse(disc.copyOf().also { it[1] = 'p'.code.toByte() })).isNull()
        assertThat(ParamSfo.parse("PSF\u0000".toByteArray() + disc.copyOfRange(4, disc.size))).isNull()
    }
}
