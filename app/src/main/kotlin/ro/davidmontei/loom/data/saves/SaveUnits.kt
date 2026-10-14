package ro.davidmontei.loom.data.saves

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.download.Volumes
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.storage.saves.AzaharSaveLocation
import ro.davidmontei.loom.storage.saves.AzaharSaveLocator
import ro.davidmontei.loom.storage.saves.NcsdReader
import ro.davidmontei.loom.storage.saves.SaveRoot
import ro.davidmontei.loom.storage.saves.ThreeDsTitleId
import java.io.File
import java.io.IOException

/** Where an installed game's save is, or why Loom can't sync it. */
sealed interface ResolvedSave {
    val romId: Int

    data class Ready(
        override val romId: Int,
        /** The emulator id Loom gives RomM, e.g. "azahar". */
        val emulator: String,
        /** The save unit's folders, archived under their root names. */
        val roots: List<SaveRoot>,
        /** The name RomM stores an upload under (it adds a timestamp), e.g. "Pokemon X.zip". */
        val fileName: String,
        /** The game's title, for backups and archival copies. */
        val title: String,
    ) : ResolvedSave

    /** Loom can't sync this game until something changes (another emulator, a missing title ID). */
    data class Unsupported(override val romId: Int, val reason: String) : ResolvedSave

    /**
     * Loom can't look right now (the card is out, a folder can't be read): the game sits this run
     * out. Never read as "no save", which could let a download through.
     */
    data class Unavailable(override val romId: Int, val reason: String) : ResolvedSave
}

/**
 * Maps an installed game to its save unit. Only 3DS games played in Azahar are synced for now
 * (the owner's choice; other emulators keep their saves where Loom can't reach them, or aren't
 * set up yet). A game's title ID comes from RomM when it read one, else from the cartridge image's
 * header; when both are known and differ, Loom doesn't guess.
 */
class SaveUnitResolver(private val settings: SettingsStore, private val volumes: Volumes) {

    /** Whether Loom syncs saves for games of this ROM's platform at all. */
    fun supports(rom: RomEntity): Boolean = rom.platformSlug in AZAHAR_PLATFORMS

    /** The emulator id Loom syncs this ROM's saves as; null when it doesn't sync them. */
    fun emulatorFor(rom: RomEntity): String? = AzaharSaveLocator.EMULATOR_ID.takeIf { supports(rom) }

    suspend fun resolve(rom: RomEntity, install: InstallEntity): ResolvedSave = withContext(Dispatchers.IO) {
        if (!supports(rom)) return@withContext ResolvedSave.Unsupported(rom.id, "Loom syncs 3DS saves (Azahar) only, for now")
        if (!volumes.isMounted(install.volumeUuid)) {
            return@withContext ResolvedSave.Unavailable(rom.id, "The card holding this game isn't mounted")
        }
        val userDir = azaharUserDir(install) ?: return@withContext ResolvedSave.Unsupported(
            rom.id, "Azahar's folder isn't in ${install.relPath.substringBefore('/')} yet: run the game in Azahar once, or set the folder in Settings",
        )
        val titleId = when (val id = titleIdOf(rom, File(install.romsRoot, install.relPath))) {
            is TitleIdResult.Found -> id.titleId
            is TitleIdResult.None -> return@withContext ResolvedSave.Unsupported(rom.id, id.reason)
        }
        val location = try {
            AzaharSaveLocator(userDir.toPath()).locate(titleId)
        } catch (e: IOException) {
            return@withContext ResolvedSave.Unavailable(rom.id, "Couldn't read Azahar's folder: ${e.message}")
        }
        when (location) {
            is AzaharSaveLocation.Found -> ResolvedSave.Ready(
                romId = rom.id,
                emulator = AzaharSaveLocator.EMULATOR_ID,
                roots = listOf(location.unit),
                fileName = "${rom.fsNameNoExt.ifBlank { rom.title }}.zip",
                title = rom.title,
            )
            AzaharSaveLocation.NoSdCard -> ResolvedSave.Unsupported(rom.id, "Azahar hasn't set up its SD card in $userDir yet")
            is AzaharSaveLocation.Ambiguous -> ResolvedSave.Unsupported(
                rom.id, "Azahar has several SD card profiles and this game's save isn't in exactly one of them",
            )
            AzaharSaveLocation.NotAGame -> ResolvedSave.Unsupported(rom.id, "$titleId is an update or DLC, which keeps no save of its own")
        }
    }

    /**
     * Azahar's user directory for a game: the folder set in Settings, else the game's own system
     * folder when Azahar keeps its data there (`sdmc/Nintendo 3DS`, as on the Thor).
     */
    private suspend fun azaharUserDir(install: InstallEntity): File? {
        settings.snapshot().azaharUserDir?.let { return File(it) }
        val systemFolder = File(install.romsRoot, install.relPath.substringBefore('/'))
        return systemFolder.takeIf { File(it, "sdmc/Nintendo 3DS").isDirectory }
    }

    private sealed interface TitleIdResult {
        data class Found(val titleId: ThreeDsTitleId) : TitleIdResult
        data class None(val reason: String) : TitleIdResult
    }

    private fun titleIdOf(rom: RomEntity, installed: File): TitleIdResult {
        val fromRomm = listOfNotNull(rom.saveTarget, rom.titleId).firstNotNullOfOrNull { raw -> ThreeDsTitleId.parse(raw)?.takeIf { it.isGame } }
        val fromCartridge = cartridgeOf(installed)?.let { NcsdReader.titleId(it.toPath()) }
        return when {
            fromRomm != null && fromCartridge != null && fromRomm != fromCartridge -> TitleIdResult.None(
                "RomM says this is title $fromRomm but the cartridge says $fromCartridge; Loom won't guess which save is its",
            )
            fromRomm != null -> TitleIdResult.Found(fromRomm)
            fromCartridge != null -> TitleIdResult.Found(fromCartridge)
            else -> TitleIdResult.None("Loom can't tell this game's title ID: it isn't a .3ds or .cci cartridge image, and RomM didn't read one")
        }
    }

    /** The cartridge image: the install itself, or the one image at the top of an install folder. */
    private fun cartridgeOf(installed: File): File? = when {
        installed.isFile -> installed
        installed.isDirectory -> installed.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in CARTRIDGE_EXTENSIONS }.singleOrNull()
        else -> null
    }

    companion object {
        /** RomM's slugs for the platforms whose saves Loom syncs through Azahar. */
        val AZAHAR_PLATFORMS = setOf("3ds", "new-nintendo-3ds")

        private val CARTRIDGE_EXTENSIONS = setOf("3ds", "cci")
    }
}
