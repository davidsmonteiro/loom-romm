package ro.davidmontei.loom.storage.saves

import ro.davidmontei.loom.storage.plan.NameSanitizer
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The save on disk no longer is the one Loom looked at: the emulator wrote to it in between. */
class SaveChangedException(message: String) : IOException(message)

/**
 * Copies of local saves, taken before Loom overwrites one. Each game has a folder of copies named
 * by the time they were taken (UTC), e.g. `20261013-221507-123 Pokemon X.zip`, newest [keep]
 * kept. Names sort in the order the backups were taken even when the clock went back: a backup
 * is then stamped just after the newest one. [dir] is a dot folder at the root of the card holding the saves, outside the ROMs root,
 * so Cocoon never sees it and it outlives a reinstall of Loom. Each copy is what Loom uploads for
 * the save unit, so it can be restored by hand or uploaded to RomM: the zip of its folders, or a
 * single-file unit's raw file, named `.sav`.
 */
class SaveBackups(
    private val dir: Path,
    private val keep: Int = BACKUPS_KEPT,
    /** Writes the archive and returns its hash; a seam for tests of a card that loses writes. */
    private val archive: (unit: SaveUnit, target: Path) -> String = { unit, target -> unit.pack(target) },
    private val now: () -> Instant = Instant::now,
) {
    init {
        require(keep >= 1) { "Keep at least one backup" }
    }

    data class Backup(val file: Path, val hash: String)

    /**
     * Archives [unit] under [key] (one folder per game) and returns the copy once it reads back
     * as [expectedHash], the hash Loom decided on. Then prunes that game's oldest backups, never
     * the one just made. When the game's newest backup already holds that save, it is the copy:
     * a restore failing the same way at every attempt would otherwise fill the folder with one
     * save, pushing out older ones that may be the only copy of another.
     *
     * @throws SaveChangedException when the unit no longer hashes to [expectedHash].
     * @throws IOException when the copy can't be written or doesn't read back intact. The unit
     *   itself is never touched, and no partial backup is left.
     */
    fun backUp(key: String, label: String, unit: SaveUnit, expectedHash: String): Backup {
        val folder = Files.createDirectories(dir.resolve(key))
        val extension = if (unit is SaveUnit.SingleFile) "sav" else "zip"
        newestHolding(key, extension, expectedHash)?.let { newest ->
            if (unit.hash() != expectedHash) throw SaveChangedException("The save changed before it was backed up")
            return Backup(newest, expectedHash)
        }
        val name = freeName(folder, "${STAMP.format(stampFor(key))} ${NameSanitizer.sanitize(label, fallback = "save")}", extension)
        val part = folder.resolve(".$name.part")
        try {
            val written = archive(unit, part)
            if (written != expectedHash) throw SaveChangedException("The save changed while it was being backed up")
            FileChannel.open(part, StandardOpenOption.WRITE).use { it.force(true) }
            val readBack = if (unit is SaveUnit.SingleFile) SaveHasher.md5(part) else SaveHasher.hashFile(part)
            if (readBack != written) throw IOException("The backup didn't read back intact")
            val file = folder.resolve(name)
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE)
            prune(key, file)
            return Backup(file, written)
        } finally {
            Files.deleteIfExists(part)
        }
    }

    /** [backUp] for a unit of folders. */
    fun backUp(key: String, label: String, unit: List<SaveRoot>, expectedHash: String): Backup =
        backUp(key, label, SaveUnit.Folders(unit), expectedHash)

    /** A game's backups, newest first. */
    fun list(key: String): List<Path> {
        val folder = dir.resolve(key)
        if (!Files.isDirectory(folder)) return emptyList()
        return checkedIo { Files.list(folder).use { list -> list.iterator().asSequence().filter(::isBackup).toList() } }
            .sortedByDescending { it.fileName.toString() }
    }

    /** The game's newest backup when it is a copy of the save hashing as [hash], in the kind of file [extension] names; else null. */
    private fun newestHolding(key: String, extension: String, hash: String): Path? {
        val newest = list(key).firstOrNull()?.takeIf { it.fileName.toString().endsWith(".$extension") } ?: return null
        val held = try {
            if (extension == "sav") SaveHasher.md5(newest) else SaveHasher.hashFile(newest)
        } catch (e: IOException) {
            return null // unreadable: a new backup is made
        }
        return newest.takeIf { held == hash }
    }

    private fun prune(key: String, made: Path) {
        list(key).filter { it != made }.drop(keep - 1).forEach { Files.deleteIfExists(it) }
    }

    /**
     * The time a new backup of [key] is named by: now, or just after the newest backup when the
     * clock is behind it, so a new backup never sorts as an old one and gets pruned first.
     */
    private fun stampFor(key: String): Instant {
        val at = now()
        val newest = list(key).firstNotNullOfOrNull(::takenAt) ?: return at
        return if (at.isAfter(newest)) at else newest.plusMillis(1)
    }

    /** The time in a backup's name; null when it holds no real time. */
    private fun takenAt(file: Path): Instant? =
        runCatching { STAMP.parse(file.fileName.toString().take(STAMP_LENGTH), Instant::from) }.getOrNull()

    private fun freeName(folder: Path, stem: String, extension: String): String {
        var name = "$stem.$extension"
        var n = 2
        while (Files.exists(folder.resolve(name))) name = "$stem-${n++}.$extension"
        return name
    }

    /** Only files Loom named: a user's own files in the folder are never pruned. */
    private fun isBackup(file: Path): Boolean =
        Files.isRegularFile(file) && BACKUP_NAME.matches(file.fileName.toString())

    companion object {
        /** Backups kept per game. */
        const val BACKUPS_KEPT = 10

        /** Where backups go, under the root of the volume holding the saves. */
        const val BACKUP_DIR = ".loom/save-backups"

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
        private const val STAMP_LENGTH = 19
        private val BACKUP_NAME = Regex("""\d{8}-\d{6}-\d{3} .*\.(zip|sav)""")
    }
}
