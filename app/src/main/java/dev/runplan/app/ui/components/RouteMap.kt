package dev.runplan.app.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Paces
import dev.runplan.core.PaceZones
import dev.runplan.core.RouteLine
import dev.runplan.core.TrackPoint
import dev.runplan.core.Zone
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import kotlin.math.cos

/**
 * Standard OpenStreetMap tiles (no API key), shown greyed and lightened so the
 * route colours carry the information.
 */
private val Muted = ColorMatrixColorFilter(
    ColorMatrix().apply {
        setSaturation(0f)
        postConcat(ColorMatrix(floatArrayOf(
            0.55f, 0f, 0f, 0f, 112f,
            0f, 0.55f, 0f, 0f, 112f,
            0f, 0f, 0.55f, 0f, 112f,
            0f, 0f, 0f, 1f, 0f,
        )))
    },
)

/** Keep at most [max] points, evenly spaced, always including the last. */
fun downsample(points: List<TrackPoint>, max: Int): List<TrackPoint> {
    if (points.size <= max) return points
    val step = points.size.toDouble() / max
    return (0 until max).map { points[(it * step).toInt()] } + points.last()
}

/** The colour of each point: the training zone of the pace there. */
private fun zoneColours(points: List<TrackPoint>, paces: Paces?): IntArray = IntArray(points.size) { i ->
    val p = points[i]
    val pace = if (p.fSpeed.isFinite() && p.fSpeed > 0.5) 1000 / p.fSpeed else Double.NaN
    val z = paces?.let { PaceZones.of(pace, it) } ?: if (pace.isFinite()) Zone.E else null
    Palette.zone(z).toArgb()
}

/**
 * The route as a thin line coloured by pace zone, each pass shifted a little to the right of its
 * direction of travel (so an out-and-back shows both ways side by side), with small arrowheads
 * showing the direction. Worked out in screen pixels on every draw, so it holds at any zoom.
 */
private class RouteOverlay(private val geo: List<GeoPoint>, private val colours: IntArray, private val density: Float) : Overlay() {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = android.graphics.Path()
    private val pt = Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || geo.size < 2) return
        val pj = map.projection
        val n = geo.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            pj.toPixels(geo[i], pt)
            xs[i] = pt.x.toFloat(); ys[i] = pt.y.toFloat()
        }
        val span = 10f * density
        val (ox, oy) = RouteLine.offset(xs, ys, 1.75f * density, span)
        var i = 0
        while (i < n - 1) {
            val colour = colours[i + 1]
            path.reset()
            path.moveTo(ox[i], oy[i])
            var j = i + 1
            path.lineTo(ox[j], oy[j])
            while (j < n - 1 && colours[j + 1] == colour) { j++; path.lineTo(ox[j], oy[j]) }
            line.color = colour
            c.drawPath(path, line)
            i = j
        }
        val len = 5f * density
        val half = 3.5f * density
        for (a in RouteLine.arrows(ox, oy, 80f * density, span)) {
            // a small arrowhead pointing the way you ran, a little wider than the line
            path.reset()
            path.moveTo(a.x + a.dx * len, a.y + a.dy * len)
            path.lineTo(a.x - a.dy * half, a.y + a.dx * half)
            path.lineTo(a.x + a.dy * half, a.y - a.dx * half)
            path.close()
            head.color = colours[a.index]
            c.drawPath(path, head)
        }
    }
}

/**
 * Interactive map of a run, the route coloured by the training zone of your pace
 * (OpenStreetMap data, CARTO light tiles).
 */
@Composable
fun RouteMap(points: List<TrackPoint>, paces: Paces?, modifier: Modifier = Modifier, height: Dp = 280.dp) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            overlayManager.tilesOverlay.setColorFilter(Muted)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = false
            isVerticalMapRepetitionEnabled = false
            setHorizontalMapRepetitionEnabled(false)
            minZoomLevel = 3.0
            overlays.add(CopyrightOverlay(ctx).apply { setTextSize(9) })
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }
    AndroidView(
        factory = { map },
        modifier = modifier.fillMaxWidth().height(height),
        update = { m ->
            m.overlays.removeAll { it is RouteOverlay || it is Polygon }
            if (points.size < 2) return@AndroidView
            val density = m.resources.displayMetrics.density
            val shown = downsample(points, 1500)
            m.overlays.add(RouteOverlay(shown.map { GeoPoint(it.bestLat, it.bestLon) }, zoneColours(shown, paces), density))
            val start = GeoPoint(points.first().bestLat, points.first().bestLon)
            val end = GeoPoint(points.last().bestLat, points.last().bestLon)
            fun dot(at: GeoPoint, fill: Color) = Polygon(m).apply {
                setPoints(Polygon.pointsAsCircle(at, 7.0))
                infoWindow = null
                fillPaint.color = fill.toArgb()
                outlinePaint.color = Color.White.toArgb()
                outlinePaint.strokeWidth = 2.5f * density
            }
            m.overlays.add(dot(start, Palette.Good))
            m.overlays.add(dot(end, Palette.Ink))
            val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.bestLat, it.bestLon) })
            val pad = (32 * density).toInt()
            m.addOnFirstLayoutListener { _, _, _, _, _ -> m.zoomToBoundingBox(box.increaseByScale(1.1f), false, pad) }
            if (m.width > 0) m.zoomToBoundingBox(box.increaseByScale(1.1f), false, pad)
            m.invalidate()
        },
    )
}

/** Small route silhouette for lists (no tiles, no network). */
@Composable
fun RouteThumb(points: List<TrackPoint>, modifier: Modifier = Modifier, color: Color = Palette.Ink) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val lat0 = points.first().bestLat
        val k = cos(Math.toRadians(lat0))
        val xs = points.map { it.bestLon * k }
        val ys = points.map { -it.bestLat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val span = maxOf(maxX - minX, maxY - minY).takeIf { it > 0 } ?: return@Canvas
        val pad = 3.dp.toPx()
        val scale = (minOf(size.width, size.height) - 2 * pad) / span
        val ox = (size.width - (maxX - minX) * scale) / 2
        val oy = (size.height - (maxY - minY) * scale) / 2
        val path = Path()
        xs.indices.forEach { i ->
            val o = Offset((ox + (xs[i] - minX) * scale).toFloat(), (oy + (ys[i] - minY) * scale).toFloat())
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
