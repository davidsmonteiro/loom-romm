package ro.davidmontei.loom.ui

import android.graphics.Bitmap
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.theme.LoomTheme
import java.io.File

/**
 * Robolectric qualifiers approximating the AYN Thor's top screen: 1920×1080 at roughly 2.3×,
 * i.e. about 840×470 dp in landscape.
 */
const val THOR_TOP_SCREEN = "w840dp-h470dp-land-xhdpi"

@Composable
fun LoomTestTheme(content: @Composable () -> Unit) = LoomTheme {
    CompositionLocalProvider(LocalIndication provides FocusRingIndication()) { content() }
}

/** Saves what the rule currently shows to app/build/screenshots/<name>.png for a human to look at. */
fun ComposeContentTestRule.saveScreenshot(name: String) {
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val dir = File("build/screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
