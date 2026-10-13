package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_MAIN
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_METADATA
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_UNIT_HASH
import ro.davidmontei.loom.storage.saves.SaveFixtures.folderOf
import ro.davidmontei.loom.storage.saves.SaveFixtures.zipOf
import ro.davidmontei.loom.storage.saves.SaveFixtures.zipWithSymlink
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.util.TimeZone

class SaveZipperTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun path(name: String): Path = tmp.root.toPath().resolve(name)

    private fun azaharData(at: String = "title/00040000/0011c400/data") =
        folderOf(path(at), "00000001/main" to AZAHAR_MAIN, "00000001.metadata" to AZAHAR_METADATA)

    private fun names(zip: Path): List<String> = ZipFile.builder().setPath(zip).get().use { z -> z.entries.toList().map { it.name } }

    @Test fun zippingThenHashingGivesTheFolderHash() {
        val unit = listOf(SaveRoot("data", azaharData()))
        val written = SaveZipper.zip(unit, path("unit.zip"))
        assertThat(written).isEqualTo(AZAHAR_UNIT_HASH)
        assertThat(SaveHasher.hashFile(path("unit.zip"))).isEqualTo(AZAHAR_UNIT_HASH)
        assertThat(SaveHasher.hashUnit(unit)).isEqualTo(AZAHAR_UNIT_HASH)
    }

    /** Argosy's 3DS archives are rooted at `data/` too: the same names in, the same names out. */
    @Test fun archivesHoldOnlyFilesUnderTheRootInCodePointOrder() {
        val data = folderOf(
            path("data"),
            "00000001/main" to AZAHAR_MAIN, "00000001.metadata" to AZAHAR_METADATA,
            "Z.bin" to byteArrayOf(1), "a/b/c.bin" to byteArrayOf(2),
        )
        Files.createDirectories(data.resolve("empty"))
        SaveZipper.zip(listOf(SaveRoot("data", data)), path("unit.zip"))
        assertThat(names(path("unit.zip"))).containsExactly(
            "data/00000001.metadata", "data/00000001/main", "data/Z.bin", "data/a/b/c.bin",
        ).inOrder()
    }

    /** Same files in, same bytes out: file times and the device's time zone don't leak in. */
    @Test fun theSameUnitAlwaysGivesTheSameBytes() {
        val data = azaharData()
        SaveZipper.zip(listOf(SaveRoot("data", data)), path("first.zip"))
        Files.setLastModifiedTime(data.resolve("00000001/main"), FileTime.fromMillis(0))
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            SaveZipper.zip(listOf(SaveRoot("data", data)), path("second.zip"))
        } finally {
            TimeZone.setDefault(zone)
        }
        assertThat(Files.readAllBytes(path("second.zip"))).isEqualTo(Files.readAllBytes(path("first.zip")))
        ZipFile.builder().setPath(path("first.zip")).get().use { zip ->
            for (entry in zip.entries) {
                assertWithMessage(entry.name).that(entry.generalPurposeBit.usesUTF8ForNames()).isTrue()
                assertWithMessage(entry.name).that(entry.extraFields.map { it.headerId.value }).isEmpty()
                assertWithMessage(entry.name).that(LocalDateTime.ofInstant(entry.lastModifiedTime.toInstant(), TimeZone.getDefault().toZoneId()))
                    .isEqualTo(LocalDateTime.of(2000, 1, 1, 0, 0))
            }
        }
    }

    /** Non-ASCII names survive (RomM decodes them as UTF-8 thanks to the flag). */
    @Test fun unicodeNamesRoundTrip() {
        val data = folderOf(path("data"), "Pokémon ～ 😀.sav" to byteArrayOf(7))
        SaveZipper.zip(listOf(SaveRoot("data", data)), path("unit.zip"))
        assertThat(names(path("unit.zip"))).containsExactly("data/Pokémon ～ 😀.sav")
    }

    @Test fun symlinksInAUnitAreNotFollowed() {
        val outside = folderOf(path("outside"), "secret" to byteArrayOf(1))
        val data = azaharData()
        Files.createSymbolicLink(data.resolve("link"), outside.resolve("secret"))
        Files.createSymbolicLink(data.resolve("dirlink"), outside)
        assertThat(SaveZipper.entries(listOf(SaveRoot("data", data))).map { it.name })
            .containsExactly("data/00000001.metadata", "data/00000001/main")
    }

    /**
     * A folder Loom can't read (or one that vanishes mid-walk) is an IOException, "try again
     * later", never an unchecked exception that would crash a sync or read as "no save".
     */
    @Test fun anUnreadableFolderInTheUnitIsAnIOException() {
        val data = azaharData()
        val locked = data.resolve("00000001")
        val before = Files.getPosixFilePermissions(locked)
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
        try {
            val unit = listOf(SaveRoot("data", data))
            assertThrows(IOException::class.java) { SaveHasher.hashUnit(unit) }
            assertThrows(IOException::class.java) { SaveZipper.zip(unit, path("unit.zip")) }
            assertThrows(IOException::class.java) { SaveZipper.entries(unit) }
        } finally {
            Files.setPosixFilePermissions(locked, before)
        }
    }

    @Test fun anEmptyUnitIsNeverArchived() {
        val empty = Files.createDirectories(path("data/00000001"))
        assertThrows(SaveArchiveException::class.java) { SaveZipper.zip(listOf(SaveRoot("data", empty.parent)), path("unit.zip")) }
    }

    @Test fun unzipRestoresTheUnitByteForByte() {
        SaveZipper.zip(listOf(SaveRoot("data", azaharData())), path("unit.zip"))
        val written = SaveZipper.unzip(path("unit.zip"), path("restore"), roots = setOf("data"))
        assertThat(written).containsExactly("data/00000001.metadata", "data/00000001/main")
        assertThat(Files.readAllBytes(path("restore/data/00000001/main"))).isEqualTo(AZAHAR_MAIN)
        assertThat(SaveHasher.hashUnit(listOf(SaveRoot("data", path("restore/data"))))).isEqualTo(AZAHAR_UNIT_HASH)
    }

    /** An Argosy 3DS archive: directory entries, plus extdata Loom doesn't restore. */
    @Test fun unzipTakesOnlyTheRootsAsked() {
        val zip = zipOf(
            path("argosy.zip"),
            "data/" to null, "data/00000001/" to null,
            "data/00000001/main" to AZAHAR_MAIN, "data/00000001.metadata" to AZAHAR_METADATA,
            "extdata/" to null, "extdata/user/save.dat" to byteArrayOf(9),
        )
        assertThat(SaveZipper.unzip(zip, path("restore"), roots = setOf("data")))
            .containsExactly("data/00000001/main", "data/00000001.metadata")
        assertThat(Files.exists(path("restore/extdata"))).isFalse()
    }

    @Test fun unzipNeedsAFreshFolderAndLeavesAnExistingOneAlone() {
        val zip = zipOf(path("unit.zip"), "data/main" to byteArrayOf(1))
        val existing = folderOf(path("restore"), "keep" to byteArrayOf(5))
        assertThrows(FileAlreadyExistsException::class.java) { SaveZipper.unzip(zip, existing, setOf("data")) }
        assertThat(Files.readAllBytes(existing.resolve("keep"))).isEqualTo(byteArrayOf(5))
    }

    @Test fun unzipRefusesNamesThatEscapeOrAreAmbiguous() {
        val unsafe = listOf(
            "data/../../evil", "../evil", "/etc/evil", "C:/evil", "data\\..\\evil", "data//main", "data/./main", "data/\u0000",
        )
        for ((i, name) in unsafe.withIndex()) {
            val zip = zipOf(path("bad$i.zip"), "data/main" to byteArrayOf(1), name to byteArrayOf(2))
            val dest = path("restore$i")
            assertThrows(name, SaveArchiveException::class.java) { SaveZipper.unzip(zip, dest, setOf("data")) }
            assertWithMessage("$name leaves no folder behind").that(Files.exists(dest)).isFalse()
        }
        assertWithMessage("nothing escaped").that(Files.exists(path("evil"))).isFalse()
    }

    /** Even outside the roots asked for: an archive with one bad name isn't trusted at all. */
    @Test fun unzipRefusesAnUnsafeNameUnderAnotherRoot() {
        val zip = zipOf(path("bad.zip"), "data/main" to byteArrayOf(1), "extdata/../../evil" to byteArrayOf(2))
        assertThrows(SaveArchiveException::class.java) { SaveZipper.unzip(zip, path("restore"), setOf("data")) }
    }

    @Test fun unzipRefusesDuplicateNamesAndSymlinks() {
        val twice = zipOf(path("twice.zip"), "data/main" to byteArrayOf(1), "data/main" to byteArrayOf(2))
        assertThrows(SaveArchiveException::class.java) { SaveZipper.unzip(twice, path("r1"), setOf("data")) }
        val link = zipWithSymlink(path("link.zip"), "data/main", "/storage/emulated/0/secret")
        assertThrows(SaveArchiveException::class.java) { SaveZipper.unzip(link, path("r2"), setOf("data")) }
        assertThat(Files.exists(path("r1"))).isFalse()
        assertThat(Files.exists(path("r2"))).isFalse()
    }

    @Test fun unzipRefusesAnArchiveWithNothingToRestore() {
        val elsewhere = zipOf(path("elsewhere.zip"), "main" to byteArrayOf(1), "data" to byteArrayOf(2), "extdata/x" to byteArrayOf(3))
        assertThrows(SaveArchiveException::class.java) { SaveZipper.unzip(elsewhere, path("restore"), setOf("data")) }
        assertThat(Files.exists(path("restore"))).isFalse()
    }

    /** One entry over the per-entry cap is refused as soon as it passes it. */
    @Test fun unzipRefusesAnEntryLargerThanASaveCanBe() {
        val zip = zipOf(path("big.zip"), "data/main" to ByteArray(100))
        assertThrows(SaveArchiveException::class.java) { SaveZipper.unzip(zip, path("restore"), setOf("data"), maxEntryBytes = 99) }
        assertThat(Files.exists(path("restore"))).isFalse()
        assertThat(SaveZipper.unzip(zip, path("restore"), setOf("data"), maxEntryBytes = 100)).containsExactly("data/main")
    }

    /** Many entries just under the per-entry cap stop at the unit's budget instead of filling the card. */
    @Test fun unzipStopsAtTheUnitsBudget() {
        val zip = zipOf(path("bomb.zip"), "data/a" to ByteArray(100), "data/b" to ByteArray(100), "data/c" to ByteArray(100))
        assertThrows(SaveArchiveException::class.java) {
            SaveZipper.unzip(zip, path("restore"), setOf("data"), maxEntryBytes = 100, maxTotalBytes = 200)
        }
        assertThat(Files.exists(path("restore"))).isFalse()
        assertThat(SaveZipper.unzip(zip, path("restore"), setOf("data"), maxEntryBytes = 100, maxTotalBytes = 300)).hasSize(3)
    }

    @Test fun unzipRefusesAnArchiveWithTooManyEntries() {
        val zip = zipOf(path("many.zip"), "data/a" to byteArrayOf(1), "data/b" to byteArrayOf(2), "extdata/c" to byteArrayOf(3))
        assertThrows(SaveArchiveException::class.java) { SaveZipper.unzip(zip, path("restore"), setOf("data"), maxEntries = 2) }
        assertThat(Files.exists(path("restore"))).isFalse()
        assertThat(SaveZipper.unzip(zip, path("restore"), setOf("data"), maxEntries = 3)).hasSize(2)
    }

    /**
     * The SD card ignores case: `main` and `MAIN` would land on one file, and the restored save
     * would differ from the archive RomM hashed with both entries.
     */
    @Test fun unzipRefusesNamesTheCardCantTellApart() {
        val cases = listOf(
            listOf("data/00000001/main", "data/00000001/MAIN"),
            listOf("data/main", "data/MAIN/x"),
            listOf("data/main", "data/main/x"),
        )
        for ((i, names) in cases.withIndex()) {
            val zip = zipOf(path("case$i.zip"), *names.map { it to byteArrayOf(i.toByte()) }.toTypedArray())
            val dest = path("restore$i")
            assertThrows(names.toString(), SaveArchiveException::class.java) { SaveZipper.unzip(zip, dest, setOf("data")) }
            assertWithMessage("$names leaves no folder behind").that(Files.exists(dest)).isFalse()
        }
    }

    /** A damaged entry is caught by its CRC while unpacking, before anything is restored. */
    @Test fun unzipRefusesADamagedEntry() {
        val zip = path("stored.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            val body = "a save that will be damaged".toByteArray()
            val crc = java.util.zip.CRC32().apply { update(body) }
            out.putNextEntry(java.util.zip.ZipEntry("data/main").apply {
                method = java.util.zip.ZipEntry.STORED; size = body.size.toLong(); compressedSize = size; this.crc = crc.value
            })
            out.write(body)
            out.closeEntry()
        }
        val bytes = Files.readAllBytes(zip)
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("damaged")
        bytes[at] = 'D'.code.toByte()
        Files.write(zip, bytes)
        assertThrows(SaveArchiveException::class.java) { SaveZipper.unzip(zip, path("restore"), setOf("data")) }
        assertThat(Files.exists(path("restore"))).isFalse()
    }
}
