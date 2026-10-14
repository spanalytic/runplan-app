package dev.runplan.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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

/**
 * One location fix. [speed] is the receiver's own ground speed in m/s (GNSS Doppler on most phones),
 * NaN when the fix has none; [speedAcc] is its 1-sigma accuracy in m/s, NaN when unknown.
 * The odometer fills in [dist] (cumulative metres), [fLat]/[fLon] (filtered position) and
 * [fSpeed] (smoothed speed, m/s) when it records the point. [baroAlt] is altitude from the phone's
 * barometer (standard atmosphere, so offset by the weather but steady over a run), NaN without one.
 */
data class TrackPoint(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val acc: Float,
    val speed: Float = Float.NaN,
    val speedAcc: Float = Float.NaN,
    val dist: Double = Double.NaN,
    val fLat: Double = Double.NaN,
    val fLon: Double = Double.NaN,
    val fSpeed: Double = Double.NaN,
    val baroAlt: Double = Double.NaN,
) {
    /** Filtered position when available, raw otherwise. */
    val bestLat: Double get() = if (fLat.isFinite()) fLat else lat
    val bestLon: Double get() = if (fLon.isFinite()) fLon else lon
}

/** One axis of a constant-velocity Kalman filter: state [position, velocity] in metres. */
private class Axis {
    var p = 0.0
    var v = 0.0
    var p00 = 0.0; var p01 = 0.0; var p11 = 0.0

    fun reset(pos: Double, vel: Double, posVar: Double, velVar: Double) {
        p = pos; v = vel; p00 = posVar; p01 = 0.0; p11 = velVar
    }

    /** Predict forward [dt] seconds with white-noise acceleration of variance [q] (m²/s⁴). */
    fun predict(dt: Double, q: Double) {
        p += v * dt
        val n00 = p00 + 2 * dt * p01 + dt * dt * p11 + q * dt.pow(4) / 4
        val n01 = p01 + dt * p11 + q * dt.pow(3) / 2
        val n11 = p11 + q * dt * dt
        p00 = n00; p01 = n01; p11 = n11
    }

    fun innovation(z: Double) = z - p
    fun innovationVar(r: Double) = p00 + r

    fun update(z: Double, r: Double) {
        val s = p00 + r
        val k0 = p00 / s
        val k1 = p01 / s
        val y = z - p
        p += k0 * y
        v += k1 * y
        val n00 = (1 - k0) * p00
        val n01 = (1 - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00; p01 = n01; p11 = n11
    }
}

/**
 * Distance and current pace from location fixes.
 *
 * Tuned on a real run recorded alongside a Galaxy Watch. On that phone the receiver's Doppler
 * speed read about 6% low, and position noise under trees made per-second pace swing by minutes.
 *
 * - Positions go through a constant-velocity Kalman filter with outlier gating.
 * - Distance is measured along the filtered track in 5-second chords (matched the watch to within 1%),
 *   each chord capped at 15% over the calibrated Doppler distance so multipath drift can't add much.
 *   Standing still adds nothing.
 * - Current pace blends two estimates: Doppler speed, calibrated against the chord distance over the
 *   last two minutes and with one-off outliers rejected (a reading more than 35% away from the last
 *   five is ignored until it persists), and the track distance covered in the last 15 seconds.
 *
 * Times are monotonic milliseconds (e.g. elapsedRealtime). [pause] stops counting; the first fix
 * after [resume] starts a fresh segment.
 */
class Odometer(private val maxAccuracy: Float = 25f, private val tauSec: Double = 8.0) {
    /** Cumulative distance; never decreases. */
    var distanceM = 0.0; private set
    var lastFixMs = -1L; private set
    private var lastGoodMs = -1L
    private var paused = false
    val points = ArrayList<TrackPoint>()

    private val x = Axis()
    private val y = Axis()
    private var started = false
    private var lastMs = 0L
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var mPerDegLon = 0.0
    private var rejects = 0

    // distance in chords
    private var committed = 0.0
    private var anchorX = 0.0
    private var anchorY = 0.0
    private var anchorMs = 0L
    private var dopSince = 0.0                         // raw Doppler metres since the anchor
    private var dopKnown = true                        // every fix since the anchor had Doppler

