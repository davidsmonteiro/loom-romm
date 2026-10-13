package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_MAIN
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_METADATA
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
            unpack = { archive, dest, roots ->
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

    private class ProcessDeath : Error("killed")
}
