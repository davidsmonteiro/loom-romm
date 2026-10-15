package ro.davidmontei.loom.data.saves

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.db.SaveDao
import ro.davidmontei.loom.data.db.SaveKeyEntity
import ro.davidmontei.loom.data.db.SaveKeySource
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.storage.saves.PpssppSaveLocation
import ro.davidmontei.loom.storage.saves.PpssppSaveLocator
import ro.davidmontei.loom.storage.saves.PspDiscId
import ro.davidmontei.loom.storage.saves.PspDiscIdReader
import ro.davidmontei.loom.storage.saves.PspDiscIdResult
import ro.davidmontei.loom.storage.saves.SaveZipper
import java.io.File
import java.io.IOException

/**
 * PSP games' saves in PPSSPP (ADR 0014): every folder in its memory stick's `PSP/SAVEDATA` whose
 * name starts with the game's disc ID, installed game data left out, zipped as Argosy zips them.
 * On the Thor the memory stick is `ROMs/saves/PPSSPP`, chosen in PPSSPP's System settings.
 *
 * The disc ID is RomM's when it read one (Sigil, at scan time), else read from the game's ISO, CSO
 * or PBP on the Thor, and kept with what was looked at (the images' sizes and times, and RomM's
 * ID), so later scans don't read the image again. When neither says, or they disagree, or the
 * image names two IDs, the owner picks the save folders' prefix; the pick stands until RomM or
 * the image names another disc.
 */
