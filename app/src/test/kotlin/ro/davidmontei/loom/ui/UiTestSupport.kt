package ro.davidmontei.loom.ui

import android.graphics.Bitmap
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import ro.davidmontei.loom.ui.components.DialogHost
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.theme.LoomTheme
import java.io.File

/**
 * Robolectric qualifiers approximating the AYN Thor's top screen: 1920×1080 at roughly 2.3×,
 * i.e. about 840×470 dp in landscape.
 */
const val THOR_TOP_SCREEN = "w840dp-h470dp-land-xhdpi"

/**
 * The Thor's top screen exactly, as `dumpsys display` reports it: 1920×1080 px at 369 dpi (2.30625×),
 * so 832×468 dp. For measuring what is drawn in pixels.
 */
const val THOR_TOP_SCREEN_EXACT = "w832dp-h468dp-land-369dpi"

@Composable
fun LoomTestTheme(content: @Composable () -> Unit) = LoomTheme {
    CompositionLocalProvider(LocalIndication provides FocusRingIndication()) { DialogHost { content() } }
}

/** Saves what the rule currently shows to app/build/screenshots/<name>.png for a human to look at. */
fun ComposeContentTestRule.saveScreenshot(name: String) {
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val dir = File("build/screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
