package ro.davidmontei.loom.storage.saves

import java.nio.file.Files
import java.nio.file.LinkOption
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
    }
}
