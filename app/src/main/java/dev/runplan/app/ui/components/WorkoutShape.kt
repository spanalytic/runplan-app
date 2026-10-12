package dev.runplan.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Paces
import dev.runplan.core.RunStep
import dev.runplan.core.Workout
import dev.runplan.core.Zone

/** One bar of the workout shape: [weight] is its duration, [zone] its intensity. */
data class Bar(val weight: Float, val zone: Zone?)

fun bars(w: Workout, p: Paces): List<Bar> = w.flatSteps().map { s ->
    val pace = s.zone?.let { p.centre(it) } ?: p.jog
    val sec = s.secs ?: s.metres?.let { it / 1000 * pace } ?: 600.0
    Bar(sec.toFloat(), s.zone)
}

fun bars(steps: List<RunStep>, p: Paces?): List<Bar> = steps.map { s ->
    val pace = if (s.hasTarget) (s.fast + s.slow) / 2 else p?.jog ?: 400.0
    val sec = s.secs ?: s.metres?.let { it / 1000 * pace } ?: 600.0
    Bar(sec.toFloat(), s.zone)
}

/**
 * The session drawn as bars: width = time, height and colour = intensity.
 * With [progress] (0..1 of total time) the elapsed part is drawn solid and the rest faded,
 * with a marker at the current position.
 */
@Composable
fun WorkoutShape(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    progress: Float? = null,
    faded: Color = Palette.Line,
    marker: Color = Palette.Ink,
    gap: Dp = 2.dp,
) {
    val total = remember(bars) { bars.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f) }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val g = gap.toPx()
        val usable = size.width - g * (bars.size - 1).coerceAtLeast(0)
        val r = CornerRadius(2.dp.toPx())
        var x = 0f
        val cut = progress?.let { it.coerceIn(0f, 1f) * size.width }
        for (b in bars) {
            val w = (b.weight / total * usable).coerceAtLeast(1.5f)
            val h = Palette.height(b.zone) * size.height
            val top = size.height - h
            val c = Palette.zone(b.zone)
            if (cut == null || x + w <= cut) {
                drawRoundRect(c, Offset(x, top), Size(w, h), r)
            } else if (x >= cut) {
                drawRoundRect(c.copy(alpha = 0.32f), Offset(x, top), Size(w, h), r)
            } else {
                drawRoundRect(c.copy(alpha = 0.32f), Offset(x, top), Size(w, h), r)
                drawRoundRect(c, Offset(x, top), Size(cut - x, h), r)
            }
            x += w + g
        }
        if (cut != null) {
            drawRect(marker, Offset(cut - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
        }
    }
}

/** Fraction of the session completed, by time weight. */
fun progressOf(bars: List<Bar>, stepIndex: Int, stepProgress: Double): Float {
    val total = bars.sumOf { it.weight.toDouble() }
    if (total <= 0) return 0f
    val done = bars.take(stepIndex).sumOf { it.weight.toDouble() } +
        (bars.getOrNull(stepIndex)?.weight ?: 0f) * (if (stepProgress.isFinite()) stepProgress else 0.0)
    return (done / total).toFloat()
}
