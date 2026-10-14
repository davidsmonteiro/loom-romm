package ro.davidmontei.loom.data.mapping

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.storage.mapping.EmulatorData
import ro.davidmontei.loom.storage.mapping.FolderDecision
import ro.davidmontei.loom.storage.mapping.FolderDetector
import ro.davidmontei.loom.storage.mapping.FolderNames
import ro.davidmontei.loom.storage.mapping.SystemFolder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the ROMs root's folders: their names, how many entries they hold, and what ES-DE says they are. */
object SystemFolderScanner {
    /** ES-DE's description of a system folder; not a game. */
    private const val ES_DE_INFO = "systeminfo.txt"

    /**
     * The folders in [root], or only those whose names are in [only] (compared as the card does, ignoring case).
     * An emulator's own folders inside a system folder (Azahar's `sdmc`, `nand`… in `n3ds`) aren't games,
     * and the ROMs root's `saves` folder (emulators' saves, Eden's on the Thor) is no system folder.
     */
    fun scan(root: File, only: Collection<String>? = null): List<SystemFolder> {
        val wanted = only?.map(FolderNames::key)?.toSet()
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && !EmulatorData.isSavesFolder(f.name) }.orEmpty()
            .filter { wanted == null || FolderNames.key(it.name) in wanted }
            .sortedBy { it.name.lowercase() }
        return dirs.map { dir ->
            val games = dir.list().orEmpty().count {
                !it.startsWith(".") && !it.equals(ES_DE_INFO, ignoreCase = true) && !EmulatorData.isEmulatorData(it)
            }
            SystemFolder(dir.name, games, systemName(File(dir, ES_DE_INFO)))
        }
    }

    /** The line after "Full system name:" in ES-DE's systeminfo.txt. */
    private fun systemName(info: File): String? = runCatching {
        if (!info.isFile || info.length() > 64_000) return null
        info.useLines { lines -> lines.dropWhile { it.trim() != "Full system name:" }.drop(1).firstOrNull()?.trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * Chooses each platform's system folder from what is on the card (see [FolderDetector]) and
 * remembers the choice, so a platform's games keep one home even if more candidate folders appear.
 */
@Singleton
class FolderChooser @Inject constructor(
    private val settings: SettingsStore,
    private val mapping: MappingRepository,
) {
    /**
     * Where the platform's games go, or the folders to ask about. Null when Loom can't look: no ROMs
     * folder yet, the card unmounted, or no all-files access. Nothing is decided (or remembered) then;
     * queueing reports the reason.
     */
    suspend fun decide(slug: String, fsSlug: String): FolderDecision? = withContext(Dispatchers.IO) {
        val platform = mapping.currentMapper().resolve(slug, fsSlug)
        if (platform.folderChosen) return@withContext FolderDetector.decide(platform, emptyList())
        val root = readableRoot() ?: return@withContext null
        FolderDetector.decide(platform, SystemFolderScanner.scan(root, platform.candidates))
    }

    /** Which of [names] are folders in the ROMs root, with how many games each holds; null when Loom can't look. */
    suspend fun onCard(names: Collection<String>): List<SystemFolder>? = withContext(Dispatchers.IO) {
        readableRoot()?.let { SystemFolderScanner.scan(it, names) }
    }

    /** The ROMs root, when there is one and Loom may list it (it's mounted, with all-files access). */
    private suspend fun readableRoot(): File? = settings.snapshot().romsRoot?.let(::File)?.takeIf { it.list() != null }

    /** Every folder in the ROMs root that can be a system folder, for one that isn't among a platform's usual names. */
    suspend fun allFolders(): List<SystemFolder> = withContext(Dispatchers.IO) {
        settings.snapshot().romsRoot?.let { SystemFolderScanner.scan(File(it)) }.orEmpty().filter { FolderNames.validate(it.name) == null }
    }

    /** Remembers [folder] for the platform: the user's answer, or with [detected] one Loom found on the card. */
    suspend fun choose(fsSlug: String, folder: String, detected: Boolean = false) = mapping.chooseFolder(fsSlug, folder, detected)
}
