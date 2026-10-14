package ro.davidmontei.loom.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.davidmontei.loom.data.db.TitledConflict
import ro.davidmontei.loom.data.saves.ConflictChoice
import ro.davidmontei.loom.data.saves.ServerSaveInfo
import ro.davidmontei.loom.data.saves.conflictReason
import ro.davidmontei.loom.data.saves.emulatorName
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomDialog
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.StatusBadge
import ro.davidmontei.loom.ui.components.ago
import ro.davidmontei.loom.ui.components.dateTime
import ro.davidmontei.loom.ui.theme.LoomColors
import ro.davidmontei.loom.ui.theme.tabular
import java.time.Instant

/** A save conflict as the queue shows it: both sides as Loom found them. */
data class ConflictItem(
    val romId: Int,
    val title: String,
    /** Why it's a conflict, in words. */
    val reason: String,
    /** The Thor's save: its newest file's time and its size; null when the Thor has none. */
    val thorModifiedMs: Long?,
    val thorBytes: Long?,
    /** RomM's newest save: when it was uploaded and by which emulator; null when RomM has none. */
    val serverUpdatedMs: Long?,
    val serverEmulator: String?,
    /** Asked of RomM, which Loom doesn't keep: null until it answers, or when it can't say. */
    val serverBytes: Long? = null,
    val serverDevice: String? = null,
    /** The emulator writing the Thor's save, named in the "close it first" question. */
    val emulator: String? = null,
    /** The user chose Skip: it stays listed until either side changes. */
    val skipped: Boolean = false,
    /** An answer is being carried out. */
    val busy: Boolean = false,
) {
    val thorHasSave get() = thorBytes != null
    val serverHasSave get() = serverUpdatedMs != null
}

/** A game whose save RomM can put back on the Thor: newer there, or gone from the Thor. */
data class RestorableSave(val romId: Int, val title: String, val missing: Boolean, val emulator: String, val busy: Boolean = false)

/** A game whose save was synced lately, and when. */
data class RecentSave(val romId: Int, val title: String, val atMs: Long)

/** [server]: what RomM said about conflicts' server saves, by save id. [busy]: games an answer is being carried out for. */
fun TitledConflict.toItem(server: Map<Int, ServerSaveInfo>, busy: Set<Int>): ConflictItem {
    val info = conflict.serverSaveId?.let(server::get)
    return ConflictItem(
        romId = conflict.romId,
        title = title ?: "ROM ${conflict.romId}",
        reason = conflictReason(conflict.reason),
        thorModifiedMs = conflict.localModifiedMs,
        thorBytes = conflict.localBytes,
        serverUpdatedMs = conflict.serverUpdatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        serverEmulator = conflict.serverEmulator,
        serverBytes = info?.bytes,
        serverDevice = info?.deviceName,
        emulator = localEmulator,
        skipped = conflict.skipped,
        busy = conflict.romId in busy,
    )
}

/** The Thor's side of a conflict: "Changed 14 Oct, 13:05", "512 KB". */
fun thorSide(item: ConflictItem): List<String> =
    if (!item.thorHasSave) listOf("No save") else listOfNotNull(item.thorModifiedMs?.let { "Changed ${dateTime(it)}" }, item.thorBytes?.let(::formatBytes))

/** RomM's side of a conflict: "Uploaded 14 Oct, 12:40", "520 KB · Azahar", "From Steam Deck" (when RomM says). */
fun serverSide(item: ConflictItem): List<String> {
    val at = item.serverUpdatedMs ?: return listOf("No save")
    return listOfNotNull(
        "Uploaded ${dateTime(at)}",
        listOfNotNull(item.serverBytes?.let(::formatBytes), item.serverEmulator?.let(::emulatorName) ?: "emulator unknown").joinToString(" · "),
        item.serverDevice?.let { "From $it" },
    )
}

/**
 * A side in one line for a row: "uploaded 14 Oct, 12:40 · 520 KB · Azahar · from Steam Deck". Loom's
 * own words go lowercase mid-line; names (the emulator, the device) stay as they are.
 */
private fun sideLine(side: List<String>) = side.mapIndexed { i, part ->
    if (i == 0 || part.startsWith("From ")) part.replaceFirstChar { it.lowercase() } else part
}.joinToString(" · ")

