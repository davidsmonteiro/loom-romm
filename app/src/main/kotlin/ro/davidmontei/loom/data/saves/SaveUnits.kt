package ro.davidmontei.loom.data.saves

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.download.Volumes
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.storage.saves.AzaharSaveLocation
import ro.davidmontei.loom.storage.saves.AzaharSaveLocator
import ro.davidmontei.loom.storage.saves.EdenSaveLocator
import ro.davidmontei.loom.storage.saves.MelonDsSaveLocator
import ro.davidmontei.loom.storage.saves.NcsdReader
import ro.davidmontei.loom.storage.saves.PpssppSaveLocator
import ro.davidmontei.loom.storage.saves.SaveUnit
import ro.davidmontei.loom.storage.saves.SaveZipper
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
        /** The save unit: what Loom hashes, uploads and restores as the game's save. */
        val unit: SaveUnit,
        /** The name RomM stores an upload under (it adds a timestamp), e.g. "Pokemon X.zip". */
        val fileName: String,
        /** The game's title, for backups and archival copies. */
        val title: String,
        /** The largest save Loom restores for this emulator, and so uploads. */
        val maxUnitBytes: Long = SaveZipper.MAX_UNIT_BYTES,
    ) : ResolvedSave {
        /** Where the save is, as Loom records it with the save's scan and baseline. */
        val unitPath: String get() = unit.path
    }

    /** Loom can't sync this game until something changes (another emulator, a missing title ID). */
    data class Unsupported(override val romId: Int, val reason: String) : ResolvedSave

    /**
     * Loom can't tell which of the emulator's save folders is the game's until the user says
     * (a Switch game whose title ID Loom can't read): [reason] says why it can't tell.
     */
    data class NeedsFolder(override val romId: Int, val reason: String) : ResolvedSave

    /**
     * The emulator keeps the game's save in two folders now, each holding a different save (a
     * Switch game in Eden's console-wide folder and the profile's), and the user hasn't said which
     * one Loom syncs: [reason] says which is which.
     */
    data class Moved(override val romId: Int, val reason: String) : ResolvedSave

    /**
     * Loom can't look right now (the card is out, a folder can't be read): the game sits this run
     * out. Never read as "no save", which could let a download through.
     */
    data class Unavailable(override val romId: Int, val reason: String) : ResolvedSave
}

/**
 * Maps an installed game to its save unit: 3DS games played in Azahar, Switch games played in
 * Eden ([EdenSaves]), DS games played in melonDS ([MelonDsSaves]) and PSP games played in PPSSPP
 * ([PpssppSaves]). Other emulators keep their saves where Loom can't reach them, or aren't set up
 * yet (the owner's choice). A 3DS game's title ID comes from RomM when it read one, else from the
 * cartridge image's header; when both are known and differ, Loom doesn't guess.
 */
