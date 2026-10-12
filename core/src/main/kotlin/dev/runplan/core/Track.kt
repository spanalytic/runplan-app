package dev.runplan.core

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
 * [fSpeed] (smoothed speed, m/s) when it records the point.
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
 * Distance and current pace from location fixes, robust to the two things that make phone GPS pace
 * jumpy: everyday position noise, and multipath "drift" where the reported position wanders off
 * by tens of metres while you are moving steadily.
 *
 * - Positions go through a constant-velocity Kalman filter with outlier gating, so single bad fixes
 *   are ignored and noise is smoothed.
 * - Current pace comes from the receiver's Doppler speed (unaffected by position error) when the fix
 *   carries one, otherwise from the filter's velocity, smoothed with a [tauSec] time constant.
 * - Distance integrates the Doppler speed (held within 3% of the filtered track) when available, so
 *   position drift can't add distance you didn't run; otherwise it follows the filtered track.
 *
 * Times are monotonic milliseconds (e.g. elapsedRealtime). [pause] stops counting; the first fix
 * after [resume] starts a fresh segment.
 */
class Odometer(private val maxAccuracy: Float = 25f, private val tauSec: Double = 5.0) {
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
    private var speed = Double.NaN          // smoothed ground speed, m/s

    fun pause() { paused = true; started = false; speed = Double.NaN }
    fun resume() { paused = false }

    private fun east(lon: Double) = (lon - lon0) * mPerDegLon
    private fun north(lat: Double) = (lat - lat0) * M_PER_DEG_LAT

    private fun record(p: TrackPoint) {
        points += p.copy(dist = distanceM, fLat = lat0 + y.p / M_PER_DEG_LAT, fLon = lon0 + x.p / mPerDegLon, fSpeed = speed)
    }

    private fun doppler(p: TrackPoint): Double? {
        if (!p.speed.isFinite() || p.speed < 0) return null
        if (p.speedAcc.isFinite() && p.speedAcc > 1.5f) return null
        return p.speed.toDouble()
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
            speed = dop ?: Double.NaN
            lastMs = p.tMs
            started = true
            rejects = 0
            record(p)
            return true
        }

        val dt = (p.tMs - lastMs) / 1000.0
        if (dt <= 0) return false
        val px = x.p
        val py = y.p
        x.predict(dt, Q)
        y.predict(dt, Q)

        val r = max(p.acc.toDouble(), 3.0).pow(2)
        val zx = east(p.lon)
        val zy = north(p.lat)
        val d2 = x.innovation(zx).pow(2) / x.innovationVar(r) + y.innovation(zy).pow(2) / y.innovationVar(r)
        val accepted = d2 <= GATE || rejects >= MAX_REJECTS
        if (accepted) {
            if (rejects >= MAX_REJECTS) {
                // the "outliers" have persisted: we were wrong, re-acquire at the measurement
                x.reset(zx, x.v, r, 4.0)
                y.reset(zy, y.v, r, 4.0)
            } else {
                x.update(zx, r)
                y.update(zy, r)
            }
            rejects = 0
        } else {
            rejects++
        }
        lastMs = p.tMs

        // distance: filtered track, held within a band around the Doppler distance
        val posInc = hypot(x.p - px, y.p - py)
        val filtSpeed = hypot(x.v, y.v)
        val inc = if (dop != null) {
            // Doppler speed is unbiased and immune to position wander; the track only nudges it
            val dopInc = dop * dt
            if (dop < STATIONARY) 0.0 else posInc.coerceIn(0.97 * dopInc, 1.03 * dopInc)
        } else {
            if (filtSpeed < STATIONARY) 0.0 else min(posInc, (filtSpeed + 1.0) * dt)
        }
        distanceM += inc

        // pace: smooth speed (not pace) so slow paces don't swing wildly
        val measured = dop ?: filtSpeed
        // position-derived speed is much noisier than Doppler, so it gets heavier smoothing
        val alpha = 1 - exp(-dt / (if (dop != null) tauSec else tauSec * 2.5))
        speed = if (speed.isNaN()) measured else speed + alpha * (measured - speed)

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
        private const val Q = 0.5            // acceleration noise variance, m²/s⁴ (a runner changes speed gently)
        private const val GATE = 16.0        // Mahalanobis² gate (~4 sigma, 2 dof)
        private const val MAX_REJECTS = 5    // after this many consecutive rejects, trust the GPS again
        private const val STATIONARY = 0.3   // m/s; below this, no distance is added
        private const val MIN_SPEED = 0.5    // m/s; below this (33:20/km) pace shows as stopped
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
