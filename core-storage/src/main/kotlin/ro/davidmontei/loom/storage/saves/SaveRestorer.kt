package ro.davidmontei.loom.storage.saves

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Puts a save archive in place of a unit's folders, or a raw save in place of a single-file unit,
 * so that the save on disk is always either the old one or the new one, checked, and never a mix:
 *
 * 1. The archive is unpacked (a raw save copied) into a work folder beside the unit (same card, so
 *    moves are renames) and checked against its hash. Nothing in the unit has been touched yet.
 * 2. A journal listing the new files and the old ones is written. Every old file is moved aside
 *    into the work folder, then every new file is moved into place, one rename per file.
 * 3. The unit is hashed again. Only when it matches the archive is the old copy dropped.
 *
 * Any failure in steps 2 and 3 moves the new files out and the old ones back. If Loom dies
 * midway, or putting the old files back fails too, [recover] does the same from the journal the
 * next time. No move ever replaces a file. The caller backs the unit up
 * with [SaveBackups] first; this is the second line of defence, not the only one.
 */
class SaveRestorer(
    /** Unpacks the archive into the work folder, at most maxBytes; a seam for tests of a card that loses writes. */
    private val unpack: (archive: Path, dest: Path, roots: Set<String>, maxBytes: Long) -> Unit =
        { a, d, r, max -> SaveZipper.unzip(a, d, r, maxTotalBytes = max) },
    private val mover: FileMover = FileMover.Rename,
) {

    /** Moves one file; the target never exists. A seam for tests that fail or die mid-restore. */
    fun interface FileMover {
        fun move(from: Path, to: Path)

        companion object {
            /**
             * A rename that refuses a target that exists: rename(2) would silently replace it, so a
             * mistake would overwrite part of a save instead of failing.
             */
            val Rename = FileMover { from, to ->
                if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(to.toString())
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    /**
     * Replaces [unit] with what [archive] holds under the unit's root names, or for a
     * [SaveUnit.SingleFile] with [archive] itself, a raw save, and returns the restored unit's hash.
     * [expectedCurrent] is the hash the unit had when it was backed up (null when it held no save);
     * if it no longer matches, nothing is touched. The archive may unpack to at most [maxUnitBytes].
     *
     * @throws SaveChangedException when the unit changed since it was backed up.
     * @throws SaveArchiveException when the archive is unsafe, holds nothing for the unit (a raw
     *   save: is empty), or the files don't come out as the archive says.
     * @throws IOException when the card can't be written. In every case the unit is left as it was.
     */
    fun restore(archive: Path, unit: SaveUnit, expectedCurrent: String?, maxUnitBytes: Long = SaveZipper.MAX_UNIT_BYTES): String {
        val parent = parentOf(unit)
        recover(unit)
        if (unit.hash() != expectedCurrent) throw SaveChangedException("The save changed since it was backed up")
        val expected = hashOnceRestored(archive, unit)

        Files.createDirectories(parent)
        val work = Files.createDirectory(parent.resolve(WORK_PREFIX + UUID.randomUUID()))
        val incoming = try {
            stage(archive, unit, work.resolve(NEW), expected, maxUnitBytes)
        } catch (e: Exception) {
            deleteTree(work)
            throw e
        }

        val outgoing = try {
            filesOf(unit).also { writeJournal(work, Journal(incoming, it.map { e -> e.name }.toSet())) }
        } catch (e: Exception) {
            deleteTree(work)
            throw e
        }
        try {
            for (old in outgoing) moveInto(old.file, work.resolve(OLD).resolve(old.name))
            for (name in incoming) moveInto(work.resolve(NEW).resolve(name), live(unit, name))
            if (unit.hash() != expected) throw SaveArchiveException("The restored save doesn't match the archive")
            Files.createFile(work.resolve(DONE))
        } catch (e: Exception) {
            try {
                rollBack(work, unit)
            } catch (undo: Exception) {
                throw IOException("Couldn't put the old save back; its files are kept in $work", undo).apply { addSuppressed(e) }
            }
            throw e
        }
        // A folder the archive lacks was emptied as its old files moved aside: the emulator would list it.
        if (unit is SaveUnit.Prefixed) runCatching { removeEmpty(unit.roots.map { it.folder }) }
        runCatching { deleteTree(work) } // a leftover finished work folder is removed by the next recover
        return expected
    }

    /**
     * The hash [unit] has once [archive] is restored in its place, as [restore] checks and returns
     * it: only the archive's files under the unit's roots count; a raw save is all of it.
     *
     * @throws IOException when the archive can't be read, or isn't a zip where the unit needs one.
     */
    fun hashOnceRestored(archive: Path, unit: SaveUnit): String = when (unit) {
        is SaveUnit.Folders -> SaveHasher.hashZip(archive, unit.roots.map { it.name }.toSet())
        is SaveUnit.Prefixed -> SaveHasher.hashZip(archive, unit.roots.map { it.name }.toSet())
        is SaveUnit.SingleFile -> SaveHasher.md5(archive)
    }

    /** [restore] for a unit of folders. */
    fun restore(archive: Path, unit: List<SaveRoot>, expectedCurrent: String?, maxUnitBytes: Long = SaveZipper.MAX_UNIT_BYTES): String =
        restore(archive, SaveUnit.Folders(unit), expectedCurrent, maxUnitBytes)

    /**
     * Cleans up after a restore Loom didn't finish: a restore that died or failed while swapping
     * files is undone, so the old save is back; any other leftover work folder is removed. Old
     * files a work folder still holds are only dropped once their restore finished. Returns whether
     * there was anything to clean up.
     *
     * A leftover of another game's restore, in a folder several games share (melonDS's, PPSSPP's
     * `SAVEDATA`, Eden's profile), is undone too, but one that can't be is that game's to report:
     * none of this unit's files are in it, so it never stops this one.
     *
     * @throws IOException when this unit's old files can't be put back; the work folder is then
     *   kept, so nothing is lost and the next attempt can finish the job. The message names it.
     */
    fun recover(unit: SaveUnit): Boolean {
        val parent = parentOf(unit)
        if (!parent.isDirectory(LinkOption.NOFOLLOW_LINKS)) return false
        val leftovers = checkedIo {
            Files.list(parent).use { list -> list.iterator().asSequence().filter { it.name.startsWith(WORK_PREFIX) }.toList() }
        }
        for (work in leftovers) {
            val unfinished = !Files.exists(work.resolve(DONE)) && (Files.exists(work.resolve(JOURNAL)) || holdsFiles(work.resolve(OLD)))
            try {
                if (unfinished) rollBack(work, unit) else deleteTree(work)
            } catch (e: Exception) {
                if (!namesFilesOf(work, unit)) continue
                if (!unfinished) throw e
                throw IOException("Loom couldn't undo a restore it didn't finish: its files are kept in $work", e)
            }
        }
        return leftovers.isNotEmpty()
    }

    /** Whether [work] names a file of [unit]'s, or can't be read to tell. */
    private fun namesFilesOf(work: Path, unit: SaveUnit): Boolean = try {
        val old = work.resolve(OLD)
        val aside = if (!old.isDirectory(LinkOption.NOFOLLOW_LINKS)) emptyList() else checkedIo {
            Files.walk(old).use { walk -> walk.iterator().asSequence().filter { it != old }.map { old.relativize(it).joinToString("/") { p -> p.name } }.toList() }
        }
        val journal = readJournal(work)
        (journal.incoming + journal.outgoing + aside).any { name ->
            when (unit) {
                is SaveUnit.SingleFile -> name.equals(unit.name, ignoreCase = true)
                is SaveUnit.Prefixed -> SaveZipper.rootOf(name)?.startsWith(unit.prefix, ignoreCase = true) == true
                is SaveUnit.Folders -> unit.roots.any { it.name.equals(SaveZipper.rootOf(name), ignoreCase = true) }
            }
        }
    } catch (e: IOException) {
        true
    }

    /** [recover] for a unit of folders. */
    fun recover(unit: List<SaveRoot>): Boolean = recover(SaveUnit.Folders(unit))

    /**
     * Takes the new files that made it into place back out, and moves every old file back. Safe to
     * run again after it stopped midway: a live file whose new copy left `new/` is the new one only
     * while its old namesake is still aside in `old/`; once that is gone, it was already put back.
     * Among prefixed folders, those that held no file before the restore are removed once empty
     * again, as the restore made them.
     *
     * A single file's old save may find an empty file in its place: melonDS makes one for a game
     * run with no save, as it was while the old one was aside. That placeholder moves aside in
     * turn. An empty old file, a placeholder itself, gives way to whatever file is there now.
     */
    private fun rollBack(work: Path, unit: SaveUnit) {
        val journal = readJournal(work)
        for (name in journal.incoming) {
            val live = live(unit, name)
            val putIn = !Files.exists(work.resolve(NEW).resolve(name), LinkOption.NOFOLLOW_LINKS) && Files.exists(live, LinkOption.NOFOLLOW_LINKS)
            val isNew = name !in journal.outgoing || Files.exists(work.resolve(OLD).resolve(name), LinkOption.NOFOLLOW_LINKS)
            if (putIn && isNew) moveInto(live, work.resolve(ROLLED_BACK).resolve(name))
        }
        val old = work.resolve(OLD)
        if (old.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            val aside = checkedIo { Files.walk(old).use { walk -> walk.iterator().asSequence().filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList() } }
            for (file in aside) {
                val name = old.relativize(file).joinToString("/") { it.name }
                val live = live(unit, name)
                if (unit is SaveUnit.SingleFile && Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                    if (isEmptyFile(live)) moveInto(live, work.resolve(PLACEHOLDERS).resolve(name))
                    else if (isEmptyFile(file)) continue
                }
                moveInto(file, live)
            }
        }
        if (unit is SaveUnit.Prefixed) {
            val made = journal.incoming.mapNotNull(SaveZipper::rootOf).toSet() - journal.outgoing.mapNotNull(SaveZipper::rootOf).toSet()
            runCatching { removeEmpty(made.map { unit.anchor.resolve(it) }) }
        }
        // A placeholder the emulator wrote to after all, as it moved aside, is a save now: it stays.
        if (holdsWrittenFiles(work.resolve(PLACEHOLDERS))) throw IOException("A file written as it moved aside is kept in $work")
        deleteTree(work)
    }

    private fun isEmptyFile(file: Path): Boolean = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == 0L

    private fun holdsWrittenFiles(folder: Path): Boolean = folder.isDirectory(LinkOption.NOFOLLOW_LINKS) &&
        checkedIo { Files.walk(folder).use { walk -> walk.iterator().asSequence().any { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Files.size(it) > 0L } } }

    private fun moveInto(from: Path, to: Path) {
        Files.createDirectories(to.parent)
        mover.move(from, to)
    }

    /**
     * Puts what [archive] holds for [unit] in [dest], a folder on the unit's card, checked against
     * [expected] and flushed to the card. Returns the names the files have in the unit.
     */
    private fun stage(archive: Path, unit: SaveUnit, dest: Path, expected: String, maxUnitBytes: Long): List<String> {
        val staged = when (unit) {
            is SaveUnit.Folders -> unpackChecked(archive, unit.roots, dest, expected, maxUnitBytes)
            is SaveUnit.Prefixed -> unpackChecked(archive, unit.roots, dest, expected, maxUnitBytes)
            is SaveUnit.SingleFile -> {
                val size = Files.size(archive)
                if (size == 0L) throw SaveArchiveException("The save is empty: there's nothing to restore")
                if (size > maxUnitBytes) throw SaveArchiveException("The save is larger than a save unit can hold")
                val copy = Files.createDirectory(dest).resolve(unit.name)
                Files.copy(archive, copy)
                if (SaveHasher.md5(copy) != expected) throw SaveArchiveException("The copied save doesn't match the one downloaded")
                listOf(SaveEntry(unit.name, copy))
            }
        }
        staged.forEach { FileChannel.open(it.file, StandardOpenOption.WRITE).use { c -> c.force(true) } }
        return staged.map { it.name }
    }

    private fun unpackChecked(archive: Path, unitRoots: List<SaveRoot>, dest: Path, expected: String, maxUnitBytes: Long): List<SaveEntry> {
        val roots = unitRoots.map { SaveRoot(it.name, dest.resolve(it.name)) }
        unpack(archive, dest, roots.map { it.name }.toSet(), maxUnitBytes)
        if (SaveHasher.hashUnit(roots) != expected) throw SaveArchiveException("The unpacked save doesn't match the archive")
        return SaveZipper.entries(roots)
    }

    /**
     * The unit's files as they are now, by their names in the unit. A single file counts even
     * when it is empty, a placeholder the emulator made: it is moved aside like any old save.
     */
    private fun filesOf(unit: SaveUnit): List<SaveEntry> = when (unit) {
        is SaveUnit.Folders -> SaveZipper.entries(unit.roots)
        is SaveUnit.Prefixed -> SaveZipper.entries(unit.roots)
        is SaveUnit.SingleFile -> listOfNotNull(SaveEntry(unit.name, unit.file).takeIf { Files.exists(unit.file, LinkOption.NOFOLLOW_LINKS) })
    }

    /**
     * Where the file named [name] ("data/00000001/main") lives: in its root's folder. A root that
     * isn't the unit's is another game's, whose restore Loom didn't finish in a folder several
     * games share (Eden's profile folder). When the unit's roots are named after their folders,
     * as Azahar's `data` and Eden's title folders are, so is that one, beside them. Prefixed
     * folders and single files live in the unit's folder, whichever game's they are.
     */
    private fun live(unit: SaveUnit, name: String): Path = when (unit) {
        is SaveUnit.Folders -> {
            val rootName = SaveZipper.rootOf(name)
            val root = unit.roots.firstOrNull { it.name == rootName }
            when {
                root != null -> root.folder.resolve(name.substringAfter('/'))
                rootName != null && unit.roots.all { it.folder.fileName.toString() == it.name } -> unit.anchor.resolve(name)
                else -> throw SaveArchiveException("No root for $name")
            }
        }
        is SaveUnit.Prefixed -> unit.anchor.resolve(name)
        is SaveUnit.SingleFile -> unit.anchor.resolve(name)
    }

    /**
     * Removes each of [folders] that holds no file, only folders if anything: the folders go,
     * never a file, so one written meanwhile keeps its folder.
     */
    private fun removeEmpty(folders: Collection<Path>) {
        for (folder in folders) {
            if (!folder.isDirectory(LinkOption.NOFOLLOW_LINKS)) continue
            val tree = checkedIo { Files.walk(folder).use { walk -> walk.iterator().asSequence().toList() } }
            if (tree.any { !it.isDirectory(LinkOption.NOFOLLOW_LINKS) }) continue
            try {
                tree.sortedDescending().forEach { Files.delete(it) } // the deepest first
            } catch (e: DirectoryNotEmptyException) {
                // Something was written in it meanwhile: it stays.
            }
        }
    }

    /** The files a restore puts in place and the ones it moves aside, by their names in the unit. */
    private class Journal(val incoming: List<String>, val outgoing: Set<String>)

    /** One name per line: the new files, an empty line, then the old files. */
    private fun writeJournal(work: Path, journal: Journal) {
        val names = journal.incoming + journal.outgoing
        names.firstOrNull { '\n' in it }?.let { throw SaveArchiveException("A file name holds a line break: $it") }
        val part = work.resolve("$JOURNAL.part")
        Files.write(part, (journal.incoming + "" + journal.outgoing).joinToString("\n").toByteArray(Charsets.UTF_8))
        FileChannel.open(part, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(part, work.resolve(JOURNAL), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readJournal(work: Path): Journal {
        val journal = work.resolve(JOURNAL)
        if (!Files.exists(journal)) return Journal(emptyList(), emptySet())
        val lines = String(Files.readAllBytes(journal), Charsets.UTF_8).split('\n')
        val gap = lines.indexOf("").let { if (it < 0) lines.size else it }
        return Journal(lines.subList(0, gap), lines.drop(gap + 1).filter { it.isNotEmpty() }.toSet())
    }

    private fun holdsFiles(folder: Path): Boolean = folder.isDirectory(LinkOption.NOFOLLOW_LINKS) &&
        checkedIo { Files.walk(folder).use { walk -> walk.anyMatch { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) } } }

    /** The folder the unit's files are restored in, beside which the work folder goes. */
    private fun parentOf(unit: SaveUnit): Path = when (unit) {
        is SaveUnit.Folders -> unit.anchor.also { parent ->
            require(unit.roots.all { it.folder.toAbsolutePath().parent == parent }) { "A unit's folders must share one parent to be restored" }
        }
        is SaveUnit.Prefixed -> unit.anchor
        is SaveUnit.SingleFile -> unit.anchor
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
        private const val PLACEHOLDERS = "placeholders"
        private const val JOURNAL = "journal"
        private const val DONE = "done"
    }
}