@Composable
internal fun ConflictRow(item: ConflictItem, modifier: Modifier, onFocus: (Boolean) -> Unit, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    LaunchedEffect(isFocused) { onFocus(isFocused) }
    Column(
        modifier.fillMaxWidth()
            .background(if (isFocused) LoomColors.SurfaceHighest else LoomColors.SurfaceRaised, RoundedCornerShape(12.dp))
            .clickable(interaction, FocusRingIndication(cornerRadius = 12.dp), role = Role.Button, onClick = onOpen)
            .padding(Space.m)
            .testTag("conflict-${item.romId}"),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                item.busy -> StatusBadge("Working…", color = LoomColors.Accent, onColor = LoomColors.OnAccent)
                item.skipped -> StatusBadge("Skipped", color = LoomColors.SurfaceHighest, onColor = LoomColors.TextMuted)
                else -> StatusBadge("Choose", color = LoomColors.Error, onColor = LoomColors.OnError)
            }
        }
        Text(item.reason, style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Thor: ${sideLine(thorSide(item))}    RomM: ${sideLine(serverSide(item))}",
            style = MaterialTheme.typography.labelMedium.tabular(), color = LoomColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun RestorableRow(item: RestorableSave, modifier: Modifier, onFocus: (Boolean) -> Unit, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    LaunchedEffect(isFocused) { onFocus(isFocused) }
    Column(
        modifier.fillMaxWidth()
            .background(if (isFocused) LoomColors.SurfaceHighest else LoomColors.SurfaceRaised, RoundedCornerShape(12.dp))
            .clickable(interaction, FocusRingIndication(cornerRadius = 12.dp), role = Role.Button, onClick = onOpen)
            .padding(Space.m)
            .testTag("restore-${item.romId}"),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.busy) StatusBadge("Working…", color = LoomColors.Accent, onColor = LoomColors.OnAccent)
            else StatusBadge(if (item.missing) "Missing on the Thor" else "Newer on RomM", color = LoomColors.SurfaceHighest, onColor = LoomColors.Text)
        }
        Text(
            if (item.missing) "A puts RomM's save back on the Thor, once ${emulatorName(item.emulator)} is closed."
            else "A takes RomM's save, once ${emulatorName(item.emulator)} is closed. The Thor's is backed up first.",
            style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted,
        )
    }
}

/**
 * When a game's save last synced: a past event, since the save may have changed or be in conflict
 * by now. Focusable only so the pad can scroll through them; there is nothing to press.
 */
@Composable
internal fun RecentSaveRow(item: RecentSave, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth()
            .background(LoomColors.Surface, RoundedCornerShape(12.dp))
            .focusable(interactionSource = interaction)
            .indication(interaction, FocusRingIndication(cornerRadius = 12.dp))
            .padding(Space.m)
            .testTag("synced-${item.romId}"),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("synced ${ago(item.atMs)}", style = MaterialTheme.typography.labelMedium.tabular(), color = LoomColors.TextMuted)
    }
}

/**
 * Which save to keep: both sides as Loom found them, and the four answers. Skip has focus, and B
 * skips too: nothing changes on either side until the user picks one.
 */
@Composable
internal fun ConflictDialog(item: ConflictItem, onAnswer: (ConflictChoice) -> Unit) {
    LoomDialog(
        title = "${item.title}: which save to keep?",
        onDismiss = { onAnswer(ConflictChoice.SKIP) },
        body = {
            Text(item.reason, style = MaterialTheme.typography.bodyLarge, color = LoomColors.TextMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                SaveSide("On the Thor", thorSide(item), Modifier.weight(1f).testTag("thor-side"))
                SaveSide("On RomM", serverSide(item), Modifier.weight(1f).testTag("server-side"))
            }
            Text("Keep both uploads the Thor's save to RomM as a separate copy, then takes RomM's. Skip leaves both as they are.",
                style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted)
        },
        buttons = { first ->
            LoomButton("Skip", { onAnswer(ConflictChoice.SKIP) }, Modifier.focusRequester(first))
            LoomButton("Keep Thor", { onAnswer(ConflictChoice.KEEP_THOR) }, enabled = item.thorHasSave)
            LoomButton("Keep server", { onAnswer(ConflictChoice.KEEP_SERVER) }, enabled = item.serverHasSave)
            LoomButton("Keep both", { onAnswer(ConflictChoice.KEEP_BOTH) }, enabled = item.thorHasSave && item.serverHasSave)
        },
    )
}

@Composable
private fun SaveSide(heading: String, lines: List<String>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(heading.uppercase(), style = MaterialTheme.typography.labelMedium, color = LoomColors.TextMuted)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium.tabular(), color = LoomColors.Text) }
    }
}
