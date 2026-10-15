package ro.davidmontei.loom.ui.second

import okhttp3.HttpUrl
import ro.davidmontei.loom.ui.library.GameItem

/**
 * What the second screen shows: whatever the screen on top publishes about what the pad is on.
 * Only to read: the second screen takes no input. (Not "Companion", which every Kotlin class's
 * companion object would shadow.)
 */
sealed interface CompanionContent {
    /** Nothing to show: the Loom mark. */
    data object Idle : CompanionContent

    /** The library's focused game, or the one last focused, as it is now. */
    data class Game(val game: GameItem, val platformName: String?, val coverBase: HttpUrl?) : CompanionContent
}
