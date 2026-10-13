package ro.davidmontei.loom.storage.plan

import kotlinx.serialization.Serializable
import ro.davidmontei.loom.storage.mapping.MultiDiscLayout
import java.util.Locale

/** A file that will end up in the install: a downloaded part or a member of an unpacked archive. */
data class PayloadFile(val relPath: String, val size: Long)

@Serializable
enum class EntryKind { FILE, DIRECTORY }

/** Copy [from] (payload path) to [to] (path inside the entry; "" when the entry *is* the file). */
data class Placement(val from: String, val to: String)

/** A file Loom writes itself: a generated playlist or a sheet rewritten after renaming its tracks. */
data class GeneratedFile(val to: String, val content: String)

/** The single entry an install adds to its system folder, and how to fill it. */
data class Layout(
    val entryName: String,
    val kind: EntryKind,
    val placements: List<Placement>,
    val generated: List<GeneratedFile>,
    val isDiscSet: Boolean,
    /** A plain folder: Cocoon only sees it with "Scan Subfolders" on. */
    val needsSubfolderScan: Boolean,
)

/**
 * Turns the files of one ROM into exactly one entry in its system folder, so the install can be
 * made visible with one rename and Cocoon shows it once:
 *
 * | payload                                   | entry                                      |
 * |-------------------------------------------|--------------------------------------------|
 * | one file (after dropping junk)            | that file                                  |
 * | discs: ≥1 launchable + its tracks/sidecars| `Name.m3u/` (or `Name/`) with `Name.m3u`   |
 * | anything else                             | `Name/` mirroring the ROM's folder         |
 *
 * A lone `.bin` on systems where Cocoon ignores names ending in "bin" is wrapped like a disc set.
 */
object LayoutDecider {
    /** Cocoon's platform regex for these folders excludes names ending in "bin". */
    private val COCOON_HIDES_BIN = setOf("psx", "dreamcast", "saturn", "segacd")

    /** Files that travel with a disc image. */
    private val SIDECARS = setOf("sbi", "sub", "lsd", "wav", "mdf", "ecm", "raw", "dat", "m3u")

