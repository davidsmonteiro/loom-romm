package ro.davidmontei.loom.ui.second

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the screens publish for the second screen, and whether one shows it. Screens publish with
 * [PublishCompanion]; the newest screen to arrive is the one shown. A screen on its way out (Back,
 * or another screen opened over it) can still publish while it animates away, and leaves after the
 * one replacing it has arrived: neither takes the second screen from that newer one.
 */
class CompanionHolder(
    /** What the queue holds, shown under the game: the activity's, while a second screen shows it. */
    val strip: StateFlow<QueueStrip?> = MutableStateFlow(null),
) {
    private val _content = MutableStateFlow<CompanionContent>(CompanionContent.Idle)
    val content: StateFlow<CompanionContent> = _content.asStateFlow()

    private val _presenting = MutableStateFlow(false)

    /** Whether a second screen shows [content] now. */
    val presenting: StateFlow<Boolean> = _presenting.asStateFlow()

    /** Each screen's latest, in the order the screens arrived. */
    private val published = LinkedHashMap<Any, CompanionContent>()

    fun publish(owner: Any, content: CompanionContent) {
        published[owner] = content
        _content.value = published.values.last()
    }

    /** [owner] has left: what it published goes, and the screen that arrived before it is shown, if it's still there. */
    fun withdraw(owner: Any) {
        published.remove(owner)
        _content.value = published.values.lastOrNull() ?: CompanionContent.Idle
    }

    internal fun setPresenting(on: Boolean) {
        _presenting.value = on
    }
}

/** The activity's [CompanionHolder]: none in tests and previews, where publishing does nothing. */
val LocalCompanion = staticCompositionLocalOf<CompanionHolder?> { null }

/** Puts [content] on the second screen while this screen is shown, as it changes. */
@Composable
fun PublishCompanion(content: CompanionContent) {
    val holder = LocalCompanion.current ?: return
    val owner = remember { Any() }
    SideEffect { holder.publish(owner, content) }
    DisposableEffect(holder) { onDispose { holder.withdraw(owner) } }
}

/** Whether a second screen shows what the screens publish: then a wide window leaves its own panel out. */
@Composable
fun secondScreenShowing(): Boolean = LocalCompanion.current?.presenting?.collectAsState()?.value ?: false
