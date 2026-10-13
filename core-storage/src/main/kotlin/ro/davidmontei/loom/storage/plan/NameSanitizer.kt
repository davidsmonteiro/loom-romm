package ro.davidmontei.loom.storage.plan

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * Makes one path component safe for the SD card (exFAT/FAT rules, which are the strictest Loom
 * meets) and visible to Cocoon. Applied to every name Loom writes under the ROMs root.
 */
object NameSanitizer {
    /** Room left under 255 bytes for ".loomtmp" and a " (romm-123456)" collision suffix. */
    const val MAX_BYTES = 240

    private val RESERVED = Regex("^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?$", RegexOption.IGNORE_CASE)

    /** Sanitises [name]; returns [fallback] when nothing usable is left. */
    fun sanitize(name: String, fallback: String = "untitled"): String {
        var s = Normalizer.normalize(name, Normalizer.Form.NFC)
        s = buildString {
            for (c in s) when {
                c.code < 0x20 || c.code == 0x7F -> Unit
                c == ':' -> append(" -")
                c in "/\\*?\"<>|" -> append('_')
                else -> append(c)
            }
        }
        s = s.replace(Regex("\\s+"), " ")
        // Trailing dots/spaces are silently dropped by vfat/exfat; leading dots hide the file from Cocoon.
        s = s.trimEnd('.', ' ').trimStart(' ', '.')
        if (s.isEmpty()) return fallback
        if (RESERVED.matches(s)) {
            val dot = s.indexOf('.')
            s = if (dot < 0) "${s}_" else s.substring(0, dot) + "_" + s.substring(dot)
        }
        return truncate(s)
    }

    /**
     * Shortens to [maxBytes] of UTF-8, keeping the extension (when it's short) and never splitting
     * a character or grapheme cluster.
     */
    fun truncate(name: String, maxBytes: Int = MAX_BYTES): String {
        if (utf8Length(name) <= maxBytes) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
        val stem = name.substring(0, name.length - ext.length)
        val budget = maxBytes - utf8Length(ext)
        val it = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(stem) }
        var end = 0
        var next = it.next()
        while (next != BreakIterator.DONE && utf8Length(stem.substring(0, next)) <= budget) {
            end = next
            next = it.next()
        }
        return stem.substring(0, end).trimEnd(' ', '.') + ext
    }

    /** Sanitises each component of a relative path ("Disc 1/Game.cue"). Empty components are dropped. */
    fun sanitizePath(relPath: String): String =
        relPath.split('/', '\\').filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/") { sanitize(it) }

    /**
     * Comparison key for a path on a case-insensitive file system (exFAT, FAT, casefolded f2fs):
     * two names with the same key collide.
     */
    fun key(path: String): String =
        Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT).uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    /** Adds a stable suffix before the extension to separate two ROMs that sanitise to one name. */
    fun withSuffix(name: String, romId: Int, isDirectory: Boolean): String {
        val suffix = " (romm-$romId)"
        if (isDirectory && !name.endsWith(".m3u")) return truncate(name + suffix)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) truncate(name.substring(0, dot) + suffix + name.substring(dot)) else truncate(name + suffix)
    }

    private fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
