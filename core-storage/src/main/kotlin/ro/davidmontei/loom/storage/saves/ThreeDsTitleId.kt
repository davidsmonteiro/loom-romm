package ro.davidmontei.loom.storage.saves

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.extension

/**
 * A 3DS title ID, e.g. `000400000011C400` (Pokémon Omega Ruby): the high half is the category
 * (`00040000` for a game), the low half the title's own id. Azahar names save folders after the
 * halves in lowercase: `title/00040000/0011c400`.
 */
data class ThreeDsTitleId(val value: Long) {
    val high: String get() = "%08x".format(value ushr 32)
    val low: String get() = "%08x".format(value and 0xFFFF_FFFFL)

    /** A game's own title: updates, DLC and system titles keep no save of their own. */
    val isGame: Boolean get() = value ushr 32 == GAME_CATEGORY

    override fun toString(): String = "%016X".format(value)

    companion object {
        private const val GAME_CATEGORY = 0x0004_0000L
        private val SIXTEEN_HEX = Regex("[0-9A-Fa-f]{16}")
        private val SPLIT_HEX = Regex("([0-9A-Fa-f]{8})/([0-9A-Fa-f]{8})")

        /** Reads "000400000011C400" or RomM's folder-split save target "00040000/0011c400". */
        fun parse(text: String): ThreeDsTitleId? {
            val trimmed = text.trim()
            SPLIT_HEX.matchEntire(trimmed)?.let { m ->
                return ThreeDsTitleId((m.groupValues[1].toLong(16) shl 32) or m.groupValues[2].toLong(16))
            }
            return if (SIXTEEN_HEX.matches(trimmed)) ThreeDsTitleId(java.lang.Long.parseUnsignedLong(trimmed, 16)) else null
        }
    }
}

/**
 * Reads the title ID from a 3DS cartridge image (`.3ds`, `.cci`): its NCSD header has "NCSD" at
 * 0x100 and the title ID at 0x108, little-endian. That header isn't encrypted, so this works on
 * any dump. CIA and 3DSX files are not cartridge images and give null, as does anything unreadable.
 */
object NcsdReader {
    private const val MAGIC_OFFSET = 0x100
    private const val TITLE_ID_OFFSET = 0x108
    private val MAGIC = "NCSD".toByteArray(Charsets.US_ASCII)
    private val EXTENSIONS = setOf("3ds", "cci")

    fun titleId(rom: Path): ThreeDsTitleId? {
        if (rom.extension.lowercase() !in EXTENSIONS) return null
        val header = ByteBuffer.allocate(TITLE_ID_OFFSET + 8).order(ByteOrder.LITTLE_ENDIAN)
        try {
            FileChannel.open(rom, StandardOpenOption.READ).use { channel ->
                while (header.hasRemaining()) if (channel.read(header) < 0) return null
            }
        } catch (e: IOException) {
            return null
        }
        if (MAGIC.indices.any { header.get(MAGIC_OFFSET + it) != MAGIC[it] }) return null
        return ThreeDsTitleId(header.getLong(TITLE_ID_OFFSET))
    }
}
