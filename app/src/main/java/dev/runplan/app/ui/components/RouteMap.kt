package dev.runplan.app.ui.components

import android.graphics.Paint
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
import dev.runplan.core.TrackPoint
import dev.runplan.core.Zone
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.cos

/** Light, low-contrast basemap so the route colours carry the information. */
private val CartoLight = XYTileSource(
    "CartoLight", 0, 20, 512, "@2x.png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/light_all/",
        "https://b.basemaps.cartocdn.com/light_all/",
        "https://c.basemaps.cartocdn.com/light_all/",
    ),
    "© OpenStreetMap contributors © CARTO",
)

/** Keep at most [max] points, evenly spaced, always including the last. */
fun downsample(points: List<TrackPoint>, max: Int): List<TrackPoint> {
    if (points.size <= max) return points
    val step = points.size.toDouble() / max
    return (0 until max).map { points[(it * step).toInt()] } + points.last()
}

/** Splits the track into runs of the same pace zone, so each can be drawn in that zone's colour. */
private fun segments(points: List<TrackPoint>, paces: Paces?): List<Pair<Zone?, List<GeoPoint>>> {
    val out = ArrayList<Pair<Zone?, MutableList<GeoPoint>>>()
    for (p in points) {
        val pace = if (p.fSpeed.isFinite() && p.fSpeed > 0.5) 1000 / p.fSpeed else Double.NaN
        val z = paces?.let { PaceZones.of(pace, it) } ?: if (pace.isFinite()) Zone.E else null
        val g = GeoPoint(p.bestLat, p.bestLon)
        val last = out.lastOrNull()
        if (last != null && last.first == z) last.second += g
        else {
            last?.second?.lastOrNull()?.let { prev -> out += z to mutableListOf(prev, g) } ?: run { out += z to mutableListOf(g) }
        }
    }
    return out
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
            setTileSource(CartoLight)
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
            m.overlays.removeAll { it is Polyline || it is Polygon }
            if (points.size < 2) return@AndroidView
            val density = m.resources.displayMetrics.density
            for ((zone, geo) in segments(downsample(points, 1500), paces)) {
                if (geo.size < 2) continue
                m.overlays.add(Polyline(m).apply {
                    setPoints(geo)
                    infoWindow = null
                    outlinePaint.color = Palette.zone(zone).toArgb()
                    outlinePaint.strokeWidth = 5f * density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                })
            }
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
