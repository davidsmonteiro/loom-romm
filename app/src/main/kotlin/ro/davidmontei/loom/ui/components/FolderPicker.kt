package ro.davidmontei.loom.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.ui.theme.LoomColors
import java.io.File

/**
 * Browses folders with the D-pad (the system picker is touch-first and returns SAF URIs Loom
 * doesn't need with all-files access). Shows how many of the folders inside look like Cocoon
 * system folders, so picking the right ROMs root is easy to confirm.
 */
@Composable
fun FolderPicker(
    volumes: List<VolumeInfo>,
    initial: File?,
    knownSystemFolders: Set<String>,
    onPick: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf(initial?.takeIf { it.isDirectory }) }
    val firstFocus = remember { FocusRequester() }
    val children = remember(current) {
        current?.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name.lowercase() }
    }
    val systems = children.count { it.name.lowercase() in knownSystemFolders }
    LaunchedEffect(current) { runCatching { firstFocus.requestFocus() } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(current?.path ?: "Choose a storage", style = MaterialTheme.typography.titleSmall, color = LoomColors.TextMuted)
        if (current != null) {
            Text(
                if (systems > 0) "$systems folders here look like Cocoon systems (${children.filter { it.name.lowercase() in knownSystemFolders }.take(6).joinToString { it.name }}…)"
                else "No system folders (snes, psx, gba…) here yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (systems > 0) LoomColors.Installed else LoomColors.TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                LoomButton("Use this folder", onClick = { onPick(current!!) }, primary = true, modifier = Modifier.focusRequester(firstFocus))
                LoomButton("Up", onClick = {
                    val parent = current!!.parentFile
                    current = if (parent == null || volumes.any { it.root == current }) null else parent
                })
            }
        }
        // Padding keeps the outset focus ring inside the list's clip.
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (current == null) {
                items(volumes, key = { it.id }) { v ->
                    LoomRow(
                        title = v.label,
                        subtitle = v.root.path,
                        onClick = { current = v.root },
                        leading = { Icon(if (v.removable) Icons.Outlined.SdCard else Icons.Outlined.PhoneAndroid, null, tint = LoomColors.TextMuted) },
                        modifier = if (v == volumes.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            } else {
                items(children, key = { it.path }) { dir ->
                    LoomRow(
                        title = dir.name,
                        onClick = { current = dir },
                        leading = { Icon(Icons.Outlined.Folder, null, tint = if (dir.name.lowercase() in knownSystemFolders) LoomColors.Installed else LoomColors.TextMuted) },
                    )
                }
            }
        }
    }
}
