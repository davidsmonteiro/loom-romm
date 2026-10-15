package ro.davidmontei.loom.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.InstallState
import ro.davidmontei.loom.ui.second.CompanionContent
import ro.davidmontei.loom.ui.second.CompanionHolder

/** What the second screen shows as screens come and go: the newest screen's. */
class CompanionHolderTest {
    private val holder = CompanionHolder()
    private fun game(id: Int, title: String) = CompanionContent.Game(
        GameItem(id, title, 1_000, null, emptyList(), InstallState.NOT_INSTALLED, null, null, null, adopted = false, removedOnServer = false,
            installPath = null),
        "Super Nintendo", null,
    )
    private val library = Any()
    private val queue = Any()

    /** Opening the queue: it arrives before the library has left, and the library leaving doesn't clear it. */
    @Test fun aScreenLeavingDoesntClearTheOneThatReplacedIt() {
        holder.publish(library, game(1, "Chrono Trigger"))
        holder.publish(queue, CompanionContent.Idle)
        holder.publish(queue, game(2, "EarthBound"))
        holder.withdraw(library)
        assertThat(holder.content.value).isEqualTo(game(2, "EarthBound"))
        holder.withdraw(queue)
        assertThat(holder.content.value).isEqualTo(CompanionContent.Idle)
    }

    /** A download moving on under the library as it animates away doesn't take the second screen back. */
    @Test fun aScreenOnItsWayOutDoesntTakeItBack() {
        holder.publish(library, game(1, "Chrono Trigger"))
        holder.publish(queue, game(2, "EarthBound"))
        holder.publish(library, game(1, "Chrono Trigger (42%)"))
        assertThat(holder.content.value).isEqualTo(game(2, "EarthBound"))
    }

    /** Back from the queue: the library is shown again, and anew. */
    @Test fun goingBackShowsTheScreenBelowAgain() {
        holder.publish(library, game(1, "Chrono Trigger"))
        holder.publish(queue, game(2, "EarthBound"))
        val libraryAgain = Any()
        holder.publish(libraryAgain, game(1, "Chrono Trigger"))
        holder.withdraw(queue)
        assertThat(holder.content.value).isEqualTo(game(1, "Chrono Trigger"))
    }
}
