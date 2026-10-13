package ro.davidmontei.loom.data.mapping

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.settings.SettingsStore
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

    /** The folders in [root], or only those whose names are in [only] (compared as the card does, ignoring case). */
    fun scan(root: File, only: Collection<String>? = null): List<SystemFolder> {
        val wanted = only?.map(FolderNames::key)?.toSet()
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
            .filter { wanted == null || FolderNames.key(it.name) in wanted }
            .sortedBy { it.name.lowercase() }
        return dirs.map { dir ->
            val games = dir.list().orEmpty().count { !it.startsWith(".") && !it.equals(ES_DE_INFO, ignoreCase = true) }
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
    /** Where [slug]'s games go, or the folders to ask about. Without a ROMs folder there is nothing to look at. */
    suspend fun decide(slug: String, fsSlug: String): FolderDecision = withContext(Dispatchers.IO) {
        val platform = mapping.currentMapper().resolve(slug, fsSlug)
        val root = settings.snapshot().romsRoot?.let(::File)
        val onCard = if (root == null || platform.folderChosen) emptyList() else SystemFolderScanner.scan(root, platform.candidates)
        FolderDetector.decide(platform, onCard)
    }

    /** Every folder in the ROMs root, for choosing one that isn't among a platform's usual names. */
    suspend fun allFolders(): List<SystemFolder> = withContext(Dispatchers.IO) {
        settings.snapshot().romsRoot?.let { SystemFolderScanner.scan(File(it)) }.orEmpty()
    }

    suspend fun choose(slug: String, fsSlug: String, folder: String) = mapping.chooseFolder(slug, fsSlug, folder)
}
