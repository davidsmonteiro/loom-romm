package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.RAW_SAVE
import ro.davidmontei.loom.storage.saves.SaveFixtures.RAW_SAVE_MD5
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class RawSaveFormatsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(vararg parts: ByteArray): Path = Files.write(tmp.newFile().toPath(), parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, value).array()

    private val magic = "ARGOSY".toByteArray() + byteArrayOf(1, 0)

    /** A trailer as Argosy builds one around [json]: the JSON, its length, the magic. */
    private fun trailer(json: ByteArray, length: Int = json.size) = json + le32(length) + magic

    /** The 28 bytes Argosy v2.22 appends, as they are in a file it uploaded. */
    private val argosys = hex("7b2268223a747275652c2276223a317d" + "10000000" + "4152474f53590100")

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun aTrailerArgosyAddedIsStripped() {
        assertThat(trailer("""{"h":true,"v":1}""".toByteArray())).isEqualTo(argosys)
        val save = file(RAW_SAVE, argosys)
        // hashlib.md5(raw + trailer): RomM's hash of the save Argosy uploaded.
        assertThat(SaveHasher.md5(save)).isEqualTo("24b2e18ddb22f635e51ea692d19e615a")
        assertThat(ArgosyTrailer.size(save)).isEqualTo(28)

        assertThat(ArgosyTrailer.strip(save)).isEqualTo(28)
        assertThat(SaveHasher.md5(save)).isEqualTo(RAW_SAVE_MD5)
        assertThat(ArgosyTrailer.strip(save)).isEqualTo(0)
        assertThat(Files.readAllBytes(save)).isEqualTo(RAW_SAVE)
    }

    /** Argosy's strip rule reads only the length and the magic, so any bytes in the JSON's place come off too. */
    @Test fun anyJsonInsideTheTrailerIsStrippedAsArgosyDoes() {
        val save = file(RAW_SAVE, trailer("not json at all".toByteArray()))
        assertThat(ArgosyTrailer.strip(save)).isEqualTo(12 + 15)
        assertThat(Files.readAllBytes(save)).isEqualTo(RAW_SAVE)

        val noJson = file(RAW_SAVE, trailer(ByteArray(0)))
        assertThat(ArgosyTrailer.strip(noJson)).isEqualTo(12)
        assertThat(Files.readAllBytes(noJson)).isEqualTo(RAW_SAVE)
    }

    @Test fun noMagicIsNoTrailer() {
        val otherVersion = file(RAW_SAVE, argosys.copyOf().also { it[26] = 2 })
        assertThat(ArgosyTrailer.strip(otherVersion)).isEqualTo(0)
        assertThat(Files.size(otherVersion)).isEqualTo(RAW_SAVE.size + 28L)
        assertThat(ArgosyTrailer.size(file(RAW_SAVE))).isEqualTo(0)
        assertThat(ArgosyTrailer.size(file(magic.copyOf(11)))).isEqualTo(0)
        assertThat(ArgosyTrailer.size(file())).isEqualTo(0)
    }

    @Test fun aLengthOver1024IsNoTrailer() {
        assertThat(ArgosyTrailer.size(file(RAW_SAVE, trailer(ByteArray(1025))))).isEqualTo(0)
        assertThat(ArgosyTrailer.size(file(RAW_SAVE, trailer(ByteArray(1024))))).isEqualTo(1036)
        assertThat(ArgosyTrailer.size(file(RAW_SAVE, trailer(ByteArray(16), length = -1)))).isEqualTo(0)
    }

    @Test fun aTrailerLongerThanTheFileIsNoTrailer() {
        assertThat(ArgosyTrailer.size(file(trailer(ByteArray(4), length = 16)))).isEqualTo(0)
        // Only a trailer: Argosy's rule strips it all, and an empty file is no save.
        val onlyTrailer = file(argosys)
        assertThat(ArgosyTrailer.strip(onlyTrailer)).isEqualTo(28)
        assertThat(Files.size(onlyTrailer)).isEqualTo(0)
    }

    @Test fun aDeSmuMeSaveIsRecognised() {
        val footer = "|<--Snip above here to create a raw sav by excluding this DeSmuME savedata footer:".toByteArray() +
            le32(RAW_SAVE.size) + le32(0) + le32(2) + le32(2) + le32(RAW_SAVE.size) + le32(0) + "|-DESMUME SAVE-|".toByteArray()
        assertThat(footer.size).isEqualTo(122)
        assertThat(isDesmumeSave(file(RAW_SAVE, footer))).isTrue()
        assertThat(isDesmumeSave(file(RAW_SAVE, footer, argosys))).isTrue()

        assertThat(isDesmumeSave(file(RAW_SAVE))).isFalse()
        assertThat(isDesmumeSave(file(RAW_SAVE, argosys))).isFalse()
        assertThat(isDesmumeSave(file("-DESMUME SAVE-|".toByteArray()))).isFalse()
        assertThat(isDesmumeSave(file())).isFalse()
    }
}
