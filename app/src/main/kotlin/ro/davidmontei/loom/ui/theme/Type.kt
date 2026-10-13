package ro.davidmontei.loom.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ro.davidmontei.loom.R

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Tabular figures so sizes, percentages and speeds don't jitter as they update. */
private const val TABULAR = "tnum"

private fun style(size: Int, weight: FontWeight, lineHeight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
)

// Sizes are a notch above Material defaults: the Thor's 6" panel is read at arm's length.
val LoomTypography = Typography(
    displaySmall = style(34, FontWeight.SemiBold, 42),
    headlineMedium = style(28, FontWeight.SemiBold, 36),
    headlineSmall = style(24, FontWeight.SemiBold, 32),
    titleLarge = style(22, FontWeight.SemiBold, 28),
    titleMedium = style(18, FontWeight.Medium, 24),
    titleSmall = style(16, FontWeight.Medium, 22),
    bodyLarge = style(18, FontWeight.Normal, 26),
    bodyMedium = style(16, FontWeight.Normal, 22),
    bodySmall = style(14, FontWeight.Normal, 20),
    labelLarge = style(16, FontWeight.Medium, 20, 0.1),
    labelMedium = style(14, FontWeight.Medium, 18, 0.2),
    labelSmall = style(12, FontWeight.Medium, 16, 0.3),
)

/** Use for any number that changes in place: byte counts, progress, speeds, ETAs. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR)
