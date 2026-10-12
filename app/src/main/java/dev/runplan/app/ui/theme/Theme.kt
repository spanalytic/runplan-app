package dev.runplan.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.runplan.app.R
import dev.runplan.core.PaceStatus
import dev.runplan.core.Zone

object Palette {
    val Paper = Color(0xFFF2F4F6)
    val Surface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF16202E)
    val Slate = Color(0xFF5B6675)
    val Line = Color(0xFFD9DEE4)
    val Signal = Color(0xFF2457F5)
    val Amber = Color(0xFFE2A93B)
    val Good = Color(0xFF3F9C7A)

    /** Cool-to-hot ramp: the zone colour carries the intensity. */
    fun zone(z: Zone?): Color = when (z) {
        Zone.E -> Color(0xFF8DBFA9)
        Zone.M -> Color(0xFF5B95C9)
        Zone.HM -> Color(0xFF4373C9)
        Zone.T -> Color(0xFFE2A93B)
        Zone.I -> Color(0xFFE5603C)
        Zone.R -> Color(0xFFA54FC0)
        Zone.G -> Signal
        null -> Color(0xFFC3CAD3)
    }

    fun zone(code: String): Color = zone(Zone.entries.firstOrNull { it.code == code })

    /** Relative intensity used for bar heights in the workout shape. */
    fun height(z: Zone?): Float = when (z) {
        Zone.E -> 0.38f
        Zone.M -> 0.58f
        Zone.HM -> 0.66f
        Zone.T -> 0.74f
        Zone.G -> 0.8f
        Zone.I -> 0.9f
        Zone.R -> 1f
        null -> 0.22f
    }

    /** Full-bleed background of the run screen. */
    fun field(s: PaceStatus): Pair<Color, Color> = when (s) {
        PaceStatus.SLOW -> Amber to Ink
        PaceStatus.FAST -> Signal to Color.White
        PaceStatus.IN_BAND -> Ink to Color.White
        PaceStatus.NONE -> Ink to Color.White
    }
}

val Condensed = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

val Body = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

/** Tabular figures so paces and clocks don't jitter as digits change. */
private const val TNUM = "tnum"

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 96.sp, lineHeight = 92.sp, fontFeatureSettings = TNUM),
    displayMedium = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 56.sp, fontFeatureSettings = TNUM),
    displaySmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 42.sp, fontFeatureSettings = TNUM),
    headlineMedium = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Body, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 14.sp, fontFeatureSettings = TNUM),
)

private val colors = lightColorScheme(
    primary = Palette.Signal,
    onPrimary = Color.White,
    secondary = Palette.Ink,
    onSecondary = Color.White,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Surface,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Paper,
    onSurfaceVariant = Palette.Slate,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Surface,
    surfaceContainerLow = Palette.Paper,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
    error = Color(0xFFC2402A),
)

@Composable
fun RunPlanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
