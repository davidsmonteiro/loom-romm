package ro.davidmontei.loom.data.saves

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.InstallKind
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.storage.plan.NameSanitizer
import ro.davidmontei.loom.storage.saves.MelonDsSaveLocation
import ro.davidmontei.loom.storage.saves.MelonDsSaveLocator
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * DS games' saves in melonDS: one raw `.sav` per game in its save folder, named after the ROM file
 * melonDS opened (ADR 0014). On the Thor that folder is `ROMs/saves/melonDS`, set in melonDS with
 * "Save next to ROM file" off: next to the ROM, a game launched before melonDS scanned it saves
 * in Android/data instead. The installed file's name is the game's key, so it needs no reading
 * and nothing is remembered; two installed games whose saves would have one name, as the SD card
 * compares names, both stop.
 */
class MelonDsSaves(private val settings: SettingsStore, private val catalog: CatalogDao) {
    suspend fun resolve(rom: RomEntity, install: InstallEntity): ResolvedSave = withContext(Dispatchers.IO) {
        val dir = saveDir(settings.snapshot(), install.romsRoot) ?: return@withContext ResolvedSave.Unsupported(
            rom.id, "melonDS's saves aren't in ${defaultSaveDir(install.romsRoot)}: in melonDS's Save Files settings, turn off " +
                "Save next to ROM file and set the Save file directory there, or choose its folder in Settings",
        )
        try {
            when (val location = MelonDsSaveLocator.locate(dir.toPath(), File(install.romsRoot, install.relPath).toPath())) {
                is MelonDsSaveLocation.Found -> {
                    val name = location.unit.name
                    // Two games syncing one file would restore each other's saves over it.
                    otherGameWithSave(rom.id, name)?.let { other ->
                        return@withContext ResolvedSave.Unsupported(
                            rom.id, "$other has the same melonDS save file ($name), and Loom syncs a save for one game only",
                        )
                    }
                    ResolvedSave.Ready(
                        romId = rom.id,
                        emulator = MelonDsSaveLocator.EMULATOR_ID,
                        unit = location.unit,
                        fileName = name, // raw, as Argosy uploads it: <ROM name>.sav
                        title = rom.title,
                    )
                }
                MelonDsSaveLocation.NoFolder -> ResolvedSave.Unsupported(rom.id, "There's no folder at $dir: choose the folder melonDS saves in, in Settings")
                MelonDsSaveLocation.NoCartridge -> ResolvedSave.Unsupported(
                    rom.id, "Its folder doesn't hold exactly one DS ROM, so Loom can't tell which file melonDS names its save after",
                )
                MelonDsSaveLocation.DsiWare -> ResolvedSave.Unsupported(rom.id, DSIWARE)
            }
        } catch (e: IOException) {
            ResolvedSave.Unavailable(rom.id, "Couldn't read the game or melonDS's save folder: ${e.message}")
        }
    }

    /**
     * The title of another installed DS game whose save melonDS would keep as [name] too, compared
     * as the SD card compares names. Read from the installs alone, so a clash stops both games
     * before either syncs: only a folder install is listed, for its one ROM, and only a game whose
     * name clashes has its header read. DSiWare has no `.sav`, so it clashes with none.
     *
     * @throws IOException when another game's folder or ROM can't be read.
     */
    private suspend fun otherGameWithSave(romId: Int, name: String): String? {
        val key = NameSanitizer.key(name)
        val other = catalog.installsOf(SaveUnitResolver.MELONDS_PLATFORMS).filter { it.romId != romId }.firstOrNull { install ->
            val installed = File(install.romsRoot, install.relPath)
            val cart = if (install.kind == InstallKind.FILE) installed.toPath() else MelonDsSaveLocator.cartOf(installed.toPath())
            cart != null && NameSanitizer.key(MelonDsSaveLocator.saveNameFor(cart.fileName.toString())) == key &&
                !(Files.isRegularFile(cart) && MelonDsSaveLocator.isDsiWare(cart))
        } ?: return null
        return catalog.rom(other.romId)?.title ?: "Another game"
    }

    companion object {
        /** melonDS's save folder in the ROMs root, as on the Thor. */
        const val DEFAULT_FOLDER = "saves/melonDS"

        const val DSIWARE = "melonDS keeps DSiWare saves inside its DSi NAND, which Loom doesn't sync"

        fun defaultSaveDir(romsRoot: String) = File(romsRoot, DEFAULT_FOLDER)

        /** melonDS's save folder: the one chosen in Settings, else `<ROMs root>/saves/melonDS` when it is there. */
        fun saveDir(settings: Settings, romsRoot: String): File? =
            settings.melonDsSaveDir?.let(::File) ?: defaultSaveDir(romsRoot).takeIf(File::isDirectory)
    }
}
