package ro.davidmontei.loom.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import okhttp3.HttpUrl
import ro.davidmontei.loom.data.catalog.CoverRef
import ro.davidmontei.loom.data.catalog.CoverStore
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.ui.components.LoomProgressBar
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.StatusBadge
import ro.davidmontei.loom.ui.theme.LoomColors

/** What a game is, in a line: "Super Nintendo · 1.5 MB · USA, Europe". */
fun factsLine(game: GameItem, platformName: String?): String =
    listOfNotNull(platformName, formatBytes(game.sizeBytes), game.regions.takeIf { it.isNotEmpty() }?.joinToString()).joinToString(" · ")

/** Where the game is: on the device and where, how its download goes, or why it failed and whether retrying helps. */
fun stateLines(game: GameItem): List<String> = buildList {
    when (game.state) {
        InstallState.INSTALLED -> add("On this device at ${game.installPath}" + when {
            game.matchedByName -> " (Loom found it there, by its name only)"
            game.adopted -> " (Loom found it there)"
            else -> ""
        })
        InstallState.QUEUED, InstallState.DOWNLOADING ->
            add(if (game.finishing) "Moving it into your ROMs folder" else game.stateDetail ?: "Downloading")
        InstallState.FAILED -> {
            add(game.stateDetail ?: "The last download failed")
            if (!game.retryable) add("Retrying won't help until this is fixed.")
        }
        InstallState.NOT_INSTALLED -> add(if (game.removedOnServer) "No longer on the RomM server" else "Not on this device")
    }
}

/**
 * The badge on a game's cover, row and panel: "On device", how far its download is, or "Finishing…"
 * while it is moved into place, whatever the progress says. Null for a game that is simply not on
 * the device.
 */
fun stateBadgeText(game: GameItem): String? = when (game.state) {
    InstallState.INSTALLED -> "On device"
    InstallState.DOWNLOADING -> game.stateDetail.takeIf { game.finishing } ?: game.progress?.let { "${(it * 100).toInt()}%" }
        ?: (game.stateDetail ?: "Downloading")
    InstallState.QUEUED -> game.stateDetail ?: "Queued"
    InstallState.FAILED -> "Failed"
    InstallState.NOT_INSTALLED -> if (game.removedOnServer) "Removed from RomM" else null
}

@Composable
internal fun StateBadge(game: GameItem) {
    val text = stateBadgeText(game) ?: return
    when (game.state) {
        InstallState.INSTALLED -> StatusBadge(text)
        InstallState.DOWNLOADING -> StatusBadge(text, color = LoomColors.Accent, onColor = LoomColors.OnAccent)
        InstallState.QUEUED -> StatusBadge(text, color = LoomColors.SurfaceHighest, onColor = LoomColors.Text)
        InstallState.FAILED -> StatusBadge(text, color = LoomColors.Error, onColor = LoomColors.OnError)
        InstallState.NOT_INSTALLED -> StatusBadge(text, color = LoomColors.SurfaceHighest, onColor = LoomColors.TextMuted)
    }
}

internal fun saveColor(save: GameSave) = when (save.state) {
    SaveSyncState.IN_SYNC -> LoomColors.Installed
    SaveSyncState.CONFLICT, SaveSyncState.ERROR -> LoomColors.Error
    SaveSyncState.SERVER_NEWER, SaveSyncState.LOCAL_MISSING, SaveSyncState.NEEDS_FOLDER, SaveSyncState.MOVED -> LoomColors.Accent
    else -> LoomColors.TextMuted
}

/**
 * What a game's details say of it, in the details themselves and in the panels that show the
 * focused game: what it is, where it is, its download's progress, and where its save stands. Each
 * line takes [lineLimit] lines at most, and the save's line [saveLines], in [style]. [saveExtras] go under the
 * save's line: the details' look at the save and its buttons. A panel only ever shows the save as
 * it stands; looking at it is the details' to start.
 *
 * Its parts are the column's own, spaced as the column spaces them.
 */
@Composable
fun ColumnScope.GameFacts(
    game: GameItem,
    platformName: String?,
    saveLines: Int = 2,
    lineLimit: Int = Int.MAX_VALUE,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    saveExtras: @Composable ColumnScope.() -> Unit = {},
) {
    // Grouped, rather than spaced like the column's parts: a 3DS game's details have to fit the
    // screen with a long title and path, its save and a row of save buttons.
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        (listOf(factsLine(game, platformName)) + stateLines(game)).forEach {
            Text(it, style = style, color = LoomColors.TextMuted, maxLines = lineLimit, overflow = TextOverflow.Ellipsis)
        }
    }
    game.progress?.let { LoomProgressBar(it) }
    game.save?.let { save ->
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(saveLine(save), style = style, color = saveColor(save), maxLines = saveLines,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("save-line"))
            saveExtras()
        }
    }
}

/** A game's cover, or the first letters of its title where it has none. */
@Composable
internal fun Cover(game: GameItem, coverBase: HttpUrl?, modifier: Modifier) {
    val path = game.coverPath
    if (path == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(game.title.take(2).uppercase(), style = MaterialTheme.typography.headlineSmall, color = LoomColors.TextFaint)
        }
        return
    }
    AsyncImage(
        // Stored covers show offline too; the address is only for ones not fetched yet. Decoded at the
        // size they are stored, a focused card's: sized to the card, the focus would enlarge them again.
        model = ImageRequest.Builder(LocalContext.current).data(CoverRef(path, coverBase, game.coverLarge))
            .size(CoverStore.WIDTH, CoverStore.HEIGHT).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
