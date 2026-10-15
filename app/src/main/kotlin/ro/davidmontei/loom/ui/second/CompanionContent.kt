package ro.davidmontei.loom.ui.second

import okhttp3.HttpUrl
import ro.davidmontei.loom.romm.pairing.DeviceAuthSession
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.RomDetails
import ro.davidmontei.loom.ui.queue.ConflictItem
import ro.davidmontei.loom.ui.queue.QueueItem

/**
 * What the second screen shows: whatever the screen on top publishes about what the pad is on.
 * Only to read: the second screen takes no input. (Not "Companion", which every Kotlin class's
 * companion object would shadow.)
 */
sealed interface CompanionContent {
    /** Nothing to show: the Loom mark. */
    data object Idle : CompanionContent

    /** The library's focused game, or the one last focused, as it is now, with what RomM says of it when it says something. */
    data class Game(val game: GameItem, val platformName: String?, val coverBase: HttpUrl?, val details: RomDetails? = null) : CompanionContent

    /** The queue's save conflict: its two sides beside each other, which its row has room for on one line only. */
    data class Conflict(val item: ConflictItem) : CompanionContent

    /** The queue's download, large. */
    data class Job(val item: QueueItem) : CompanionContent

    /** Pairing's code to scan with a phone, large, with the code to type and how long it lasts from [startedAt]. */
    data class Pairing(val session: DeviceAuthSession, val startedAt: Long) : CompanionContent
}

/**
 * What the queue holds, under the game on the second screen: the download under way ([current], null
 * when none is moving), how many more wait or are paused, how many failed, and the save conflicts
 * waiting for an answer.
 */
data class QueueStrip(val current: QueueItem?, val waiting: Int, val failed: Int, val conflicts: Int) {
    /** "2 more in the queue · 1 failed · 1 save conflict" */
    val counts: String get() = listOfNotNull(
        waiting.takeIf { it > 0 }?.let { if (current != null) "$it more in the queue" else "$it in the queue" },
        failed.takeIf { it > 0 }?.let { "$it failed" },
        conflicts.takeIf { it > 0 }?.let { if (it == 1) "1 save conflict" else "$it save conflicts" },
    ).joinToString(" · ")
}

/** The strip for the queue's [jobs] and [conflicts] (those not skipped), or null when there is nothing in it. */
fun queueStrip(jobs: List<QueueItem>, conflicts: Int): QueueStrip? {
    val waitingOrMoving = jobs.filterNot { it.failed }
    val current = waitingOrMoving.firstOrNull { it.phase.isActive }
    val strip = QueueStrip(current, waitingOrMoving.size - (if (current != null) 1 else 0), jobs.count { it.failed }, conflicts)
    return strip.takeIf { current != null || it.waiting > 0 || it.failed > 0 || conflicts > 0 }
}
