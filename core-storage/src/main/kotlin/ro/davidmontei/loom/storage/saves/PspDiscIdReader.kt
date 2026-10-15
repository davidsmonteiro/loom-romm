package ro.davidmontei.loom.storage.saves

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** What reading a PSP image's disc ID found. */
sealed interface PspDiscIdResult {
    /** Where the ID was read: `UMD_DATA.BIN`, `PARAM.SFO`, or both, agreeing. */
    enum class Source { UMD, SFO, BOTH }

    data class Found(val discId: PspDiscId, val from: Source) : PspDiscIdResult

    /** `UMD_DATA.BIN` and `PARAM.SFO` name different IDs: Loom doesn't guess which one the game saves under. */
    data class Disagree(val umd: PspDiscId, val sfo: PspDiscId) : PspDiscIdResult

    /** No disc ID: not an image Loom reads, a damaged or cut-short one, or one naming none. */
    data class None(val reason: String) : PspDiscIdResult

    /** A format Loom doesn't read on the Thor (CHD, ZSO, CSO v2): RomM's ID, read on the server, is the one. */
    data class NotRead(val format: String) : PspDiscIdResult
}

/**
 * Reads a PSP game's disc ID from its image, for games RomM reports none for (a zip it couldn't
 * look into, a PSN game, a server without Sigil). Only a few sectors are read.
 *
 * - ISO, as Sigil reads it (`iso9660.c`, `psp.c`): the Primary Volume Descriptor at sector 16, its
 *   root directory's record at +156 (the extent, LE u32 at +2; the size at +10). A directory is
 *   read sector by sector, at most 4, as records (length at 0, a 0 length ending the sector;
 *   extent at +2, size at +10, flags at +25, 0x02 a directory; the name's length at +32, the name
 *   at +33, matched ignoring case, with or without `;1`). The ID is `UMD_DATA.BIN`'s first field,
 *   checked against `PSP_GAME/PARAM.SFO`'s `DISC_ID`, which is where PPSSPP takes it from.
 * - CSO (CISO v1), as PPSSPP reads it (`BlockDevices.cpp`): "CISO", the image's size (u64 at
 *   0x08), the frame size (u32 at 0x10, a power of two of at least one sector), the version (0x14)
 *   and the index alignment (0x15), then an index of frames + 1 u32s at 0x18. A frame starts at
 *   its index value without bit 31, shifted left by the alignment, and ends where the next one
 *   starts; with bit 31 it is stored as it is, else raw-deflated.
 * - PBP (a PSN game's EBOOT.PBP): "\0PBP", a version, then eight u32 offsets at 0x08, the first
 *   two bounding its PARAM.SFO, whose `DISC_ID` is the ID.
 */
object PspDiscIdReader {
    private const val SECTOR = 2048
    private const val MAX_DIRECTORY_SECTORS = 4
    private const val MAX_FRAME_BYTES = 1 shl 20

    /** @throws java.io.IOException when the file can't be read (the card was pulled): try again later. */
    fun read(image: Path): PspDiscIdResult = FileChannel.open(image, StandardOpenOption.READ).use { Reader(it).read() }

    private class Malformed(val reason: String) : Exception(reason)

    /** A file's place in the image, in sectors, and its length in bytes. */
    private class Extent(val sector: Long, val length: Long)

    private class Reader(private val channel: FileChannel) {
        private val size = channel.size()

        fun read(): PspDiscIdResult = try {
            val head = bytes(0, minOf(size, 8L).toInt(), "too short to be a PSP image")
            when {
                magic(head, "CISO") -> {
                    val header = ByteBuffer.wrap(bytes(0, 0x18, CUT)).order(ByteOrder.LITTLE_ENDIAN)
                    if ((header.get(0x14).toInt() and 0xFF) > 1) PspDiscIdResult.NotRead("CSO v2") else iso(CsoSectors(header))
                }
                magic(head, "ZISO") -> PspDiscIdResult.NotRead("ZSO")
                magic(head, "MComprHD") -> PspDiscIdResult.NotRead("CHD")
                magic(head, "\u0000PBP") -> pbp()
                else -> iso(PlainSectors())
            }
        } catch (e: Malformed) {
            PspDiscIdResult.None(e.reason)
        }

