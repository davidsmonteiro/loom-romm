package ro.davidmontei.loom.storage.saves

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Where a DS game's save is in melonDS's save folder, or why Loom can't say. */
sealed interface MelonDsSaveLocation {
    /**
     * The save unit: the game's `.sav`. It may not exist yet (no save on this device), or be the
     * empty file melonDS creates when a game first runs, which is no save either.
     */
    data class Found(val unit: SaveUnit.SingleFile) : MelonDsSaveLocation

    /** No save folder: melonDS isn't set to keep its saves there (or the folder was never made). */
    data object NoFolder : MelonDsSaveLocation

    /** A folder install without exactly one cartridge at its top: Loom can't tell which file melonDS opens. */
    data object NoCartridge : MelonDsSaveLocation

    /** DSiWare, whose save melonDS keeps inside its DSi NAND image, not in a `.sav`. */
    data object DsiWare : MelonDsSaveLocation
}

/**
 * Finds DS saves in melonDS's save folder (Settings → Save Files → Save file directory, with
 * "Save next to ROM file" off; on the Thor, `ROMs/saves/melonDS`). melonDS keeps one raw `.sav`
 * per game, named after the ROM file it opened with the last extension replaced, a zipped ROM
 * by the zip's name (`SramProvider.kt`). DS games have no ID melonDS names saves by: the
 * installed file's name is the key.
 */
object MelonDsSaveLocator {
    /** The emulator id Loom gives RomM for melonDS saves, as Argosy and RomM's web player do. */
    const val EMULATOR_ID = "melonds"

    /** Files melonDS opens as a DS game: ROMs, and archives holding one. */
    private val CARTRIDGE_EXTENSIONS = setOf("nds", "dsi", "srl", "zip", "7z")
    private val ARCHIVES = setOf("zip", "7z")

    /** The `.sav` melonDS keeps for [romFileName], by melonDS's own expression. */
    fun saveNameFor(romFileName: String): String = romFileName.replaceAfterLast('.', "sav", "$romFileName.sav")

    /**
     * The file melonDS opens for an install: the installed file itself, or the one cartridge at
     * the top of an install folder. Null when the folder holds none or several.
     *
     * @throws java.io.IOException when the folder can't be listed: try again later.
     */
    fun cartOf(installed: Path): Path? = when {
        installed.isRegularFile(LinkOption.NOFOLLOW_LINKS) -> installed
        installed.isDirectory(LinkOption.NOFOLLOW_LINKS) -> checkedIo {
            Files.list(installed).use { list ->
                list.iterator().asSequence()
                    .filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) && it.extension.lowercase() in CARTRIDGE_EXTENSIONS }
                    .toList()
            }
        }.singleOrNull()
        else -> null
    }

    /**
     * The save of the game installed at [installed] in [saveDir], melonDS's save folder.
     *
     * @throws java.io.IOException when the install or the folder can't be read: try again later.
     */
    fun locate(saveDir: Path, installed: Path): MelonDsSaveLocation {
        val cart = cartOf(installed) ?: return MelonDsSaveLocation.NoCartridge
        // An archive's header is its own, not the ROM's: melonDS's listing reads inside it, Loom doesn't.
        if (cart.extension.lowercase() !in ARCHIVES && NdsHeader.read(cart)?.isDsiWare == true) return MelonDsSaveLocation.DsiWare
        if (!saveDir.isDirectory(LinkOption.NOFOLLOW_LINKS)) return MelonDsSaveLocation.NoFolder
        return MelonDsSaveLocation.Found(SaveUnit.SingleFile(saveDir.resolve(saveNameFor(cart.name))))
    }
}
