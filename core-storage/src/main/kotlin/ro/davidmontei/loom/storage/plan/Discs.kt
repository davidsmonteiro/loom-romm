package ro.davidmontei.loom.storage.plan

import java.util.Locale

/** Knowledge about disc images: which files launch, which are tracks, and how discs are ordered. */
object Discs {
    /** Files an emulator is pointed at for one disc (unless a sheet lists them as its track). */
    val LAUNCHABLE = setOf(
        "cue", "gdi", "ccd", "mds", "cdi", "chd", "iso", "pbp", "cso", "zso", "rvz", "gcz", "wbfs", "toc",
    )

    /** Raw images that only count as a disc on their own, when no sheet references them. */
    val LONE_TRACKS = setOf("bin", "img")

    /** Sheet formats whose referenced files are tracks, not separate discs. */
    val SHEETS = setOf("cue", "gdi", "ccd", "mds", "toc")

    /** Documents and artwork that never matter to Cocoon or an emulator. */
    val JUNK_EXTENSIONS = setOf("txt", "nfo", "diz", "url", "pdf", "jpg", "jpeg", "png", "gif", "md", "htm", "html",
        "sfv", "md5", "sha1", "doc", "rtf", "db")

    private val JUNK_NAMES = setOf(".ds_store", "thumbs.db", "desktop.ini")

    fun ext(name: String): String = name.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isJunkPath(relPath: String): Boolean {
        val parts = relPath.split('/')
        val name = parts.last().lowercase(Locale.ROOT)
        return parts.any { it == "__MACOSX" } || name.startsWith("._") || name in JUNK_NAMES
    }

    fun isDocument(relPath: String): Boolean = ext(relPath) in JUNK_EXTENSIONS

    /**
     * Files referenced by a sheet, relative to the sheet's folder: `FILE "x.bin" BINARY` lines in a
     * .cue, the 5th column of .gdi track lines; .ccd/.mds use same-named companions.
     */
    fun referencedBy(sheetName: String, content: String?): List<String> {
        val base = sheetName.substringAfterLast('/').substringBeforeLast('.')
        return when (ext(sheetName)) {
            "cue" -> CUE_FILE.findAll(content.orEmpty()).map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim() }.toList()
            "gdi" -> content.orEmpty().lines().drop(1).mapNotNull { line -> GDI_TRACK.find(line.trim())?.let { m ->
                m.groupValues[1].ifEmpty { m.groupValues[2] }
            } }
            "ccd" -> listOf("$base.img", "$base.sub")
            "mds" -> listOf("$base.mdf")
            else -> emptyList()
        }
    }

    private val CUE_FILE = Regex("""(?im)^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+\S+\s*$""")
    private val GDI_TRACK = Regex("""^\d+\s+\d+\s+\d+\s+\d+\s+(?:"([^"]+)"|(\S+))\s+-?\d+$""")

    private val DISC_NUMBER = listOf(
        Regex("""(?i)[(\[]\s*(?:disc|disk|cd|dvd)\s*([0-9]+)(?:\s*of\s*[0-9]+)?\s*[)\]]"""),
        Regex("""(?i)\b(?:disc|disk|cd)\s*([0-9]+)\b"""),
    )
    private val SIDE = Regex("""(?i)[(\[]\s*side\s*([A-Z])\s*[)\]]""")

    /** Disc number parsed from tags like "(Disc 2)", "[CD2]", "(Side B)"; null when absent. */
    fun discNumber(name: String): Int? {
        DISC_NUMBER.firstNotNullOfOrNull { it.find(name) }?.let { return it.groupValues[1].toInt() }
        SIDE.find(name)?.let { return it.groupValues[1].uppercase()[0] - 'A' + 1 }
        return null
    }

    /** Orders discs by parsed number, then by a natural, case-insensitive name comparison. */
    fun order(names: List<String>): List<String> =
        names.sortedWith(compareBy<String, Int?>(nullsLast()) { discNumber(it) }.then(NaturalOrder))

    /** "Disc 2" before "Disc 10". */
    object NaturalOrder : Comparator<String> {
        private val CHUNK = Regex("""\d+|\D+""")
        override fun compare(a: String, b: String): Int {
            val x = CHUNK.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
            val y = CHUNK.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]; val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                        ?: p.trimStart('0').compareTo(q.trimStart('0'))
                } else p.compareTo(q)
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}

/** Writes `.m3u` playlists the way Cocoon, RetroArch and DuckStation read them. */
object M3u {
    /** UTF-8 without BOM, LF line endings, one relative path per line, trailing newline. */
    fun build(entries: List<String>): String = entries.joinToString(separator = "\n", postfix = "\n")

    /** Entries of an existing playlist, ignoring comments and blank lines. */
    fun parse(content: String): List<String> =
        content.removePrefix("﻿").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
}
