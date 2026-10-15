package ro.davidmontei.loom.ui.second

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import okhttp3.HttpUrl
import ro.davidmontei.loom.ui.components.LoomProgressBar
import ro.davidmontei.loom.ui.components.QR_MARGINS
import ro.davidmontei.loom.ui.components.QrCode
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.StatusBadge
import ro.davidmontei.loom.ui.library.Cover
import ro.davidmontei.loom.ui.library.GameFacts
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.StateBadge
import ro.davidmontei.loom.ui.onboarding.Countdown
import ro.davidmontei.loom.ui.onboarding.pairingCodeStyle
import ro.davidmontei.loom.ui.queue.ConflictItem
import ro.davidmontei.loom.ui.queue.JobBadge
import ro.davidmontei.loom.ui.queue.QueueItem
import ro.davidmontei.loom.ui.queue.SaveSide
import ro.davidmontei.loom.ui.queue.serverSide
import ro.davidmontei.loom.ui.queue.thorSide
import ro.davidmontei.loom.ui.queue.transferLine
import ro.davidmontei.loom.ui.theme.LoomColors
import ro.davidmontei.loom.ui.theme.tabular

/**
 * The focused game, as a wide window shows it beside the games and a second screen on its own: its
 * cover, its title, and what its details say. Nothing in it can be pressed or focused: the pad stays
 * with the games, and A opens the details to act on the game.
 *
 * Wider than tall (the Thor's bottom screen), the cover is beside the words; taller, above them,
 * taking the room they leave. Where that leaves too little (a phone on its side), a small cover sits
 * beside the title and the rest takes fewer lines. Either way the words fit.
 */
@Composable
fun GamePanel(game: GameItem, platformName: String?, coverBase: HttpUrl?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.testTag("game-panel")) {
        when {
            maxWidth > maxHeight -> {
                val coverWidth = maxWidth * 0.42f
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    PanelCover(game, coverBase, Modifier.widthIn(max = coverWidth).aspectRatio(3f / 4f, matchHeightConstraintsFirst = true))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Title(game)
                        PanelFacts(game, platformName)
                    }
                }
            }
            maxHeight >= TALL_ENOUGH_FOR_A_COVER_ABOVE -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                PanelCover(game, coverBase, Modifier.weight(1f, fill = false).padding(bottom = Space.s).align(Alignment.CenterHorizontally)
                    .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true))
                Title(game)
                PanelFacts(game, platformName)
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    PanelCover(game, coverBase, Modifier.size(width = 72.dp, height = 96.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) { Title(game) }
                }
                PanelFacts(game, platformName, short = true)
            }
        }
    }
}

/** Below this a cover above the words would be too small to make out. */
private val TALL_ENOUGH_FOR_A_COVER_ABOVE = 460.dp

@Composable
private fun PanelCover(game: GameItem, coverBase: HttpUrl?, modifier: Modifier) {
    Cover(game, coverBase, modifier.clip(RoundedCornerShape(12.dp)).background(LoomColors.SurfaceRaised).testTag("panel-cover"))
}

@Composable
private fun ColumnScope.Title(game: GameItem) {
    Text(game.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
    StateBadge(game)
}

@Composable
private fun ColumnScope.PanelFacts(game: GameItem, platformName: String?, short: Boolean = false) {
    GameFacts(game, platformName, saveLines = if (short) 2 else 3, lineLimit = if (short) 1 else 2, style = MaterialTheme.typography.bodyMedium)
}

/**
 * A save conflict as the queue's focused row: both sides beside each other, which the row has room
 * for on one line only. Choosing stays on the top screen, where the pad is.
 */
@Composable
fun ConflictPanel(item: ConflictItem, modifier: Modifier = Modifier) {
    Column(modifier.testTag("conflict-panel"), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        when {
            item.busy -> StatusBadge("Working…", color = LoomColors.Accent, onColor = LoomColors.OnAccent)
            item.skipped -> StatusBadge("Skipped", color = LoomColors.SurfaceHighest, onColor = LoomColors.TextMuted)
            else -> StatusBadge("Choose which to keep", color = LoomColors.Error, onColor = LoomColors.OnError)
        }
        Text(item.reason, style = MaterialTheme.typography.bodyMedium, color = LoomColors.TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            SaveSide("On the Thor", thorSide(item), Modifier.weight(1f).testTag("panel-thor-side"))
            SaveSide("On RomM", serverSide(item), Modifier.weight(1f).testTag("panel-server-side"))
        }
    }
}

/** A download as the queue's focused row, large: how far it is, how fast, and how long it has left; or why it failed. */
@Composable
fun JobPanel(item: QueueItem, modifier: Modifier = Modifier) {
    Column(modifier.testTag("job-panel"), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        JobBadge(item)
        if (!item.failed) {
            LoomProgressBar(item.fraction, Modifier.padding(top = Space.s), height = 10.dp,
                color = if (item.phase.isActive) LoomColors.Accent else LoomColors.TextFaint)
            Text(transferLine(item), style = MaterialTheme.typography.bodyLarge.tabular(), color = LoomColors.TextMuted)
        } else {
            Text(item.detail ?: "The download failed.", style = MaterialTheme.typography.bodyMedium, color = LoomColors.TextMuted, maxLines = 4,
                overflow = TextOverflow.Ellipsis)
            if (!item.retryable) Text("Retrying won't help until this is fixed.", style = MaterialTheme.typography.bodyMedium, color = LoomColors.Error)
        }
    }
}

/**
 * What the queue holds, in a strip under the game: the download under way and how far it is, then
 * how many more wait, failed, or are save conflicts to settle.
 */
@Composable
fun QueueStripRow(strip: QueueStrip, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().background(LoomColors.Surface, RoundedCornerShape(12.dp)).padding(horizontal = Space.m, vertical = Space.s)
            .testTag("queue-strip"),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        strip.current?.let { current ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                JobBadge(current)
                Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
            }
        }
        // The counts in full, the save conflicts last among them: the bar takes what they leave.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            strip.current?.let { LoomProgressBar(it.fraction, Modifier.weight(1f), height = 4.dp) }
            if (strip.counts.isNotEmpty()) {
                Text(strip.counts, style = MaterialTheme.typography.labelMedium.tabular(), color = LoomColors.TextMuted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Pairing's code to scan with a phone, as large as the screen leaves room for, with the code under it
 * and how long it lasts. The step on top keeps the words and where to type the code without a camera.
 */
@Composable
fun PairingPanel(pairing: CompanionContent.Pairing, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.testTag("pairing-panel"), contentAlignment = Alignment.Center) {
        val qr = (minOf(maxWidth, maxHeight - UNDER_THE_QR) - QR_MARGINS).coerceAtLeast(0.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
            QrCode(pairing.session.verificationUrlComplete.toString(), size = qr)
            Text(pairing.session.displayCode, style = pairingCodeStyle(), color = LoomColors.Accent, maxLines = 1)
            Countdown(pairing.startedAt, pairing.session.expiresInSeconds)
        }
    }
}

/** What goes under the QR code: the code's line and the countdown's, and the space above each. */
private val UNDER_THE_QR = 88.dp
