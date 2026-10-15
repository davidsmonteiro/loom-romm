package ro.davidmontei.loom.storage.saves

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A PSP `PARAM.SFO`: the table of keys and values a PSP game, each of its save folders and its
 * installed data carry. Read as PPSSPP reads it (`Core/ELF/ParamSFO.cpp`), all little-endian:
 * - the header: magic `00 50 53 46` (0x46535000), version, the key table's start (u32 at 0x08),
 *   the data table's (u32 at 0x0C) and the number of entries (u32 at 0x10);
 * - then 16 bytes per entry: the key's offset in the key table (u16; the key is NUL-terminated
 *   ASCII), the format (u16: 0x0004 raw bytes, 0x0204 UTF-8 text, 0x0404 a u32), the value's
 *   length and its room (u32 each), and its offset in the data table (u32).
 *
 * A text value is its length's bytes, cut at the first NUL: `DISC_ID` is `ULUS10064\0`.
 */
class ParamSfo private constructor(
    /** Every key with a value Loom could read, whatever its format. */
    val keys: Set<String>,
    private val strings: Map<String, String>,
    private val ints: Map<String, Long>,
) {
    /** The text value of [key]; null when there is none, or it isn't text. */
    fun string(key: String): String? = strings[key]

    /** The u32 value of [key]; null when there is none, or it isn't a number. */
    fun int(key: String): Long? = ints[key]

    companion object {
        /** No PARAM.SFO Loom reads is larger: a game's is about 1 KiB. Argosy's install-data rule reads no more either. */
        const val MAX_BYTES = 64 * 1024

        private const val MAGIC = 0x46535000
        private const val HEADER_BYTES = 0x14
        private const val ENTRY_BYTES = 16
        private const val MAX_ENTRIES = 1024
        private const val RAW = 0x0004
        private const val TEXT = 0x0204
        private const val INT = 0x0404

        /**
         * The SFO in [bytes]; null when they aren't one: a wrong magic, or a table that runs past
         * the end or points past it, as PPSSPP refuses them. A value that runs past the end is
         * left out, as PPSSPP leaves it out.
         */
        fun parse(bytes: ByteArray): ParamSfo? {
            val size = bytes.size.toLong()
            if (size < HEADER_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.getInt(0) != MAGIC) return null
            val keyStart = buffer.getInt(0x08).toLong() and 0xFFFF_FFFFL
            val dataStart = buffer.getInt(0x0C).toLong() and 0xFFFF_FFFFL
            val count = buffer.getInt(0x10).toLong() and 0xFFFF_FFFFL
            if (keyStart > size || dataStart > size) return null
            if (count > MAX_ENTRIES || HEADER_BYTES + count * ENTRY_BYTES > size) return null
            val keys = mutableSetOf<String>()
            val strings = mutableMapOf<String, String>()
            val ints = mutableMapOf<String, Long>()
            for (i in 0 until count.toInt()) {
                val entry = HEADER_BYTES + i * ENTRY_BYTES
                val keyAt = keyStart + (buffer.getShort(entry).toLong() and 0xFFFF)
                val format = buffer.getShort(entry + 2).toInt() and 0xFFFF
                val length = buffer.getInt(entry + 4).toLong() and 0xFFFF_FFFFL
                val dataAt = dataStart + (buffer.getInt(entry + 12).toLong() and 0xFFFF_FFFFL)
                if (keyAt >= size || dataAt >= size) return null
                val key = textAt(bytes, keyAt.toInt(), bytes.size, Charsets.US_ASCII)
                if (key.isEmpty()) continue
                when (format) {
                    INT -> if (dataAt + 4 <= size) {
                        ints[key] = buffer.getInt(dataAt.toInt()).toLong() and 0xFFFF_FFFFL
                        keys += key
                    }
                    TEXT -> if (dataAt + length <= size) {
                        strings[key] = textAt(bytes, dataAt.toInt(), (dataAt + length).toInt(), Charsets.UTF_8)
                        keys += key
                    }
                    RAW -> if (dataAt + length <= size) keys += key
                }
            }
            return ParamSfo(keys, strings, ints)
        }

        /** The text from [from] up to the first NUL, or up to [to]. */
        private fun textAt(bytes: ByteArray, from: Int, to: Int, charset: java.nio.charset.Charset): String {
            var end = from
            while (end < to && bytes[end] != 0.toByte()) end++
            return String(bytes, from, end - from, charset)
        }
    }
}
