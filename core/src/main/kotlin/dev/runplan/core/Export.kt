package dev.runplan.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One lap of an exported activity: a step of the workout. Times are on the track's monotonic clock. */
data class Lap(val startClockMs: Long, val sec: Double, val metres: Double, val resting: Boolean)

/**
 * Activity file writers. Track times are monotonic (elapsedRealtime); [startWallMs] is the
 * wall-clock time that corresponds to [startClockMs], so pauses keep their real gaps.
 */
object ActivityFiles {
    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    private fun time(startWallMs: Long, startClockMs: Long, t: Long) = iso.format(Instant.ofEpochMilli(startWallMs + (t - startClockMs)))
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun f(d: Double, n: Int) = String.format(Locale.ROOT, "%.${n}f", d)

    /** GPX 1.1 track using filtered positions. */
    fun gpx(name: String, startWallMs: Long, startClockMs: Long, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="RunPlan" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("<metadata><time>").append(time(startWallMs, startClockMs, startClockMs)).append("</time></metadata>\n")
        append("<trk><name>").append(esc(name)).append("</name><type>running</type><trkseg>\n")
        for (p in points) {
            append("<trkpt lat=\"").append(f(p.bestLat, 7)).append("\" lon=\"").append(f(p.bestLon, 7)).append("\">")
            append("<ele>").append(f(p.alt, 1)).append("</ele><time>").append(time(startWallMs, startClockMs, p.tMs)).append("</time></trkpt>\n")
        }
        append("</trkseg></trk>\n</gpx>\n")
    }

    /**
     * Garmin TCX with one lap per workout step, so intervals appear as laps in Strava and
     * Garmin Connect. Sport is Running.
     */
    fun tcx(startWallMs: Long, startClockMs: Long, points: List<TrackPoint>, laps: List<Lap>, notes: String): String = buildString {
        val ls = laps.ifEmpty {
            listOf(Lap(startClockMs, ((points.lastOrNull()?.tMs ?: startClockMs) - startClockMs) / 1000.0, points.lastOrNull()?.dist ?: 0.0, false))
        }
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">""").append('\n')
        append("<Activities><Activity Sport=\"Running\">\n")
        append("<Id>").append(time(startWallMs, startClockMs, startClockMs)).append("</Id>\n")
        ls.forEachIndexed { i, lap ->
            val end = ls.getOrNull(i + 1)?.startClockMs ?: Long.MAX_VALUE
            val pts = points.filter { (i == 0 || it.tMs >= lap.startClockMs) && it.tMs < end }
            append("<Lap StartTime=\"").append(time(startWallMs, startClockMs, lap.startClockMs)).append("\">")
            append("<TotalTimeSeconds>").append(f(lap.sec, 1)).append("</TotalTimeSeconds>")
            append("<DistanceMeters>").append(f(lap.metres, 1)).append("</DistanceMeters>")
            append("<Calories>0</Calories>")
            append("<Intensity>").append(if (lap.resting) "Resting" else "Active").append("</Intensity>")
            append("<TriggerMethod>Manual</TriggerMethod>")
            if (pts.isNotEmpty()) {
                append("<Track>\n")
                for (p in pts) {
                    append("<Trackpoint><Time>").append(time(startWallMs, startClockMs, p.tMs)).append("</Time>")
                    append("<Position><LatitudeDegrees>").append(f(p.bestLat, 7)).append("</LatitudeDegrees>")
                    append("<LongitudeDegrees>").append(f(p.bestLon, 7)).append("</LongitudeDegrees></Position>")
                    append("<AltitudeMeters>").append(f(p.alt, 1)).append("</AltitudeMeters>")
                    if (p.dist.isFinite()) append("<DistanceMeters>").append(f(p.dist, 1)).append("</DistanceMeters>")
                    append("</Trackpoint>\n")
                }
                append("</Track>")
            }
            append("</Lap>\n")
        }
        append("<Notes>").append(esc(notes)).append("</Notes>\n")
        append("</Activity></Activities>\n</TrainingCenterDatabase>\n")
    }
}

/** Colour band for a stretch of track, by which training zone its pace falls in. */
object PaceZones {
    /** Zone whose band the pace (s/km) is closest to; null when stopped/unknown. */
    fun of(pace: Double, p: Paces): Zone? {
        if (!pace.isFinite()) return null
        val centres = listOf(Zone.E to (p.easyFast + p.easySlow) / 2, Zone.M to p.marathon, Zone.T to p.threshold, Zone.I to p.interval, Zone.R to p.rep)
        // matches the run coaching: slower than marathon pace is easy
        if (pace > p.easyCap + Paces.TOLERANCE) return Zone.E
        return centres.minBy { kotlin.math.abs(it.second - pace) }.first
    }
}
