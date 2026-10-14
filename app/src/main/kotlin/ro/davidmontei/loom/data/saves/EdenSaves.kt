package ro.davidmontei.loom.data.saves

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.db.SaveDao
import ro.davidmontei.loom.data.db.SwitchTitleEntity
import ro.davidmontei.loom.data.db.SwitchTitleSource
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.storage.saves.EdenSaveLocation
import ro.davidmontei.loom.storage.saves.EdenSaveLocator
import ro.davidmontei.loom.storage.saves.EdenTitleFolder
import ro.davidmontei.loom.storage.saves.SwitchHeaderKey
import ro.davidmontei.loom.storage.saves.SwitchTitleId
import ro.davidmontei.loom.storage.saves.SwitchTitleIdReader
import ro.davidmontei.loom.storage.saves.SwitchTitleIdResult
import java.io.File
import java.io.IOException

/** The save folders the owner can pick a Switch game's from, or why there's nothing to pick. */
sealed interface EdenFolderChoice {
    /** Eden's title folders no other installed game has, newest first; [current] is the owner's earlier pick. */
    data class Options(val folders: List<EdenTitleFolder>, val current: SwitchTitleId?) : EdenFolderChoice

    data class Unavailable(val reason: String) : EdenFolderChoice
}

/**
 * Switch games' saves in Eden. Eden keeps a game's save in a folder named after its title ID,
 * which RomM 5.3.1 never supplies, so Loom reads it from the game's XCI or NSP with the header key
 * in the owner's prod.keys (ADR 0012), and remembers it with the image's size and time so later
 * scans don't read the image again. Without a usable key it asks the owner, once per game, which
 * save folder is the game's.
 *
 * The key is read from the card only when a game's title ID isn't known yet, held for that one
 * read and wiped: it is never logged, stored or uploaded.
 */
