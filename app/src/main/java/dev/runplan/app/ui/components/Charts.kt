package dev.runplan.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import dev.runplan.app.ui.theme.Body
import dev.runplan.app.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private val AxisText = TextStyle(fontFamily = Body, fontSize = 11.sp, color = Palette.Slate, fontFeatureSettings = "tnum")
private val Grid = Color(0xFFE6E9ED)

/** Clean tick step: 1/2/5 x 10^k, or one of [preferred] when given (e.g. pace seconds). */
private fun niceStep(range: Double, target: Int, preferred: List<Double>? = null): Double {
    val raw = range / target
    if (preferred != null) return preferred.firstOrNull { it >= raw } ?: preferred.last()
    val mag = 10.0.pow(floor(ln(raw) / ln(10.0)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
}

private fun percentile(xs: List<Double>, p: Double): Double {
    val s = xs.sorted()
    return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
}

/**
 * Single-measure line chart with one y-axis, hairline grid and tap/drag to inspect.
 * Segments take [colorAt] of their starting point (so a pace line can carry zone colour);
 * NaN values break the line.
 *
 * @param invertY draw larger values lower (pace: faster is higher)
 * @param robust clip the y-range to the 2nd-98th percentile so walking breaks don't flatten the chart
 */
@Composable
fun LineChart(
    data: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    colorAt: (Int) -> Color = { Palette.Ink },
    invertY: Boolean = false,
    robust: Boolean = false,
    area: Boolean = false,
    dots: Boolean = false,
    yTicks: List<Double>? = null,
    minSpan: Double = 0.0,
    yFormat: (Double) -> String,
    xFormat: (Double) -> String,
    inspect: (Int) -> String,
) {
    val finite = remember(data) { data.filter { it.second.isFinite() } }
    if (finite.size < 2) return
    val tm = rememberTextMeasurer()
    var sel by remember(data) { mutableStateOf<Int?>(null) }
    val ys = finite.map { it.second }
    var lo = if (robust) percentile(ys, 0.02) else ys.min()
    var hi = if (robust) percentile(ys, 0.98) else ys.max()
    if (hi - lo < max(minSpan, 1e-6)) {
        val mid = (hi + lo) / 2
        val half = max(minSpan, 2.0) / 2
        lo = mid - half; hi = mid + half
    }
    val step = niceStep(hi - lo, 3, yTicks)
    lo = floor(lo / step) * step
    hi = ceil(hi / step) * step
    val x0 = data.first().first
    val x1 = max(data.last().first, x0 + 1e-6)

    Column(modifier.fillMaxWidth()) {
        Text(
            sel?.let { inspect(it) } ?: "Tap or drag the chart for values",
            style = MaterialTheme.typography.labelMedium,
            color = if (sel != null) Palette.Ink else Palette.Slate,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(data) {
                    val left = 44.dp.toPx()
                    fun pick(x: Float) {
                        val f = ((x - left) / (size.width - left)).coerceIn(0f, 1f)
                        val tx = x0 + f * (x1 - x0)
                        sel = data.indices.minByOrNull { abs(data[it].first - tx) }
                    }
                    detectTapGestures { pick(it.x) }
                }
                .pointerInput(data) {
                    val left = 44.dp.toPx()
                    detectDragGestures(onDragEnd = {}) { change, _ ->
                        val f = ((change.position.x - left) / (size.width - left)).coerceIn(0f, 1f)
                        val tx = x0 + f * (x1 - x0)
                        sel = data.indices.minByOrNull { abs(data[it].first - tx) }
                    }
                },
        ) {
            val left = 44.dp.toPx()
            val bottom = 18.dp.toPx()
            val w = size.width - left
            val h = size.height - bottom
            fun px(x: Double) = (left + (x - x0) / (x1 - x0) * w).toFloat()
            fun py(y: Double): Float {
                val f = ((y.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat()
                return if (invertY) f * h else h - f * h
            }
            // grid + y labels
            var t = lo
            while (t <= hi + step / 2) {
                val y = py(t)
                drawLine(Grid, Offset(left, y), Offset(size.width, y), 1f)
                val layout = tm.measure(yFormat(t), AxisText)
                drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2f))
                t += step
            }
            // x labels: start, middle, end
            listOf(x0, (x0 + x1) / 2, x1).forEachIndexed { i, xv ->
                val layout = tm.measure(xFormat(xv), AxisText)
                val x = px(xv) - when (i) { 0 -> 0f; 1 -> layout.size.width / 2f; else -> layout.size.width.toFloat() }
                drawText(layout, topLeft = Offset(x, h + 4.dp.toPx()))
            }
            // area wash
            if (area) {
                val path = Path()
                var started = false
                data.forEach { (xv, yv) ->
                    if (!yv.isFinite()) return@forEach
                    if (!started) { path.moveTo(px(xv), h); path.lineTo(px(xv), py(yv)); started = true } else path.lineTo(px(xv), py(yv))
                }
                data.lastOrNull { it.second.isFinite() }?.let { path.lineTo(px(it.first), h) }
                path.close()
                drawPath(path, colorAt(0).copy(alpha = 0.10f))
            }
            // line, coloured per segment
            val stroke = 2.dp.toPx()
            for (i in 0 until data.size - 1) {
                val a = data[i]; val b = data[i + 1]
                if (!a.second.isFinite() || !b.second.isFinite()) continue
                drawLine(colorAt(i), Offset(px(a.first), py(a.second)), Offset(px(b.first), py(b.second)), stroke, cap = StrokeCap.Round)
            }
            if (dots) data.forEachIndexed { i, (xv, yv) ->
                if (!yv.isFinite()) return@forEachIndexed
                drawCircle(Color.White, 6.dp.toPx(), Offset(px(xv), py(yv)))
                drawCircle(colorAt(i), 4.dp.toPx(), Offset(px(xv), py(yv)))
            }
            // inspection crosshair
            sel?.let { i ->
                val (xv, yv) = data[i]
                val x = px(xv)
                drawLine(Palette.Ink.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), 1.dp.toPx())
                if (yv.isFinite()) {
                    drawCircle(Color.White, 6.dp.toPx(), Offset(x, py(yv)))
                    drawCircle(colorAt(i), 4.dp.toPx(), Offset(x, py(yv)))
                }
            }
        }
    }
}

/**
 * Columns from one baseline (<=24dp thick, 4dp rounded top) with an optional "planned" tick per
 * column. Tap a column to select it; [inspect] describes the selection.
 */
@Composable
fun ColumnChart(
    values: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    planned: List<Double?> = emptyList(),
    color: Color = Palette.Signal,
    height: Dp = 170.dp,
    yFormat: (Double) -> String,
    inspect: (Int) -> String,
) {
    if (values.isEmpty()) return
    val tm = rememberTextMeasurer()
    var sel by remember(values) { mutableStateOf<Int?>(values.indices.last) }
    val top = max(values.max(), planned.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val step = niceStep(top, 3)
    val hi = ceil(top / step) * step
    val showPlanned = planned.any { it != null }

    Column(modifier.fillMaxWidth()) {
        Text(sel?.let { inspect(it) } ?: "", style = MaterialTheme.typography.labelMedium, color = Palette.Ink)
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(values) {
                val left = 36.dp.toPx()
                detectTapGestures { p ->
                    val slot = (size.width - left) / values.size
                    sel = ((p.x - left) / slot).toInt().coerceIn(0, values.size - 1)
                }
            },
        ) {
            val left = 36.dp.toPx()
            val bottom = 18.dp.toPx()
            val h = size.height - bottom
            val slot = (size.width - left) / values.size
            val barW = min(24.dp.toPx(), slot * 0.62f)
            fun py(v: Double) = (h - v / hi * h).toFloat()
            var t = 0.0
            while (t <= hi + step / 2) {
                val y = py(t)
                drawLine(Grid, Offset(left, y), Offset(size.width, y), 1f)
                val l = tm.measure(yFormat(t), AxisText)
                drawText(l, topLeft = Offset(left - l.size.width - 6.dp.toPx(), y - l.size.height / 2f))
                t += step
            }
            val r = 4.dp.toPx()
            values.forEachIndexed { i, v ->
                val cx = left + slot * i + slot / 2
                val faded = sel != null && sel != i
                if (v > 0) {
                    val y = py(v)
                    val rect = RoundRect(
                        left = cx - barW / 2, top = y, right = cx + barW / 2, bottom = h,
                        topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                    )
                    drawPath(Path().apply { addRoundRect(rect) }, color.copy(alpha = if (faded) 0.45f else 1f))
                }
                planned.getOrNull(i)?.let { pv ->
                    val y = py(pv)
                    drawLine(Palette.Ink, Offset(cx - barW / 2 - 3.dp.toPx(), y), Offset(cx + barW / 2 + 3.dp.toPx(), y), 2.dp.toPx(), cap = StrokeCap.Round)
                }
                // label every other column when crowded
                if (values.size <= 8 || i % 2 == (values.size - 1) % 2) {
                    val l = tm.measure(labels[i], AxisText)
                    drawText(l, topLeft = Offset(cx - l.size.width / 2f, h + 4.dp.toPx()))
                }
            }
        }
        if (showPlanned) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendKey(color, "Run", bar = true)
                LegendKey(Palette.Ink, "Planned", bar = false)
            }
        }
    }
}

