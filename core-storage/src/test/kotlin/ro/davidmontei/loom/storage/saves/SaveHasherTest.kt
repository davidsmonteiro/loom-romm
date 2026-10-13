package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_MAIN
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_METADATA
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_UNIT_HASH
import ro.davidmontei.loom.storage.saves.SaveFixtures.folderOf
import ro.davidmontei.loom.storage.saves.SaveFixtures.zipOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Every expected hash here was computed by RomM's algorithm run on Python's own `zipfile`. */
class SaveHasherTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun path(name: String): Path = tmp.root.toPath().resolve(name)

    /** The vector from the design: a directory entry, mixed case, a nested name. */
    @Test fun zipHashMatchesRomm() {
        val zip = zipOf(
            path("save.zip"),
            "dir/" to null,
            "b/DATA.BIN" to byteArrayOf(0, 1, 2),
            "a.sav" to "hello".toByteArray(),
            "B.txt" to "x".toByteArray(),
        )
        assertThat(SaveHasher.hashFile(zip)).isEqualTo("c64a891c4749dd26b722d6d0f1dc3e6c")
        assertThat(SaveHasher.hashZip(zip)).isEqualTo("c64a891c4749dd26b722d6d0f1dc3e6c")
    }

    @Test fun anEmptyZipHashesToTheMd5OfNothing() {
        assertThat(SaveHasher.hashFile(zipOf(path("empty.zip")))).isEqualTo(SaveHasher.EMPTY)
        assertThat(SaveHasher.EMPTY).isEqualTo("d41d8cd98f00b204e9800998ecf8427e")
    }

    /** Argosy pins the same vector in SaveArchiverHashParityTest, so the two clients agree. */
    @Test fun matchesArgosysParityVector() {
        val zip = zipOf(path("argosy.zip"), "a.sav" to byteArrayOf(0, 1, 2), "b.sav" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertThat(SaveHasher.hashFile(zip)).isEqualTo("fe72f8d850245659647bd6b5f3577a7a")
    }

    /** Python sorts by code point; UTF-16 order would put the emoji (a surrogate pair) first. */
    @Test fun namesSortByCodePointLikePython() {
        val zip = zipOf(path("order.zip"), "😀.sav" to "b".toByteArray(), "～.sav" to "a".toByteArray())
        assertThat(SaveHasher.hashFile(zip)).isEqualTo("29e09f4d8ae9046b15f483be6d487d2f")
    }

    /** RomM decides by content: a zip named .sav is still a zip, and a file is just its MD5. */
    @Test fun zipsAreRecognisedByContentNotName() {
        val zipNamedSav = zipOf(path("game.sav"), "a.sav" to byteArrayOf(0, 1, 2), "b.sav" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertThat(SaveHasher.isZip(zipNamedSav)).isTrue()
        assertThat(SaveHasher.hashFile(zipNamedSav)).isEqualTo("fe72f8d850245659647bd6b5f3577a7a")

        val raw = path("hello.zip").also { Files.write(it, "hello".toByteArray()) }
        assertThat(SaveHasher.isZip(raw)).isFalse()
        assertThat(SaveHasher.hashFile(raw)).isEqualTo("5d41402abc4b2a76b9719d911017c592")

        // A save that merely starts like a zip has no central directory: Python's is_zipfile says no.
        val lookalike = path("main").also { Files.write(it, byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(60)) }
        assertThat(SaveHasher.isZip(lookalike)).isFalse()
        assertThat(SaveHasher.hashFile(lookalike)).isEqualTo(SaveHasher.md5(lookalike))
    }

    @Test fun hashZipRefusesAFileThatIsNotAZip() {
        val raw = path("main").also { Files.write(it, ByteArray(64)) }
        assertThrows(IOException::class.java) { SaveHasher.hashZip(raw) }
    }

    /** Azahar's save folder hashed as the archive Loom would upload, without writing it. */
    @Test fun aFolderHashesAsTheArchiveItBecomes() {
        val data = folderOf(path("0011c400/data"), "00000001/main" to AZAHAR_MAIN, "00000001.metadata" to AZAHAR_METADATA)
        assertThat(SaveHasher.hashUnit(listOf(SaveRoot("data", data)))).isEqualTo(AZAHAR_UNIT_HASH)
    }

    @Test fun aUnitWithoutFilesHasNoHash() {
        val empty = Files.createDirectories(path("title/00040000/00055d00/data/00000001"))
        assertThat(SaveHasher.hashUnit(listOf(SaveRoot("data", empty.parent)))).isNull()
        assertThat(SaveHasher.hashUnit(listOf(SaveRoot("data", path("missing"))))).isNull()
    }

    /** Only part of an archive is restored (Argosy adds extdata/): its hash on its own. */
    @Test fun hashZipCanCountOnlySomeRoots() {
        val zip = zipOf(
            path("argosy-3ds.zip"),
            "data/" to null,
            "data/00000001/main" to AZAHAR_MAIN,
            "data/00000001.metadata" to AZAHAR_METADATA,
            "extdata/user/save.dat" to byteArrayOf(9),
            "data" to byteArrayOf(1), // a top-level file is not under the data root
        )
        assertThat(SaveHasher.hashZip(zip, roots = setOf("data"))).isEqualTo(AZAHAR_UNIT_HASH)
        assertThat(SaveHasher.hashZip(zip)).isNotEqualTo(AZAHAR_UNIT_HASH)
    }

    /** RomM gives up on an entry over 512 MiB; Loom stops reading there too. */
    @Test fun hashingStopsAtAnEntryTooLargeToBeASave() {
        val zip = zipOf(path("big.zip"), "data/main" to ByteArray(100))
        assertThrows(IOException::class.java) { SaveHasher.hashZip(zip, maxEntryBytes = 99) }
        assertThat(SaveHasher.hashZip(zip, maxEntryBytes = 100)).isEqualTo(SaveHasher.hashZip(zip))
    }

    @Test fun md5OfAFile() {
        val file = path("a.sav").also { Files.write(it, "hello".toByteArray()) }
        assertThat(SaveHasher.md5(file)).isEqualTo("5d41402abc4b2a76b9719d911017c592")
    }
}
