package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Title IDs from Switch images, read as on the Thor. The images in `resources/switch` are
 * synthetic, written by `make_fixtures.py` with the `cryptography` package's AES-XTS (not Loom's)
 * and a made-up header key: no console's key and no game's files are in this repository.
 */
class SwitchTitleIdReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/switch/$name")!!.use { it.readBytes() }

    private fun keyFrom(name: String) = SwitchHeaderKey.of(hex(String(resource(name)).trim()))

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The fixture [name] as a file on the "card", cut to [length] bytes when given. */
    private fun image(name: String, length: Int? = null): Path {
        val bytes = resource(name)
        return tmp.root.toPath().resolve(name).also { Files.write(it, if (length == null) bytes else bytes.copyOf(length)) }
    }

    private fun read(name: String, key: String = "test-header-key.txt") = keyFrom(key).use { SwitchTitleIdReader.read(image(name), it) }

    @Test fun readsTheGamesTitleIdFromEachKindOfImage() {
        val expected = String(resource("expected.txt")).lines().filter { it.isNotBlank() }.map { it.split(' ') }
        assertThat(expected.map { it[0] }).containsExactly("game.xci", "game.nsp", "bundle.nsp", "update.nsp", "nca2.nsp", "game.nsz")
        for ((file, id) in expected.filter { it[0] != "update.nsp" }) {
            assertThat(read(file)).isEqualTo(SwitchTitleIdResult.Found(SwitchTitleId.parse(id)!!))
        }
    }

    /** A card's update partition holds system titles' programs; only its secure partition is the game. */
    @Test fun aCardImageIsReadFromItsSecurePartition() {
        assertThat(read("game.xci")).isEqualTo(SwitchTitleIdResult.Found(SwitchTitleId(0x0100A0B00C0D0000L)))
    }

    /** A game with its update merged in and a second program: the application's own ID, not the update's or the other program's. */
    @Test fun ofSeveralProgramsTheApplicationsOwnIdWins() {
        assertThat(read("bundle.nsp")).isEqualTo(SwitchTitleIdResult.Found(SwitchTitleId(0x0100F00D00BA5000L)))
    }

    @Test fun anUpdateAloneNamesTheGameItUpdates() {
        assertThat(read("update.nsp")).isEqualTo(SwitchTitleIdResult.UpdateOnly(SwitchTitleId(0x0100C0FFEE000000L)))
    }

    @Test fun twoGamesOnOneCardAreNotGuessed() {
        assertThat(read("two-games.xci")).isEqualTo(
            SwitchTitleIdResult.SeveralGames(listOf(SwitchTitleId(0x0100111111111000L), SwitchTitleId(0x0100222222222000L))),
        )
    }

    @Test fun dlcHasNoProgram() {
        assertThat(read("dlc.nsp")).isEqualTo(SwitchTitleIdResult.NoProgram)
    }

    @Test fun anotherKeyOpensNothing() {
        for (file in listOf("game.xci", "game.nsp", "game.nsz")) {
            assertThat(read(file, key = "other-header-key.txt")).isEqualTo(SwitchTitleIdResult.WrongKey)
        }
    }

    /** A download cut short, or a card that lost writes: never an exception, never a wrong ID. */
    @Test fun anImageCutShortIsNotReadable() {
        val full = resource("game.xci").size
        for (length in listOf(0, 0x100, 0x13F, 0x200, 0x230, 0x2A0, full - 0x1000, full - 0x200)) {
            val result = keyFrom("test-header-key.txt").use { SwitchTitleIdReader.read(image("game.xci", length), it) }
            assertThat(result).isInstanceOf(SwitchTitleIdResult.NotReadable::class.java)
        }
        val nsp = resource("game.nsp").size
        for (length in listOf(0x10, 0x60, 0x100, nsp / 2)) {
            val result = keyFrom("test-header-key.txt").use { SwitchTitleIdReader.read(image("game.nsp", length), it) }
            assertThat(result).isInstanceOf(SwitchTitleIdResult.NotReadable::class.java)
        }
    }

    @Test fun anythingButASwitchImageIsNotReadable() {
        val cartridge = ByteBuffer.allocate(0x400).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(0x100); put("NCSD".toByteArray()); putLong(0x108, 0x000400000011C400L)
        }.array()
        val files = mapOf("Pokemon X.3ds" to cartridge, "noise.xci" to ByteArray(0x4000) { (it * 7).toByte() }, "empty.nsp" to ByteArray(0))
        for ((name, bytes) in files) {
            val file = tmp.root.toPath().resolve(name).also { Files.write(it, bytes) }
            val result = keyFrom("test-header-key.txt").use { SwitchTitleIdReader.read(file, it) }
            assertThat(result).isInstanceOf(SwitchTitleIdResult.NotReadable::class.java)
        }
    }

    /** A damaged table can't make Loom allocate or read gigabytes. */
    @Test fun aDamagedPartitionTableIsRefused() {
        val bytes = resource("game.nsp")
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, Int.MAX_VALUE)
        val file = tmp.root.toPath().resolve("damaged.nsp").also { Files.write(it, bytes) }
        val result = keyFrom("test-header-key.txt").use { SwitchTitleIdReader.read(file, it) }
        assertThat(result).isEqualTo(SwitchTitleIdResult.NotReadable("a partition table is damaged"))
    }

    /** A file that can't be read is "try again later", not "no title ID". */
    @Test fun aMissingFileThrows() {
        keyFrom("test-header-key.txt").use { key ->
            assertThrows(IOException::class.java) { SwitchTitleIdReader.read(tmp.root.toPath().resolve("gone.xci"), key) }
        }
    }

    @Test fun parsesTitleIds() {
        assertThat(SwitchTitleId.parse("010015100B514000")).isEqualTo(SwitchTitleId(0x010015100B514000L))
        assertThat(SwitchTitleId.parse("01001f5010dfa000").toString()).isEqualTo("01001F5010DFA000")
        assertThat(SwitchTitleId.parse("01001F5010DFA800")!!.isApplication).isFalse()
        assertThat(SwitchTitleId.parse("4E431822D6DEFD527268BA35B5C09533")).isNull()
        assertThat(SwitchTitleId.parse("game_data.sav")).isNull()
    }
}
