package ro.davidmontei.loom.ui.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.davidmontei.loom.data.download.FolderAsk
import ro.davidmontei.loom.storage.mapping.FolderDecision
import ro.davidmontei.loom.storage.mapping.SystemFolder
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomDialog
import ro.davidmontei.loom.ui.components.LoomRow
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.gameCount
import ro.davidmontei.loom.ui.layout.rememberLoomLayout
import ro.davidmontei.loom.ui.theme.LoomColors
import ro.davidmontei.loom.ui.theme.tabular

/**
 * Asked at a platform's first download when the card has several folders its games could go in
 * ([FolderDecision.Ask]): from the library, for the game whose X asked ([romId]), or from the queue,
 * for a game waiting there for it. The answer is remembered as the platform's folder.
 */
data class FolderQuestion(
    /** The game queued once it's answered; null when the game already waits in the queue. */
    val romId: Int?,
    /** The game it was asked for, for what Loom says once it's answered. */
    val title: String?,
    val platformId: Int,
    val platformName: String,
    val slug: String,
    val fsSlug: String,
    val options: List<SystemFolder>,
    val suggested: String,
    /** Every folder in the ROMs folder, once the user asks for one that isn't among [options]. */
    val allFolders: List<SystemFolder>? = null,
)

/** The question [this] asks, for [romId] (to queue once it's answered) or a game already waiting, named [title]. */
fun FolderAsk.toQuestion(romId: Int?, title: String?) =
    FolderQuestion(romId, title, platformId, platformName, slug, fsSlug, options, suggested)

/**
 * Where a platform's games go, when the card has several folders that could be the one Cocoon reads.
 * Focus starts on the likeliest, so A takes it; "Another folder…" lists every folder in the ROMs folder.
 * B from every folder goes back to the likely ones ([onShowSuggested]); from those it closes ([onDismiss]).
 */
@Composable
fun FolderQuestionDialog(
    q: FolderQuestion,
    onChoose: (String) -> Unit,
    onShowAll: () -> Unit,
    onShowSuggested: () -> Unit,
    onDismiss: () -> Unit,
) {
    LoomDialog(
        title = "Where does Cocoon look for ${q.platformName} games?",
        onDismiss = if (q.allFolders != null) onShowSuggested else onDismiss,
        body = {
            Text(
                if (q.allFolders == null) "Your card has more than one folder these games could go in. Loom remembers your " +
                    "answer; Settings › Platform folders can change it."
                else "Every folder in your ROMs folder.",
                style = MaterialTheme.typography.bodyMedium, color = LoomColors.TextMuted,
            )
            val folders = q.allFolders ?: q.options
            val grid = rememberLazyGridState()
            val suggested = remember { FocusRequester() }
            // After the dialog's own first focus: scroll the likeliest into view, then focus it. Again when
            // the pad is pressed after a tap (touch mode focuses nothing), before that press is replayed.
            val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
            LaunchedEffect(folders, keyboard) {
                val index = folders.indexOfFirst { it.name == q.suggested }.coerceAtLeast(0)
                grid.scrollToItem(index)
                delay(50)
                runCatching { suggested.requestFocus() }
            }
            LazyVerticalGrid(
                // Two on a narrow screen, so folder names aren't cut short.
                columns = GridCells.Fixed(if (rememberLoomLayout().compact) 2 else 3),
                state = grid,
                modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp).testTag("folder-options"),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                items(folders, key = { it.name }) { folder ->
                    LoomRow(
                        title = "${folder.name}/",
                        subtitle = folder.systemName,
                        subtitleLines = 1,
                        dense = true,
                        trailing = {
                            Text(if (folder.games == 0) "empty" else gameCount(folder.games),
                                style = MaterialTheme.typography.labelMedium.tabular(), color = LoomColors.TextMuted)
                        },
                        onClick = { onChoose(folder.name) },
                        modifier = (if (folder.name == q.suggested) Modifier.focusRequester(suggested) else Modifier).testTag("folder-${folder.name}"),
                    )
                }
            }
        },
        buttons = { first ->
            if (q.allFolders == null) LoomButton("Another folder…", onShowAll, Modifier.focusRequester(first))
            LoomButton("Cancel", onDismiss, if (q.allFolders == null) Modifier else Modifier.focusRequester(first))
        },
    )
}
