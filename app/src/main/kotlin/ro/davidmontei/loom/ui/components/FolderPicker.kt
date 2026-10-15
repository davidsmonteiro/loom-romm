package ro.davidmontei.loom.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.storage.mapping.EmulatorData
import ro.davidmontei.loom.ui.theme.LoomColors
import java.io.File

/** What the picker says about the folder it shows, and whether that looks like the one wanted. */
class FolderNote(val text: String, val looksRight: Boolean)

/**
 * Browses folders with the D-pad (the system picker is touch-first and returns SAF URIs Loom
 * doesn't need with all-files access). Shows how many of the folders inside look like Cocoon
 * system folders, so picking the right ROMs root is easy to confirm; [note] says something else
 * about the open folder and its children when it isn't a ROMs root being picked, and the folders in
 * [knownSystemFolders] are the ones to highlight.
 *
 * With [focusOnOpen] false the screen around it keeps focus (onboarding puts it on its suggested
 * folder) until the user moves within the picker. [focusOn], a folder inside [initial], takes focus
 * in place of "Use this folder": Settings opens the picker beside the current ROMs folder, and A
 * must go into that folder, not pick its parent.
 *
 * B, while the picker has focus, goes back up through the folders opened with A, then leaves it to
 * the screen. Going up puts focus on the folder just left, so A goes back into it rather than
 * picking its parent.
 *
 * [first] goes on the control focus lands on, wherever that is now: a screen sending focus into
 * the picker puts it there.
 */
@Composable
fun FolderPicker(
    volumes: List<VolumeInfo>,
    initial: File?,
    knownSystemFolders: Set<String>,
    onPick: (File) -> Unit,
    modifier: Modifier = Modifier,
    focusOnOpen: Boolean = true,
    focusOn: File? = null,
    note: ((folder: File, children: List<File>) -> FolderNote)? = null,
    first: Modifier = Modifier,
) {
    var current by remember { mutableStateOf(initial?.takeIf { it.isDirectory }) }
    // The folder (or volume) just left by going up, or [focusOn] at first.
    var left by remember { mutableStateOf(focusOn) }
    // How many folders below the starting one were opened: B goes back up through these.
    var depth by remember { mutableIntStateOf(0) }
    var focusable by remember { mutableStateOf(focusOnOpen) }
    var hasFocus by remember { mutableStateOf(false) }
    fun open(dir: File) { current = dir; left = null; depth++; focusable = true }
    fun up() {
        val dir = current ?: return
        val parent = dir.parentFile
        current = if (parent == null || volumes.any { it.root == dir }) null else parent
        left = dir
        depth = (depth - 1).coerceAtLeast(0)
        focusable = true
    }
    BackHandler(enabled = hasFocus && depth > 0) { up() }
    val firstFocus = remember { FocusRequester() }
    val children = remember(current) {
        current?.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name.lowercase() }
    }
    // Emulators' saves (a ROMs folder's `saves`) are never a system's, whatever names the screen knows.
    fun looksLikeSystem(dir: File) = dir.name.lowercase() in knownSystemFolders && !EmulatorData.isSavesFolder(dir.name)
    val systems = children.count(::looksLikeSystem)
    // Focus lands on the row of the folder just left, if it is listed; otherwise on "Use this folder" (or the first volume).
    val focusIndex = left?.let { l -> if (current == null) volumes.indexOfFirst { it.root == l } else children.indexOf(l) }?.takeIf { it >= 0 }
    val list = rememberLazyListState()
    LaunchedEffect(current) { list.scrollToItem(focusIndex ?: 0) } // so that row is composed, and a folder opens at its top
    if (focusable) InitialFocus(firstFocus, current)

    Column(modifier.onFocusChanged { hasFocus = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(current?.path ?: "Choose a storage", style = MaterialTheme.typography.titleSmall, color = LoomColors.TextMuted)
        if (current != null) {
            val said = note?.invoke(current!!, children) ?: FolderNote(
                if (systems > 0) "$systems folders here look like Cocoon systems (${children.filter(::looksLikeSystem).take(6).joinToString { it.name }}…)"
                else "No system folders (snes, psx, gba…) here yet.",
                looksRight = systems > 0,
            )
            Text(said.text, style = MaterialTheme.typography.bodyMedium, color = if (said.looksRight) LoomColors.Installed else LoomColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                LoomButton("Use this folder", onClick = { onPick(current!!) }, primary = true,
                    modifier = if (focusIndex == null) first.focusRequester(firstFocus) else Modifier)
                LoomButton("Up", onClick = { up() })
            }
        }
        // Padding keeps the outset focus ring inside the list's clip.
        LazyColumn(Modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (current == null) {
                itemsIndexed(volumes, key = { _, v -> v.id }) { i, v ->
                    LoomRow(
                        title = v.label,
                        subtitle = v.root.path,
                        onClick = { open(v.root) },
                        leading = { Icon(if (v.removable) Icons.Outlined.SdCard else Icons.Outlined.PhoneAndroid, null, tint = LoomColors.TextMuted) },
                        modifier = if (i == (focusIndex ?: 0)) first.focusRequester(firstFocus) else Modifier,
                    )
                }
            } else {
                itemsIndexed(children, key = { _, dir -> dir.path }) { i, dir ->
                    LoomRow(
                        title = dir.name,
                        onClick = { open(dir) },
                        leading = { Icon(Icons.Outlined.Folder, null, tint = if (looksLikeSystem(dir)) LoomColors.Installed else LoomColors.TextMuted) },
                        modifier = if (i == focusIndex) first.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}
