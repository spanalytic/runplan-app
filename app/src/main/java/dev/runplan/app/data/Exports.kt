package dev.runplan.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.runplan.core.ActivityFiles
import dev.runplan.core.Analytics
import dev.runplan.core.Plan
import dev.runplan.core.RunStat
import dev.runplan.core.Fmt
import dev.runplan.core.Lap
import dev.runplan.core.TrackPoint
import java.io.File
import java.time.LocalDate

/** CSV and GPX exports, shared through the system share sheet. */
object Exports {
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

    /** One row per week from the first run to this week, with planned km where a plan covers it. */
    fun weeklyCsv(ctx: Context, runs: List<RunRecord>, plan: Plan?) {
        if (runs.isEmpty()) return
        val today = LocalDate.now().toEpochDay()
        val weeks = ((Analytics.mondayOf(today) - Analytics.mondayOf(runs.minOf { it.epochDay })) / 7 + 1).toInt()
        val periods = Analytics.weekly(runs.map { RunStat(it.epochDay, it.metres, it.sec) }, today, weeks)
        val f = File(dir(ctx), "runplan_weekly.csv")
        f.bufferedWriter().use { w ->
            w.write("week_start,runs,distance_km,duration_sec,avg_pace_sec_per_km,planned_km\n")
            periods.forEach { p ->
                val planned = plan?.let { pl ->
                    pl.workouts.filter { it.epochDay in p.startEpochDay until p.startEpochDay + 7 }
                        .takeIf { it.isNotEmpty() }?.sumOf { it.estimate(pl.paces).first }
                }
                val pace = if (p.metres > 0) p.sec / (p.metres / 1000) else Double.NaN
                w.write(listOf(
                    LocalDate.ofEpochDay(p.startEpochDay).toString(), p.runs.toString(), n(p.metres / 1000, 2),
                    n(p.sec, 0), n(pace, 0), planned?.let { n(it, 1) } ?: "",
                ).joinToString(",") + "\n")
            }
        }
        share(ctx, f, "text/csv", "Export weekly distance")
    }

    /** Every best effort in every run, with VDOT. */
    fun effortsCsv(ctx: Context, runs: List<RunRecord>) {
        val f = File(dir(ctx), "runplan_best_efforts.csv")
        f.bufferedWriter().use { w ->
            w.write("date,run_title,effort,distance_m,time_sec,pace_sec_per_km,vdot\n")
            runs.sortedBy { it.startMs }.forEach { r ->
                r.efforts.orEmpty().forEach { e ->
                    w.write(listOf(
                        LocalDate.ofEpochDay(r.epochDay).toString(), csvCell(r.title), e.label, n(e.metres, 1),
                        n(e.sec, 1), n(e.pace, 1), if (e.metres >= 1600) n(e.vdot, 1) else "",
                    ).joinToString(",") + "\n")
                }
            }
        }
        share(ctx, f, "text/csv", "Export best efforts")
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

    /** GPX track (filtered positions) for Strava, Garmin Connect etc. */
    fun gpx(ctx: Context, r: RunRecord, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        val f = File(dir(ctx), "runplan_${LocalDate.ofEpochDay(r.epochDay)}.gpx")
        f.writeText(ActivityFiles.gpx(r.title, r.startMs, if (r.startClockMs > 0) r.startClockMs else points.first().tMs, points))
        share(ctx, f, "application/gpx+xml", "Export GPX")
    }

    /** TCX with one lap per workout step. */
    fun tcx(ctx: Context, r: RunRecord, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        val laps = r.steps.mapIndexedNotNull { i, s -> r.stepStartsMs.getOrNull(i)?.let { Lap(it, s.sec, s.metres, s.label.startsWith("Recovery")) } }
        val f = File(dir(ctx), "runplan_${LocalDate.ofEpochDay(r.epochDay)}.tcx")
        f.writeText(ActivityFiles.tcx(r.startMs, if (r.startClockMs > 0) r.startClockMs else points.first().tMs, points, laps, Strava.description(r)))
        share(ctx, f, "application/vnd.garmin.tcx+xml", "Export TCX")
    }
}