class SaveUnitResolver(
    private val settings: SettingsStore,
    private val volumes: Volumes,
    private val eden: EdenSaves,
    private val melonDs: MelonDsSaves,
    private val ppsspp: PpssppSaves,
) {

    /** Whether Loom syncs saves for games of this ROM's platform at all. */
    fun supports(rom: RomEntity): Boolean = supportsPlatform(rom.platformSlug)

    /** The emulator id Loom syncs this ROM's saves as; null when it doesn't sync them. */
    fun emulatorFor(rom: RomEntity): String? = emulatorForPlatform(rom.platformSlug)

    suspend fun resolve(rom: RomEntity, install: InstallEntity): ResolvedSave = withContext(Dispatchers.IO) {
        val emulator = emulatorFor(rom) ?: return@withContext ResolvedSave.Unsupported(rom.id, unsupportedReason(rom.platformSlug))
        if (!volumes.isMounted(install.volumeUuid)) {
            return@withContext ResolvedSave.Unavailable(rom.id, "The card holding this game isn't mounted")
        }
        when (emulator) {
            EdenSaveLocator.EMULATOR_ID -> eden.resolve(rom, install)
            MelonDsSaveLocator.EMULATOR_ID -> melonDs.resolve(rom, install)
            PpssppSaveLocator.EMULATOR_ID -> ppsspp.resolve(rom, install)
            else -> resolveAzahar(rom, install)
        }
    }

    private suspend fun resolveAzahar(rom: RomEntity, install: InstallEntity): ResolvedSave {
        val userDir = azaharUserDir(install) ?: return ResolvedSave.Unsupported(
            rom.id, "Azahar's folder isn't in ${install.relPath.substringBefore('/')} yet: run the game in Azahar once, or set the folder in Settings",
        )
        val titleId = when (val id = titleIdOf(rom, File(install.romsRoot, install.relPath))) {
            is TitleIdResult.Found -> id.titleId
            is TitleIdResult.None -> return ResolvedSave.Unsupported(rom.id, id.reason)
        }
        val location = try {
            AzaharSaveLocator(userDir.toPath()).locate(titleId)
        } catch (e: IOException) {
            return ResolvedSave.Unavailable(rom.id, "Couldn't read Azahar's folder: ${e.message}")
        }
        return when (location) {
            is AzaharSaveLocation.Found -> ResolvedSave.Ready(
                romId = rom.id,
                emulator = AzaharSaveLocator.EMULATOR_ID,
                unit = SaveUnit.Folders(listOf(location.unit)),
                fileName = archiveName(rom),
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
        return systemFolder.takeIf(::holdsAzaharData)
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

        /** RomM's slug for the platform whose saves Loom syncs through Eden. */
        val EDEN_PLATFORMS = setOf("switch")

        /** RomM's slugs for the platforms whose saves Loom syncs through melonDS: DS games, and DSi ones on cartridge. */
        val MELONDS_PLATFORMS = setOf("nds", "nintendo-dsi")

        /** RomM's slugs for the platforms whose saves Loom syncs through PPSSPP: UMD and PSN games, and minis. */
        val PPSSPP_PLATFORMS = setOf("psp", "psp-minis")

        private val CARTRIDGE_EXTENSIONS = setOf("3ds", "cci")

        /** Android/data, where these emulators keep their saves: other apps can't read it on Android 11+. */
        private const val ANDROID_DATA = "in Android/data, where Loom can't reach them"

        /** The emulator id Loom syncs a platform's saves as; null for a platform it doesn't sync. */
        fun emulatorForPlatform(slug: String): String? = when (slug) {
            in AZAHAR_PLATFORMS -> AzaharSaveLocator.EMULATOR_ID
            in EDEN_PLATFORMS -> EdenSaveLocator.EMULATOR_ID
            in MELONDS_PLATFORMS -> MelonDsSaveLocator.EMULATOR_ID
            in PPSSPP_PLATFORMS -> PpssppSaveLocator.EMULATOR_ID
            else -> null
        }

        fun supportsPlatform(slug: String): Boolean = emulatorForPlatform(slug) != null

        /**
         * Why Loom doesn't sync a platform's saves, naming the emulator Cocoon uses for it on the
         * Thor. The emulators that keep saves in Android/data can't be reached; the others aren't
         * supported yet (docs/ROADMAP.md).
         */
        fun unsupportedReason(slug: String): String = when (slug) {
            "psx" -> "DuckStation keeps its memory cards $ANDROID_DATA"
            "ngc", "wii" -> "Dolphin keeps its saves $ANDROID_DATA"
            "wiiu" -> "Cemu keeps its saves $ANDROID_DATA"
            else -> "Loom syncs 3DS, Switch, DS and PSP saves only, for now"
        }

        /** Whether [folder] is Azahar's user directory: it holds Azahar's `sdmc/Nintendo 3DS`. */
        fun holdsAzaharData(folder: File): Boolean = File(folder, "sdmc/Nintendo 3DS").isDirectory

        /** The name RomM stores a game's save archive under (it adds a timestamp), e.g. "Pokemon X.zip". */
        fun archiveName(rom: RomEntity): String = "${rom.fsNameNoExt.ifBlank { rom.title }}.zip"
    }
}
