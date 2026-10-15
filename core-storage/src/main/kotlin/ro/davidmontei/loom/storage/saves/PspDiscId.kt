package ro.davidmontei.loom.storage.saves

/**
 * A PSP disc ID, e.g. `ULUS10064`: four capital letters, then five digits. PPSSPP names a game's
 * save folders after it (`ULUS10064DATA00`, `ULUS10064SETTINGS`), and its installed data's too.
 * The disc carries it twice: in `UMD_DATA.BIN` with a dash (`ULUS-10064`), and as
 * `PSP_GAME/PARAM.SFO`'s `DISC_ID` without, which is where PPSSPP takes it from. RomM reports it
 * without the dash, as Sigil reads it from `UMD_DATA.BIN`.
 */
data class PspDiscId(val value: String) {
    init {
        require(SHAPE.matches(value)) { "Not a PSP disc ID: $value" }
    }

    override fun toString(): String = value

    companion object {
        private val SHAPE = Regex("[A-Z]{4}[0-9]{5}")
        private const val UMD_SCAN_BYTES = 2048

        /** [text] as a disc ID, as RomM and PARAM.SFO write one; null for any other shape. */
        fun parse(text: String): PspDiscId? = text.trim().takeIf(SHAPE::matches)?.let(::PspDiscId)

        /**
         * The disc ID `UMD_DATA.BIN` starts with (`ULUS-10064|0123456789ABCDEF|0001|G`), read as
         * Sigil reads it (`psp.c`): within its first [length] bytes, at most 2048 and at least 10,
         * the text before the first `|`, without spaces or tabs around it, must be four capitals,
         * an optional dash and five digits. The dash is dropped.
         */
        fun fromUmdData(bytes: ByteArray, length: Int = bytes.size): PspDiscId? {
            val scan = minOf(length, bytes.size, UMD_SCAN_BYTES)
            if (scan < 10) return null
            val pipe = (0 until scan).firstOrNull { bytes[it] == '|'.code.toByte() } ?: return null
            val field = String(bytes, 0, pipe, Charsets.ISO_8859_1).trim(' ', '\t')
            val id = when {
                field.length == 9 -> field
                field.length == 10 && field[4] == '-' -> field.removeRange(4, 5)
                else -> return null
            }
            return id.takeIf(SHAPE::matches)?.let(::PspDiscId)
        }
    }
}
