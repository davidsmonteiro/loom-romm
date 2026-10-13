package ro.davidmontei.loom.storage.engine

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import ro.davidmontei.loom.storage.plan.Discs
import ro.davidmontei.loom.storage.plan.PartCheck
import ro.davidmontei.loom.storage.plan.PayloadFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

/**
 * Reads zip and 7z archives: checks every entry's CRC (and, when RomM recorded them, each member's
 * size and CRC from `archive_members`), and optionally unpacks them. Unpacking refuses entries
 * that would land outside the target folder (zip-slip) and skips symlinks.
 */
class Archives(private val crash: CrashPoints = CrashPoints.None) {

    /** Checks [file] without unpacking it. Returns how it was verified. */
    fun verify(file: Path, check: PartCheck, romFileId: Int): String {
        val seen = walk(file, romFileId) { _, input -> drain(input, null) }
        return compareMembers(seen, check, romFileId)
    }

    /** Unpacks [file] into [dest] (created fresh) and returns the extracted files, relative to [dest]. */
    fun extract(file: Path, dest: Path, check: PartCheck, romFileId: Int): Pair<List<PayloadFile>, String> {
        Files.createDirectories(dest)
        val root = dest.toAbsolutePath().normalize()
        var count = 0
        val seen = walk(file, romFileId) { name, input ->
            val target = safeTarget(root, name) ?: return@walk drain(input, null)
            Files.createDirectories(target.parent)
            val crc = Files.newOutputStream(target).use { out -> drain(input, out) }
            if (++count == 1) crash.hit(CrashPoint.MID_EXTRACT)
            crc
        }
        val verifiedBy = compareMembers(seen, check, romFileId)
        val files = seen.filter { safeTarget(root, it.name) != null }
            .map { PayloadFile(it.name.replace('\\', '/').trimStart('/'), it.size) }
        return files to verifiedBy
    }

    /** One entry as found in the archive, with the CRC computed while reading it. */
    private data class Seen(val name: String, val size: Long, val crc: Long)

    private fun walk(file: Path, romFileId: Int, consume: (String, InputStream) -> Pair<Long, Long>): List<Seen> = try {
        when (Discs.ext(file.fileName.toString().removeSuffix(".part")).ifEmpty { sniff(file) }) {
            "7z" -> walk7z(file, romFileId, consume)
            else -> walkZip(file, romFileId, consume)
        }
    } catch (e: EngineException) {
        throw e
    } catch (e: IOException) {
        throw EngineException.Corrupt(romFileId, "Archive can't be read: ${e.message}")
    }

    private fun walkZip(file: Path, romFileId: Int, consume: (String, InputStream) -> Pair<Long, Long>): List<Seen> {
        val out = mutableListOf<Seen>()
        ZipFile.builder().setPath(file).get().use { zip ->
            for (entry in zip.entriesInPhysicalOrder) {
                if (entry.isDirectory || entry.isUnixSymlink) continue
                val (size, crc) = zip.getInputStream(entry).use { consume(entry.name, it) }
                if (entry.crc != -1L && entry.crc != crc) {
                    throw EngineException.Corrupt(romFileId, "CRC mismatch in ${entry.name}")
                }
                out += Seen(entry.name, size, crc)
            }
        }
        return out
    }

    private fun walk7z(file: Path, romFileId: Int, consume: (String, InputStream) -> Pair<Long, Long>): List<Seen> {
        val out = mutableListOf<Seen>()
        SevenZFile.builder().setPath(file).get().use { sz ->
            while (true) {
                val entry = sz.nextEntry ?: break
                if (entry.isDirectory || entry.isAntiItem || !entry.hasStream()) continue
                val stream = object : InputStream() {
                    override fun read(): Int = sz.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
                }
                val (size, crc) = consume(entry.name, stream)
                if (entry.hasCrc && entry.crcValue != crc) {
                    throw EngineException.Corrupt(romFileId, "CRC mismatch in ${entry.name}")
                }
                out += Seen(entry.name, size, crc)
            }
        }
        return out
    }

    private fun compareMembers(seen: List<Seen>, check: PartCheck, romFileId: Int): String {
        if (check !is PartCheck.ArchiveMembers) return "ARCHIVE_CRC"
        val byName = seen.associateBy { it.name.replace('\\', '/').trimStart('/') }
        for (member in check.members) {
            val found = byName[member.name.replace('\\', '/').trimStart('/')]
                ?: seen.firstOrNull { it.size == member.size && member.crc.equals("%08x".format(it.crc), ignoreCase = true) }
                ?: throw EngineException.Corrupt(romFileId, "Archive is missing ${member.name}")
            if (found.size != member.size) throw EngineException.Corrupt(romFileId, "${member.name} has the wrong size")
            val expected = member.crc?.lowercase()?.padStart(8, '0')
            if (expected != null && expected != "%08x".format(found.crc)) {
                throw EngineException.Corrupt(romFileId, "${member.name} doesn't match RomM's checksum")
            }
        }
        return "ARCHIVE_MEMBERS"
    }

    /** Reads the stream to the end, optionally copying it, and returns (size, crc32). */
    private fun drain(input: InputStream, out: OutputStream?): Pair<Long, Long> {
        val crc = CRC32()
        val buf = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            crc.update(buf, 0, n)
            out?.write(buf, 0, n)
            total += n
        }
        return total to crc.value
    }

    /** Resolves an entry name under [root], or null if it would escape it or is junk. */
    private fun safeTarget(root: Path, name: String): Path? {
        val clean = name.replace('\\', '/')
        if (clean.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(clean)) return null
        if (clean.split('/').any { it == ".." }) return null
        if (Discs.isJunkPath(clean)) return null
        val target = root.resolve(clean).normalize()
        return target.takeIf { it.startsWith(root) && it != root }
    }

    private fun sniff(file: Path): String {
        val head = ByteArray(6)
        Files.newInputStream(file).use { it.read(head) }
        return if (head[0] == '7'.code.toByte() && head[1] == 'z'.code.toByte()) "7z" else "zip"
    }
}
