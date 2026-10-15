package ro.davidmontei.loom.ui.second

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ro.davidmontei.loom.R
import ro.davidmontei.loom.ui.components.Space

/**
 * The second screen: what the screen on top publishes about what the pad is on, on black (an OLED
 * panel's off pixels), or the Loom mark while there's nothing to show. Under a game, the [strip]
 * says what the queue holds.
 */
@Composable
fun SecondScreenContent(content: CompanionContent, strip: QueueStrip? = null) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.xl)
            .testTag("second-screen"),
    ) {
        when (content) {
            CompanionContent.Idle ->
                Image(painterResource(R.drawable.ic_loom_mark), contentDescription = "Loom", modifier = Modifier.size(72.dp).align(Alignment.Center))
            is CompanionContent.Game -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                GamePanel(content.game, content.platformName, content.coverBase, Modifier.weight(1f).fillMaxWidth())
                strip?.let { QueueStripRow(it) }
            }
            is CompanionContent.Conflict -> ConflictPanel(content.item, Modifier.fillMaxSize())
            is CompanionContent.Job -> JobPanel(content.item, Modifier.fillMaxSize())
        }
    }
}
