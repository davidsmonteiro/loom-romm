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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ro.davidmontei.loom.R
import ro.davidmontei.loom.ui.components.Space
import kotlin.time.Duration.Companion.minutes

/**
 * How long the second screen stays lit with no button pressed and nothing touched. It shows the same
 * thing for as long as Loom is left open, and an OLED panel keeps the mark of what it shows for long.
 */
val DIM_AFTER = 3.minutes

/**
 * The second screen: what the screen on top publishes about what the pad is on, on black (an OLED
 * panel's off pixels), or the Loom mark while there's nothing to show. Under a game, the [strip]
 * says what the queue holds.
 *
 * It goes dark, all black, once [DIM_AFTER] passes without a button or a touch: [inputs] counts them,
 * and the next one lights it again. A download moving on doesn't keep it lit. A pairing code does: it
 * is read off this screen with a phone, which takes as long as it takes, and it goes once it's used.
 */
@Composable
fun SecondScreenContent(content: CompanionContent, strip: QueueStrip? = null, inputs: Long = 0) {
    var lit by remember { mutableStateOf(true) }
    val dims = content !is CompanionContent.Pairing
    LaunchedEffect(inputs, dims) {
        lit = true
        if (dims) {
            delay(DIM_AFTER)
            lit = false
        }
    }
    if (lit) Lit(content, strip) else Box(Modifier.fillMaxSize().background(Color.Black).testTag("second-screen-dark"))
}

/** The second screen as [holder] has it: what the screens publish, the queue's strip, and the input that keeps it lit. */
@Composable
fun SecondScreenPanel(holder: CompanionHolder) {
    SecondScreenContent(holder.content.collectAsStateWithLifecycle().value, holder.strip.collectAsStateWithLifecycle().value,
        holder.inputs.collectAsStateWithLifecycle().value)
}

@Composable
private fun Lit(content: CompanionContent, strip: QueueStrip?) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.xl)
            .testTag("second-screen"),
    ) {
        when (content) {
            CompanionContent.Idle ->
                Image(painterResource(R.drawable.ic_loom_mark), contentDescription = "Loom", modifier = Modifier.size(72.dp).align(Alignment.Center))
            is CompanionContent.Game -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                GamePanel(content.game, content.platformName, content.coverBase, content.details, Modifier.weight(1f).fillMaxWidth())
                strip?.let { QueueStripRow(it) }
            }
            is CompanionContent.Conflict -> ConflictPanel(content.item, Modifier.fillMaxSize())
            is CompanionContent.Job -> JobPanel(content.item, Modifier.fillMaxSize())
            is CompanionContent.Pairing -> PairingPanel(content, Modifier.fillMaxSize())
        }
    }
}