    // Doppler calibration: (time, chord metres, raw Doppler metres)
    private val calib = ArrayDeque<Triple<Long, Double, Double>>()
    var calibration = 1.0; private set

    // pace estimates
    private val dopHist = ArrayDeque<Double>()
    private var dopEma = Double.NaN
    private val distHist = ArrayDeque<Pair<Long, Double>>()
    private var speed = Double.NaN                     // blended, m/s

    fun pause() {
        paused = true; started = false
        speed = Double.NaN; dopEma = Double.NaN
        dopHist.clear(); distHist.clear()
    }
    fun resume() { paused = false }

    private fun east(lon: Double) = (lon - lon0) * mPerDegLon
    private fun north(lat: Double) = (lat - lat0) * M_PER_DEG_LAT

    private fun record(p: TrackPoint) {
        points += p.copy(dist = distanceM, fLat = lat0 + y.p / M_PER_DEG_LAT, fLon = lon0 + x.p / mPerDegLon, fSpeed = speed)
    }

    private fun doppler(p: TrackPoint): Double? {
        if (!p.speed.isFinite() || p.speed < 0) return null
        if (p.speedAcc.isFinite() && p.speedAcc > 1.0f) return null
        return p.speed.toDouble()
    }

    private fun median(xs: Collection<Double>): Double = xs.sorted().let { s -> if (s.isEmpty()) Double.NaN else s[s.size / 2] }

    private fun setAnchor(ms: Long) {
        anchorX = x.p; anchorY = y.p; anchorMs = ms
        dopSince = 0.0; dopKnown = true
    }

    /** @return true if the fix was used. */
    fun add(p: TrackPoint): Boolean {
        lastFixMs = p.tMs
        if (p.acc <= maxAccuracy) lastGoodMs = p.tMs
        if (paused || p.acc > maxAccuracy) return false
        val dop = doppler(p)

        if (!started) {
            if (points.isEmpty() || mPerDegLon == 0.0) {
                lat0 = p.lat; lon0 = p.lon
                mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(lat0))
            }
            val r = max(p.acc.toDouble(), 3.0).pow(2)
            x.reset(east(p.lon), 0.0, r, 4.0)
            y.reset(north(p.lat), 0.0, r, 4.0)
            lastMs = p.tMs
            started = true
            rejects = 0
            setAnchor(p.tMs)
            committed = distanceM
            if (dop != null) { dopHist.addLast(dop); dopEma = dop * calibration; speed = dopEma }
            distHist.addLast(p.tMs to distanceM)
            record(p)
            return true
        }

        val dt = (p.tMs - lastMs) / 1000.0
        if (dt <= 0) return false
        x.predict(dt, Q)
        y.predict(dt, Q)

        val r = max(p.acc.toDouble(), 3.0).pow(2)
        val zx = east(p.lon)
        val zy = north(p.lat)
        val d2 = x.innovation(zx).pow(2) / x.innovationVar(r) + y.innovation(zy).pow(2) / y.innovationVar(r)
        val accepted = d2 <= GATE || rejects >= MAX_REJECTS
        if (accepted) {
            if (rejects >= MAX_REJECTS) {
                x.reset(zx, x.v, r, 4.0)
                y.reset(zy, y.v, r, 4.0)
                setAnchor(p.tMs)            // don't count the jump to the re-acquired position
            } else {
                x.update(zx, r)
                y.update(zy, r)
            }
            rejects = 0
        } else {
            rejects++
        }
        lastMs = p.tMs
        val filtSpeed = hypot(x.v, y.v)

        // robust Doppler: a reading far from the recent median is replaced by the median
        var dopUse: Double? = null
        if (dop != null) {
            val med = median(dopHist)
            dopUse = if (med.isNaN() || med < 0.5 || abs(dop - med) <= OUTLIER * med) dop else med
            dopHist.addLast(dop)
            while (dopHist.size > 5) dopHist.removeFirst()
            dopSince += dop * dt
        } else {
            dopKnown = false
            dopSince += filtSpeed * dt
        }

