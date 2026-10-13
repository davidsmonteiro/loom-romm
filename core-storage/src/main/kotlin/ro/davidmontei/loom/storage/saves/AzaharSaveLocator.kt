package ro.davidmontei.loom.storage.saves

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Where a 3DS game's save is under Azahar's user directory, or why Loom can't say. */
sealed interface AzaharSaveLocation {
    /**
     * The save unit: the title's `data` folder, archived under the root `data`. It may not exist
     * yet (no save on this device). Extdata is left out: its id can't be derived from the title ID.
     */
    data class Found(val unit: SaveRoot) : AzaharSaveLocation

    /** No `sdmc/Nintendo 3DS/<id0>/<id1>` yet: Azahar hasn't run with this user directory. */
    data object NoSdCard : AzaharSaveLocation

    /** Several id0/id1 trees, and the save is not in exactly one of them. Loom doesn't guess. */
    data class Ambiguous(val trees: List<Path>) : AzaharSaveLocation

    /** Not a game's own title ID (an update, DLC or system title). */
    data object NotAGame : AzaharSaveLocation
}

/**
 * Finds 3DS saves under Azahar's user directory. On the Thor that directory is the n3ds system
 * folder itself (`ROMs/n3ds`, next to the games), and a save lives in
 * `sdmc/Nintendo 3DS/<id0>/<id1>/title/00040000/<low title id>/data/`. The id0 and id1 folders
 * are 32 hex characters, random per console (all zeros on the Thor), so they are found by listing.
 */
class AzaharSaveLocator(private val userDir: Path) {

    fun locate(titleId: ThreeDsTitleId): AzaharSaveLocation {
        if (!titleId.isGame) return AzaharSaveLocation.NotAGame
        val trees = id1Trees()
        val tree = when {
            trees.isEmpty() -> return AzaharSaveLocation.NoSdCard
            trees.size == 1 -> trees.single()
            else -> trees.filter { dataFolder(it, titleId).isDirectory(LinkOption.NOFOLLOW_LINKS) }
                .singleOrNull() ?: return AzaharSaveLocation.Ambiguous(trees)
        }
        return AzaharSaveLocation.Found(SaveRoot(ROOT_NAME, dataFolder(tree, titleId)))
    }

    private fun dataFolder(id1Tree: Path, titleId: ThreeDsTitleId): Path =
        id1Tree.resolve("title").resolve(titleId.high).resolve(titleId.low).resolve(ROOT_NAME)

    /** Every `<id0>/<id1>` folder under the SD root, sorted so a listing is stable. */
    private fun id1Trees(): List<Path> {
        val sdRoot = userDir.resolve("sdmc").resolve("Nintendo 3DS")
        return idFolders(sdRoot).flatMap(::idFolders).sorted()
    }

    private fun idFolders(dir: Path): List<Path> {
        if (!dir.isDirectory(LinkOption.NOFOLLOW_LINKS)) return emptyList()
        // Through a sequence: Stream.toList() is missing before Android 14.
        return Files.list(dir).use { list ->
            list.iterator().asSequence().filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) && ID_FOLDER.matches(it.name) }.toList()
        }
    }

    companion object {
        /** The emulator id Loom gives RomM for Azahar saves. */
        const val EMULATOR_ID = "azahar"

        /** The archive root of a 3DS title save, as Argosy writes it. */
        const val ROOT_NAME = "data"

        private val ID_FOLDER = Regex("[0-9A-Fa-f]{32}")
    }
}
