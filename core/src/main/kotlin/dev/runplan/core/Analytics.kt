package dev.runplan.core

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** Minimal per-run numbers the aggregate analytics need. */
data class RunStat(val epochDay: Long, val metres: Double, val sec: Double)

/** One bucket of a weekly/monthly volume chart. */
data class Period(val startEpochDay: Long, val metres: Double, val sec: Double, val runs: Int)

/** A best effort found inside a run. */
data class Effort(val label: String, val metres: Double, val sec: Double) {
    val pace: Double get() = sec / (metres / 1000)
    val vdot: Double get() = Vdot.vdot(metres, sec)
}

object Analytics {
    /** Distances searched for best efforts (Strava-style). */
    val EFFORTS = listOf(
        "1 km" to 1000.0, "1 mile" to 1609.344, "5K" to 5000.0,
        "10K" to 10000.0, "Half" to 21097.5, "Marathon" to 42195.0,
    )

    /** VDOT is only meaningful for efforts of about a mile or more. */
    private const val MIN_VDOT_DISTANCE = 1600.0

    /** Cumulative (seconds-from-start, metres) along the track, from the odometer's distance when present. */
    fun profile(points: List<TrackPoint>): Pair<DoubleArray, DoubleArray> {
        if (points.isEmpty()) return DoubleArray(0) to DoubleArray(0)
        val t0 = points.first().tMs
        val t = DoubleArray(points.size) { (points[it].tMs - t0) / 1000.0 }
        val haveDist = points.all { it.dist.isFinite() }
        val d = DoubleArray(points.size)
        if (haveDist) {
            val d0 = points.first().dist
            for (i in points.indices) d[i] = points[i].dist - d0
        } else {
            for (i in 1 until points.size) {
                val a = points[i - 1]; val b = points[i]
                d[i] = d[i - 1] + Geo.haversine(a.bestLat, a.bestLon, b.bestLat, b.bestLon)
            }
        }
        // enforce monotonic distance (guards against any tiny negative steps)
        for (i in 1 until d.size) if (d[i] < d[i - 1]) d[i] = d[i - 1]
        return t to d
    }

    /** Fastest time for each effort distance anywhere in the run (interpolated start). */
    fun bestEfforts(points: List<TrackPoint>): List<Effort> {
        val (t, d) = profile(points)
        if (d.isEmpty()) return emptyList()
        val total = d.last()
        val out = ArrayList<Effort>()
        for ((label, dist) in EFFORTS) {
            if (total < dist) continue
            var best = Double.MAX_VALUE
            var i = 0
            for (j in d.indices) {
                val target = d[j] - dist
                if (target < 0) continue
                while (i + 1 < d.size && d[i + 1] <= target) i++
                val tStart = if (i + 1 < d.size && d[i + 1] > d[i]) {
                    t[i] + (target - d[i]) / (d[i + 1] - d[i]) * (t[i + 1] - t[i])
                } else t[i]
                best = min(best, t[j] - tStart)
            }
            if (best < Double.MAX_VALUE && best > 0) out += Effort(label, dist, best)
        }
        return out
    }

    /** The effort that implies the highest VDOT (mile and longer), or null. */
    fun fitness(efforts: List<Effort>): Effort? =
        efforts.filter { it.metres >= MIN_VDOT_DISTANCE }.maxByOrNull { it.vdot }

    /**
     * Total climb.
     *
     * With a barometer the altitude is steady to well under a metre, so a light 50 m average and
     * a 2 m step are enough. Phone GPS altitude is much worse: whole metres, about ±1.5 m of
     * jitter and several metres of slow drift. The old 15-second average with a 2 m step counted
     * that jitter as climb (54 m on a route with about half that). GPS altitude is now averaged
     * over 150 m of distance and a rise only counts once it clears 4 m from the last low point.
     */
    fun ascent(points: List<TrackPoint>): Double {
        val src = altitudes(points)
        val alts = smoothByDistance(points, src.values, if (src.baro) BARO_WINDOW_M else GPS_WINDOW_M).filter { it.isFinite() }
        if (alts.size < 2) return 0.0
        val step = if (src.baro) BARO_STEP_M else GPS_STEP_M
        // ref is the last turning point; a rise only counts once it clears the step, but then
        // counts in full up to its top (a plain step count would drop the last metres of every hill)
        var up = 0.0
        var ref = alts.first()
        var climbing = false
        for (a in alts) {
            if (climbing) {
                if (a > ref) { up += a - ref; ref = a } else if (ref - a >= step) { climbing = false; ref = a }
            } else {
                if (a < ref) ref = a else if (a - ref >= step) { climbing = true; up += a - ref; ref = a }
            }
        }
        return up
    }

    /** Smoothed altitude per point (NaN where unknown), as used for climb; for the elevation chart. */
    fun smoothedAltitude(points: List<TrackPoint>): DoubleArray {
        val src = altitudes(points)
        return smoothByDistance(points, src.values, if (src.baro) BARO_WINDOW_M else GPS_WINDOW_M)
    }