        // distance: chords along the filtered track
        val chord = hypot(x.p - anchorX, y.p - anchorY)
        val chordSec = (p.tMs - anchorMs) / 1000.0
        val cap = if (dopKnown) CAP_FACTOR * calibration * dopSince + CAP_SLACK else (filtSpeed + 1.5) * chordSec
        val stationary = chordSec > 0 && dopSince / chordSec < STATIONARY
        val inc = if (stationary) 0.0 else min(chord, cap)
        if (chordSec >= CHORD_SEC) {
            committed += inc
            // chords that hit the cap are suspect (drift), so they don't feed the calibration
            if (dopKnown && !stationary && chord < cap) calib.addLast(Triple(p.tMs, inc, dopSince))
            setAnchor(p.tMs)
            distanceM = max(distanceM, committed)
        } else {
            distanceM = max(distanceM, committed + inc)
        }

        // calibration of Doppler against the track, over the last two minutes
        while (calib.isNotEmpty() && p.tMs - calib.first().first > CALIB_MS) calib.removeFirst()
        val cd = calib.sumOf { it.third }
        if (cd > 150) calibration = (calib.sumOf { it.second } / cd).coerceIn(0.85, 1.25)

        // pace: blend calibrated Doppler with track distance over the last 15 s
        val alpha = 1 - exp(-dt / tauSec)
        if (dopUse != null) {
            val v = dopUse * calibration
            dopEma = if (dopEma.isNaN()) v else dopEma + alpha * (v - dopEma)
        } else {
            dopEma = if (dopEma.isNaN()) filtSpeed else dopEma + alpha / 2.5 * (filtSpeed - dopEma)
        }
        distHist.addLast(p.tMs to distanceM)
        while (distHist.size > 2 && p.tMs - distHist.first().first > WINDOW_MS) distHist.removeFirst()
        val (t0, d0) = distHist.first()
        val span = (p.tMs - t0) / 1000.0
        val vTrack = if (span >= 8) (distanceM - d0) / span else Double.NaN
        speed = when {
            // the track can't pull pace more than 20% away from calibrated Doppler (guards against drift)
            vTrack.isFinite() && dopEma.isFinite() && dopEma > 0.5 -> (vTrack.coerceIn(0.8 * dopEma, 1.2 * dopEma) + dopEma) / 2
            vTrack.isFinite() && dopEma.isFinite() -> (vTrack + dopEma) / 2
            dopEma.isFinite() -> dopEma
            else -> vTrack
        }

        record(p)
        return accepted
    }

    /** Smoothed current speed in m/s (NaN before the first fix of a segment). */
    fun speed(): Double = speed

    /** Smoothed pace in s/km, or NaN if stopped / no recent fix. */
    fun pace(nowMs: Long): Double {
        if (lastFixMs < 0 || nowMs - lastFixMs > 6_000 || !started) return Double.NaN
        val s = speed
        if (!s.isFinite() || s < MIN_SPEED) return Double.NaN
        return 1000.0 / s
    }

    fun gpsOk(nowMs: Long): Boolean = lastGoodMs >= 0 && nowMs - lastGoodMs < 6_000

    companion object {
        private const val M_PER_DEG_LAT = 111_195.0
        private const val Q = 0.5            // acceleration noise variance, m²/s⁴
        private const val GATE = 16.0        // Mahalanobis² gate (~4 sigma, 2 dof)
        private const val MAX_REJECTS = 5    // after this many consecutive rejects, trust the GPS again
        private const val STATIONARY = 0.3   // m/s; below this, no distance is added
        private const val MIN_SPEED = 0.5    // m/s; below this (33:20/km) pace shows as stopped
        private const val CHORD_SEC = 5.0
        private const val CAP_FACTOR = 1.15  // a chord may exceed the calibrated Doppler distance by 15% ...
        private const val CAP_SLACK = 1.0    // ... plus 1 m
        private const val OUTLIER = 0.35
        private const val CALIB_MS = 120_000L
        private const val WINDOW_MS = 15_000L
    }
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
