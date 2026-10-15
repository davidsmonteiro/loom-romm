package ro.davidmontei.loom.storage.saves

import ro.davidmontei.loom.storage.engine.formatBytes
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.Locale
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Where a PSP game's saves are on PPSSPP's memory stick, or why Loom can't say. */
sealed interface PpssppSaveLocation {
    /**
     * The save unit: every folder in SAVEDATA whose name starts with the game's disc ID, its
     * installed data left out. It may hold no folder yet (no save on this device).
     */
    data class Found(val unit: SaveUnit.Prefixed) : PpssppSaveLocation

    /** No SAVEDATA folder: PPSSPP hasn't run with this memory stick (it makes SAVEDATA as it starts). */
    data object NoSaveData : PpssppSaveLocation
}

/** One disc ID's save folders in SAVEDATA, for the owner to pick a game's from when Loom can't tell its disc ID. */
data class PspSavePrefix(val discId: PspDiscId, val folderCount: Int, val bytes: Long, val newestModified: Instant?, val fileCount: Int = 0)

/**
 * Finds PSP saves on PPSSPP's memory stick (Settings → System → Memory Stick folder; on the Thor,
 * `ROMs/saves/PPSSPP`). PPSSPP keeps them in `PSP/SAVEDATA`, or `SAVEDATA` when the chosen folder
 * is itself named PSP (`PathUtil.cpp`), one folder per save named after the game's disc ID and
 * the save (`ULUS10064DATA00`, `ULUS10064SETTINGS`). A game's save is all of them, as Argosy
 * uploads it: one zip of those folders, each under its own name.
 *
 * Games that install data to the memory stick put it in SAVEDATA too, under the same prefix. It
 * is a copy of the disc, not progress, and is left out by Argosy's rule exactly, so a save
 * hashes the same on both sides ([isGameDataInstall]).
 */
object PpssppSaveLocator {
    /** The emulator id Loom gives RomM for PPSSPP saves, as Argosy and RomM's web player do. */
    const val EMULATOR_ID = "ppsspp"

    private const val SAVEDATA = "SAVEDATA"
    private const val PSP = "PSP"
    private const val PARAM_SFO = "PARAM.SFO"
    private val INI_FILES = listOf("ppsspp.ini", "ppssppvr.ini")
    private val SAVEDATA_KEYS = listOf("SAVEDATA_PARAMS", "SAVEDATA_FILE_LIST")

    /**
     * The SAVEDATA folder of the memory stick the owner chose: the memory stick itself, its PSP
     * folder, SAVEDATA or a folder in it all lead there, as in Argosy. A path through a SAVEDATA
     * folder is cut there; otherwise the first of `SAVEDATA` and `PSP/SAVEDATA` in it that exists,
     * else where PPSSPP would make it.
     */
    fun saveDataOf(chosen: Path): Path {
        val at = (0 until chosen.nameCount).lastOrNull { chosen.getName(it).toString().equals(SAVEDATA, ignoreCase = true) }
        if (at != null) return generateSequence(chosen) { it.parent }.elementAt(chosen.nameCount - 1 - at)
        return listOf(chosen.resolve(SAVEDATA), chosen.resolve(PSP).resolve(SAVEDATA)).firstOrNull { it.isDirectory(LinkOption.NOFOLLOW_LINKS) }
            ?: if (chosen.name.equals(PSP, ignoreCase = true)) chosen.resolve(SAVEDATA) else chosen.resolve(PSP).resolve(SAVEDATA)
    }

    /** Whether [chosen] is PPSSPP's memory stick: PPSSPP keeps its settings in `PSP/SYSTEM/ppsspp.ini` there. */
    fun holdsPpssppData(chosen: Path): Boolean {
        val system = saveDataOf(chosen).parent?.resolve("SYSTEM") ?: return false
        return INI_FILES.any { system.resolve(it).isRegularFile(LinkOption.NOFOLLOW_LINKS) }
    }

    /**
     * The saves of the game with [discId] in [saveData], matched ignoring case as Argosy and the
     * SD card do.
     *
     * @throws IOException when SAVEDATA can't be listed: try again later.
     */
    fun locate(saveData: Path, discId: PspDiscId): PpssppSaveLocation {
        if (!saveData.isDirectory(LinkOption.NOFOLLOW_LINKS)) return PpssppSaveLocation.NoSaveData
        val (installs, saves) = folders(saveData).filter { it.name.startsWith(discId.value, ignoreCase = true) }.partition(::isGameDataInstall)
        val roots = saves.map { SaveRoot(it.name, it) }.sortedWith(compareBy(CodePointOrder) { it.name })
        return PpssppSaveLocation.Found(SaveUnit.Prefixed(saveData, discId.value, roots, installs.map { it.name }.toSet()))
    }

