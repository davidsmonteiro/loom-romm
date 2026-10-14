package ro.davidmontei.loom.storage.saves

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A Switch title ID, e.g. `010015100B514000` (Super Mario Bros. Wonder). Eden names a game's save
 * folder after its application's ID in uppercase hex. An update's ID is the application's plus
 * 0x800, an add-on's has other low bits: only an application's own ID (low 12 bits zero) has a save.
 */
data class SwitchTitleId(val value: Long) {
    val isApplication: Boolean get() = (value and LOW_BITS) == 0L

    override fun toString(): String = "%016X".format(value)

    companion object {
        private const val LOW_BITS = 0xFFFL
        private const val PATCH = 0x800L
        private val SIXTEEN_HEX = Regex("[0-9A-Fa-f]{16}")

        fun parse(text: String): SwitchTitleId? =
            text.trim().takeIf(SIXTEEN_HEX::matches)?.let { SwitchTitleId(java.lang.Long.parseUnsignedLong(it, 16)) }

        /** The application an update's program belongs to; null for any other non-application ID. */
        internal fun applicationOfPatch(id: Long): Long? = if ((id and LOW_BITS) == PATCH) id - PATCH else null
    }
}

/** What reading a Switch image's title ID found. */
sealed interface SwitchTitleIdResult {
    /** The game's own (application) title ID. */
    data class Found(val titleId: SwitchTitleId) : SwitchTitleIdResult

    /** Only an update's program: the save belongs to the game it updates, [game]. */
    data class UpdateOnly(val game: SwitchTitleId) : SwitchTitleIdResult

    /** Not an XCI or NSP image (nor their XCZ, NSZ forms), or one that is damaged or cut short. */
    data class NotReadable(val reason: String) : SwitchTitleIdResult

    /** None of the image's NCA headers decrypts with the key: prod.keys isn't a dump of this key, or is damaged. */
    data object WrongKey : SwitchTitleIdResult

    /** The image opens but holds no game program (DLC, for one). */
    data object NoProgram : SwitchTitleIdResult

    /** Several games in one image: Loom can't tell which one this is. */
    data class SeveralGames(val titleIds: List<SwitchTitleId>) : SwitchTitleIdResult
}

/**
 * Reads a game's title ID from its Switch image, which RomM 5.3.1 can't: its NCA headers are
 * encrypted with the console's header key ([SwitchHeaderKey]), which RomM doesn't have. Only a few
 * kilobytes are read: the partition tables and the first 0x400 bytes of each NCA.
 *
 * - XCI (a card dump): "HEAD" at 0x100, the root HFS0's offset at 0x130 (u64 LE). The root lists
 *   the card's partitions; the game is in "secure", itself an HFS0.
 * - NSP: a PFS0 at 0.
 * - HFS0 / PFS0: magic, u32 file count at 4, u32 string table size at 8, then the file entries
 *   (0x40 bytes each in an HFS0, 0x18 in a PFS0: u64 offset, u64 size, u32 name offset), the
 *   string table, and the files' data, which entry offsets count from.
 * - NCA header, once decrypted: "NCA3" or "NCA2" at 0x200, the content type at 0x205 (0 is a
 *   program), the program ID at 0x210 (u64 LE). NCZ files (in XCZ and NSZ) keep the NCA's header as it was.
 *
 * The title ID is the program's: of several programs, the application's own (low 12 bits zero;
 * an update's ends in 0x800, a multi-program game's other programs in 1, 2…).
 */
object SwitchTitleIdReader {
    /** Extensions of the images Loom reads. */
    val EXTENSIONS = setOf("xci", "xcz", "nsp", "nsz")

    private const val HEADER_BYTES = 0x400
    private const val MAX_ENTRIES = 4096
    private const val MAX_STRING_TABLE = 1 shl 20
    private const val PROGRAM = 0

    /** @throws IOException when the file can't be read (the card was pulled): try again later. */
    fun read(image: Path, key: SwitchHeaderKey): SwitchTitleIdResult =
        FileChannel.open(image, StandardOpenOption.READ).use { channel -> Reader(channel).read(key) }

    private class Entry(val name: String, val offset: Long, val size: Long)

    private class Malformed(val reason: String) : Exception(reason)

    private class Reader(private val channel: FileChannel) {
        private val size = channel.size()

        fun read(key: SwitchHeaderKey): SwitchTitleIdResult = try {
            readNcas(ncaEntries(), key)
        } catch (e: Malformed) {
            SwitchTitleIdResult.NotReadable(e.reason)
        }