    /** Whether this track has barometric altitude (and climb uses it). */
    fun hasBarometer(points: List<TrackPoint>): Boolean = altitudes(points).baro

    private class Altitudes(val values: DoubleArray, val baro: Boolean)

    /**
     * Barometric altitude when most points have it, shifted to sit on the GPS altitude (the
     * barometer is relative: weather moves its absolute value); GPS altitude otherwise.
     */
    private fun altitudes(points: List<TrackPoint>): Altitudes {
        val gps = DoubleArray(points.size) { val a = points[it].alt; if (a.isFinite() && a != 0.0) a else Double.NaN }
        if (points.isEmpty() || points.count { it.baroAlt.isFinite() } < points.size * 0.8) return Altitudes(gps, false)
        val diffs = points.indices.filter { gps[it].isFinite() && points[it].baroAlt.isFinite() }.map { gps[it] - points[it].baroAlt }.sorted()
        val offset = if (diffs.isEmpty()) 0.0 else diffs[diffs.size / 2]
        return Altitudes(DoubleArray(points.size) { points[it].baroAlt + offset }, true)
    }

    /** Average of [values] over [window] metres of distance centred on each point; NaN stays NaN. */
    private fun smoothByDistance(points: List<TrackPoint>, values: DoubleArray, window: Double): DoubleArray {
        val out = DoubleArray(points.size) { Double.NaN }
        val (_, d) = profile(points)
        val idx = points.indices.filter { values[it].isFinite() }
        if (idx.isEmpty()) return out
        var lo = 0
        var hi = 0           // window is idx[lo until hi]
        var sum = 0.0
        for (k in idx.indices) {
            val c = d[idx[k]]
            while (hi < idx.size && d[idx[hi]] <= c + window / 2) { sum += values[idx[hi]]; hi++ }
            while (d[idx[lo]] < c - window / 2) { sum -= values[idx[lo]]; lo++ }
            out[idx[k]] = sum / (hi - lo)
        }
        return out
    }

    const val GPS_WINDOW_M = 150.0
    const val GPS_STEP_M = 4.0
    const val BARO_WINDOW_M = 50.0
    const val BARO_STEP_M = 2.0

    /** (seconds, pace s/km) every point, from the smoothed speed; NaN pace where stopped. */
    fun paceSeries(points: List<TrackPoint>, maxPoints: Int = 400): List<Pair<Double, Double>> {
        if (points.size < 2) return emptyList()
        val t0 = points.first().tMs
        val step = max(1, points.size / maxPoints)
        return points.indices.step(step).map { i ->
            val p = points[i]
            val s = if (p.fSpeed.isFinite()) p.fSpeed else p.speed.toDouble()
            (p.tMs - t0) / 1000.0 to if (s.isFinite() && s > 0.5) 1000 / s else Double.NaN
        }
    }

    /** (km, metres above or below the start) smoothed. */
    fun elevationSeries(points: List<TrackPoint>, maxPoints: Int = 300): List<Pair<Double, Double>> {
        val (_, d) = profile(points)
        if (d.size < 2) return emptyList()
        val alts = smoothedAltitude(points)
        val step = max(1, points.size / maxPoints)
        val idx = points.indices.step(step).filter { alts[it].isFinite() }
        if (idx.isEmpty()) return emptyList()
        val start = alts[idx.first()]
        return idx.map { d[it] / 1000 to alts[it] - start }
    }

    fun mondayOf(epochDay: Long): Long = PlanBuilder.mondayOf(epochDay)

    /** The last [count] weeks (Monday-based) ending with the week containing [today], oldest first. */
    fun weekly(runs: List<RunStat>, today: Long, count: Int): List<Period> {
        val thisMonday = mondayOf(today)
        return (count - 1 downTo 0).map { k ->
            val start = thisMonday - 7L * k
            val rs = runs.filter { it.epochDay in start until start + 7 }
            Period(start, rs.sumOf { it.metres }, rs.sumOf { it.sec }, rs.size)
        }
    }

    /** The last [count] calendar months ending with the month containing [today], oldest first. */
    fun monthly(runs: List<RunStat>, today: Long, count: Int): List<Period> {
        val first = LocalDate.ofEpochDay(today).withDayOfMonth(1)
        return (count - 1 downTo 0).map { k ->
            val m = first.minusMonths(k.toLong())
            val start = m.toEpochDay()
            val end = m.plusMonths(1).toEpochDay()
            val rs = runs.filter { it.epochDay in start until end }
            Period(start, rs.sumOf { it.metres }, rs.sumOf { it.sec }, rs.size)
        }
    }

    /** Rolling best VDOT over the previous [windowDays] at each of the given days. */
    fun rollingBest(points: List<Pair<Long, Double>>, days: List<Long>, windowDays: Int = 42): List<Pair<Long, Double>> =
        days.mapNotNull { day ->
            points.filter { it.first in (day - windowDays + 1)..day }.maxOfOrNull { it.second }?.let { day to it }
        }
}