        private fun iso(sectors: Sectors): PspDiscIdResult {
            val pvd = sectors.read(16)
            if (pvd.get(0) != 1.toByte() || !magic(pvd.array().copyOfRange(1, 6), "CD001")) return PspDiscIdResult.None("not a PSP disc image")
            val root = Extent(u32(pvd, 156 + 2), u32(pvd, 156 + 10))
            val umd = find(sectors, root, "UMD_DATA.BIN", directory = false)?.let { file ->
                if (file.length == 0L) null else PspDiscId.fromUmdData(sectors.read(file.sector).array(), minOf(file.length, SECTOR.toLong()).toInt())
            }
            val sfo = find(sectors, root, "PSP_GAME", directory = true)
                ?.let { find(sectors, it, "PARAM.SFO", directory = false) }
                ?.let { sfoOf(sectors, it) }
                ?.string("DISC_ID")?.let(PspDiscId::parse)
            return when {
                umd != null && sfo != null && umd != sfo -> PspDiscIdResult.Disagree(umd, sfo)
                umd != null && sfo != null -> PspDiscIdResult.Found(umd, PspDiscIdResult.Source.BOTH)
                umd != null -> PspDiscIdResult.Found(umd, PspDiscIdResult.Source.UMD)
                sfo != null -> PspDiscIdResult.Found(sfo, PspDiscIdResult.Source.SFO)
                else -> PspDiscIdResult.None("the image names no disc ID")
            }
        }

        /** The record named [name] in [dir], a directory or a file as [directory] says. */
        private fun find(sectors: Sectors, dir: Extent, name: String, directory: Boolean): Extent? {
            val count = minOf((dir.length + SECTOR - 1) / SECTOR, MAX_DIRECTORY_SECTORS.toLong())
            for (s in 0 until count) {
                val sector = sectors.read(dir.sector + s)
                var pos = 0
                while (pos < SECTOR) {
                    val length = sector.get(pos).toInt() and 0xFF
                    // A 0 length pads to the next sector, as records never cross one; a record shorter than 34 bytes holds no name.
                    if (length < 34 || pos + length > SECTOR) break
                    val nameLength = sector.get(pos + 32).toInt() and 0xFF
                    if (33 + nameLength <= length && named(sector, pos + 33, nameLength, name)) {
                        val isDirectory = (sector.get(pos + 25).toInt() and 0x02) != 0
                        if (isDirectory == directory) return Extent(u32(sector, pos + 2), u32(sector, pos + 10))
                    }
                    pos += length
                }
            }
            return null
        }

        private fun named(sector: ByteBuffer, at: Int, length: Int, name: String): Boolean {
            val text = String(sector.array(), at, length, Charsets.ISO_8859_1)
            return text.equals(name, ignoreCase = true) || text.equals("$name;1", ignoreCase = true)
        }

        private fun sfoOf(sectors: Sectors, file: Extent): ParamSfo? {
            if (file.length == 0L || file.length > ParamSfo.MAX_BYTES) return null
            val count = ((file.length + SECTOR - 1) / SECTOR).toInt()
            val bytes = ByteArray(count * SECTOR)
            for (s in 0 until count) sectors.read(file.sector + s).array().copyInto(bytes, s * SECTOR)
            return ParamSfo.parse(bytes.copyOf(file.length.toInt()))
        }

        private fun pbp(): PspDiscIdResult {
            val header = ByteBuffer.wrap(bytes(0, 0x28, "the PBP is cut short")).order(ByteOrder.LITTLE_ENDIAN)
            val from = u32(header, 0x08)
            val to = u32(header, 0x0C)
            if (from < 0x28 || to <= from || to - from > ParamSfo.MAX_BYTES) return PspDiscIdResult.None("the PBP's PARAM.SFO is damaged")
            if (to > size) throw Malformed("the PBP is cut short")
            val id = ParamSfo.parse(bytes(from, (to - from).toInt(), "the PBP is cut short"))?.string("DISC_ID")?.let(PspDiscId::parse)
                ?: return PspDiscIdResult.None("the PBP's PARAM.SFO names no disc ID")
            return PspDiscIdResult.Found(id, PspDiscIdResult.Source.SFO)
        }

        private interface Sectors {
            /** Sector [n] of the image. @throws Malformed when the image ends first. */
            fun read(n: Long): ByteBuffer
        }

