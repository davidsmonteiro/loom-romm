package ro.davidmontei.loom.storage.mapping

import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.isDirectory

/**
 * Folders an emulator keeps among the games. On the Thor, Azahar's user directory is the n3ds
 * system folder itself, so its `sdmc/` (with every 3DS save), `nand/`, `config/`, `states/` and
 * so on sit next to the `.3ds` files. Loom never counts them as games, adopts them, installs over
 * them or offloads them.
 */
object EmulatorData {
    /** The folders of Azahar's (Citra's) user directory. None of them is ever a game. */
    val AZAHAR_FOLDERS: Set<String> = setOf(
        "amiibo", "cache", "cheats", "config", "dump", "external_dlls", "gpu_drivers", "icons", "load", "log",
        "nand", "screenshots", "sdmc", "shaders", "states", "sysdata",
    )

    /**
     * The folder in the ROMs root that holds emulators' saves moved to the card (Eden's in
     * `saves/Eden` on the Thor). It is never a system folder: not detected, offered, adopted from,
     * installed into or offloaded.
     */
    const val SAVES_FOLDER = "saves"

    /** Whether an entry named [name] at the top of a system folder is emulator data, not a game. */
    fun isEmulatorData(name: String): Boolean = name.lowercase(Locale.ROOT) in AZAHAR_FOLDERS

    /** Whether a folder named [name] at the top of the ROMs root is [SAVES_FOLDER] (the card ignores case). */
    fun isSavesFolder(name: String): Boolean = name.equals(SAVES_FOLDER, ignoreCase = true)

    /**
     * Whether [dir] is (or holds) an emulator's user directory: Azahar's `sdmc` or `nand` folder
     * is in it. Such a folder is never deleted as a game, whatever it is called.
     */
    fun holdsEmulatorData(dir: Path): Boolean =
        listOf("sdmc", "nand").any { dir.resolve(it).isDirectory(LinkOption.NOFOLLOW_LINKS) }
}