        /** The NCAs of the game: an XCI's secure partition, or an NSP's files. */
        private fun ncaEntries(): List<Entry> {
            val start = bytes(0, 0x140, "too short to be a Switch image")
            val files = when {
                magic(start, 0, "PFS0") -> partition(0, PFS0)
                magic(start, 0x100, "HEAD") -> {
                    val root = partition(start.getLong(0x130), HFS0)
                    val secure = root.firstOrNull { it.name == "secure" } ?: throw Malformed("the card image has no secure partition")
                    partition(secure.offset, HFS0)
                }
                else -> throw Malformed("not an XCI or NSP image")
            }
            return files.filter { it.name.lowercase().let { n -> n.endsWith(".nca") || n.endsWith(".ncz") } }
        }

        private fun readNcas(ncas: List<Entry>, key: SwitchHeaderKey): SwitchTitleIdResult {
            if (ncas.isEmpty()) return SwitchTitleIdResult.NoProgram
            var opened = 0
            val programs = mutableListOf<Long>()
            for (nca in ncas) {
                if (nca.size < HEADER_BYTES) throw Malformed("${nca.name} is too short to be an NCA")
                // A trimmed card image only loses padding: a file it lists that runs past the end was cut short.
                if (nca.offset + nca.size > size) throw Malformed("the image is cut short")
                val header = bytes(nca.offset, HEADER_BYTES, "the image is cut short").array()
                key.decryptNcaHeader(header, HEADER_BYTES)
                val decrypted = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                if (!magic(decrypted, 0x200, "NCA3") && !magic(decrypted, 0x200, "NCA2")) continue
                opened++
                if (header[0x205].toInt() == PROGRAM) programs += decrypted.getLong(0x210)
            }
            if (opened == 0) return SwitchTitleIdResult.WrongKey
            val applications = programs.filter { (it and 0xFFFL) == 0L }.distinct()
            val patched = programs.mapNotNull(SwitchTitleId::applicationOfPatch).distinct()
            return when {
                applications.size == 1 -> SwitchTitleIdResult.Found(SwitchTitleId(applications.single()))
                applications.size > 1 -> SwitchTitleIdResult.SeveralGames(applications.sorted().map(::SwitchTitleId))
                patched.size == 1 -> SwitchTitleIdResult.UpdateOnly(SwitchTitleId(patched.single()))
                patched.size > 1 -> SwitchTitleIdResult.SeveralGames(patched.sorted().map(::SwitchTitleId))
                else -> SwitchTitleIdResult.NoProgram
            }
        }

        private class Format(val magic: String, val entrySize: Int)

        /** The files of the HFS0 or PFS0 at [at], with offsets made absolute. */
        private fun partition(at: Long, format: Format): List<Entry> {
            if (at < 0 || at >= size) throw Malformed("a partition lies past the end of the image")
            val head = bytes(at, 0x10, "the image is cut short")
            if (!magic(head, 0, format.magic)) throw Malformed("no ${format.magic} partition where the image says")
            val count = head.getInt(4)
            val strings = head.getInt(8)
            if (count !in 0..MAX_ENTRIES || strings !in 0..MAX_STRING_TABLE) throw Malformed("a partition table is damaged")
            val tableBytes = count * format.entrySize + strings
            val table = bytes(at + 0x10, tableBytes, "the image is cut short")
            val dataStart = at + 0x10 + tableBytes
            return (0 until count).map { i ->
                val e = i * format.entrySize
                val offset = table.getLong(e)
                val length = table.getLong(e + 8)
                val nameAt = table.getInt(e + 16)
                if (offset < 0 || length < 0 || nameAt !in 0 until strings) throw Malformed("a partition table is damaged")
                Entry(name(table, count * format.entrySize + nameAt, tableBytes), dataStart + offset, length)
            }
        }

        private fun name(table: ByteBuffer, from: Int, end: Int): String {
            var to = from
            while (to < end && table.get(to) != 0.toByte()) to++
            return String(table.array(), from, to - from, Charsets.UTF_8)
        }

        /** [length] bytes at [at]; [short] says why when the file ends first. */
        private fun bytes(at: Long, length: Int, short: String): ByteBuffer {
            if (at < 0 || at + length > size) throw Malformed(short)
            val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, at + buffer.position()) < 0) throw Malformed(short)
            }
            return buffer
        }

        private fun magic(buffer: ByteBuffer, at: Int, magic: String): Boolean =
            magic.indices.all { buffer.get(at + it) == magic[it].code.toByte() }

        companion object {
            private val HFS0 = Format("HFS0", 0x40)
            private val PFS0 = Format("PFS0", 0x18)
        }
    }
}