        private inner class PlainSectors : Sectors {
            override fun read(n: Long): ByteBuffer = ByteBuffer.wrap(bytes(n * SECTOR, SECTOR, CUT)).order(ByteOrder.LITTLE_ENDIAN)
        }

        /** The ISO inside a CSO whose [header] is read, a frame at a time; the last frame inflated is kept, as neighbouring sectors share it. */
        private inner class CsoSectors(header: ByteBuffer) : Sectors {
            private val total = header.getLong(0x08)
            private val frameSize = header.getInt(0x10)
            private val align = header.get(0x15).toInt() and 0xFF
            private val shift = Integer.numberOfTrailingZeros(frameSize / SECTOR)
            private var cached = -1L
            private var frame = ByteArray(0)

            init {
                if (frameSize < SECTOR || frameSize > MAX_FRAME_BYTES || (frameSize and (frameSize - 1)) != 0) throw Malformed("the CSO's frame size is damaged")
                if (total <= 0 || total > MAX_IMAGE_BYTES || align > MAX_ALIGN) throw Malformed("the CSO's header is damaged")
                // As PPSSPP checks: the last frame must end within the file.
                if (offset(index((total + frameSize - 1) / frameSize)) > size) throw Malformed(CUT)
            }

            override fun read(n: Long): ByteBuffer {
                if (n < 0 || (n + 1) * SECTOR > total) throw Malformed(CUT)
                val f = n shr shift
                val within = ((n and ((1L shl shift) - 1)) * SECTOR).toInt()
                val entry = index(f)
                if (entry and STORED != 0L) return ByteBuffer.wrap(bytes(offset(entry) + within, SECTOR, CUT)).order(ByteOrder.LITTLE_ENDIAN)
                if (f != cached) {
                    frame = inflate(offset(entry), offset(index(f + 1)))
                    cached = f
                }
                return ByteBuffer.wrap(frame.copyOfRange(within, within + SECTOR)).order(ByteOrder.LITTLE_ENDIAN)
            }

            private fun index(frame: Long): Long = u32(ByteBuffer.wrap(bytes(0x18 + 4 * frame, 4, CUT)).order(ByteOrder.LITTLE_ENDIAN), 0)

            /** Where a frame starts, from its index value: without bit 31, shifted left by the alignment. */
            private fun offset(entry: Long): Long = (entry and 0x7FFF_FFFFL) shl align

            /** A raw-deflated frame from [from] to [to], which must give exactly a frame's bytes, as PPSSPP requires. */
            private fun inflate(from: Long, to: Long): ByteArray {
                if (to <= from || to - from > 2L * frameSize + (1L shl align)) throw Malformed("a CSO frame is damaged")
                if (to > size) throw Malformed(CUT)
                // One byte more than the frame: zlib may ask for a dummy byte after a raw stream.
                val input = bytes(from, (to - from).toInt(), CUT).copyOf((to - from).toInt() + 1)
                val inflater = Inflater(true)
                try {
                    inflater.setInput(input)
                    val out = ByteArray(frameSize + 1)
                    var n = 0
                    while (!inflater.finished()) {
                        val got = inflater.inflate(out, n, out.size - n)
                        if (got == 0 && (inflater.needsInput() || inflater.needsDictionary() || n == out.size)) break
                        n += got
                    }
                    if (!inflater.finished() || n != frameSize) throw Malformed("a CSO frame is damaged")
                    return out.copyOf(frameSize)
                } catch (e: DataFormatException) {
                    throw Malformed("a CSO frame is damaged")
                } finally {
                    inflater.end()
                }
            }
        }

        /** [length] bytes at [at]; [short] says why when the file ends first. */
        private fun bytes(at: Long, length: Int, short: String): ByteArray {
            if (at < 0 || at + length > size) throw Malformed(short)
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, at + buffer.position()) < 0) throw Malformed(short)
            }
            return buffer.array()
        }

        private fun magic(bytes: ByteArray, magic: String): Boolean =
            bytes.size >= magic.length && magic.indices.all { bytes[it] == magic[it].code.toByte() }

        private fun u32(buffer: ByteBuffer, at: Int): Long = buffer.getInt(at).toLong() and 0xFFFF_FFFFL

        companion object {
            private const val CUT = "the image is cut short"
            private const val STORED = 0x8000_0000L
            private const val MAX_ALIGN = 31
            private const val MAX_IMAGE_BYTES = 1L shl 40
        }
    }
}
