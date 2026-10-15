package ro.davidmontei.loom.data.saves

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.db.SaveDao
import ro.davidmontei.loom.data.db.SaveKeyEntity
import ro.davidmontei.loom.data.db.SaveKeySource
import ro.davidmontei.loom.data.db.SyncedFolderEntity
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.storage.saves.EdenSaveLocation
import ro.davidmontei.loom.storage.saves.EdenSaveLocator
import ro.davidmontei.loom.storage.saves.SAVE_SLOT
import ro.davidmontei.loom.storage.saves.SaveRoot
import ro.davidmontei.loom.storage.saves.SaveUnit
import ro.davidmontei.loom.storage.saves.SwitchHeaderKey
import ro.davidmontei.loom.storage.saves.SwitchTitleId
import ro.davidmontei.loom.storage.saves.SwitchTitleIdReader
import ro.davidmontei.loom.storage.saves.SwitchTitleIdResult
import ro.davidmontei.loom.storage.saves.UnitSnapshot
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/** The two Eden folders a Switch game's save can be synced from, or why there's nothing to pick. */
sealed interface SyncedFolderChoice {
    /** Both folders, newest first; [current] is the one Loom syncs, or synced until Eden moved the save. */
    data class Options(val folders: List<SyncedFolderOption>, val current: Path?) : SyncedFolderChoice

    data class Unavailable(val reason: String) : SyncedFolderChoice
}

/** One of a Switch game's two Eden folders, as the owner tells them apart: which it is, and what it holds. */
data class SyncedFolderOption(val folder: Path, val consoleWide: Boolean, val fileCount: Int, val bytes: Long, val newestModified: Instant?)

/**
 * Switch games' saves in Eden. Eden keeps a game's save in a folder named after its title ID,
 * which RomM 5.3.1 never supplies, so Loom reads it from the game's XCI or NSP with the header key
 * in the owner's prod.keys (ADR 0012), and remembers it with the image's size and time so later
 * scans don't read the image again. Without a usable key it asks the owner, once per game, which
 * save folder is the game's.
 *
 * The key is read from the card only when a game's title ID isn't known yet, held for that one
 * read and dropped: it is never logged, stored or uploaded.
 */
