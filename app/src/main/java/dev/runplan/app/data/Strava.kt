package dev.runplan.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.runplan.app.RunPlanApp
import dev.runplan.core.ActivityFiles
import dev.runplan.core.Fmt
import dev.runplan.core.Lap
import dev.runplan.core.TrackPoint
import java.io.File
import java.time.LocalDate

/**
 * Free Strava route (no API app, no subscription): save the run as a TCX in Downloads,
 * with one lap per workout step, and open Strava's file uploader in the browser.
 */
object Strava {
    const val UPLOAD_URL = "https://www.strava.com/upload/select"
    private val store get() = RunPlanApp.store

    fun fileName(r: RunRecord) = "runplan_${LocalDate.ofEpochDay(r.epochDay)}_${r.id.takeLast(6)}.tcx"

    fun tcx(r: RunRecord, points: List<TrackPoint>): String {
        val laps = r.steps.mapIndexedNotNull { i, s ->
            r.stepStartsMs.getOrNull(i)?.let { Lap(it, s.sec, s.metres, s.label.startsWith("Recovery")) }
        }
        val clock0 = if (r.startClockMs > 0) r.startClockMs else points.first().tMs
        return ActivityFiles.tcx(r.startMs, clock0, points, laps, description(r))
    }

    /**
     * Saves the TCX to Downloads and opens the uploader. Returns the name of the saved file.
     * @throws IllegalStateException if the run has no track or the file can't be written
     */
    fun sendViaWeb(ctx: Context, r: RunRecord, points: List<TrackPoint>): String {
        check(points.size >= 2) { "This run has no GPS track to upload." }
        val name = fileName(r)
        val bytes = tcx(r, points).toByteArray()
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.garmin.tcx+xml")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't save to Downloads.")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Couldn't save to Downloads.")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            // Android 8-9: app-specific Downloads folder, visible in the file picker under the app's folder
            val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            File(dir, name).writeBytes(bytes)
        }
        store.markSentToStrava(r.id)
        openUploader(ctx)
        return name
    }

    /** Opens the uploader in a browser, not the Strava app (the app can't import files). */
    fun openUploader(ctx: Context) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(UPLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val browser = ctx.packageManager
            .resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" && it != "com.strava" }
        if (browser != null) view.setPackage(browser)
        runCatching { ctx.startActivity(view) }.onFailure { ctx.startActivity(view.setPackage(null)) }
    }

    fun description(r: RunRecord): String = buildString {
        val plan = store.plan.value
        val w = r.workoutId?.let { plan?.find(it) }
        if (w != null) append(Fmt.summary(w)).append('\n')
        val paced = r.steps.filter { it.fast.isFinite() && it.label.startsWith("Rep") }
        if (paced.isNotEmpty()) {
            append("Reps on target: ${paced.count { it.hit == true }} of ${paced.size}\n")
            paced.forEach { append("${it.label}: ${Fmt.pace(it.pace)}/km (target ${Fmt.band(it.fast, it.slow)})\n") }
        }
        append("Recorded with RunPlan")
    }
}
