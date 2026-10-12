package dev.runplan.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.runplan.core.Fmt
import dev.runplan.core.TrackPoint
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** CSV and GPX exports, shared through the system share sheet. */
object Exports {
    private val iso = DateTimeFormatter.ISO_INSTANT

    private fun dir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    private fun share(ctx: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    private fun n(d: Double, digits: Int = 1) = if (d.isFinite()) "%.${digits}f".format(d) else ""

    /** One row per run. */
    fun runsCsv(ctx: Context, runs: List<RunRecord>) {
        val f = File(dir(ctx), "runplan_runs.csv")
        f.bufferedWriter().use { w ->
            w.write("date,title,workout_id,duration_sec,distance_km,avg_pace_sec_per_km,avg_pace,steps_on_target,steps_with_target\n")
            runs.sortedBy { it.startMs }.forEach { r ->
                val targeted = r.steps.mapNotNull { it.hit }
                w.write(listOf(
                    LocalDate.ofEpochDay(r.epochDay).toString(), csvCell(r.title), r.workoutId ?: "",
                    n(r.sec, 0), n(r.metres / 1000, 3), n(r.pace, 0), Fmt.pace(r.pace),
                    targeted.count { it }.toString(), targeted.size.toString(),
                ).joinToString(",") + "\n")
            }
        }
        share(ctx, f, "text/csv", "Export runs")
    }

    /** One row per step of one run. */
    fun stepsCsv(ctx: Context, r: RunRecord) {
        val f = File(dir(ctx), "runplan_${LocalDate.ofEpochDay(r.epochDay)}_steps.csv")
        f.bufferedWriter().use { w ->
            w.write("step,label,zone,target_fast,target_slow,duration_sec,distance_m,pace_sec_per_km,on_target\n")
            r.steps.forEachIndexed { i, s ->
                w.write(listOf(
                    (i + 1).toString(), csvCell(s.label), s.zoneCode, n(s.fast, 0), n(s.slow, 0),
                    n(s.sec, 0), n(s.metres, 0), n(s.pace, 0), s.hit?.toString() ?: "",
                ).joinToString(",") + "\n")
            }
        }
        share(ctx, f, "text/csv", "Export steps")
    }

    /** Raw GPS fixes (time, position, accuracy, Doppler speed) as CSV. */
    fun trackCsv(ctx: Context, r: RunRecord, source: File) {
        if (!source.exists()) return
        val f = File(dir(ctx), "runplan_${LocalDate.ofEpochDay(r.epochDay)}_track.csv")
        source.copyTo(f, overwrite = true)
        share(ctx, f, "text/csv", "Export GPS track")
    }

    /** GPX track for Strava, Garmin Connect etc. Times are reconstructed from the run's wall-clock start. */
    fun gpx(ctx: Context, r: RunRecord, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        val t0 = points.first().tMs
        val f = File(dir(ctx), "runplan_${LocalDate.ofEpochDay(r.epochDay)}.gpx")
        f.bufferedWriter().use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="RunPlan" xmlns="http://www.topografix.com/GPX/1/1">
<metadata><time>${iso.format(Instant.ofEpochMilli(r.startMs).atOffset(ZoneOffset.UTC))}</time></metadata>
<trk><name>${r.title.replace("&", "and").replace("<", "")}</name><type>running</type><trkseg>
""")
            points.forEach { p ->
                val t = Instant.ofEpochMilli(r.startMs + (p.tMs - t0))
                w.write("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\"><ele>${"%.1f".format(p.alt)}</ele><time>${iso.format(t)}</time></trkpt>\n")
            }
            w.write("</trkseg></trk>\n</gpx>\n")
        }
        share(ctx, f, "application/gpx+xml", "Export GPX")
    }
}
