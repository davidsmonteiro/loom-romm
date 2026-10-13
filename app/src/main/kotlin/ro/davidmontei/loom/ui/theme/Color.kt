package ro.davidmontei.loom.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Loom's palette: quiet charcoal surfaces, warm off-white text, one silk-gold accent (focus rings,
 * progress, primary actions), sage for "installed", soft red for errors. Dark theme only.
 */
object LoomColors {
    val Background = Color(0xFF121316)
    val Surface = Color(0xFF1A1C20)
    val SurfaceRaised = Color(0xFF23262B)
    val SurfaceHighest = Color(0xFF2D3036)
    val Outline = Color(0xFF3A3D44)

    val Text = Color(0xFFEDE8E0)
    val TextMuted = Color(0xFFA8A39B)
    val TextFaint = Color(0xFF6F6B65)

    val Accent = Color(0xFFD9A441)
    val OnAccent = Color(0xFF1A1407)
    val AccentMuted = Color(0xFF5C4720)

    val Installed = Color(0xFF7FA38A)
    val OnInstalled = Color(0xFF0F1A13)

    val Error = Color(0xFFE5736A)
    val OnError = Color(0xFF2A0D0A)
}
