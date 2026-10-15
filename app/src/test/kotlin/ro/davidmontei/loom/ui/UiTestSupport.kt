package ro.davidmontei.loom.ui

import android.graphics.Bitmap
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ro.davidmontei.loom.ui.components.DialogHost
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.theme.LoomTheme
import java.io.File

/**
 * The AYN Thor's top screen exactly, as `dumpsys display` reports it: 1920×1080 px at 369 dpi
 * (2.30625×), so 832.5×468.3 dp. 468 dp is the full height, since Loom hides the system bars
 * (the configuration's h444dp leaves out the 55 px navigation bar). 832 dp is just under the
 * 840 dp of a wide window, as on the device.
 */
const val THOR_TOP_SCREEN = "w832dp-h468dp-land-369dpi"

/**
 * The Thor's bottom screen with Loom on it (Cocoon can open apps there): 1240×1080 px at 369 dpi,
 * so 537.7×468.3 dp with the bars hidden.
 */
const val THOR_BOTTOM_ACTIVITY = "w538dp-h468dp-land-369dpi"

/**
 * The Thor's bottom screen as a second screen beside Loom: there Compose measures the display's
 * configuration, `sw468dp w538dp h444dp` in `dumpsys window displays`, not the whole panel.
 */
const val THOR_BOTTOM_PRESENTATION = "w538dp-h444dp-land-369dpi"

/** A phone in portrait: 1080×2340 px at 420 dpi, Compose's reference phone (`Devices.PHONE`, 411×891 dp). */
const val PHONE_PORTRAIT = "w411dp-h891dp-port-420dpi"

/** The same phone in landscape. */
const val PHONE_LANDSCAPE = "w891dp-h411dp-land-420dpi"

/** A tablet in landscape: 2560×1600 px at xhdpi, the size of Compose's reference tablet (`Devices.TABLET`, 1280×800 dp). */
const val TABLET_LANDSCAPE = "w1280dp-h800dp-land-xhdpi"

/** The same tablet in portrait. */
const val TABLET_PORTRAIT = "w800dp-h1280dp-port-xhdpi"

@Composable
fun LoomTestTheme(content: @Composable () -> Unit) = LoomTheme {
    CompositionLocalProvider(LocalIndication provides FocusRingIndication()) { DialogHost { content() } }
}

/**
 * Whether everything in the node tagged [tag] is on the screen and inside it, every text there whole
 * but for an ellipsis where its lines run out: none squeezed by a lack of room.
 */
fun ComposeContentTestRule.assertFitsIn(tag: String) {
    mainClock.advanceTimeBy(300); waitForIdle()
    val root = onRoot().fetchSemanticsNode().boundsInRoot
    val pane = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    assertThat(pane.bottom).isAtMost(root.bottom)
    assertThat(pane.right).isAtMost(root.right)
    val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        .fetchSemanticsNodes()
    assertThat(texts).isNotEmpty()
    for (node in texts) {
        val text = node.config[SemanticsProperties.Text].joinToString()
        assertWithMessage(text).that(node.boundsInRoot.bottom).isAtMost(pane.bottom)
        assertWithMessage(text).that(node.boundsInRoot.right).isAtMost(pane.right)
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertWithMessage(text).that(layout.didOverflowHeight && !layout.multiParagraph.didExceedMaxLines).isFalse()
    }
}

/** Saves what the rule currently shows to app/build/screenshots/<name>.png for a human to look at. */
fun ComposeContentTestRule.saveScreenshot(name: String) {
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val dir = File("build/screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