    fun decide(
        entryStem: String,
        payload: List<PayloadFile>,
        multiDisc: MultiDiscLayout,
        systemFolder: String,
        readText: (String) -> String? = { null },
    ): Layout {
        var files = payload.filterNot { Discs.isJunkPath(it.relPath) }
        files.filterNot { Discs.isDocument(it.relPath) }.takeIf { it.isNotEmpty() }?.let { files = it }
        require(files.isNotEmpty()) { "Nothing to install" }
        val (collapsed, wrapper) = collapseWrapper(files)
        files = collapsed
        val read: (String) -> String? = { rel -> readText(if (wrapper == null) rel else "$wrapper/$rel") }
        val source: (String) -> String = { rel -> if (wrapper == null) rel else "$wrapper/$rel" }

        val stem = NameSanitizer.sanitize(entryStem.removeSuffix(".m3u"), fallback = "rom")

        if (files.size == 1) {
            val only = files.single()
            val name = NameSanitizer.sanitize(only.relPath.substringAfterLast('/'))
            val hiddenBin = Discs.ext(name) == "bin" && systemFolder in COCOON_HIDES_BIN
            if (!hiddenBin) {
                return Layout(name, EntryKind.FILE, listOf(Placement(source(only.relPath), "")), emptyList(),
                    isDiscSet = false, needsSubfolderScan = false)
            }
        }

        val renames = files.associate { it.relPath to NameSanitizer.sanitizePath(it.relPath) }.let(::dedupe)
        val referenced = files.filter { Discs.ext(it.relPath) in Discs.SHEETS }.flatMap { sheet ->
            val dir = sheet.relPath.substringBeforeLast('/', "")
            Discs.referencedBy(sheet.relPath, read(sheet.relPath)).map { ref -> join(dir, ref) }
        }.map { it.lowercase(Locale.ROOT) }.toSet()

        val launchables = files.filter { f ->
            val ext = Discs.ext(f.relPath)
            f.relPath.lowercase(Locale.ROOT) !in referenced &&
                (ext in Discs.LAUNCHABLE || ext in Discs.LONE_TRACKS)
        }
        val accounted = files.all { f ->
            f in launchables || f.relPath.lowercase(Locale.ROOT) in referenced || Discs.ext(f.relPath) in SIDECARS
        }
        val isDiscSet = launchables.isNotEmpty() && accounted

        val generated = mutableListOf<GeneratedFile>()
        val rewrittenSheets = mutableSetOf<String>()
        for (sheet in files.filter { Discs.ext(it.relPath) in setOf("cue", "gdi") }) {
            val text = read(sheet.relPath) ?: continue
            val dir = sheet.relPath.substringBeforeLast('/', "")
            var updated = text
            for (ref in Discs.referencedBy(sheet.relPath, text)) {
                val original = files.firstOrNull { it.relPath.equals(join(dir, ref), ignoreCase = true) } ?: continue
                val newName = renames.getValue(original.relPath).substringAfterLast('/')
                if (newName != ref) updated = updated.replace(ref, newName)
            }
            if (updated != text) {
                generated += GeneratedFile(renames.getValue(sheet.relPath), updated)
                rewrittenSheets += sheet.relPath
            }
        }

        val placements = files.filterNot { it.relPath in rewrittenSheets }
            .filterNot { isDiscSet && Discs.ext(it.relPath) == "m3u" }
            .map { Placement(source(it.relPath), renames.getValue(it.relPath)) }

        if (!isDiscSet) {
            return Layout(stem, EntryKind.DIRECTORY, placements, generated, isDiscSet = false, needsSubfolderScan = true)
        }

        val playlist = existingPlaylist(files, renames, read) ?: Discs.order(launchables.map { renames.getValue(it.relPath) })
        generated += GeneratedFile("$stem.m3u", M3u.build(playlist))
        val entryName = if (multiDisc == MultiDiscLayout.M3U_DIR) "$stem.m3u" else stem
        return Layout(entryName, EntryKind.DIRECTORY, placements, generated, isDiscSet = true,
            needsSubfolderScan = multiDisc == MultiDiscLayout.FOLDER)
    }

    /** A playlist shipped with the ROM is kept only if every entry points at a file we install. */
    private fun existingPlaylist(
        files: List<PayloadFile>,
        renames: Map<String, String>,
        read: (String) -> String?,
    ): List<String>? {
        val m3u = files.filter { Discs.ext(it.relPath) == "m3u" }
        if (m3u.size != 1) return null
        val dir = m3u.single().relPath.substringBeforeLast('/', "")
        val entries = M3u.parse(read(m3u.single().relPath) ?: return null)
        if (entries.isEmpty()) return null
        return entries.map { entry ->
            val target = files.firstOrNull { it.relPath.equals(join(dir, entry), ignoreCase = true) } ?: return null
            renames.getValue(target.relPath)
        }
    }

    /** Strips one folder that wraps everything (common in archives): "Game/a.cue" → "a.cue". */
    private fun collapseWrapper(files: List<PayloadFile>): Pair<List<PayloadFile>, String?> {
        val firsts = files.map { it.relPath.substringBefore('/', missingDelimiterValue = "") }
        val wrapper = firsts.first()
        if (wrapper.isEmpty() || firsts.any { it != wrapper } || files.any { !it.relPath.contains('/') }) return files to null
        return files.map { it.copy(relPath = it.relPath.substringAfter('/')) } to wrapper
    }

    /** Two payload paths that sanitise to the same (case-insensitive) name get "_2", "_3"… */
    private fun dedupe(renames: Map<String, String>): Map<String, String> {
        val seen = mutableMapOf<String, Int>()
        return renames.mapValues { (_, target) ->
            val key = NameSanitizer.key(target)
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            if (n == 1) target else {
                val dot = target.lastIndexOf('.')
                if (dot > target.lastIndexOf('/')) target.substring(0, dot) + "_$n" + target.substring(dot) else "${target}_$n"
            }
        }
    }

    private fun join(dir: String, name: String): String =
        (if (dir.isEmpty()) name else "$dir/$name").replace('\\', '/')
}