class PpssppSaves(
    private val settings: SettingsStore,
    private val catalog: CatalogDao,
    private val saves: SaveDao,
    private val now: () -> Long = System::currentTimeMillis,
    /** The largest PSP save Loom restores, and so uploads. */
    private val maxUnitBytes: Long = SaveZipper.MAX_UNIT_BYTES,
) {
    suspend fun resolve(rom: RomEntity, install: InstallEntity): ResolvedSave = withContext(Dispatchers.IO) {
        val stick = memoryStick(settings.snapshot(), install.romsRoot) ?: return@withContext ResolvedSave.Unsupported(
            rom.id, "PPSSPP's memory stick isn't ${defaultMemoryStick(install.romsRoot)}: in PPSSPP's System settings, set the " +
                "Memory Stick folder there, or choose PPSSPP's folder in Settings",
        )
        try {
            val discId = when (val disc = discId(rom, install)) {
                is Disc.Known -> disc.id
                is Disc.Ask -> return@withContext ResolvedSave.NeedsFolder(rom.id, disc.reason)
                is Disc.CantLook -> return@withContext ResolvedSave.Unavailable(rom.id, disc.reason)
            }
            // Two games syncing one set of folders would restore each other's saves over them.
            otherGameWithDisc(rom.id, discId)?.let { other ->
                return@withContext ResolvedSave.Unsupported(
                    rom.id, "$other has the same PSP disc ID ($discId), and Loom syncs a game's save folders for one game only",
                )
            }
            val saveData = PpssppSaveLocator.saveDataOf(stick.toPath())
            when (val location = PpssppSaveLocator.locate(saveData, discId)) {
                is PpssppSaveLocation.Found -> ResolvedSave.Ready(
                    romId = rom.id,
                    emulator = PpssppSaveLocator.EMULATOR_ID,
                    unit = location.unit,
                    fileName = SaveUnitResolver.archiveName(rom),
                    title = rom.title,
                    maxUnitBytes = maxUnitBytes,
                )
                PpssppSaveLocation.NoSaveData -> ResolvedSave.Unsupported(rom.id, "There's no $saveData yet: run a game in PPSSPP once")
            }
        } catch (e: IOException) {
            ResolvedSave.Unavailable(rom.id, "Couldn't read the game or PPSSPP's memory stick: ${e.message}")
        }
    }

    /** The title of another installed PSP game whose saves start with [discId] too, by its save key or RomM's ID. */
    private suspend fun otherGameWithDisc(romId: Int, discId: PspDiscId): String? =
        saves.otherGamesWithSaveKey(EMULATOR, discId.value, romId).firstOrNull()
            ?: catalog.installedRomsOf(SaveUnitResolver.PPSSPP_PLATFORMS).firstOrNull { it.id != romId && rommDiscId(it) == discId }?.title

    private sealed interface Disc {
        data class Known(val id: PspDiscId) : Disc

        /** Loom can't tell: the owner picks the save folders' prefix. */
        data class Ask(val reason: String) : Disc

        /** The image can't be read now: try again later. */
        data class CantLook(val reason: String) : Disc
    }

    /**
     * The game's disc ID: as found before while RomM's ID and the images are as they were, else
     * found again from them. The owner's pick stands while they can't say otherwise ([checkPick]).
     */
    private suspend fun discId(rom: RomEntity, install: InstallEntity): Disc {
        val recorded = saves.saveKey(EMULATOR, rom.id)
        val fromRomm = rommDiscId(rom)
        val images = imagesOf(File(install.romsRoot, install.relPath))
        val looked = images.joinToString("") { "${it.path}\t${it.length()}\t${it.lastModified()}\n" } + "romm\t${fromRomm ?: ""}"
        val known = recorded?.let { PspDiscId.parse(it.saveKey) }
        if (recorded != null && known != null && recorded.imageFingerprint == looked) return Disc.Known(known)
        val image = try {
            imageDiscId(images)
        } catch (e: IOException) {
            return Disc.CantLook("Couldn't read the game's image: ${e.message}")
        }
        val found = answer(fromRomm, image)
        if (recorded?.source == SaveKeySource.OWNER && known != null) return checkPick(recorded, known, found, looked)
        return when (found) {
            is Answer.Found -> {
                saves.upsertSaveKey(SaveKeyEntity(EMULATOR, rom.id, found.id.value, found.source, looked, now()))
                Disc.Known(found.id)
            }
            is Answer.Unclear -> {
                // An ID found before no longer holds, so it doesn't keep its folders from other games.
                if (recorded != null) saves.forgetLearnedSaveKey(EMULATOR, rom.id)
                Disc.Ask(found.reason)
            }
        }
    }

    /**
     * The owner's pick, checked whenever RomM's ID or the images changed: RomM or the image naming
     * another disc, with nothing to contradict it, asks again, as syncing another game's folders
     * under this one would put the wrong save on RomM. Otherwise the pick is kept with what was
     * looked at.
     */
    private suspend fun checkPick(recorded: SaveKeyEntity, chosen: PspDiscId, found: Answer, looked: String): Disc {
        if (found is Answer.Found && found.id != chosen) {
            val who = if (found.source == SaveKeySource.ROMM) "RomM says" else "the game's image says"
            return Disc.Ask("You chose the save folders starting with $chosen, but $who it is ${found.id}. Choose its save folders again")
        }
        saves.upsertSaveKey(recorded.copy(imageFingerprint = looked))
        return Disc.Known(chosen)
    }

    /** What RomM and the image say together: one disc ID and where it came from, or why Loom can't tell. */
    private sealed interface Answer {
        data class Found(val id: PspDiscId, val source: SaveKeySource) : Answer
        data class Unclear(val reason: String) : Answer
    }

    private fun answer(fromRomm: PspDiscId?, image: PspDiscIdResult): Answer = when {
        image is PspDiscIdResult.Disagree -> Answer.Unclear(
            "Its image names two disc IDs, ${image.umd} in UMD_DATA.BIN and ${image.sfo} in PARAM.SFO, so Loom can't tell which its save folders start with",
        )
        fromRomm != null && image is PspDiscIdResult.Found && image.discId != fromRomm -> Answer.Unclear(
            "RomM says this is disc $fromRomm but its image says ${image.discId}, so Loom can't tell which its save folders start with",
        )
        fromRomm != null -> Answer.Found(fromRomm, SaveKeySource.ROMM)
        image is PspDiscIdResult.Found -> Answer.Found(image.discId, SaveKeySource.IMAGE)
        image is PspDiscIdResult.NotRead -> Answer.Unclear("RomM didn't read this game's disc ID, and Loom can't read a ${image.format} on the Thor")
        else -> Answer.Unclear("RomM didn't read this game's disc ID, and Loom can't: ${(image as PspDiscIdResult.None).reason}")
    }

    /**
     * What the install's images say: the one ID they name, or why there isn't one. Several images
     * naming different IDs name none.
     *
     * @throws IOException when an image can't be read (the card was pulled).
     */
    private fun imageDiscId(images: List<File>): PspDiscIdResult {
        if (images.isEmpty()) return PspDiscIdResult.None(NOT_AN_IMAGE)
        val results = images.map { PspDiscIdReader.read(it.toPath()) }
        val found = results.filterIsInstance<PspDiscIdResult.Found>().map { it.discId }.distinct()
        return results.firstOrNull { it is PspDiscIdResult.Disagree }
            ?: when {
                found.size > 1 -> PspDiscIdResult.None("its files name different discs (${found.joinToString(" and ")})")
                found.size == 1 -> results.first { it is PspDiscIdResult.Found }
                else -> results.firstOrNull { it is PspDiscIdResult.NotRead } ?: results.first()
            }
    }

    /** The install's images: the install itself, or the ones at the top of an install folder. */
    private fun imagesOf(installed: File): List<File> = when {
        installed.isFile -> listOf(installed).filter(::isImage)
        installed.isDirectory -> installed.listFiles().orEmpty().filter { it.isFile && isImage(it) }.sortedBy { it.name }
        else -> emptyList()
    }

    private fun isImage(file: File) = file.extension.lowercase() in IMAGE_EXTENSIONS

    companion object {
        /** PPSSPP's id, under which its games' disc IDs are kept with other emulators' save keys. */
        private const val EMULATOR = PpssppSaveLocator.EMULATOR_ID

        /** PPSSPP's memory stick in the ROMs root, as on the Thor. */
        const val DEFAULT_FOLDER = "saves/PPSSPP"

        /** Files a PSP game is played from that Loom looks at: those it reads an ID from, and those it leaves to RomM. */
        private val IMAGE_EXTENSIONS = setOf("iso", "cso", "ciso", "pbp", "chd", "zso")

        const val NOT_AN_IMAGE = "it isn't an ISO, CSO or PBP"

        /** RomM's `save_target_layout` for a save named by a prefix, whose `save_target` is the disc ID. */
        private const val FOLDER_PREFIX = "folder-prefix"

        fun defaultMemoryStick(romsRoot: String) = File(romsRoot, DEFAULT_FOLDER)

        /** PPSSPP's memory stick: the one chosen in Settings, else `<ROMs root>/saves/PPSSPP` when PPSSPP keeps its settings there. */
        fun memoryStick(settings: Settings, romsRoot: String): File? =
            settings.ppssppMemoryStick?.let(::File) ?: defaultMemoryStick(romsRoot).takeIf { PpssppSaveLocator.holdsPpssppData(it.toPath()) }

        /**
         * The disc ID RomM read (Sigil, at scan time): its save target when that is a folder prefix,
         * else its title ID. Only a disc ID's shape counts: four capitals and five digits.
         */
        fun rommDiscId(rom: RomEntity): PspDiscId? =
            listOfNotNull(rom.saveTarget.takeIf { rom.saveTargetLayout == FOLDER_PREFIX }, rom.titleId).firstNotNullOfOrNull(PspDiscId::parse)
    }
}