    /**
     * The disc IDs SAVEDATA's save folders start with, each with its folders' count, size and
     * newest change, the most recently played first: what the owner picks a game's saves from.
     * Installed data and folders not named after a disc ID are left out.
     *
     * @throws IOException when SAVEDATA can't be read: try again later.
     */
    fun prefixes(saveData: Path): List<PspSavePrefix> {
        if (!saveData.isDirectory(LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return folders(saveData)
            .mapNotNull { folder -> PspDiscId.parse(folder.name.take(9).uppercase(Locale.ROOT))?.let { it to folder } }
            .filterNot { (_, folder) -> isGameDataInstall(folder) }
            .groupBy({ it.first }, { it.second })
            .map { (discId, folders) ->
                val snapshot = UnitSnapshot.of(folders.map { SaveRoot(it.name, it) })
                PspSavePrefix(discId, folders.size, snapshot.totalBytes, snapshot.newestModified, snapshot.fileCount)
            }
            .sortedWith(compareByDescending<PspSavePrefix> { it.newestModified }.thenBy { it.discId.value })
    }

    /**
     * Whether [folder] holds a game's installed data rather than a save, by Argosy's rule
     * (`PspFolderHandler.isGameDataInstall`, v2.21 and v2.22): its `PARAM.SFO` is a file of at most
     * 64 KiB, Loom can read it, and its bytes hold neither `SAVEDATA_PARAMS` nor
     * `SAVEDATA_FILE_LIST`, which every save PPSSPP writes has and an install never does. A
     * search for the bytes, not a parse. A folder without one, or with one Loom can't read, is
     * kept as a save rather than dropped on a guess.
     */
    fun isGameDataInstall(folder: Path): Boolean {
        val sfo = folder.resolve(PARAM_SFO)
        val bytes = try {
            val attrs = Files.readAttributes(sfo, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attrs.isRegularFile || attrs.size() > ParamSfo.MAX_BYTES) return false
            Files.readAllBytes(sfo)
        } catch (e: IOException) {
            return false
        }
        return bytes.size <= ParamSfo.MAX_BYTES && isInstallSfo(bytes)
    }

    private fun isInstallSfo(bytes: ByteArray): Boolean = SAVEDATA_KEYS.none { bytes.containsAscii(it) }

    /**
     * The unit to restore [archive] into: [unit]'s folders on the card and the game's folders the
     * archive brings, as Argosy's restore replaces all the game's folders with the archive's.
     * Archive roots not starting with the unit's prefix are another game's and are skipped; so is
     * installed data in the archive (an Argosy before v2.21 uploaded it), judged by its archived
     * `PARAM.SFO` as [isGameDataInstall] judges a folder.
     *
     * @throws UnrestorableSaveException when the archive isn't a zip or holds no save folder of
     *   this game; when it would put a save where the card keeps this game's installed data, or
     *   installed data where the card keeps a save; when a folder's name differs from another's
     *   only in case, which the SD card can't tell apart; or when it unpacks to more than
     *   [maxBytes].
     * @throws IOException when the archive can't be read.
     */
    fun forRestore(unit: SaveUnit.Prefixed, archive: Path, maxBytes: Long = SaveZipper.MAX_UNIT_BYTES): SaveUnit.Prefixed {
        if (!SaveHasher.isZip(archive)) throw UnrestorableSaveException("RomM's save for this game isn't an archive of PPSSPP's save folders; nothing was replaced")
        val inArchive = SaveZipper.rootsIn(archive)
        val ours = inArchive.filter { it.startsWith(unit.prefix, ignoreCase = true) }
        if (ours.isEmpty()) {
            throw UnrestorableSaveException(
                if (inArchive.isEmpty()) {
                    "RomM's save isn't laid out as PPSSPP saves are; nothing was replaced"
                } else {
                    "RomM's save holds ${names(inArchive)}, not folders starting with ${unit.prefix}; nothing was replaced"
                },
            )
        }
        val (installs, saves) = ours.partition { root ->
            SaveZipper.readEntry(archive, "$root/$PARAM_SFO", ParamSfo.MAX_BYTES)?.let(::isInstallSfo) ?: false
        }
        installs.firstOrNull { name -> unit.roots.any { it.name.equals(name, ignoreCase = true) } }?.let {
            throw UnrestorableSaveException("RomM's save holds $it as installed game data, where the Thor keeps a save; nothing was replaced")
        }
        if (saves.isEmpty()) throw UnrestorableSaveException("RomM's save holds only installed game data for ${unit.prefix}, no save; nothing was replaced")
        saves.firstOrNull { name -> unit.leftOut.any { it.equals(name, ignoreCase = true) } }?.let {
            throw UnrestorableSaveException("RomM's save holds $it as a save, where the Thor keeps this game's installed data; nothing was replaced")
        }
        for (name in saves) {
            if ('\\' in name || '\u0000' in name) throw UnrestorableSaveException("RomM's save names a folder Loom can't make; nothing was replaced")
            unit.roots.firstOrNull { it.name != name && it.name.equals(name, ignoreCase = true) }?.let {
                throw UnrestorableSaveException("RomM's save names $name where the Thor has ${it.name}, which differs only in case; nothing was replaced")
            }
            saves.firstOrNull { it != name && it.equals(name, ignoreCase = true) }?.let {
                throw UnrestorableSaveException("RomM's save names both $name and $it, which the SD card can't tell apart; nothing was replaced")
            }
        }
        val added = saves.filter { name -> unit.roots.none { it.name == name } }.map { SaveRoot(it, unit.parent.resolve(it)) }
        val roots = (unit.roots + added).sortedWith(compareBy(CodePointOrder) { it.name })
        val size = SaveZipper.unpackedSize(archive, roots.map { it.name }.toSet())
        if (size > maxBytes) {
            throw UnrestorableSaveException("RomM's save unpacks to ${formatBytes(size)}, more than Loom restores (${formatBytes(maxBytes)}); nothing was replaced")
        }
        return unit.copy(roots = roots)
    }

    /** A few of [roots]' names, for a message. */
    private fun names(roots: Collection<String>): String =
        roots.take(3).joinToString() + if (roots.size > 3) " and ${roots.size - 3} more" else ""

    /** The folders directly in [dir]. */
    private fun folders(dir: Path): List<Path> = checkedIo {
        Files.list(dir).use { list -> list.iterator().asSequence().filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) }.toList() }
    }

    /** Whether [needle] appears in the bytes as ASCII, as Argosy looks for a key in PARAM.SFO. */
    private fun ByteArray.containsAscii(needle: String): Boolean {
        val target = needle.toByteArray(Charsets.US_ASCII)
        if (target.size > size) return false
        return (0..size - target.size).any { start -> target.indices.all { this[start + it] == target[it] } }
    }
}
