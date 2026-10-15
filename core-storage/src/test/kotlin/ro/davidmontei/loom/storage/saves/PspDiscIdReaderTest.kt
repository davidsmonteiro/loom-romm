package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.PspDiscIdResult.Source
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Disc IDs from PSP images, read as on the Thor. The images in `resources/psp` are synthetic,
 * written by `make_fixtures.py` with Python's own struct and zlib, not Loom's code, and hold no
 * game's data. `expected.txt` gives each one's MD5 and what reading it must give.
 */
class PspDiscIdReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/psp/$name")!!.use { it.readBytes() }

    /** [bytes] (the fixture [name] by default) as a file on the "card". */
    private fun image(name: String, bytes: ByteArray = resource(name)): Path = tmp.root.toPath().resolve(name).also { Files.write(it, bytes) }

    private fun read(name: String) = PspDiscIdReader.read(image(name))

    private val ulus10064 = PspDiscId("ULUS10064")

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A CSO's index: each frame's u32, bit 31 set for a stored one. */
    private fun csoIndex(cso: ByteArray): List<Long> {
        val buffer = ByteBuffer.wrap(cso).order(ByteOrder.LITTLE_ENDIAN)
        val frames = (buffer.getLong(0x08) + buffer.getInt(0x10) - 1) / buffer.getInt(0x10)
        return (0..frames.toInt()).map { buffer.getInt(0x18 + 4 * it).toLong() and 0xFFFF_FFFFL }
    }

    @Test fun everyImageReadsAsPythonSays() {
        val expected = String(resource("expected.txt")).lines().filter { it.isNotBlank() }.map { it.split(' ') }
        assertThat(expected.map { it[0] }).containsExactly(
            "game.iso", "umd-only.iso", "sfo-only.iso", "disagree.iso", "other.iso", "cut.iso",
            "game.cso", "game-align2.cso", "game-bigframe.cso", "game-v2.cso", "game.pbp",
        )
        for (line in expected) {
            assertThat(md5(resource(line[0]))).isEqualTo(line[1])
            val result = read(line[0])
            when (line[2]) {
                "FOUND" -> assertThat(result).isEqualTo(PspDiscIdResult.Found(PspDiscId(line[3]), Source.valueOf(line[4])))
                "DISAGREE" -> assertThat(result).isEqualTo(PspDiscIdResult.Disagree(PspDiscId(line[3]), PspDiscId(line[4])))
                "NONE" -> assertThat(result).isInstanceOf(PspDiscIdResult.None::class.java)
                "NOTREAD" -> assertThat(result).isInstanceOf(PspDiscIdResult.NotRead::class.java)
                else -> error("Unknown answer in expected.txt: $line")
            }
        }
    }

    @Test fun anIsoNamesItsDiscIdInUmdData() {
        assertThat(read("game.iso")).isEqualTo(PspDiscIdResult.Found(ulus10064, Source.BOTH))
        assertThat(read("umd-only.iso")).isEqualTo(PspDiscIdResult.Found(ulus10064, Source.UMD))
    }

    @Test fun anIsoWithoutUmdDataUsesPspGamesSfo() {
        assertThat(read("sfo-only.iso")).isEqualTo(PspDiscIdResult.Found(PspDiscId("ULJM05001"), Source.SFO))
    }

    /** RomM reads UMD_DATA.BIN, PPSSPP the SFO: the save folders could be named after either. */
    @Test fun umdDataAndSfoDisagreeingIsNoAnswer() {
        assertThat(read("disagree.iso")).isEqualTo(PspDiscIdResult.Disagree(ulus10064, PspDiscId("ULUS10065")))
    }

    @Test fun aCsoReadsLikeItsIso() {
        for (name in listOf("game.cso", "game-align2.cso", "game-bigframe.cso")) {
            assertThat(read(name)).isEqualTo(PspDiscIdResult.Found(ulus10064, Source.BOTH))
        }
        assertThat(ByteBuffer.wrap(resource("game-align2.cso")).get(0x15)).isEqualTo(2.toByte())
        assertThat(ByteBuffer.wrap(resource("game-bigframe.cso")).order(ByteOrder.LITTLE_ENDIAN).getInt(0x10)).isEqualTo(0x1000)
    }

    /** The sectors read sit in both kinds of frame: the PVD and the directories deflated, UMD_DATA.BIN stored. */
    @Test fun storedAndDeflatedFramesBothRead() {
        val index = csoIndex(resource("game.cso"))
        assertThat(index[20] and 0x8000_0000L).isNotEqualTo(0L)
        assertThat(listOf(16, 18, 19, 21).map { index[it] and 0x8000_0000L }).containsExactly(0L, 0L, 0L, 0L)
        // Two sectors a frame: PARAM.SFO is the second sector of a stored frame, PSP_GAME the second of a deflated one.
        val big = csoIndex(resource("game-bigframe.cso"))
        assertThat(big[10] and 0x8000_0000L).isNotEqualTo(0L)
        assertThat(big[9] and 0x8000_0000L).isEqualTo(0L)

        assertThat(read("game.cso")).isEqualTo(PspDiscIdResult.Found(ulus10064, Source.BOTH))
        assertThat(read("game-bigframe.cso")).isEqualTo(PspDiscIdResult.Found(ulus10064, Source.BOTH))
    }

    @Test fun aDamagedCsoFrameHasNone() {
        val cso = resource("game.cso")
        val pvdFrame = ((csoIndex(cso)[16] and 0x7FFF_FFFFL)).toInt()
        val damaged = cso.copyOf().also { for (i in 0 until 4) it[pvdFrame + i] = 0xFF.toByte() }
        assertThat(PspDiscIdReader.read(image("damaged.cso", damaged))).isInstanceOf(PspDiscIdResult.None::class.java)
    }

    @Test fun aCsoV2IsNotRead() {
        assertThat(read("game-v2.cso")).isEqualTo(PspDiscIdResult.NotRead("CSO v2"))
    }

    /** A PSN game: no UMD, only its EBOOT.PBP's PARAM.SFO. */
    @Test fun aPbpNamesItsDiscId() {
        assertThat(read("game.pbp")).isEqualTo(PspDiscIdResult.Found(PspDiscId("NPUZ00001"), Source.SFO))
    }

    @Test fun aDiscWithoutEitherHasNone() {
        assertThat(read("other.iso")).isInstanceOf(PspDiscIdResult.None::class.java)
        assertThat(PspDiscIdReader.read(image("notes.iso", "not an image".toByteArray()))).isInstanceOf(PspDiscIdResult.None::class.java)
        assertThat(PspDiscIdReader.read(image("empty.iso", ByteArray(0)))).isInstanceOf(PspDiscIdResult.None::class.java)
        assertThat(PspDiscIdReader.read(image("zeros.iso", ByteArray(22 * 2048)))).isInstanceOf(PspDiscIdResult.None::class.java)
    }

    /** A download cut short, or a card that lost writes: never an exception, never another ID. */
    @Test fun aCutShortImageHasNone() {
        assertThat(read("cut.iso")).isInstanceOf(PspDiscIdResult.None::class.java)
        for (name in listOf("game.iso", "game.cso", "game-align2.cso", "game-bigframe.cso", "game.pbp")) {
            val full = resource(name)
            val whole = read(name)
            for (length in (0 until full.size step 97) + listOf(full.size - 1)) {
                val result = PspDiscIdReader.read(image("cut-$name", full.copyOf(length)))
                if (result != whole) assertThat(result).isInstanceOf(PspDiscIdResult.None::class.java)
            }
        }
        // PARAM.SFO is the last sector, so a disc image one byte short has lost it; a PBP's SFO comes first.
        for (name in listOf("game.iso", "game.cso", "game-align2.cso", "game-bigframe.cso")) {
            val full = resource(name)
            assertThat(PspDiscIdReader.read(image("cut-$name", full.copyOf(full.size - 1)))).isInstanceOf(PspDiscIdResult.None::class.java)
        }
    }

    /** RomM reads CHD images on the server; Loom would need libchdr's codecs. */
    @Test fun aChdIsLeftToRomm() {
        val chd = "MComprHD".toByteArray() + ByteArray(0x7C)
        assertThat(PspDiscIdReader.read(image("game.chd", chd))).isEqualTo(PspDiscIdResult.NotRead("CHD"))
        assertThat(PspDiscIdReader.read(image("game.zso", "ZISO".toByteArray() + ByteArray(0x14)))).isEqualTo(PspDiscIdResult.NotRead("ZSO"))
    }
}
