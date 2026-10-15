package ro.davidmontei.loom.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo

/** How wide Loom's window is, at Material's breakpoints: 600 and 840 dp. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/**
 * The layout Loom's window calls for. The Thor's top screen, 832×468 dp, is MEDIUM: just under
 * EXPANDED's 840 dp, and every screen is laid out for it. Its bottom screen, 538 dp wide, and a
 * phone in portrait are COMPACT: one pane at a time.
 */
data class LoomLayout(val width: WidthClass, val tallEnough: Boolean) {
    val compact get() = width == WidthClass.COMPACT
}

/** [tallEnough] is Material's medium height, 480 dp: neither of the Thor's screens is. */
fun loomLayout(widthDp: Float, heightDp: Float) = LoomLayout(
    width = when {
        widthDp < 600f -> WidthClass.COMPACT
        widthDp < 840f -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    },
    tallEnough = heightDp >= 480f,
)

/**
 * The layout for the window being drawn in. Its size comes from the window itself, not
 * `LocalConfiguration`, which describes the display and which Compose's lint refuses.
 */
@Composable
fun rememberLoomLayout(): LoomLayout {
    val size = LocalWindowInfo.current.containerDpSize
    return remember(size) { loomLayout(size.width.value, size.height.value) }
}
