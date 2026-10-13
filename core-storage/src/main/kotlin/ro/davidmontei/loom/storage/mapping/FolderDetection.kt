package ro.davidmontei.loom.storage.mapping

/** A folder in the ROMs root, as Loom sees it when choosing where a platform's games go. */
data class SystemFolder(
    val name: String,
    /** Entries inside it other than frontend metadata (ES-DE's systeminfo.txt) and hidden files. */
    val games: Int,
    /** What the folder says it is (ES-DE's "Full system name"), if it says. */
    val systemName: String? = null,
)

/** Where a platform's games go, or the folders to ask the user to choose between. */
sealed interface FolderDecision {
    data class Use(val folder: String, val why: Why) : FolderDecision

    /** Several folders could be the one Cocoon reads; [suggested] is the likeliest. */
    data class Ask(val options: List<SystemFolder>, val suggested: String) : FolderDecision

    enum class Why {
        /** The user picked it. */
        CHOSEN,

        /** The only candidate that already holds games. */
        HAS_GAMES,

        /** The only candidate on the card. */
        ONLY_FOLDER,

        /** None is on the card yet: Loom creates the default. */
        NEW_FOLDER,
    }
}

/**
 * Picks the system folder for a platform from what is on the card. Cocoon lets each system read
 * any folder, so its name alone doesn't say which one Cocoon uses: Loom decides only when a single
 * candidate stands out, and otherwise asks (once; the answer becomes the user's choice).
 */
object FolderDetector {
    fun decide(platform: ResolvedPlatform, onCard: List<SystemFolder>): FolderDecision {
        if (platform.folderChosen) return FolderDecision.Use(platform.folder, FolderDecision.Why.CHOSEN)
        val byKey = onCard.associateBy { FolderNames.key(it.name) }
        val present = platform.candidates.mapNotNull { byKey[FolderNames.key(it)] }
        val withGames = present.filter { it.games > 0 }
        return when {
            withGames.size == 1 -> FolderDecision.Use(withGames.single().name, FolderDecision.Why.HAS_GAMES)
            present.isEmpty() -> FolderDecision.Use(platform.folder, FolderDecision.Why.NEW_FOLDER)
            present.size == 1 -> FolderDecision.Use(present.single().name, FolderDecision.Why.ONLY_FOLDER)
            else -> {
                // Folders with games first, then the default, then the other names in the mapping's order.
                val options = withGames + (present - withGames.toSet())
                FolderDecision.Ask(options, options.first().name)
            }
        }
    }
}
