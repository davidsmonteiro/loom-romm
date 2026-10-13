package ro.davidmontei.loom.storage.saves

import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * RomM's `content_hash` for a save (`handler/filesystem/assets_handler.py`, the same in 5.3.1 and
 * 5.4.0), so Loom can tell whether its copy and the server's hold the same save:
 * - a zip (recognised by its content, whatever the extension): the MD5 of the lines
 *   `name:md5(entry)` for every entry that isn't a directory, sorted by name in code-point order
 *   and joined with "\n" (no trailing newline). An empty zip gives the MD5 of nothing.
 * - anything else: the MD5 of the bytes.
 * All lowercase hex. Where RomM gives up (an entry over 512 MiB), Loom throws instead.
 *
 * Loom decides "did my copy change?" by comparing its own hashes with each other (the baseline),
 * so matching RomM exactly matters for spotting identical saves, not for safety.
 */
object SaveHasher {
    /** The MD5 of nothing, which is also the hash of an empty zip. */
    const val EMPTY = "d41d8cd98f00b204e9800998ecf8427e"

    /** Hash of a save file as RomM computes it once uploaded. */
    fun hashFile(file: Path): String = openZipOrNull(file)?.use { hashEntries(it, null, SaveZipper.MAX_ENTRY_BYTES) } ?: md5(file)

    /**
     * Hash of a zip's entries. With [roots], only entries under those top folders count: the hash
     * the part of an archive Loom restores would have as an archive of its own.
     *
     * @throws IOException if [zip] isn't a zip, or an entry is over [maxEntryBytes].
     */
    fun hashZip(zip: Path, roots: Set<String>? = null, maxEntryBytes: Long = SaveZipper.MAX_ENTRY_BYTES): String =
        (openZipOrNull(zip) ?: throw IOException("Not a zip: $zip")).use { hashEntries(it, roots, maxEntryBytes) }

    /**
     * Hash of the archive [SaveZipper.zip] would write for [roots], without writing it. Null when
     * the unit holds no files: there is no save to compare.
     *
     * @throws IOException when the unit can't be read. That is never "no save": try again later.
     */
    fun hashUnit(roots: List<SaveRoot>): String? {
        val entries = SaveZipper.entries(roots)
        if (entries.isEmpty()) return null
        return combine(entries.map { it.name to md5(it.file) })
    }

    /** Lowercase hex MD5 of [file]'s bytes. */
    fun md5(file: Path): String = Files.newInputStream(file).use { md5(it, Long.MAX_VALUE, file.toString()) }

    /** Whether RomM would treat [file] as a zip (`zipfile.is_zipfile`: a readable central directory). */
    fun isZip(file: Path): Boolean = openZipOrNull(file)?.use { true } ?: false

    /** RomM's combination of `name:md5` lines, in code-point order of the names. */
    internal fun combine(lines: List<Pair<String, String>>): String {
        val text = lines.sortedWith(compareBy(CodePointOrder) { it.first }).joinToString("\n") { "${it.first}:${it.second}" }
        return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun hashEntries(zip: ZipFile, roots: Set<String>?, maxEntryBytes: Long): String {
        val lines = zip.entries.toList()
            .filter { !it.name.endsWith("/") && (roots == null || SaveZipper.rootOf(it.name) in roots) }
            .map { entry -> entry.name to zip.getInputStream(entry).use { md5(it, maxEntryBytes, entry.name) } }
        return combine(lines)
    }

    private fun md5(input: InputStream, limit: Long, what: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("$what is larger than RomM hashes")
            digest.update(buffer, 0, n)
        }
        return digest.digest().toHex()
    }

    private fun openZipOrNull(file: Path): ZipFile? = try {
        ZipFile.builder().setPath(file).get()
    } catch (e: IOException) {
        null
    }
}
