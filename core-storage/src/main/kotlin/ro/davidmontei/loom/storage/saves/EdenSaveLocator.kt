package ro.davidmontei.loom.storage.saves

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Where a Switch game's save is under Eden's save folder, or why Loom can't say. */
sealed interface EdenSaveLocation {
    /**
     * The save unit: the title's folder, archived under its own name (the title ID), as Argosy
     * does. It may not exist yet (no save on this device).
     */
    data class Found(val unit: SaveRoot) : EdenSaveLocation

    /** No `user/save` in the folder: Eden doesn't keep its saves there (or hasn't saved yet). */
    data object NoSaveFolder : EdenSaveLocation

    /** No profile folder yet: Eden hasn't saved a game there. */
    data object NoProfile : EdenSaveLocation

    /** Several profiles, and none chosen (or the chosen one is gone). Loom doesn't guess. */
    data class ChooseProfile(val profiles: List<String>) : EdenSaveLocation

    /**
     * Eden now keeps the game's save in [now] (its console-wide folder or the profile's), while
     * Loom synced it from [synced], which still holds files. Loom doesn't switch on its own: the
     * two folders hold different saves, and either side's archive would land in the other.
     */
    data class Moved(val synced: Path, val now: Path) : EdenSaveLocation
}

/** One title's save folder in a profile, for the owner to tell games apart. */
data class EdenTitleFolder(val titleId: SwitchTitleId, val fileCount: Int, val bytes: Long, val newestModified: Instant?)

/**
 * Finds Switch saves in Eden's save folder (its NAND folder: on the Thor, `ROMs/saves/Eden`, moved
 * there from Android/data). A game's save is
 * `user/save/0000000000000000/<profile>/<title ID>/`: the profile is the 32-hex-digit user ID,
 * the title ID 16 digits, both in uppercase.
 *
 * The all-zeros profile isn't a user: Eden keeps device saves there (games saving per console,
 * such as Animal Crossing). A title found there and not in the profile is synced from there. Once
 * a game has synced from one of the two, Loom never switches it to the other by itself.
 */
class EdenSaveLocator(private val saveDir: Path) {

    private val usersDir: Path get() = saveDir.resolve("user").resolve("save").resolve(USER_FOLDER)

    /** The user profiles, sorted. @throws java.io.IOException when the folder can't be listed. */
    fun profiles(): List<String> = folders(usersDir, PROFILE).filterNot { it == DEVICE_PROFILE }.sorted()

    /**
     * The save of [titleId] in [chosenProfile], the profile chosen in Settings, which only counts
     * when there are several. [synced] is the folder the game last synced from, if any.
     *
     * @throws java.io.IOException when the folders can't be listed: try again later.
     */
    fun locate(titleId: SwitchTitleId, chosenProfile: String?, synced: Path? = null): EdenSaveLocation {
        if (!saveDir.resolve("user").resolve("save").isDirectory(LinkOption.NOFOLLOW_LINKS)) return EdenSaveLocation.NoSaveFolder
        val profiles = profiles()
        val name = titleId.toString()
        val device = usersDir.resolve(DEVICE_PROFILE).resolve(name)
        val profile = when {
            profiles.size == 1 -> profiles.single()
            profiles.isEmpty() -> null
            else -> profiles.firstOrNull { it.equals(chosenProfile, ignoreCase = true) }
        }
        val own = profile?.let { usersDir.resolve(it).resolve(name) }
        // A save already in the profile stays the one; else one among the device saves, or where Eden will put it.
        val inDevice = own?.isDirectory(LinkOption.NOFOLLOW_LINKS) != true &&
            (device.isDirectory(LinkOption.NOFOLLOW_LINKS) || name in DEVICE_SAVE_TITLES)
        val folder = if (inDevice) device else own
        // Synced from one of the two and Eden would now point at the other, while the synced one
        // still holds its save: a switch would mix two different saves.
        if (folder != null && synced != null && folder != synced && (synced == device || synced == own) && holdsFiles(synced)) {
            return EdenSaveLocation.Moved(synced, folder)
        }
        return when {
            folder != null -> EdenSaveLocation.Found(SaveRoot(name, folder))
            profiles.isEmpty() -> EdenSaveLocation.NoProfile
            else -> EdenSaveLocation.ChooseProfile(profiles)
        }
    }

    /** Whether [path] is the all-zeros folder Eden keeps console-wide saves in, or one of its title folders. */
    fun isConsoleWide(path: Path): Boolean = path.parent?.name == DEVICE_PROFILE || path.name == DEVICE_PROFILE

    private fun holdsFiles(dir: Path): Boolean = dir.isDirectory(LinkOption.NOFOLLOW_LINKS) && SaveZipper.entries(listOf(SaveRoot("x", dir))).isNotEmpty()

    /**
     * The title folders in [profile] and among the device saves, newest first, for the owner to
     * pick a game's from when Loom can't read its title ID.
     */
    fun titleFolders(profile: String?): List<EdenTitleFolder> {
        val owners = listOfNotNull(profile, DEVICE_PROFILE).map(usersDir::resolve)
        return owners.flatMap { owner ->
            folders(owner, TITLE).map { name ->
                val snapshot = UnitSnapshot.of(listOf(SaveRoot(name, owner.resolve(name))))
                EdenTitleFolder(SwitchTitleId.parse(name)!!, snapshot.fileCount, snapshot.totalBytes, snapshot.newestModified)
            }
        }.distinctBy { it.titleId }.sortedWith(compareByDescending<EdenTitleFolder> { it.newestModified }.thenBy { it.titleId.value })
    }

    private fun folders(dir: Path, pattern: Regex): List<String> {
        if (!dir.isDirectory(LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return checkedIo {
            Files.list(dir).use { list ->
                list.iterator().asSequence().filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) && pattern.matches(it.name) }.map { it.name }.toList()
            }
        }
    }

    companion object {
        /** The emulator id Loom gives RomM for Eden saves, as Argosy does. */
        const val EMULATOR_ID = "eden"

        /** The one user folder Eden (as yuzu) keeps profiles in. */
        private const val USER_FOLDER = "0000000000000000"
        private val DEVICE_PROFILE = "0".repeat(32)
        private val PROFILE = Regex("[0-9A-Fa-f]{32}")
        private val TITLE = Regex("[0-9A-Fa-f]{16}")

        /**
         * Games that save per console, not per user, so Eden keeps their save among the device
         * saves even before it has written one. From Argosy's Switch save handler.
         */
        private val DEVICE_SAVE_TITLES = setOf(
            "01006F8002326000", // Animal Crossing: New Horizons
            "0100D2F00D5C0000", // Nintendo Switch Sports
            "01000320000CC000", // 1-2-Switch
            "01002FF008C24000", // Ring Fit Adventure
            "0100C4B0034B2000", // Nintendo Labo Toy-Con 01: Variety Kit
            "01009AB0034E0000", // Nintendo Labo Toy-Con 02: Robot Kit
            "01001E9003502000", // Nintendo Labo Toy-Con 03: Vehicle Kit
            "0100165003504000", // Nintendo Labo Toy-Con 04: VR Kit
            "0100C1800A9B6000", // Go Vacation
        )

        /** Whether [dir] is Eden's save folder: it holds `user/save`. */
        fun holdsEdenData(dir: Path): Boolean = dir.resolve("user").resolve("save").isDirectory(LinkOption.NOFOLLOW_LINKS)
    }
}
