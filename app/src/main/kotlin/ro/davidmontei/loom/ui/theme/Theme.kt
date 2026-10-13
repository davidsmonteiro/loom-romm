package ro.davidmontei.loom.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private val LoomColorScheme = darkColorScheme(
    primary = LoomColors.Accent,
    onPrimary = LoomColors.OnAccent,
    primaryContainer = LoomColors.AccentMuted,
    onPrimaryContainer = LoomColors.Text,
    secondary = LoomColors.Installed,
    onSecondary = LoomColors.OnInstalled,
    tertiary = LoomColors.Installed,
    onTertiary = LoomColors.OnInstalled,
    background = LoomColors.Background,
    onBackground = LoomColors.Text,
    surface = LoomColors.Surface,
    onSurface = LoomColors.Text,
    surfaceVariant = LoomColors.SurfaceRaised,
    onSurfaceVariant = LoomColors.TextMuted,
    surfaceContainerLowest = LoomColors.Background,
    surfaceContainerLow = LoomColors.Surface,
    surfaceContainer = LoomColors.Surface,
    surfaceContainerHigh = LoomColors.SurfaceRaised,
    surfaceContainerHighest = LoomColors.SurfaceHighest,
    outline = LoomColors.Outline,
    outlineVariant = LoomColors.Outline,
    error = LoomColors.Error,
    onError = LoomColors.OnError,
)

val LoomShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Loom is dark-only by design: it sits next to Cocoon on a handheld, usually in dim rooms. */
@Composable
fun LoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LoomColorScheme,
        typography = LoomTypography,
        shapes = LoomShapes,
    ) {
        // Material text defaults to black outside a Surface; Loom draws on its own backgrounds.
        CompositionLocalProvider(LocalContentColor provides LoomColors.Text, content = content)
    }
}
