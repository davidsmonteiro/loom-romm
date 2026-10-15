package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_MAIN
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_METADATA
import ro.davidmontei.loom.storage.saves.SaveFixtures.RAW_SAVE
import ro.davidmontei.loom.storage.saves.SaveFixtures.RAW_SAVE_MD5
import ro.davidmontei.loom.storage.saves.SaveFixtures.folderOf
import ro.davidmontei.loom.storage.saves.SaveFixtures.zipOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class SaveRestorerTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun path(name: String): Path = tmp.root.toPath().resolve(name)

    /** Omega Ruby's title folder: `data` is the unit; a second slot file the server copy lacks. */
    private val title get() = path("title/00040000/0011c400")
    private fun localSave(): List<SaveRoot> {
        val data = folderOf(title.resolve("data"), "00000001/main" to AZAHAR_MAIN, "00000001.metadata" to AZAHAR_METADATA,
            "00000002/main" to byteArrayOf(2, 2))
        return listOf(SaveRoot("data", data))
    }

    private val serverMain = ByteArray(2048) { (it % 7).toByte() }
    private fun serverArchive(): Path = zipOf(path("server.zip"),
        "data/00000001/main" to serverMain, "data/00000001.metadata" to AZAHAR_METADATA, "data/00000003/main" to byteArrayOf(3),
        "extdata/000011C5/user" to byteArrayOf(9))

    /** Every file of the unit with its bytes, to compare a unit before and after. */
    private fun contents(unit: List<SaveRoot>): Map<String, List<Byte>> =
        SaveZipper.entries(unit).associate { it.name to Files.readAllBytes(it.file).toList() }

    private fun workFolders(): List<Path> = Files.list(title).use { l -> l.iterator().asSequence().filter { it.fileName.toString().startsWith(".loom-restore-") }.toList() }

    @Test fun replacesTheUnitWithTheArchivesFiles() {
        val unit = localSave()
        val archive = serverArchive()
        val restoredHash = SaveRestorer().restore(archive, unit, SaveHasher.hashUnit(unit))

        assertThat(contents(unit).keys).containsExactly("data/00000001/main", "data/00000001.metadata", "data/00000003/main")
        assertThat(Files.readAllBytes(title.resolve("data/00000001/main"))).isEqualTo(serverMain)
        assertThat(restoredHash).isEqualTo(SaveHasher.hashZip(archive, setOf("data")))
        assertThat(restoredHash).isEqualTo(SaveHasher.hashUnit(unit))
        assertThat(Files.exists(title.resolve("extdata"))).isFalse() // only the unit's roots
        assertThat(workFolders()).isEmpty()
    }

    /** A game that never saved on this device: the folder is created. */
    @Test fun restoresWhereThereWasNoSaveYet() {
        val unit = listOf(SaveRoot("data", path("title/00040000/00055d00/data")))
        val hash = SaveRestorer().restore(serverArchive(), unit, expectedCurrent = null)
        assertThat(SaveHasher.hashUnit(unit)).isEqualTo(hash)
    }

    @Test fun refusesWhenTheSaveChangedSinceItWasBackedUp() {
        val unit = localSave()
        val before = contents(unit)
        val backedUp = SaveHasher.hashUnit(unit)
        Files.write(title.resolve("data/00000001/main"), byteArrayOf(1)) // the emulator wrote meanwhile
        val now = contents(unit)
        assertThrows(SaveChangedException::class.java) { SaveRestorer().restore(serverArchive(), unit, backedUp) }
        assertThat(contents(unit)).isEqualTo(now)
        assertThat(now).isNotEqualTo(before)
        assertThrows(SaveChangedException::class.java) { SaveRestorer().restore(serverArchive(), unit, expectedCurrent = null) }
        assertThat(workFolders()).isEmpty()
    }

    @Test fun anArchiveWithNothingForTheUnitLeavesItAlone() {
        val unit = localSave()
        val before = contents(unit)
        val foreign = zipOf(path("foreign.zip"), "Pokemon X.sav" to byteArrayOf(1, 2, 3))
        assertThrows(SaveArchiveException::class.java) { SaveRestorer().restore(foreign, unit, SaveHasher.hashUnit(unit)) }
        assertThat(contents(unit)).isEqualTo(before)
        assertThat(workFolders()).isEmpty()
    }

    /** The card fails halfway through the swap: the old save is put back, file for file. */
    @Test fun aFailureMidSwapPutsTheOldSaveBack() {
        for (failAt in 1..6) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            val unit = localSave()
            val before = contents(unit)
            var moves = 0
            val failing = SaveRestorer { from, to ->
                if (++moves == failAt) throw IOException("card removed")
                SaveRestorer.FileMover.Rename.move(from, to)
            }
            assertThrows("failing move $failAt", IOException::class.java) { failing.restore(serverArchive(), unit, SaveHasher.hashUnit(unit)) }
            assertThat(contents(unit)).isEqualTo(before)
            assertThat(workFolders()).isEmpty()
        }
    }

    /** Loom dies halfway through the swap: the next restore or sync undoes it from the journal. */
    @Test fun aDeathMidSwapIsUndoneByRecover() {
        for (dieAt in 1..6) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            val unit = localSave()
            val before = contents(unit)
            var moves = 0
            val dying = SaveRestorer { from, to ->
                if (++moves == dieAt) throw ProcessDeath()
                SaveRestorer.FileMover.Rename.move(from, to)
            }
            assertThrows(ProcessDeath::class.java) { dying.restore(serverArchive(), unit, SaveHasher.hashUnit(unit)) }
            assertThat(workFolders()).hasSize(1)

            assertThat(SaveRestorer().recover(unit)).isTrue()
            assertThat(contents(unit)).isEqualTo(before)
            assertThat(workFolders()).isEmpty()
        }
    }

    /**
     * The card fails mid-swap and again while the old save is being put back (a flaky or pulled
     * card): the restore gives up with the old files half back, and the next recover finishes
     * putting them back without mistaking the ones already back for new files.
     */
    @Test fun aRollbackThatFailsMidwayIsFinishedByRecover() {
        for (failAt in 1..6) for (failAgainAt in failAt + 1..failAt + 6) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            val unit = localSave()
            val before = contents(unit)
            var moves = 0
            val flaky = SaveRestorer { from, to ->
                if (++moves == failAt || moves == failAgainAt) throw IOException("card removed")
                SaveRestorer.FileMover.Rename.move(from, to)
            }
            assertThrows("failing moves $failAt and $failAgainAt", IOException::class.java) {
                flaky.restore(serverArchive(), unit, SaveHasher.hashUnit(unit))
            }

            SaveRestorer().recover(unit)
            assertThat(contents(unit)).isEqualTo(before)
            assertThat(workFolders()).isEmpty()
        }
    }

    /**
     * Eden keeps every game's save folder in one profile folder, so a restore Loom died in leaves
     * its work folder where another game's restore looks too. Whichever game is read first undoes
     * it, instead of failing to read its own save until the other game is read.
     */
    @Test fun anotherGamesUnfinishedRestoreInTheSameFolderIsUndone() {
        val profile = path("user/save/0000000000000000/4E431822D6DEFD527268BA35B5C09533")
        for (dieAt in 1..4) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            val wonder = listOf(SaveRoot("0100A0B00C0D0000", folderOf(profile.resolve("0100A0B00C0D0000"), "game_data.sav" to byteArrayOf(1))))
            val arceus = listOf(SaveRoot("01002B300C1D2000",
                folderOf(profile.resolve("01002B300C1D2000"), "game_data.sav" to byteArrayOf(2), "option_data.sav" to byteArrayOf(3))))
            val before = contents(wonder + arceus)
            val archive = zipOf(path("arceus.zip"), "01002B300C1D2000/game_data.sav" to byteArrayOf(9), "01002B300C1D2000/new.sav" to byteArrayOf(8))
            var moves = 0
            val dying = SaveRestorer { from, to ->
                if (++moves == dieAt) throw ProcessDeath()
                SaveRestorer.FileMover.Rename.move(from, to)
            }
            assertThrows(ProcessDeath::class.java) { dying.restore(archive, arceus, SaveHasher.hashUnit(arceus)) }

            assertThat(SaveRestorer().recover(wonder)).isTrue()
            assertThat(contents(wonder + arceus)).isEqualTo(before)
            assertThat(Files.list(profile).use { l -> l.iterator().asSequence().map { it.fileName.toString() }.toList() })
                .containsExactly("0100A0B00C0D0000", "01002B300C1D2000")
        }
    }

    /** A move never replaces a file: a mistake fails instead of overwriting part of a save. */
    @Test fun aMoveRefusesATargetThatExists() {
        val from = folderOf(path("a"), "main" to byteArrayOf(1)).resolve("main")
        val to = folderOf(path("b"), "main" to byteArrayOf(2)).resolve("main")
        assertThrows(IOException::class.java) { SaveRestorer.FileMover.Rename.move(from, to) }
        assertThat(Files.readAllBytes(from)).isEqualTo(byteArrayOf(1))
        assertThat(Files.readAllBytes(to)).isEqualTo(byteArrayOf(2))
    }

    /**
     * A work folder still holding old files is never just deleted: without a finished restore
     * they are the save, so recover puts them back, and keeps them where it can't.
     */
    @Test fun recoverNeverDeletesOldFilesItCouldntPutBack() {
        val unit = localSave()
        val before = contents(unit)
        Files.delete(title.resolve("data/00000002/main"))
        folderOf(title.resolve(".loom-restore-x"), "old/data/00000002/main" to byteArrayOf(2, 2)) // its journal is gone
        assertThat(SaveRestorer().recover(unit)).isTrue()
        assertThat(contents(unit)).isEqualTo(before)
        assertThat(workFolders()).isEmpty()

        folderOf(title.resolve(".loom-restore-y"), "old/data/00000002/main" to byteArrayOf(7))
        assertThrows(IOException::class.java) { SaveRestorer().recover(unit) }
        assertThat(contents(unit)).isEqualTo(before)
        assertThat(Files.readAllBytes(title.resolve(".loom-restore-y/old/data/00000002/main"))).isEqualTo(byteArrayOf(7))
    }

    /** The journal holds one name per line, so a name with a line break is refused before any move. */
    @Test fun aNameTheJournalCantHoldIsRefused() {
        val unit = localSave()
        val before = contents(unit)
        val odd = zipOf(path("odd.zip"), "data/00000001/ma\nin" to byteArrayOf(1))
        assertThrows(SaveArchiveException::class.java) { SaveRestorer().restore(odd, unit, SaveHasher.hashUnit(unit)) }
        assertThat(contents(unit)).isEqualTo(before)
        assertThat(workFolders()).isEmpty()
    }

    /** A swap that completes but doesn't hash as the archive says (a card that loses writes) is undone. */
    @Test fun aRestoreThatDoesntCheckOutIsUndone() {
        val unit = localSave()
        val before = contents(unit)
        val lossy = SaveRestorer { from, to ->
            SaveRestorer.FileMover.Rename.move(from, to)
            val incoming = from.parent.parent.parent.fileName.toString() == "new" // new/data/00000001/main
            if (incoming && to.fileName.toString() == "main") Files.write(to, byteArrayOf(0))
        }
        assertThrows(SaveArchiveException::class.java) { lossy.restore(serverArchive(), unit, SaveHasher.hashUnit(unit)) }
        assertThat(contents(unit)).isEqualTo(before)
        assertThat(workFolders()).isEmpty()
    }

    /** Files that don't unpack as the archive says are caught before the save is touched at all. */
    @Test fun aBadUnpackNeverTouchesTheSave() {
        val unit = localSave()
        val before = contents(unit)
        var moves = 0
        val lossy = SaveRestorer(
            unpack = { archive, dest, roots, _ ->
                SaveZipper.unzip(archive, dest, roots)
                Files.write(dest.resolve("data/00000001/main"), byteArrayOf(0))
            },
            mover = { from, to -> moves++; SaveRestorer.FileMover.Rename.move(from, to) },
        )
        assertThrows(SaveArchiveException::class.java) { lossy.restore(serverArchive(), unit, SaveHasher.hashUnit(unit)) }
        assertThat(moves).isEqualTo(0)
        assertThat(contents(unit)).isEqualTo(before)
        assertThat(workFolders()).isEmpty()
    }

    /** A restore that finished but died before cleaning up keeps the new save. */
    @Test fun aFinishedRestoresLeftoversAreJustRemoved() {
        val unit = localSave()
        val before = contents(unit)
        folderOf(title.resolve(".loom-restore-x"), "journal" to "data/00000001/main".toByteArray(), "done" to byteArrayOf(),
            "old/data/00000001/main" to byteArrayOf(5))
        folderOf(title.resolve(".loom-restore-y"), "new/data/00000001/main" to byteArrayOf(6)) // died while unpacking
        assertThat(SaveRestorer().recover(unit)).isTrue()
        assertThat(contents(unit)).isEqualTo(before)
        assertThat(workFolders()).isEmpty()
        assertThat(SaveRestorer().recover(unit)).isFalse()
    }

    // --- one raw file: a DS game's .sav, in the one melonDS folder every game saves in ---

    private val melonDs get() = path("saves/melonDS")

    /** Mario Kart DS's save holding [bytes]; null for no file at all. */
    private fun rawSave(bytes: ByteArray? = byteArrayOf(1, 2, 3)): SaveUnit.SingleFile {
        val file = Files.createDirectories(melonDs).resolve("Mario Kart DS (USA).sav")
        if (bytes != null) Files.write(file, bytes)
        return SaveUnit.SingleFile(file)
    }

    private fun serverRaw(bytes: ByteArray = RAW_SAVE): Path = path("server.sav").also { Files.write(it, bytes) }

    private fun melonDsFiles(): List<String> = Files.list(melonDs).use { l -> l.iterator().asSequence().map { it.fileName.toString() }.sorted().toList() }

    @Test fun replacesARawSave() {
        val unit = rawSave()
        val restoredHash = SaveRestorer().restore(serverRaw(), unit, unit.hash())

        assertThat(Files.readAllBytes(unit.file)).isEqualTo(RAW_SAVE)
        assertThat(restoredHash).isEqualTo(RAW_SAVE_MD5)
        assertThat(unit.hash()).isEqualTo(RAW_SAVE_MD5)
        assertThat(melonDsFiles()).containsExactly("Mario Kart DS (USA).sav") // no work folder left
    }

    /** melonDS's empty placeholder is no save, so the restore expects none, and still puts the save in its place. */
    @Test fun replacesMelonDsEmptyPlaceholder() {
        val unit = rawSave(ByteArray(0))
        assertThat(SaveRestorer().restore(serverRaw(), unit, expectedCurrent = null)).isEqualTo(RAW_SAVE_MD5)
        assertThat(Files.readAllBytes(unit.file)).isEqualTo(RAW_SAVE)
        assertThat(melonDsFiles()).containsExactly("Mario Kart DS (USA).sav")
    }

    @Test fun restoresWhereThereWasNoFile() {
        val unit = rawSave(bytes = null)
        assertThat(SaveRestorer().restore(serverRaw(), unit, expectedCurrent = null)).isEqualTo(RAW_SAVE_MD5)
        assertThat(Files.readAllBytes(unit.file)).isEqualTo(RAW_SAVE)
        assertThat(melonDsFiles()).containsExactly("Mario Kart DS (USA).sav")
    }

    @Test fun refusesWhenTheFileChangedSinceItWasBackedUp() {
        val unit = rawSave()
        val backedUp = unit.hash()
        Files.write(unit.file, byteArrayOf(4, 5)) // melonDS wrote meanwhile
        assertThrows(SaveChangedException::class.java) { SaveRestorer().restore(serverRaw(), unit, backedUp) }
        assertThat(Files.readAllBytes(unit.file)).isEqualTo(byteArrayOf(4, 5))

        Files.write(unit.file, ByteArray(0))
        val placeholder = unit.hash()
        Files.write(unit.file, byteArrayOf(6)) // the game's first save, over the placeholder
        assertThrows(SaveChangedException::class.java) { SaveRestorer().restore(serverRaw(), unit, placeholder) }
        assertThat(Files.readAllBytes(unit.file)).isEqualTo(byteArrayOf(6))
        assertThat(melonDsFiles()).containsExactly("Mario Kart DS (USA).sav")
    }

    /** An empty download is no save to restore, and one larger than the emulator's saves can be isn't one either. */
    @Test fun anEmptyOrOversizedRawSaveIsRefused() {
        val unit = rawSave()
        assertThrows(SaveArchiveException::class.java) { SaveRestorer().restore(serverRaw(ByteArray(0)), unit, unit.hash()) }
        assertThrows(SaveArchiveException::class.java) { SaveRestorer().restore(serverRaw(), unit, unit.hash(), maxUnitBytes = 1024) }
        assertThat(Files.readAllBytes(unit.file)).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(melonDsFiles()).containsExactly("Mario Kart DS (USA).sav")
    }

    /** The card fails halfway through the swap: the old file is put back, the empty placeholder too. */
    @Test fun aFailureMidSwapPutsTheOldFileBack() {
        for (old in listOf(byteArrayOf(1, 2, 3), ByteArray(0))) for (failAt in 1..2) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            val unit = rawSave(old)
            var moves = 0
            val failing = SaveRestorer { from, to ->
                if (++moves == failAt) throw IOException("card removed")
                SaveRestorer.FileMover.Rename.move(from, to)
            }
            assertThrows("failing move $failAt", IOException::class.java) { failing.restore(serverRaw(), unit, unit.hash()) }
            assertThat(Files.readAllBytes(unit.file)).isEqualTo(old)
            assertThat(melonDsFiles()).containsExactly("Mario Kart DS (USA).sav")
        }
    }

    /**
     * Loom dies halfway through the swap: the next look undoes it, whether it is at this game's
     * save or another's in the same folder.
     */
    @Test fun aDeathMidSwapOfARawSaveIsUndoneByRecover() {
        for (dieAt in 1..2) for (byOther in listOf(false, true)) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            val unit = rawSave()
            val other = SaveUnit.SingleFile(melonDs.resolve("Tetris DS (USA).sav").also { Files.write(it, byteArrayOf(7)) })
            var moves = 0
            val dying = SaveRestorer { from, to ->
                if (++moves == dieAt) throw ProcessDeath()
                SaveRestorer.FileMover.Rename.move(from, to)
            }
            assertThrows(ProcessDeath::class.java) { dying.restore(serverRaw(), unit, unit.hash()) }
            assertThat(melonDsFiles().filter { it.startsWith(".loom-restore-") }).hasSize(1)

            assertThat(SaveRestorer().recover(if (byOther) other else unit)).isTrue()
            assertThat(Files.readAllBytes(unit.file)).isEqualTo(byteArrayOf(1, 2, 3))
            assertThat(Files.readAllBytes(other.file)).isEqualTo(byteArrayOf(7))
            assertThat(melonDsFiles()).containsExactly("Mario Kart DS (USA).sav", "Tetris DS (USA).sav")
        }
    }

    /** A swap that completes but doesn't hash as the download (a card that loses writes) is undone. */
    @Test fun aRawRestoreThatDoesntCheckOutIsUndone() {
        val unit = rawSave()
        val lossy = SaveRestorer { from, to ->
            SaveRestorer.FileMover.Rename.move(from, to)
            if (from.parent.fileName.toString() == "new") Files.write(to, byteArrayOf(0)) // new/Mario Kart DS (USA).sav
        }
        assertThrows(SaveArchiveException::class.java) { lossy.restore(serverRaw(), unit, unit.hash()) }
        assertThat(Files.readAllBytes(unit.file)).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(melonDsFiles()).containsExactly("Mario Kart DS (USA).sav")
    }

    private class ProcessDeath : Error("killed")
}
