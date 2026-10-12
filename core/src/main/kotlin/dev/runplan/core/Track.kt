package dev.runplan.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Great-circle distance in metres. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}

data class TrackPoint(val tMs: Long, val lat: Double, val lon: Double, val alt: Double, val acc: Float)

/**
 * Accumulates distance from GPS fixes and gives a smoothed current pace.
 * Times are monotonic milliseconds (e.g. SystemClock.elapsedRealtime). Paused time is excluded
 * by calling [pause]; the next fix after [resume] starts a new segment.
 */
class Odometer(private val windowMs: Long = 15_000, private val maxAccuracy: Float = 25f) {
    var distanceM = 0.0; private set
    var lastFixMs = -1L; private set
    private var lastGoodMs = -1L
    private var last: TrackPoint? = null
    private var paused = false
    private val window = ArrayDeque<Pair<Long, Double>>()   // (tMs, cumulative metres)
    val points = ArrayList<TrackPoint>()

    fun pause() { paused = true; last = null; window.clear() }
    fun resume() { paused = false }

    /** @return true if the fix was accepted. */
    fun add(p: TrackPoint): Boolean {
        lastFixMs = p.tMs
        if (p.acc <= maxAccuracy) lastGoodMs = p.tMs
        if (paused || p.acc > maxAccuracy) return false
        val prev = last
        if (prev != null) {
            val dt = (p.tMs - prev.tMs) / 1000.0
            if (dt <= 0) return false
            val d = Geo.haversine(prev.lat, prev.lon, p.lat, p.lon)
            if (d / dt > 12.0) return false          // implausible jump (> 43 km/h)
            distanceM += d
        }
        last = p
        points += p
        window.addLast(p.tMs to distanceM)
        while (window.size > 2 && p.tMs - window.first().first > windowMs) window.removeFirst()
        return true
    }

    /** Smoothed pace in s/km, or NaN if not moving / no recent fix. */
    fun pace(nowMs: Long): Double {
        if (window.size < 2 || nowMs - lastFixMs > 6_000) return Double.NaN
        val (t0, d0) = window.first()
        val (t1, d1) = window.last()
        val dt = (t1 - t0) / 1000.0
        val dd = d1 - d0
        if (dt < 4 || dd < 8) return Double.NaN
        val pace = dt / (dd / 1000)
        return if (pace > 1200) Double.NaN else pace   // slower than 20 min/km = stopped
    }

    fun gpsOk(nowMs: Long): Boolean = lastGoodMs >= 0 && nowMs - lastGoodMs < 6_000
}

/** Message paths shared by the phone and watch apps. */
object WearPaths {
    const val STATE = "/runplan/state"
    const val COMMAND = "/runplan/cmd"
    const val CMD_PAUSE = "pause"
    const val CMD_RESUME = "resume"
    const val CMD_SKIP = "skip"
    const val CMD_START = "start"
}
