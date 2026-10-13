package ro.davidmontei.loom.storage.saves

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Puts a save archive in place of a unit's folders so that the save on disk is always either the
 * old one or the new one, checked, and never a mix:
 *
 * 1. The archive is unpacked into a work folder beside the unit (same card, so moves are renames)
 *    and checked against the archive's hash. Nothing in the unit has been touched yet.
 * 2. A journal listing the new files is written. Every old file is moved aside into the work
 *    folder, then every new file is moved into place, one rename per file.
 * 3. The unit is hashed again. Only when it matches the archive is the old copy dropped.
 *
 * Any failure in steps 2 and 3 moves the new files out and the old ones back. If Loom dies
 * midway, [recover] does the same from the journal the next time. The caller backs the unit up
 * with [SaveBackups] first; this is the second line of defence, not the only one.
 */
class SaveRestorer(
    /** Unpacks the archive into the work folder; a seam for tests of a card that loses writes. */
    private val unpack: (archive: Path, dest: Path, roots: Set<String>) -> Unit = { a, d, r -> SaveZipper.unzip(a, d, r) },
    private val mover: FileMover = FileMover.Rename,
) {

    /** Moves one file; the target never exists. A seam for tests that fail or die mid-restore. */
    fun interface FileMover {
        fun move(from: Path, to: Path)

        companion object {
            val Rename = FileMover { from, to -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) }
        }
    }

    /**
     * Replaces [unit] with what [archive] holds under the unit's root names and returns the
     * restored unit's hash. [expectedCurrent] is the hash the unit had when it was backed up (null
     * when it held no files); if it no longer matches, nothing is touched.
     *
     * @throws SaveChangedException when the unit changed since it was backed up.
     * @throws SaveArchiveException when the archive is unsafe, holds nothing for the unit, or the
     *   files don't come out as the archive says.
     * @throws IOException when the card can't be written. In every case the unit is left as it was.
     */
    fun restore(archive: Path, unit: List<SaveRoot>, expectedCurrent: String?): String {
        val parent = parentOf(unit)
        recover(unit)
        if (SaveHasher.hashUnit(unit) != expectedCurrent) throw SaveChangedException("The save changed since it was backed up")
        val names = unit.map { it.name }.toSet()
        val expected = SaveHasher.hashZip(archive, names)

        Files.createDirectories(parent)
        val work = Files.createDirectory(parent.resolve(WORK_PREFIX + UUID.randomUUID()))
        val staged = unit.map { SaveRoot(it.name, work.resolve(NEW).resolve(it.name)) }
        val incoming = try {
            unpack(archive, work.resolve(NEW), names)
            if (SaveHasher.hashUnit(staged) != expected) throw SaveArchiveException("The unpacked save doesn't match the archive")
            SaveZipper.entries(staged).onEach { FileChannel.open(it.file, StandardOpenOption.WRITE).use { c -> c.force(true) } }.map { it.name }
        } catch (e: Exception) {
            deleteTree(work)
            throw e
        }

        writeJournal(work, incoming)
        try {
            for (old in SaveZipper.entries(unit)) moveInto(old.file, work.resolve(OLD).resolve(old.name))
            for (name in incoming) moveInto(work.resolve(NEW).resolve(name), live(unit, name))
            if (SaveHasher.hashUnit(unit) != expected) throw SaveArchiveException("The restored save doesn't match the archive")
            Files.createFile(work.resolve(DONE))
        } catch (e: Exception) {
            try {
                rollBack(work, unit)
            } catch (undo: Exception) {
                throw IOException("Couldn't put the old save back; its files are kept in $work", undo).apply { addSuppressed(e) }
            }
            throw e
        }
        runCatching { deleteTree(work) } // a leftover finished work folder is removed by the next recover
        return expected
    }

    /**
     * Cleans up after a restore Loom didn't finish: a restore that died while swapping files is
     * undone, so the old save is back; any other leftover work folder is removed. Returns whether
     * there was anything to clean up.
     *
     * @throws IOException when the old files can't be put back; the work folder is then kept, so
     *   nothing is lost and the next attempt can finish the job.
     */
    fun recover(unit: List<SaveRoot>): Boolean {
        val parent = parentOf(unit)
        if (!parent.isDirectory(LinkOption.NOFOLLOW_LINKS)) return false
        val leftovers = checkedIo {
            Files.list(parent).use { list -> list.iterator().asSequence().filter { it.name.startsWith(WORK_PREFIX) }.toList() }
        }
        for (work in leftovers) {
            if (Files.exists(work.resolve(JOURNAL)) && !Files.exists(work.resolve(DONE))) rollBack(work, unit) else deleteTree(work)
        }
        return leftovers.isNotEmpty()
    }

    /** Takes the new files that made it into place back out, and moves every old file back. */
    private fun rollBack(work: Path, unit: List<SaveRoot>) {
        for (name in readJournal(work)) {
            val live = live(unit, name)
            if (!Files.exists(work.resolve(NEW).resolve(name), LinkOption.NOFOLLOW_LINKS) && Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                moveInto(live, work.resolve(ROLLED_BACK).resolve(name))
            }
        }
        val old = work.resolve(OLD)
        if (old.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            val aside = checkedIo { Files.walk(old).use { walk -> walk.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList() } }
            for (file in aside) moveInto(file, live(unit, old.relativize(file).joinToString("/") { it.name }))
        }
        deleteTree(work)
    }

    private fun moveInto(from: Path, to: Path) {
        Files.createDirectories(to.parent)
        mover.move(from, to)
    }

    /** Where the file named [name] ("data/00000001/main") lives in the unit. */
    private fun live(unit: List<SaveRoot>, name: String): Path {
        val root = unit.firstOrNull { it.name == SaveZipper.rootOf(name) } ?: throw SaveArchiveException("No root for $name")
        return root.folder.resolve(name.substringAfter('/'))
    }

    private fun writeJournal(work: Path, incoming: List<String>) {
        val part = work.resolve("$JOURNAL.part")
        Files.write(part, incoming.joinToString("\n").toByteArray(Charsets.UTF_8))
        FileChannel.open(part, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(part, work.resolve(JOURNAL), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readJournal(work: Path): List<String> {
        val journal = work.resolve(JOURNAL)
        if (!Files.exists(journal)) return emptyList()
        return String(Files.readAllBytes(journal), Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }

    private fun parentOf(unit: List<SaveRoot>): Path {
        require(unit.isNotEmpty()) { "A save unit has at least one folder" }
        val parent = unit.first().folder.toAbsolutePath().parent
        require(unit.all { it.folder.toAbsolutePath().parent == parent }) { "A unit's folders must share one parent to be restored" }
        return parent
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        checkedIo { Files.walk(path).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }

    companion object {
        /** Work folders beside a unit: dot-named, so Cocoon and the emulator's own listing skip them. */
        const val WORK_PREFIX = ".loom-restore-"
        private const val NEW = "new"
        private const val OLD = "old"
        private const val ROLLED_BACK = "rolled-back"
        private const val JOURNAL = "journal"
        private const val DONE = "done"
    }
}