@Composable
fun LegendKey(color: Color, label: String, bar: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (bar) Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        else Box(Modifier.size(width = 14.dp, height = 2.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
    }
}

/** Kilometre splits as horizontal bars: longer = faster. Pace text stays in ink; the bar carries zone colour. */
@Composable
fun SplitBars(splits: List<Double>, colorFor: (Double) -> Color) {
    val finite = splits.filter { it.isFinite() && it > 0 }
    if (finite.isEmpty()) return
    val fastest = finite.min()
    val slowest = finite.max()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        splits.forEachIndexed { i, sec ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = Palette.Slate, modifier = Modifier.width(28.dp))
                Box(Modifier.weight(1f).height(12.dp)) {
                    if (sec.isFinite() && sec > 0) {
                        // scale between 35% (slowest) and 100% (fastest)
                        val f = if (slowest - fastest < 1) 1f else (0.35 + 0.65 * (slowest - sec) / (slowest - fastest)).toFloat()
                        Box(Modifier.fillMaxWidth(f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(colorFor(sec)))
                    }
                }
                Text(
                    dev.runplan.core.Fmt.pace(sec), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.width(52.dp).padding(start = 8.dp),
                )
                if (sec == fastest && splits.size > 1) Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Ink))
                else Spacer(Modifier.size(6.dp))
            }
        }
    }
}
