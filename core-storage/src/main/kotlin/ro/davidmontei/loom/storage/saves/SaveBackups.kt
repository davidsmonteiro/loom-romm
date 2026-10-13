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
 * Copies of local saves, taken before Loom overwrites one. Each game has a folder of zips named
 * by the time they were taken (UTC), e.g. `20261013-221507-123 Pokemon X.zip`, newest [keep]
 * kept. [dir] is a dot folder at the root of the card holding the saves, outside the ROMs root,
 * so Cocoon never sees it and it outlives a reinstall of Loom. Each zip is the save unit's archive,
 * the same shape Loom uploads, so it can be restored by hand or uploaded to RomM.
 */
class SaveBackups(
    private val dir: Path,
    private val keep: Int = BACKUPS_KEPT,
    /** Writes the archive and returns its hash; a seam for tests of a card that loses writes. */
    private val archive: (unit: List<SaveRoot>, target: Path) -> String = SaveZipper::zip,
    private val now: () -> Instant = Instant::now,
) {
    init {
        require(keep >= 1) { "Keep at least one backup" }
    }

    data class Backup(val file: Path, val hash: String)

    /**
     * Archives [unit] under [key] (one folder per game) and returns the copy once it reads back
     * as [expectedHash], the hash Loom decided on. Then prunes that game's oldest backups.
     *
     * @throws SaveChangedException when the unit no longer hashes to [expectedHash].
     * @throws IOException when the copy can't be written or doesn't read back intact. The unit
     *   itself is never touched, and no partial backup is left.
     */
    fun backUp(key: String, label: String, unit: List<SaveRoot>, expectedHash: String): Backup {
        val folder = Files.createDirectories(dir.resolve(key))
        val name = freeName(folder, "${STAMP.format(now())} ${NameSanitizer.sanitize(label, fallback = "save")}")
        val part = folder.resolve(".$name.part")
        try {
            val written = archive(unit, part)
            if (written != expectedHash) throw SaveChangedException("The save changed while it was being backed up")
            FileChannel.open(part, StandardOpenOption.WRITE).use { it.force(true) }
            if (SaveHasher.hashFile(part) != written) throw IOException("The backup didn't read back intact")
            val file = folder.resolve(name)
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE)
            prune(folder)
            return Backup(file, written)
        } finally {
            Files.deleteIfExists(part)
        }
    }

    /** A game's backups, newest first. */
    fun list(key: String): List<Path> {
        val folder = dir.resolve(key)
        if (!Files.isDirectory(folder)) return emptyList()
        return checkedIo { Files.list(folder).use { list -> list.iterator().asSequence().filter(::isBackup).toList() } }
            .sortedByDescending { it.fileName.toString() }
    }

    private fun prune(folder: Path) {
        list(folder.fileName.toString()).drop(keep).forEach { Files.deleteIfExists(it) }
    }

    private fun freeName(folder: Path, stem: String): String {
        var name = "$stem.zip"
        var n = 2
        while (Files.exists(folder.resolve(name))) name = "$stem-${n++}.zip"
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
        private val BACKUP_NAME = Regex("""\d{8}-\d{6}-\d{3} .*\.zip""")
    }
}
