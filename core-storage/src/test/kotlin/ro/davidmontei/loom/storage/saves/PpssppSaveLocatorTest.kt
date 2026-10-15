package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.storage.saves.SaveFixtures.folderOf
import ro.davidmontei.loom.storage.saves.SaveFixtures.sfoOf
import ro.davidmontei.loom.storage.saves.SaveFixtures.zipOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

class PpssppSaveLocatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun path(name: String): Path = tmp.root.toPath().resolve(name)

    /** The memory stick Loom's README sets up, and the SAVEDATA PPSSPP makes in it. */
    private val stick get() = path("ROMs/saves/PPSSPP")
    private val saveData get() = stick.resolve("PSP/SAVEDATA")

    private val ulus10064 = PspDiscId("ULUS10064")

    /** A PARAM.SFO as PPSSPP's savedata dialog writes it: the file list and params are what set it apart. */
    private fun saveSfo(folder: String) = sfoOf(
        "CATEGORY" to "MS",
        "PARENTAL_LEVEL" to 1,
        "SAVEDATA_DETAIL" to "Chapter 2",
        "SAVEDATA_DIRECTORY" to folder,
        "SAVEDATA_FILE_LIST" to ByteArray(3168) { it.toByte() },
        "SAVEDATA_PARAMS" to ByteArray(128) { 7 },
        "SAVEDATA_TITLE" to "Loom Test Game",
        "TITLE" to "Loom Test Game",
    )

    /** A PARAM.SFO as PPSSPP's game data install writes it (`PSPGamedataInstallDialog.cpp`): no file list, no params. */
    private fun installSfo(folder: String) = sfoOf(
        "CATEGORY" to "MS",
        "PARENTAL_LEVEL" to 1,
        "SAVEDATA_DETAIL" to "Installed data",
        "SAVEDATA_DIRECTORY" to folder,
        "SAVEDATA_TITLE" to "Loom Test Game",
        "TITLE" to "Loom Test Game",
    )

    private fun saveFolder(name: String, vararg files: Pair<String, ByteArray>): Path =
        folderOf(saveData.resolve(name), "PARAM.SFO" to saveSfo(name), *files)

    private fun installFolder(name: String): Path =
        folderOf(saveData.resolve(name), "PARAM.SFO" to installSfo(name), "DATA.DAT" to ByteArray(4096) { 1 })

    private fun unit(): SaveUnit.Prefixed = (PpssppSaveLocator.locate(saveData, ulus10064) as PpssppSaveLocation.Found).unit

    /** Every file and folder in SAVEDATA with its bytes (a folder's are null), work folders aside. */
    private fun saveDataTree(): Map<String, List<Byte>?> = Files.walk(saveData).use { walk ->
        walk.iterator().asSequence().filter { it != saveData }.map { saveData.relativize(it).joinToString("/") }
            .filterNot { it.startsWith(".loom-restore-") }
            .associateWith { name -> saveData.resolve(name).let { if (Files.isDirectory(it)) null else Files.readAllBytes(it).toList() } }
    }

    private fun pattern(size: Int, step: Int, start: Int) = ByteArray(size) { (it * step + start).toByte() }

    /** A stand-in PARAM.SFO for the hashed archive: what Python hashes must be easy to write out there too. */
    private val sfoStandIn = "\u0000PSF SAVEDATA_FILE_LIST SAVEDATA_PARAMS".toByteArray()

    /** An archive as Argosy writes one: each folder's directory entry, then its files. */
    private fun argosyArchive(vararg extra: Pair<String, ByteArray?>): Path = zipOf(
        path("argosy.zip"),
        "ULUS10064DATA00/" to null,
        "ULUS10064DATA00/DATA.BIN" to pattern(3000, 7, 1),
        "ULUS10064DATA00/ICON0.PNG" to pattern(500, 11, 2),
        "ULUS10064DATA00/PARAM.SFO" to sfoStandIn,
        "ULUS10064DATA01/" to null,
        "ULUS10064DATA01/DATA.BIN" to pattern(1200, 13, 3),
        "ULUS10064DATA01/PARAM.SFO" to sfoStandIn,
        *extra,
    )

    /**
     * RomM's hash of [argosyArchive]'s ULUS10064 folders, from Python:
     * md5("\n".join(f"{name}:{md5(bytes)}" for name in sorted(names))), with
     * `bytes((i * step + start) % 256 for i in range(size))` for each file.
     */
    private val argosyArchiveHash = "d9760f10aea00f00a1c997fc294d1194"

    // --- the memory stick ---

    @Test fun theMemoryStickCanBeChosenAtAnyOfItsLevels() {
        Files.createDirectories(saveData)
        for (chosen in listOf(stick, stick.resolve("PSP"), saveData, saveData.resolve("ULUS10064DATA00"))) {
            assertThat(PpssppSaveLocator.saveDataOf(chosen)).isEqualTo(saveData)
        }
        // A memory stick folder named PSP is the PSP folder itself, as PPSSPP takes it.
        val namedPsp = path("Memory/PSP")
        Files.createDirectories(namedPsp.resolve("SAVEDATA"))
        assertThat(PpssppSaveLocator.saveDataOf(namedPsp)).isEqualTo(namedPsp.resolve("SAVEDATA"))
    }

    /** Before PPSSPP has run with the folder: where it will make SAVEDATA. */
    @Test fun aMemoryStickPpssppHasntUsedPointsWhereItWillSave() {
        assertThat(PpssppSaveLocator.saveDataOf(stick)).isEqualTo(saveData)
        assertThat(PpssppSaveLocator.saveDataOf(path("Memory/psp"))).isEqualTo(path("Memory/psp/SAVEDATA"))
        assertThat(PpssppSaveLocator.locate(saveData, ulus10064)).isEqualTo(PpssppSaveLocation.NoSaveData)
    }

    @Test fun iniMarksTheMemoryStick() {
        Files.createDirectories(saveData)
        assertThat(PpssppSaveLocator.holdsPpssppData(stick)).isFalse()
        folderOf(stick.resolve("PSP/SYSTEM"), "ppsspp.ini" to "[General]\n".toByteArray())
        for (chosen in listOf(stick, stick.resolve("PSP"), saveData)) assertThat(PpssppSaveLocator.holdsPpssppData(chosen)).isTrue()

        folderOf(path("VR/PSP/SYSTEM"), "ppssppvr.ini" to byteArrayOf(1))
        assertThat(PpssppSaveLocator.holdsPpssppData(path("VR"))).isTrue()
        assertThat(PpssppSaveLocator.holdsPpssppData(path("Elsewhere"))).isFalse()
    }

    // --- a game's save folders ---

    @Test fun prefixMatchingIgnoresCaseAndOtherIds() {
        saveFolder("ULUS10064DATA00", "DATA.BIN" to byteArrayOf(1))
        saveFolder("ulus10064settings", "DATA.BIN" to byteArrayOf(2))
        saveFolder("ULUS10065DATA00", "DATA.BIN" to byteArrayOf(3))
        saveFolder("ULUS1006", "DATA.BIN" to byteArrayOf(4))
        Files.write(saveData.resolve("ULUS10064.txt"), byteArrayOf(5))
        Files.write(saveData.resolve(".nomedia"), ByteArray(0))

        assertThat(unit().roots.map { it.name }).containsExactly("ULUS10064DATA00", "ulus10064settings").inOrder()
        assertThat(unit().leftOut).isEmpty()
    }

    @Test fun rootsAreNamedAfterTheirFolders() {
        saveFolder("ULUS10064DATA00", "DATA.BIN" to byteArrayOf(1))
        saveFolder("ULUS10064DATA01", "DATA.BIN" to byteArrayOf(2))
        val unit = unit()
        assertThat(unit.roots).containsExactly(
            SaveRoot("ULUS10064DATA00", saveData.resolve("ULUS10064DATA00")),
            SaveRoot("ULUS10064DATA01", saveData.resolve("ULUS10064DATA01")),
        ).inOrder()
        assertThat(unit.prefix).isEqualTo("ULUS10064")
        assertThat(unit.path).isEqualTo("$saveData/ULUS10064*")
    }

    /** A game with no save on the card yet: its unit has no folder, and holds no save. */
    @Test fun aGameWithoutSavesHasAnEmptyUnit() {
        saveFolder("ULUS10065DATA00", "DATA.BIN" to byteArrayOf(3))
        assertThat(unit().roots).isEmpty()
        assertThat(unit().hash()).isNull()
    }

    @Test fun anInstallFolderIsLeftOut() {
        saveFolder("ULUS10064DATA00", "DATA.BIN" to byteArrayOf(1))
        installFolder("ULUS10064INSTALL")
        // Argosy's rule is a byte search, so an empty PARAM.SFO is installed data too.
        folderOf(saveData.resolve("ULUS10064EMPTY"), "PARAM.SFO" to ByteArray(0), "DATA.DAT" to byteArrayOf(1))

        assertThat(unit().roots.map { it.name }).containsExactly("ULUS10064DATA00")
        assertThat(unit().leftOut).containsExactly("ULUS10064INSTALL", "ULUS10064EMPTY")
        assertThat(PpssppSaveLocator.isGameDataInstall(saveData.resolve("ULUS10064INSTALL"))).isTrue()
        assertThat(PpssppSaveLocator.isGameDataInstall(saveData.resolve("ULUS10064DATA00"))).isFalse()
    }

    @Test fun aFolderWithoutAnSfoIsKept() {
        folderOf(saveData.resolve("ULUS10064DATA00"), "DATA.BIN" to byteArrayOf(1))
        assertThat(unit().roots.map { it.name }).containsExactly("ULUS10064DATA00")
    }

    @Test fun anSfoOver64KiBIsKept() {
        folderOf(saveData.resolve("ULUS10064BIG"), "PARAM.SFO" to installSfo("ULUS10064BIG").copyOf(65537))
        folderOf(saveData.resolve("ULUS10064MOST"), "PARAM.SFO" to installSfo("ULUS10064MOST").copyOf(65536))
        assertThat(unit().roots.map { it.name }).containsExactly("ULUS10064BIG")
        assertThat(unit().leftOut).containsExactly("ULUS10064MOST")
    }

    @Test fun anUnreadableSfoIsKept() {
        Files.createDirectories(saveData.resolve("ULUS10064DIR/PARAM.SFO"))
        val locked = folderOf(saveData.resolve("ULUS10064LOCKED"), "PARAM.SFO" to installSfo("ULUS10064LOCKED")).resolve("PARAM.SFO")
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
        assumeFalse("running as a user who reads anything", Files.isReadable(locked))

        assertThat(unit().roots.map { it.name }).containsExactly("ULUS10064DIR", "ULUS10064LOCKED")
    }

    /** What the owner picks a game's saves from: each disc ID once, its installed data aside, the latest played first. */
    @Test fun prefixesListEachDiscIdNewestFirst() {
        val old = Instant.parse("2026-01-01T00:00:00Z")
        val new = Instant.parse("2026-10-01T00:00:00Z")
        val a = saveFolder("ULUS10064DATA00", "DATA.BIN" to ByteArray(100))
        val b = saveFolder("ulus10064settings", "DATA.BIN" to ByteArray(10))
        val c = saveFolder("NPUZ00001DATA", "DATA.BIN" to ByteArray(5))
        installFolder("ULES00151INSTALL")
        saveFolder("HOMEBREW", "DATA.BIN" to byteArrayOf(1))
        for (folder in listOf(a, b)) Files.walk(folder).use { w -> w.forEach { Files.setLastModifiedTime(it, FileTime.from(old)) } }
        Files.walk(c).use { w -> w.forEach { Files.setLastModifiedTime(it, FileTime.from(new)) } }

        val prefixes = PpssppSaveLocator.prefixes(saveData)
        assertThat(prefixes.map { it.discId.value }).containsExactly("NPUZ00001", "ULUS10064").inOrder()
        val ulus = prefixes.single { it.discId == ulus10064 }
        assertThat(ulus.folderCount).isEqualTo(2)
        assertThat(ulus.fileCount).isEqualTo(4) // each folder's DATA.BIN and PARAM.SFO
        assertThat(ulus.bytes).isEqualTo(110L + saveSfo("ULUS10064DATA00").size + saveSfo("ulus10064settings").size)
        assertThat(ulus.newestModified).isEqualTo(old)
        assertThat(PpssppSaveLocator.prefixes(path("nowhere"))).isEmpty()
    }

    // --- restoring an archive ---

    @Test fun anArgosyArchiveWithFolderEntriesRestores() {
        saveFolder("ULUS10064DATA00", "DATA.BIN" to byteArrayOf(1))
        val unit = unit()
        val widened = PpssppSaveLocator.forRestore(unit, argosyArchive())
        val hash = SaveRestorer().restore(argosyArchive(), widened, unit.hash())

        assertThat(Files.readAllBytes(saveData.resolve("ULUS10064DATA00/DATA.BIN"))).isEqualTo(pattern(3000, 7, 1))
        assertThat(Files.readAllBytes(saveData.resolve("ULUS10064DATA01/DATA.BIN"))).isEqualTo(pattern(1200, 13, 3))
        assertThat(unit().hash()).isEqualTo(hash)
    }

    @Test fun anArchiveRootTheCardLacksJoinsTheUnit() {
        saveFolder("ULUS10064DATA00", "DATA.BIN" to byteArrayOf(1))
        saveFolder("ULUS10064DATA02", "DATA.BIN" to byteArrayOf(2))
        val widened = PpssppSaveLocator.forRestore(unit(), argosyArchive())
        assertThat(widened.roots).containsExactly(
            SaveRoot("ULUS10064DATA00", saveData.resolve("ULUS10064DATA00")),
            SaveRoot("ULUS10064DATA01", saveData.resolve("ULUS10064DATA01")),
            SaveRoot("ULUS10064DATA02", saveData.resolve("ULUS10064DATA02")),
        ).inOrder()

        SaveRestorer().restore(argosyArchive(), widened, unit().hash())
        // DATA02 isn't in the archive: emptied, then removed, as Argosy's restore deletes it.
        assertThat(Files.exists(saveData.resolve("ULUS10064DATA02"))).isFalse()
        assertThat(unit().roots.map { it.name }).containsExactly("ULUS10064DATA00", "ULUS10064DATA01")
    }

    @Test fun anotherGamesRootIsSkipped() {
        saveFolder("ULUS10065DATA00", "DATA.BIN" to byteArrayOf(5))
        val before = saveDataTree()
        val archive = argosyArchive("ULUS10065DATA00/" to null, "ULUS10065DATA00/DATA.BIN" to byteArrayOf(9))
        val widened = PpssppSaveLocator.forRestore(unit(), archive)
        assertThat(widened.roots.map { it.name }).containsExactly("ULUS10064DATA00", "ULUS10064DATA01")

        SaveRestorer().restore(archive, widened, null)
        assertThat(saveDataTree().filterKeys { it.startsWith("ULUS10065") }).isEqualTo(before)
    }

    @Test fun noRootOfThisGameIsRefused() {
        Files.createDirectories(saveData)
        val other = zipOf(path("other.zip"), "ULUS10065DATA00/DATA.BIN" to byteArrayOf(1))
        val flat = zipOf(path("flat.zip"), "DATA.BIN" to byteArrayOf(1))
        val onlyInstall = zipOf(path("install.zip"), "ULUS10064INSTALL/PARAM.SFO" to installSfo("ULUS10064INSTALL"))
        val raw = Files.write(path("save.bin"), byteArrayOf(1, 2, 3))
        for (archive in listOf(other, flat, onlyInstall, raw)) {
            assertThrows(archive.toString(), UnrestorableSaveException::class.java) { PpssppSaveLocator.forRestore(unit(), archive) }
        }
        val message = assertThrows(UnrestorableSaveException::class.java) { PpssppSaveLocator.forRestore(unit(), other) }.message
        assertThat(message).isEqualTo("RomM's save holds ULUS10065DATA00, not folders starting with ULUS10064; nothing was replaced")
    }

    /** The card keeps this game's installed data under that name: restoring a save there would replace a copy of the disc. */
    @Test fun aRootNamedLikeALocalInstallFolderIsRefused() {
        installFolder("ULUS10064INSTALL")
        val before = saveDataTree()
        for (sfo in listOf(saveSfo("ULUS10064INSTALL"), null)) {
            val archive = zipOf(path("archive.zip"), *listOfNotNull(
                "ULUS10064INSTALL/DATA.BIN" to byteArrayOf(1),
                sfo?.let { "ULUS10064INSTALL/PARAM.SFO" to it },
            ).toTypedArray())
            assertThrows(UnrestorableSaveException::class.java) { PpssppSaveLocator.forRestore(unit(), archive) }
        }
        assertThat(saveDataTree()).isEqualTo(before)
    }

    /** Argosy before v2.21 uploaded a game's installed data with its saves: it isn't restored, and the card's stays. */
    @Test fun installDataInAnOldArgosyArchiveIsNotRestored() {
        installFolder("ULUS10064INSTALL")
        val installed = saveDataTree().filterKeys { it.startsWith("ULUS10064INSTALL") }
        val archive = argosyArchive(
            "ULUS10064INSTALL/" to null,
            "ULUS10064INSTALL/PARAM.SFO" to installSfo("ULUS10064INSTALL"),
            "ULUS10064INSTALL/DATA.DAT" to ByteArray(4096) { 9 },
            "ULUS10064INSTALL2/PARAM.SFO" to installSfo("ULUS10064INSTALL2"),
        )
        val widened = PpssppSaveLocator.forRestore(unit(), archive)
        assertThat(widened.roots.map { it.name }).containsExactly("ULUS10064DATA00", "ULUS10064DATA01")

        SaveRestorer().restore(archive, widened, null)
        assertThat(saveDataTree().filterKeys { it.startsWith("ULUS10064INSTALL") }).isEqualTo(installed)
    }

    /** The other way round: the archive calls installed data what the card keeps as a save. Restoring it would replace the save with data that isn't one. */
    @Test fun installDataWhereTheCardKeepsASaveIsRefused() {
        saveFolder("ULUS10064DATA00", "DATA.BIN" to byteArrayOf(1))
        val archive = zipOf(path("swapped.zip"), "ULUS10064DATA00/PARAM.SFO" to installSfo("ULUS10064DATA00"), "ULUS10064DATA01/DATA.BIN" to byteArrayOf(1))
        assertThrows(UnrestorableSaveException::class.java) { PpssppSaveLocator.forRestore(unit(), archive) }
    }

    /** The SD card ignores case: two names differing only in case are one folder there. */
    @Test fun aRootDifferingOnlyInCaseIsRefused() {
        saveFolder("ULUS10064DATA00", "DATA.BIN" to byteArrayOf(1))
        val lower = zipOf(path("lower.zip"), "ulus10064data00/DATA.BIN" to byteArrayOf(2))
        assertThrows(UnrestorableSaveException::class.java) { PpssppSaveLocator.forRestore(unit(), lower) }

        val both = zipOf(path("both.zip"), "ULUS10064DATA01/DATA.BIN" to byteArrayOf(2), "ulus10064data01/OTHER.BIN" to byteArrayOf(3))
        assertThrows(UnrestorableSaveException::class.java) { PpssppSaveLocator.forRestore(unit(), both) }
    }

    @Test fun anArchiveUnpackingPastTheCapIsRefused() {
        Files.createDirectories(saveData)
        assertThat(PpssppSaveLocator.forRestore(unit(), argosyArchive(), maxBytes = 3000 + 500 + 1200 + 2L * sfoStandIn.size).roots).hasSize(2)
        val message = assertThrows(UnrestorableSaveException::class.java) {
            PpssppSaveLocator.forRestore(unit(), argosyArchive(), maxBytes = 4096)
        }.message
        assertThat(message).isEqualTo("RomM's save unpacks to ${formatBytes(4778)}, more than Loom restores (${formatBytes(4096)}); nothing was replaced")
    }

    /** Argosy appends its hardcore trailer to zips too: zip readers find the archive from its end, past it. */
    @Test fun aHardcoreTrailerAfterTheZipIsHarmless() {
        Files.createDirectories(saveData)
        val archive = argosyArchive()
        Files.write(archive, Files.readAllBytes(archive) + """{"h":true,"v":1}""".toByteArray() + byteArrayOf(16, 0, 0, 0) + "ARGOSY".toByteArray() + byteArrayOf(1, 0))
        val widened = PpssppSaveLocator.forRestore(unit(), archive)
        assertThat(SaveRestorer().restore(archive, widened, null)).isEqualTo(argosyArchiveHash)
    }

    @Test fun theRestoredUnitHashesAsPythonSays() {
        saveFolder("ULUS10064DATA00", "DATA.BIN" to byteArrayOf(1))
        val widened = PpssppSaveLocator.forRestore(unit(), argosyArchive("ULUS10065DATA00/DATA.BIN" to byteArrayOf(9)))
        SaveRestorer().restore(path("argosy.zip"), widened, unit().hash())
        assertThat(unit().hash()).isEqualTo(argosyArchiveHash)
        assertThat(SaveHasher.hashZip(path("argosy.zip"), setOf("ULUS10064DATA00", "ULUS10064DATA01"))).isEqualTo(argosyArchiveHash)
    }
}