class EdenSaves(
    private val settings: SettingsStore,
    private val catalog: CatalogDao,
    private val saves: SaveDao,
    private val now: () -> Long = System::currentTimeMillis,
    /** The largest Switch save Loom restores, and so uploads. */
    private val maxUnitBytes: Long = MAX_UNIT_BYTES,
) {
    suspend fun resolve(rom: RomEntity, install: InstallEntity): ResolvedSave = withContext(Dispatchers.IO) {
        val placed = when (val p = place(rom, install)) {
            is Placed.Stop -> return@withContext p.save
            is Placed.Known -> p
        }
        val dir = placed.dir
        try {
            val synced = saves.baseline(rom.id, SAVE_SLOT)?.unitPath?.let { Paths.get(it) }
            val pinned = saves.syncedFolder(rom.id)?.folder?.let { Paths.get(it) }
            when (val location = placed.locator.locate(placed.titleId, placed.profile, synced, pinned)) {
                is EdenSaveLocation.Found -> ResolvedSave.Ready(
                    romId = rom.id,
                    emulator = EdenSaveLocator.EMULATOR_ID,
                    unit = SaveUnit.Folders(listOf(location.unit)),
                    fileName = SaveUnitResolver.archiveName(rom),
                    title = rom.title,
                    maxUnitBytes = maxUnitBytes,
                )
                EdenSaveLocation.NoSaveFolder -> ResolvedSave.Unsupported(rom.id, "There's no user/save in $dir")
                EdenSaveLocation.NoProfile -> ResolvedSave.Unsupported(rom.id, "Eden hasn't saved a game in $dir yet: play one until it saves")
                is EdenSaveLocation.ChooseProfile -> ResolvedSave.Unsupported(rom.id, SEVERAL_PROFILES)
                is EdenSaveLocation.Moved -> ResolvedSave.Moved(
                    rom.id, "Eden now keeps this game's save in its ${kind(location.now)} folder, but Loom synced its " +
                        "${kind(location.synced)} one, which still holds a save. Choose which one Loom syncs",
                )
            }
        } catch (e: IOException) {
            ResolvedSave.Unavailable(rom.id, "Couldn't read Eden's save folder: ${e.message}")
        }
    }

    /** Where a Switch game's save is looked for: Eden's folder, the profile and the title ID, or why Loom can't look. */
    private sealed interface Placed {
        class Known(val dir: File, val locator: EdenSaveLocator, val profile: String?, val titleId: SwitchTitleId) : Placed

        /** [save] says why not: never [ResolvedSave.Ready]. */
        class Stop(val save: ResolvedSave) : Placed
    }

    private suspend fun place(rom: RomEntity, install: InstallEntity): Placed {
        val s = settings.snapshot()
        val dir = saveDir(s, install.romsRoot) ?: return Placed.Stop(ResolvedSave.Unsupported(
            rom.id, "Eden's saves aren't in ${defaultSaveDir(install.romsRoot)}: move Eden's save folder there, or choose it in Settings",
        ))
        if (!EdenSaveLocator.holdsEdenData(dir.toPath())) {
            return Placed.Stop(ResolvedSave.Unsupported(rom.id, "There's no user/save in $dir: choose the folder Eden saves in, in Settings"))
        }
        val locator = EdenSaveLocator(dir.toPath())
        try {
            val profiles = locator.profiles()
            if (profiles.size > 1 && profiles.none { it.equals(s.edenProfile, ignoreCase = true) }) {
                return Placed.Stop(ResolvedSave.Unsupported(rom.id, SEVERAL_PROFILES))
            }
            val titleId = when (val title = titleId(rom, install)) {
                is Title.Known -> title.id
                is Title.Ask -> return Placed.Stop(ResolvedSave.NeedsFolder(rom.id, title.reason))
                is Title.NoSave -> return Placed.Stop(ResolvedSave.Unsupported(rom.id, title.reason))
                is Title.CantLook -> return Placed.Stop(ResolvedSave.Unavailable(rom.id, title.reason))
            }
            // Two games syncing one folder would restore each other's saves over it.
            saves.otherGamesWithSaveKey(EMULATOR, titleId.toString(), rom.id).firstOrNull()?.let { other ->
                return Placed.Stop(ResolvedSave.Unsupported(
                    rom.id, "$other has the same Eden save folder ($titleId), and Loom syncs a folder for one game only",
                ))
            }
            return Placed.Known(dir, locator, s.edenProfile, titleId)
        } catch (e: IOException) {
            return Placed.Stop(ResolvedSave.Unavailable(rom.id, "Couldn't read Eden's save folder: ${e.message}"))
        }
    }

    /**
     * The two folders [romId]'s save can be synced from, for the owner to pick one when Eden kept
     * it in both (its console-wide folder and the profile's): what each holds, newest first, and
     * the one Loom syncs now, or synced until Eden moved it.
     */
    suspend fun syncedFolderChoice(romId: Int): SyncedFolderChoice = withContext(Dispatchers.IO) {
        val install = catalog.install(romId) ?: return@withContext SyncedFolderChoice.Unavailable("The game isn't installed any more")
        val rom = catalog.rom(romId) ?: return@withContext SyncedFolderChoice.Unavailable("The game isn't in the catalog any more")
        val placed = when (val p = place(rom, install)) {
            is Placed.Stop -> return@withContext SyncedFolderChoice.Unavailable(reasonOf(p.save))
            is Placed.Known -> p
        }
        try {
            val folders = placed.locator.bothFolders(placed.titleId, placed.profile)
            if (folders.size < 2) return@withContext SyncedFolderChoice.Unavailable("Eden has no profile folder for this game's save yet")
            val current = listOfNotNull(saves.syncedFolder(romId)?.folder, saves.baseline(romId, SAVE_SLOT)?.unitPath)
                .map { Paths.get(it) }.firstOrNull { it in folders }
            val options = folders.map { folder ->
                val snapshot = UnitSnapshot.of(listOf(SaveRoot(placed.titleId.toString(), folder)))
                SyncedFolderOption(folder, EdenSaveLocator.isConsoleWideFolder(folder), snapshot.fileCount, snapshot.totalBytes, snapshot.newestModified)
            }
            SyncedFolderChoice.Options(options.sortedWith(compareByDescending<SyncedFolderOption> { it.newestModified }.thenBy { !it.consoleWide }), current)
        } catch (e: IOException) {
            SyncedFolderChoice.Unavailable("Couldn't read Eden's save folder: ${e.message}")
        }
    }

    /**
     * The owner's answer to [SyncedFolderChoice]: Loom syncs [romId]'s save from [folder] from now
     * on, and leaves the other folder as it is. Nothing moves; the next look compares [folder]'s
     * save with RomM's as for a first sync, unless it is the folder last synced.
     */
    suspend fun chooseSyncedFolder(romId: Int, folder: Path) =
        saves.upsertSyncedFolder(SyncedFolderEntity(romId, folder.toString(), now()))

    private fun reasonOf(save: ResolvedSave): String = when (save) {
        is ResolvedSave.Unsupported -> save.reason
        is ResolvedSave.NeedsFolder -> save.reason
        is ResolvedSave.Moved -> save.reason
        is ResolvedSave.Unavailable -> save.reason
        is ResolvedSave.Ready -> ""
    }

    /**
     * What the owner can pick [romId]'s save folder from: the title folders in the profile Loom
     * syncs that no other installed game has, newest first.
     */
    suspend fun folderChoice(romId: Int): FolderChoice = withContext(Dispatchers.IO) {
        val install = catalog.install(romId) ?: return@withContext FolderChoice.Unavailable("The game isn't installed any more")
        val s = settings.snapshot()
        val dir = saveDir(s, install.romsRoot)?.takeIf { EdenSaveLocator.holdsEdenData(it.toPath()) }
            ?: return@withContext FolderChoice.Unavailable("Loom can't find Eden's save folder: choose it in Settings")
        val locator = EdenSaveLocator(dir.toPath())
        try {
            val profiles = locator.profiles()
            val profile = profiles.singleOrNull() ?: profiles.firstOrNull { it.equals(s.edenProfile, ignoreCase = true) }
            if (profiles.size > 1 && profile == null) return@withContext FolderChoice.Unavailable(SEVERAL_PROFILES)
            val taken = saves.saveKeysTakenBesides(EMULATOR, romId).toSet()
            val current = saves.saveKey(EMULATOR, romId)?.takeIf { it.source == SaveKeySource.OWNER }?.saveKey
            val folders = locator.titleFolders(profile).filter { it.titleId.toString() !in taken }
            FolderChoice.Options(folders.map { FolderChoice.Option(it.titleId.toString(), it.fileCount, it.bytes, it.newestModified) }, current)
        } catch (e: IOException) {
            FolderChoice.Unavailable("Couldn't read Eden's save folder: ${e.message}")
        }
    }

    /**
     * The owner's answer: [titleId]'s folder is [romId]'s save. It stands until they choose another.
     * Which of that title's two folders Loom syncs is asked again, should Eden keep it in both.
     */
    suspend fun chooseFolder(romId: Int, titleId: SwitchTitleId) {
        saves.upsertSaveKey(SaveKeyEntity(EMULATOR, romId, titleId.toString(), SaveKeySource.OWNER, null, now()))
        saves.forgetSyncedFolder(romId)
    }

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
     * The game's title ID: the one read from its image while the image is as it was then, else
     * read now with the owner's key, which is dropped once read. The owner's pick stands while the
     * image can't say otherwise ([checkPick]).
     */
    private suspend fun titleId(rom: RomEntity, install: InstallEntity): Title {
        val recorded = saves.saveKey(EMULATOR, rom.id)
        val images = imagesOf(File(install.romsRoot, install.relPath))
        val imagesPrint = images.joinToString("\n") { "${it.path}\t${it.length()}\t${it.lastModified()}" }
        val keyFiles = keyFiles(install.romsRoot, install.relPath.substringBefore('/')).filter(File::isFile)
        if (recorded?.source == SaveKeySource.OWNER) {
            SwitchTitleId.parse(recorded.saveKey)?.let { return checkPick(recorded, it, images, imagesPrint, keyFiles) }
        }
        if (recorded?.source == SaveKeySource.IMAGE && recorded.imageFingerprint == imagesPrint) {
            SwitchTitleId.parse(recorded.saveKey)?.let { return Title.Known(it) }
        }
        // From here an ID read from the image before no longer holds: the image changed. If this
        // read finds none, it is forgotten, so it doesn't keep its folder from other games.
        val title = readTitle(images, keyFiles)
        if (title is Title.Known) {
            saves.upsertSaveKey(SaveKeyEntity(EMULATOR, rom.id, title.id.toString(), SaveKeySource.IMAGE, imagesPrint, now()))
        } else if (recorded?.source == SaveKeySource.IMAGE && title !is Title.CantLook) {
            saves.forgetLearnedSaveKey(EMULATOR, rom.id)
        }
        return title
    }

    /**
     * The owner's pick, checked against the game's image whenever the image or prod.keys changed:
     * an image that names another title stops the game until the owner chooses again, as syncing
     * another game's folder under this one would put the wrong save on RomM. Once the image agrees,
     * or can't tell (no key, a file Loom can't read), the pick is kept with what was looked at.
     */
    private suspend fun checkPick(recorded: SaveKeyEntity, chosen: SwitchTitleId, images: List<File>, imagesPrint: String, keyFiles: List<File>): Title {
        val looked = imagesPrint + keyFiles.joinToString("") { "\nkeys\t${it.path}\t${it.length()}\t${it.lastModified()}" }
        if (images.isEmpty() || recorded.imageFingerprint == looked) return Title.Known(chosen)
        val said = when (val read = readImages(images, keyFiles)) {
            is Read.Failed -> return Title.CantLook(read.reason)
            is Read.Done -> read.results.flatMap {
                when (it) {
                    is SwitchTitleIdResult.Found -> listOf(it.titleId)
                    is SwitchTitleIdResult.UpdateOnly -> listOf(it.game)
                    is SwitchTitleIdResult.SeveralGames -> it.titleIds
                    else -> emptyList()
                }
            }.distinct()
            else -> emptyList()
        }
        if (said.isNotEmpty() && chosen !in said) {
            return Title.Ask("You chose the $chosen save folder, but the game's file says it is ${said.joinToString(" or ")}. Choose its folder again")
        }
        saves.upsertSaveKey(recorded.copy(imageFingerprint = looked))
        return Title.Known(chosen)
    }

    /** What the game's images say its title ID is, read with the owner's key. */
    private fun readTitle(images: List<File>, keyFiles: List<File>): Title {
        if (images.isEmpty()) return Title.Ask(NOT_AN_IMAGE)
        val results = when (val read = readImages(images, keyFiles)) {
            Read.NoKeys -> return Title.Ask(NO_KEYS)
            Read.NoHeaderKey -> return Title.Ask(NO_HEADER_KEY)
            is Read.Failed -> return Title.CantLook(read.reason)
            is Read.Done -> read.results
        }
        val found = results.filterIsInstance<SwitchTitleIdResult.Found>().map { it.titleId }.distinct()
        val unreadable = results.filterIsInstance<SwitchTitleIdResult.NotReadable>().firstOrNull()
        val update = results.filterIsInstance<SwitchTitleIdResult.UpdateOnly>().firstOrNull()
        return when {
            found.size == 1 -> Title.Known(found.single())
            found.size > 1 -> Title.Ask("Its files are of different games, so Loom can't tell which save is its")
            results.any { it is SwitchTitleIdResult.SeveralGames } -> Title.Ask("Its image holds several games, so Loom can't tell which save is its")
            results.any { it is SwitchTitleIdResult.WrongKey } -> Title.Ask(WRONG_KEY)
            // An update beside a file Loom can't read: that file may well be the game, which keeps the save.
            update != null && unreadable != null -> Title.Ask("Loom can't read its title ID: ${unreadable.reason}, and the file it can read is an update")
            update != null -> Title.NoSave("This is an update of ${update.game}; the game itself keeps the save")
            results.all { it is SwitchTitleIdResult.NoProgram } -> Title.NoSave("It holds no game program (DLC?), so it keeps no save of its own")
            else -> Title.Ask("Loom can't read its title ID: " + (unreadable?.reason ?: "unknown image"))
        }
    }

    private sealed interface Read {
        data object NoKeys : Read
        data object NoHeaderKey : Read
        data class Failed(val reason: String) : Read
        data class Done(val results: List<SwitchTitleIdResult>) : Read
    }

    /**
     * Reads [images] with the first header key that opens them, trying each prod.keys in turn: one
     * without a usable header_key, or whose key opens nothing, doesn't hide a good one. Each key is
     * dropped once its read is done.
     */
    private fun readImages(images: List<File>, keyFiles: List<File>): Read {
        if (keyFiles.isEmpty()) return Read.NoKeys
        var last: Read = Read.NoHeaderKey
        try {
            for (file in keyFiles) {
                val key = SwitchHeaderKey.fromProdKeys(file.toPath()) ?: continue
                val results = key.use { k -> images.map { SwitchTitleIdReader.read(it.toPath(), k) } }
                last = Read.Done(results)
                if (results.none { it is SwitchTitleIdResult.WrongKey }) break
            }
        } catch (e: IOException) {
            return Read.Failed("Couldn't read the game's image or prod.keys: ${e.message}")
        }
        return last
    }

    /** The install's images: the install itself, or the ones at the top of an install folder (a game, its update, its DLC). */
    private fun imagesOf(installed: File): List<File> = when {
        installed.isFile -> listOf(installed).filter(::isImage)
        installed.isDirectory -> installed.listFiles().orEmpty().filter { it.isFile && isImage(it) }.sortedBy { it.name }
        else -> emptyList()
    }

    private fun isImage(file: File) = file.extension.lowercase() in SwitchTitleIdReader.EXTENSIONS

    companion object {
        /** Eden's id, under which its games' title IDs are kept with other emulators' save keys. */
        private const val EMULATOR = EdenSaveLocator.EMULATOR_ID

        /** Which of a game's two Eden folders [folder] is, as the owner knows it: "console-wide" or "profile". */
        fun kind(folder: Path) = if (EdenSaveLocator.isConsoleWideFolder(folder)) "console-wide" else "profile"

        /** Eden's save folder in the ROMs root, as on the Thor. */
        const val DEFAULT_FOLDER = "saves/Eden"

        /** Switch saves run larger than 3DS ones: a few dozen MB is not unusual. RomM hashes entries up to 512 MiB. */
        const val MAX_UNIT_BYTES = 256L * 1024 * 1024

        const val SEVERAL_PROFILES = "Eden has several profiles: choose whose saves Loom syncs in Settings"
        const val NOT_AN_IMAGE = "It isn't an XCI or NSP, which Loom reads a Switch game's title ID from"
        const val NO_KEYS = "There's no prod.keys in the ROMs folder for Loom to read its title ID with"
        const val NO_HEADER_KEY = "No prod.keys in the ROMs folder has a header_key to read its title ID with"
        const val WRONG_KEY = "The header_key in prod.keys doesn't open its image, so Loom can't read its title ID"

        fun defaultSaveDir(romsRoot: String) = File(romsRoot, DEFAULT_FOLDER)

        /** Eden's save folder: the one chosen in Settings, else `<ROMs root>/saves/Eden` when it holds `user/save`. */
        fun saveDir(settings: Settings, romsRoot: String): File? =
            settings.edenSaveDir?.let(::File) ?: defaultSaveDir(romsRoot).takeIf { EdenSaveLocator.holdsEdenData(it.toPath()) }

        /** Where Loom looks for the owner's prod.keys: the ROMs root, then the Switch system folder. */
        fun keyFiles(romsRoot: String, systemFolder: String): List<File> =
            listOf(File(romsRoot, KEYS), File(File(romsRoot, systemFolder), KEYS), File(File(romsRoot, "switch"), KEYS)).distinctBy { it.path }

        private const val KEYS = "prod.keys"

        /**
         * The first of [files] holding a header_key Loom can use, for Settings to say whether Switch
         * title IDs can be read. Each key is parsed only to tell, and dropped at once.
         */
        fun usableKeyFile(files: List<File>): File? = files.filter(File::isFile).firstOrNull { file ->
            runCatching { SwitchHeaderKey.fromProdKeys(file.toPath())?.use { true } ?: false }.getOrDefault(false)
        }
    }
}
