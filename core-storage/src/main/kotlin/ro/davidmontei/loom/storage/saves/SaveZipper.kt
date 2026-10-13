package ro.davidmontei.loom.storage.saves

import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * One folder of a save unit and the name it has at the top of the unit's archive. Azahar's save is
 * `SaveRoot("data", …/title/00040000/0011c400/data)`, the root Argosy uses for it too, so an
 * archive either app uploads restores in the other.
 */
data class SaveRoot(val name: String, val folder: Path) {
    init {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") {
            "Bad archive root name: $name"
        }
    }
}

/** One file of a save unit, under the name it has in the unit's archive. */
data class SaveEntry(val name: String, val file: Path)

/** A save archive Loom refuses to restore: unsafe names, nothing to restore, or damaged entries. */
class SaveArchiveException(message: String) : IOException(message)

/**
 * Zips a save unit the same way every time and unzips one without letting it write anywhere else.
 *
 * Archives hold only files, named `root/relative/path` with "/" separators, in code-point order,
 * with a fixed timestamp and no directory entries: what goes in decides the archive. RomM's
 * content hash ignores order, timestamps and directory entries anyway; [SaveHasher.hashUnit]
 * hashes a unit under exactly these names without writing anything.
 */
object SaveZipper {
    /**
     * Every zip entry gets this DOS time, written as is (no time zone conversion). Not 1980-01-01:
     * java.util.zip reads that DOS value as "before 1980" and adds a time-zone-dependent timestamp.
     */
    private val FIXED_TIME: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)

    /** RomM stops hashing an entry past this size (`MAX_DECOMPRESSED_ENTRY_BYTES`); so does Loom. */
    const val MAX_ENTRY_BYTES = 512L * 1024 * 1024

    /**
     * Most bytes one restore may unpack. A 3DS title's save is at most a few MB; the cap keeps a
     * hostile archive of many large entries from filling the card that holds the live saves.
     */
    const val MAX_UNIT_BYTES = 64L * 1024 * 1024

    /** Most entries an archive Loom restores may hold. */
    const val MAX_UNIT_ENTRIES = 4096

    /**
     * The files of [roots] as the archive names them, in code-point order (Python's `sorted`).
     * Only regular files count: symlinks are not followed, and a missing root adds nothing.
     *
     * @throws IOException when a folder in the unit can't be read or vanishes during the walk
     *   (the SD card was pulled, the emulator replaced a folder): try again later.
     */
    fun entries(roots: List<SaveRoot>): List<SaveEntry> {
        require(roots.map { it.name }.toSet().size == roots.size) { "Archive roots must have distinct names" }
        val entries = mutableListOf<SaveEntry>()
        for (root in roots) {
            if (!root.folder.isDirectory(LinkOption.NOFOLLOW_LINKS)) continue
            checkedIo {
                Files.walk(root.folder).use { walk ->
                    walk.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                        val relative = root.folder.relativize(file).joinToString("/") { it.name }
                        entries += SaveEntry("${root.name}/$relative", file)
                    }
                }
            }
        }
        return entries.sortedWith(compareBy(CodePointOrder) { it.name })
    }

    /**
     * Writes [roots] to [target] and returns RomM's content hash of what it wrote, computed from
     * the bytes as they went in. Compare it with a hash of the unit taken before: if the emulator
     * wrote in between, they differ and the archive must not be uploaded.
     *
     * @throws SaveArchiveException when the unit holds no files: an empty archive is never a save.
     * @throws IOException when the unit can't be read; [target] may then hold a partial archive.
     */
    fun zip(roots: List<SaveRoot>, target: Path): String {
        val entries = entries(roots)
        if (entries.isEmpty()) throw SaveArchiveException("Nothing to archive in ${roots.map { it.folder }}")
        val lines = mutableListOf<Pair<String, String>>()
        ZipOutputStream(Files.newOutputStream(target), Charsets.UTF_8).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry.name).apply { setTimeLocal(FIXED_TIME) })
                val md5 = MessageDigest.getInstance("MD5")
                DigestInputStream(Files.newInputStream(entry.file), md5).use { it.copyTo(zip) }
                zip.closeEntry()
                lines += entry.name to md5.digest().toHex()
            }
        }
        return SaveHasher.combine(lines)
    }

    /**
     * Unpacks the files under [roots] from [zip] into [dest], which must not exist yet (a fresh
     * temporary folder next to the save). Entries under other roots are skipped: Argosy's 3DS
     * archives also carry `extdata/`. Returns the names written.
     *
     * The whole archive is refused, and [dest] removed, if any entry is absolute, climbs out with
     * "..", uses "\" or empty segments, or is a symlink; if two entries name the same file, even
     * only in a different case (the SD card ignores case), or one names a file another uses as a
     * folder; if an entry fails its CRC or is larger than [maxEntryBytes]; if the archive holds
     * more than [maxEntries] entries or unpacks to more than [maxTotalBytes]; and when nothing
     * under [roots] is left to restore.
     *
     * Check what was written with [SaveHasher.hashUnit] over [dest]'s roots, not with the
     * archive's own hash: that is what the restored save will be.
     */
    fun unzip(
        zip: Path,
        dest: Path,
        roots: Set<String>,
        maxEntryBytes: Long = MAX_ENTRY_BYTES,
        maxTotalBytes: Long = MAX_UNIT_BYTES,
        maxEntries: Int = MAX_UNIT_ENTRIES,
    ): List<String> {
        Files.createDirectory(dest)
        try {
            val root = dest.toAbsolutePath().normalize()
            val written = mutableListOf<String>()
            ZipFile.builder().setPath(zip).get().use { archive ->
                val all = archive.entries.toList()
                if (all.size > maxEntries) throw SaveArchiveException("Archive holds ${all.size} entries; a save unit has at most $maxEntries")
                val seen = HashSet<String>()
                val folders = HashSet<String>()
                for (entry in all) {
                    checkName(entry.name)
                    if (entry.isUnixSymlink) throw SaveArchiveException("Archive holds a symlink: ${entry.name}")
                    val key = caseKey(entry.name)
                    if (!seen.add(key)) throw SaveArchiveException("Archive names ${entry.name} twice")
                    key.indices.filter { key[it] == '/' }.forEach { folders += key.substring(0, it) }
                }
                all.firstOrNull { !it.isDirectory && caseKey(it.name) in folders }?.let {
                    throw SaveArchiveException("Archive names ${it.name} both as a file and as a folder")
                }
                var left = maxTotalBytes
                for (entry in all) {
                    if (entry.isDirectory || rootOf(entry.name) !in roots) continue
                    val target = root.resolve(entry.name).normalize()
                    if (!target.startsWith(root) || target == root) throw SaveArchiveException("Unsafe entry name: ${entry.name}")
                    Files.createDirectories(target.parent)
                    val copied = Files.newOutputStream(target).use { out ->
                        archive.getInputStream(entry).use { copyCapped(it, out, entry.name, maxEntryBytes, left) }
                    }
                    if (entry.crc != -1L && entry.crc != copied.crc) throw SaveArchiveException("CRC mismatch in ${entry.name}")
                    left -= copied.bytes
                    written += entry.name
                }
            }
            if (written.isEmpty()) throw SaveArchiveException("Nothing under ${roots.sorted()} to restore")
            return written
        } catch (e: Exception) {
            deleteTree(dest)
            throw e
        }
    }

    /** The root folder an entry sits in; null for a file at the top of the archive. */
    internal fun rootOf(name: String): String? = name.substringBefore('/', missingDelimiterValue = "").ifEmpty { null }

    private fun checkName(name: String) {
        val segments = name.removeSuffix("/").split('/')
        val unsafe = name.isEmpty() || name.startsWith("/") || '\\' in name || '\u0000' in name ||
            Regex("^[A-Za-z]:").containsMatchIn(name) || segments.any { it.isEmpty() || it == "." || it == ".." }
        if (unsafe) throw SaveArchiveException("Unsafe entry name: $name")
    }

    /** An entry's name as a case-insensitive file system sees it. */
    private fun caseKey(name: String): String = name.removeSuffix("/").lowercase(Locale.ROOT)

    private class Copied(val bytes: Long, val crc: Long)

    /** Copies at most [entryLimit] bytes, and at most [totalLeft] of the unit's budget. */
    private fun copyCapped(input: InputStream, out: OutputStream, name: String, entryLimit: Long, totalLeft: Long): Copied {
        val crc = CRC32()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > entryLimit) throw SaveArchiveException("$name is larger than a save can be")
            if (total > totalLeft) throw SaveArchiveException("The archive unpacks to more than a save unit can hold")
            crc.update(buffer, 0, n)
            out.write(buffer, 0, n)
        }
        return Copied(total, crc.value)
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        checkedIo { Files.walk(path).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

/**
 * Runs a directory walk or listing so it fails only with [IOException]. The JDK's streams wrap any
 * error after the first folder in an unchecked exception, which a caller following "an
 * IOException means try again later" would miss.
 */
internal inline fun <T> checkedIo(block: () -> T): T = try {
    block()
} catch (e: UncheckedIOException) {
    throw e.cause ?: IOException(e)
} catch (e: DirectoryIteratorException) {
    throw e.cause ?: IOException(e)
}

/** Python's `sorted()` order for strings: by code point, so characters beyond U+FFFF sort last. */
internal object CodePointOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return (a.length - i).compareTo(b.length - j)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
