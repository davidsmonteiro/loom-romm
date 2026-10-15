package ro.davidmontei.loom.storage.saves

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant

/**
 * A save unit as its files' names, sizes and times describe it, read without opening a file. Two
 * equal snapshots mean the unit almost certainly still hashes the same, so a scan can reuse the
 * last hash; [newestModified] tells whether the emulator wrote to it in the last moments.
 */
data class UnitSnapshot(
    val fileCount: Int,
    val totalBytes: Long,
    /** When the newest file was last written; null when the unit holds no files. */
    val newestModified: Instant?,
    /** MD5 over every file's name, size and modification time. */
    val fingerprint: String,
) {
    val isEmpty: Boolean get() = fileCount == 0

    companion object {
        /** @throws java.io.IOException when the unit can't be read: try again later. */
        fun of(roots: List<SaveRoot>): UnitSnapshot {
            val digest = MessageDigest.getInstance("MD5")
            var bytes = 0L
            var newest: Instant? = null
            val entries = SaveZipper.entries(roots)
            for (entry in entries) {
                val attrs = Files.readAttributes(entry.file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val modified = attrs.lastModifiedTime().toInstant()
                bytes += attrs.size()
                if (newest == null || modified.isAfter(newest)) newest = modified
                digest.update("${entry.name}\t${attrs.size()}\t$modified\n".toByteArray(Charsets.UTF_8))
            }
            return UnitSnapshot(entries.size, bytes, newest, digest.digest().toHex())
        }

        /**
         * A unit of one raw file. An empty file counts as no file, as it is no save; its size and
         * time still go in the fingerprint, so the scan sees the emulator create it or write to it.
         *
         * @throws java.io.IOException when the file can't be read, or something else is in its place.
         */
        fun ofFile(file: Path): UnitSnapshot {
            val attrs = try {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                return UnitSnapshot(0, 0, null, MessageDigest.getInstance("MD5").digest().toHex())
            }
            if (!attrs.isRegularFile) throw IOException("$file isn't a file")
            val modified = attrs.lastModifiedTime().toInstant()
            val line = "${file.fileName}\t${attrs.size()}\t$modified\n"
            val fingerprint = MessageDigest.getInstance("MD5").digest(line.toByteArray(Charsets.UTF_8)).toHex()
            val empty = attrs.size() == 0L
            return UnitSnapshot(if (empty) 0 else 1, attrs.size(), modified.takeUnless { empty }, fingerprint)
        }
    }
}
