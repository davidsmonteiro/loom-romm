package ro.davidmontei.loom.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomDialog
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.gameCount
import ro.davidmontei.loom.ui.theme.LoomColors
import ro.davidmontei.loom.ui.theme.tabular

/** A platform as the rail and the picker list it: its name, its games, and how many are on the device. */
@Composable
internal fun PlatformItem(p: PlatformSummary, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth()
            .background(if (selected) LoomColors.SurfaceHighest else LoomColors.Surface, shape)
            .clickable(remember { MutableInteractionSource() }, FocusRingIndication(cornerRadius = 12.dp), role = Role.Tab, onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.s)
            .testTag("platform-${p.platform.id}"),
    ) {
        Text(p.platform.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) LoomColors.Text else LoomColors.TextMuted)
        Text(
            if (p.installedCount > 0) "${p.installedCount} of ${p.localRomCount} · ${formatBytes(p.installedBytes)}"
            else gameCount(p.localRomCount),
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = if (p.installedCount > 0) LoomColors.Installed else LoomColors.TextFaint,
        )
    }
}

/**
 * The platforms, on a screen too narrow for the rail beside the games: opened from the platform's
 * name in the library's header, with focus on the one shown. A picks a platform; B leaves the one
 * there was. [tall] screens list more of them at once.
 */
@Composable
internal fun PlatformPicker(platforms: List<PlatformSummary>, selectedId: Int?, tall: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    LoomDialog(
        title = "Choose a platform",
        onDismiss = onDismiss,
        body = {
            val list = rememberLazyListState()
            val selectedItem = remember { FocusRequester() }
            // After the dialog's own first focus: scroll the platform shown into view, then focus it. Again when
            // the pad is pressed after a tap (touch mode focuses nothing), before that press is replayed.
            val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
            LaunchedEffect(keyboard) {
                list.scrollToItem(platforms.indexOfFirst { it.platform.id == selectedId }.coerceAtLeast(0))
                delay(50)
                runCatching { selectedItem.requestFocus() }
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = if (tall) 480.dp else 232.dp).testTag("platform-list"),
                state = list,
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                items(platforms, key = { it.platform.id }) { p ->
                    val selected = p.platform.id == selectedId
                    PlatformItem(p, selected, { onPick(p.platform.id) }, if (selected) Modifier.focusRequester(selectedItem) else Modifier)
                }
            }
        },
        buttons = { first -> LoomButton("Cancel", onDismiss, Modifier.focusRequester(first)) },
    )
}
