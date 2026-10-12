package dev.runplan.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Crash capture and one-tap bug reports.
 * The last crash is written to a file before the app dies; "Report a problem" opens a new
 * GitHub issue pre-filled with the version, device and that crash.
 */
object BugReport {
    private const val NEW_ISSUE = "https://github.com/spanalytic/runplan-app/issues/new"
    private const val MAX_TRACE = 5000          // keep the issue URL well under browser limits
    private const val RECENT_MS = 7L * 24 * 3600 * 1000
    private val stamp = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.systemDefault())

    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    /** Call once from Application.onCreate. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                file(app).writeText("${System.currentTimeMillis()}\n${thread.name}\n$trace")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    data class Crash(val atMs: Long, val thread: String, val trace: String) {
        val whenText: String get() = stamp.format(Instant.ofEpochMilli(atMs))
    }

    /** The last crash if it happened in the past week. */
    fun lastCrash(ctx: Context): Crash? = runCatching {
        val lines = file(ctx).takeIf { it.exists() }?.readLines() ?: return null
        val at = lines[0].toLong()
        if (System.currentTimeMillis() - at > RECENT_MS) return null
        Crash(at, lines[1], lines.drop(2).joinToString("\n"))
    }.getOrNull()

    fun clearCrash(ctx: Context) { file(ctx).delete() }

    /** Opens GitHub's new-issue page with everything filled in except the description. */
    fun open(ctx: Context, version: String) {
        val crash = lastCrash(ctx)
        val body = buildString {
            append("**What happened**\n\n\n\n")
            append("**What I expected**\n\n\n\n")
            append("**Steps to reproduce** (if you know them)\n\n1. \n\n")
            append("---\n")
            append("- App version: $version\n")
            append("- Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            if (crash != null) {
                append("\n**Last crash** (${crash.whenText}, thread ${crash.thread})\n\n```\n")
                append(crash.trace.take(MAX_TRACE))
                if (crash.trace.length > MAX_TRACE) append("\n… (truncated)")
                append("\n```\n")
            }
        }
        val title = if (crash != null) "Crash: ${crash.trace.lineSequence().firstOrNull()?.take(80) ?: ""}" else ""
        val uri = Uri.parse(NEW_ISSUE).buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", body)
            .appendQueryParameter("labels", "bug")
            .build()
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
