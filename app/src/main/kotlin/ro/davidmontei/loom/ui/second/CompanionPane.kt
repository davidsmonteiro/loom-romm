package ro.davidmontei.loom.ui.second

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.library.Cover
import ro.davidmontei.loom.ui.library.GameFacts
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.StateBadge
import ro.davidmontei.loom.ui.theme.LoomColors

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