class EdenSaves(
    private val settings: SettingsStore,
    private val catalog: CatalogDao,
    private val saves: SaveDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun resolve(rom: RomEntity, install: InstallEntity): ResolvedSave = withContext(Dispatchers.IO) {
        val s = settings.snapshot()
        val dir = saveDir(s, install.romsRoot) ?: return@withContext ResolvedSave.Unsupported(
            rom.id, "Eden's saves aren't in ${defaultSaveDir(install.romsRoot)}: move Eden's save folder there, or choose it in Settings",
        )
        if (!EdenSaveLocator.holdsEdenData(dir.toPath())) {
            return@withContext ResolvedSave.Unsupported(rom.id, "There's no user/save in $dir: choose the folder Eden saves in, in Settings")
        }
        val locator = EdenSaveLocator(dir.toPath())
        try {
            val profiles = locator.profiles()
            if (profiles.size > 1 && profiles.none { it.equals(s.edenProfile, ignoreCase = true) }) {
                return@withContext ResolvedSave.Unsupported(rom.id, SEVERAL_PROFILES)
            }
            val titleId = when (val title = titleId(rom, install)) {
                is Title.Known -> title.id
                is Title.Ask -> return@withContext ResolvedSave.NeedsFolder(rom.id, title.reason)
                is Title.NoSave -> return@withContext ResolvedSave.Unsupported(rom.id, title.reason)
                is Title.CantLook -> return@withContext ResolvedSave.Unavailable(rom.id, title.reason)
            }
            // Two games syncing one folder would restore each other's saves over it.
            saves.otherGamesWithSwitchTitle(titleId.toString(), rom.id).firstOrNull()?.let { other ->
                return@withContext ResolvedSave.Unsupported(
                    rom.id, "$other has the same Eden save folder ($titleId), and Loom syncs a folder for one game only",
                )
            }
            when (val location = locator.locate(titleId, s.edenProfile)) {
                is EdenSaveLocation.Found -> ResolvedSave.Ready(
                    romId = rom.id,
                    emulator = EdenSaveLocator.EMULATOR_ID,
                    roots = listOf(location.unit),
                    fileName = SaveUnitResolver.archiveName(rom),
                    title = rom.title,
                )
                EdenSaveLocation.NoSaveFolder -> ResolvedSave.Unsupported(rom.id, "There's no user/save in $dir")
                EdenSaveLocation.NoProfile -> ResolvedSave.Unsupported(rom.id, "Eden hasn't saved a game in $dir yet: play one until it saves")
                is EdenSaveLocation.ChooseProfile -> ResolvedSave.Unsupported(rom.id, SEVERAL_PROFILES)
            }
        } catch (e: IOException) {
            ResolvedSave.Unavailable(rom.id, "Couldn't read Eden's save folder: ${e.message}")
        }
    }

    /** What the owner can pick [romId]'s save folder from: the folders in the profile Loom syncs. */
    suspend fun folderChoice(romId: Int): EdenFolderChoice = withContext(Dispatchers.IO) {
        val install = catalog.install(romId) ?: return@withContext EdenFolderChoice.Unavailable("The game isn't installed any more")
        val s = settings.snapshot()
        val dir = saveDir(s, install.romsRoot)?.takeIf { EdenSaveLocator.holdsEdenData(it.toPath()) }
            ?: return@withContext EdenFolderChoice.Unavailable("Loom can't find Eden's save folder: choose it in Settings")
        val locator = EdenSaveLocator(dir.toPath())
        try {
            val profiles = locator.profiles()
            val profile = profiles.singleOrNull() ?: profiles.firstOrNull { it.equals(s.edenProfile, ignoreCase = true) }
            if (profiles.size > 1 && profile == null) return@withContext EdenFolderChoice.Unavailable(SEVERAL_PROFILES)
            val taken = saves.switchTitlesTakenBesides(romId).toSet()
            val current = saves.switchTitle(romId)?.takeIf { it.source == SwitchTitleSource.OWNER }?.let { SwitchTitleId.parse(it.titleId) }
            EdenFolderChoice.Options(locator.titleFolders(profile).filter { it.titleId.toString() !in taken }, current)
        } catch (e: IOException) {
            EdenFolderChoice.Unavailable("Couldn't read Eden's save folder: ${e.message}")
        }
    }

    /** The owner's answer: [titleId]'s folder is [romId]'s save. It stands until they choose another. */
    suspend fun chooseFolder(romId: Int, titleId: SwitchTitleId) =
        saves.upsertSwitchTitle(SwitchTitleEntity(romId, titleId.toString(), SwitchTitleSource.OWNER, null, now()))

    private sealed interface Title {
        data class Known(val id: SwitchTitleId) : Title

        /** Loom can't read it: the owner picks the folder. */
        data class Ask(val reason: String) : Title

        /** An update or DLC: the game it belongs to keeps the save. */
        data class NoSave(val reason: String) : Title

        /** The image or the keys can't be read now: try again later. */
        data class CantLook(val reason: String) : Title
    }

    /**
     * The game's title ID: the owner's pick, else the one read from its image while the image is
     * as it was then, else read now with the owner's key, which is wiped once read.
     */
    private suspend fun titleId(rom: RomEntity, install: InstallEntity): Title {
        val recorded = saves.switchTitle(rom.id)
        if (recorded?.source == SwitchTitleSource.OWNER) SwitchTitleId.parse(recorded.titleId)?.let { return Title.Known(it) }
        val images = imagesOf(File(install.romsRoot, install.relPath))
        if (images.isEmpty()) return Title.Ask(NOT_AN_IMAGE)
        val fingerprint = images.joinToString("\n") { "${it.path}\t${it.length()}\t${it.lastModified()}" }
        if (recorded?.source == SwitchTitleSource.IMAGE && recorded.imageFingerprint == fingerprint) {
            SwitchTitleId.parse(recorded.titleId)?.let { return Title.Known(it) }
        }
        val keys = keyFiles(install.romsRoot, install.relPath.substringBefore('/')).firstOrNull(File::isFile)
            ?: return Title.Ask(NO_KEYS)
        val results = try {
            val key = SwitchHeaderKey.fromProdKeys(keys.toPath()) ?: return Title.Ask(NO_HEADER_KEY)
            key.use { k -> images.map { SwitchTitleIdReader.read(it.toPath(), k) } }
        } catch (e: IOException) {
            return Title.CantLook("Couldn't read the game's image or prod.keys: ${e.message}")
        }
        val found = results.filterIsInstance<SwitchTitleIdResult.Found>().map { it.titleId }.distinct()
        return when {
            found.size == 1 -> {
                saves.upsertSwitchTitle(SwitchTitleEntity(rom.id, found.single().toString(), SwitchTitleSource.IMAGE, fingerprint, now()))
                Title.Known(found.single())
            }
            found.size > 1 -> Title.Ask("Its files are of different games, so Loom can't tell which save is its")
            results.any { it is SwitchTitleIdResult.SeveralGames } -> Title.Ask("Its image holds several games, so Loom can't tell which save is its")
            results.any { it is SwitchTitleIdResult.WrongKey } -> Title.Ask(WRONG_KEY)
            results.any { it is SwitchTitleIdResult.UpdateOnly } -> Title.NoSave(
                "This is an update of ${results.filterIsInstance<SwitchTitleIdResult.UpdateOnly>().first().game}; the game itself keeps the save",
            )
            results.all { it is SwitchTitleIdResult.NoProgram } -> Title.NoSave("It holds no game program (DLC?), so it keeps no save of its own")
            else -> Title.Ask(
                "Loom can't read its title ID: " + (results.filterIsInstance<SwitchTitleIdResult.NotReadable>().firstOrNull()?.reason ?: "unknown image"),
            )
        }
    }

    /** The install's images: the install itself, or the ones at the top of an install folder (a game, its update, its DLC). */
    private fun imagesOf(installed: File): List<File> = when {
        installed.isFile -> listOf(installed).filter(::isImage)
        installed.isDirectory -> installed.listFiles().orEmpty().filter { it.isFile && isImage(it) }.sortedBy { it.name }
        else -> emptyList()
    }

    private fun isImage(file: File) = file.extension.lowercase() in SwitchTitleIdReader.EXTENSIONS

    companion object {
        /** Eden's save folder in the ROMs root, as on the Thor. */
        const val DEFAULT_FOLDER = "saves/Eden"

        const val SEVERAL_PROFILES = "Eden has several profiles: choose whose saves Loom syncs in Settings"
        const val NOT_AN_IMAGE = "It isn't an XCI or NSP, which Loom reads a Switch game's title ID from"
        const val NO_KEYS = "There's no prod.keys in the ROMs folder for Loom to read its title ID with"
        const val NO_HEADER_KEY = "The prod.keys in the ROMs folder has no header_key to read its title ID with"
        const val WRONG_KEY = "The header_key in prod.keys doesn't open its image, so Loom can't read its title ID"

        fun defaultSaveDir(romsRoot: String) = File(romsRoot, DEFAULT_FOLDER)

        /** Eden's save folder: the one chosen in Settings, else `<ROMs root>/saves/Eden` when it holds `user/save`. */
        fun saveDir(settings: Settings, romsRoot: String): File? =
            settings.edenSaveDir?.let(::File) ?: defaultSaveDir(romsRoot).takeIf { EdenSaveLocator.holdsEdenData(it.toPath()) }

        /** Where Loom looks for the owner's prod.keys: the ROMs root, then the Switch system folder. */
        fun keyFiles(romsRoot: String, systemFolder: String): List<File> =
            listOf(File(romsRoot, KEYS), File(File(romsRoot, systemFolder), KEYS), File(File(romsRoot, "switch"), KEYS)).distinctBy { it.path }

        private const val KEYS = "prod.keys"
    }
}
