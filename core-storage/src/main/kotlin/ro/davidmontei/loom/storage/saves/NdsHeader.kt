package ro.davidmontei.loom.storage.saves

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * What a DS ROM's header says about where melonDS keeps its save: the game code (4 ASCII
 * characters at 0x0C) and, past the DS header, the DSi title ID's high half (u32 LE at 0x234), its
 * category. [dsiCategory] is null when the file ends before it.
 */
data class NdsHeader(val gameCode: String, val dsiCategory: Long?) {
    /**
     * DSiWare, as melonDS tells it (2.0.1, `RomProcessor.getRomMetadata`): a game code starting
     * with H or K and the DSiWare category. melonDS runs it from its DSi NAND image, which keeps
     * its save, so there is no `.sav` for Loom to sync. A DSi-enhanced or DSi-only cartridge
     * (category 0x00030000, other game codes) saves to a `.sav` like any cartridge.
     */
    val isDsiWare: Boolean get() = (gameCode.startsWith('H') || gameCode.startsWith('K')) && dsiCategory == DSIWARE_CATEGORY

    companion object {
        private const val GAME_CODE_OFFSET = 0x0C
        private const val DSI_CATEGORY_OFFSET = 0x234
        private const val DSIWARE_CATEGORY = 0x0003_0004L

        /**
         * The header of [rom]; null when it is too short to hold a game code, which melonDS
         * would take for a cartridge too.
         *
         * @throws java.io.IOException when the file can't be read (the card was pulled): try again later.
         */
        fun read(rom: Path): NdsHeader? {
            val header = ByteBuffer.allocate(DSI_CATEGORY_OFFSET + 4).order(ByteOrder.LITTLE_ENDIAN)
            val length = FileChannel.open(rom, StandardOpenOption.READ).use { channel ->
                var total = 0
                while (total < header.capacity()) {
                    val n = channel.read(header, total.toLong())
                    if (n < 0) break
                    total += n
                }
                total
            }
            if (length < GAME_CODE_OFFSET + 4) return null
            val gameCode = String(header.array(), GAME_CODE_OFFSET, 4, Charsets.ISO_8859_1)
            val category = if (length < header.capacity()) null else header.getInt(DSI_CATEGORY_OFFSET).toLong() and 0xFFFF_FFFFL
            return NdsHeader(gameCode, category)
        }
    }
}
