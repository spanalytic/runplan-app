package dev.runplan.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the GitHub "latest" release: CI publishes version.json next to the APKs,
 * with versionCode = the Actions run number.
 */
object Updater {
    private const val BASE = "https://github.com/spanalytic/runplan-app/releases/download/latest/"
    private const val APK = "runplan-phone.apk"

    data class Version(val code: Long, val name: String)

    fun installed(ctx: Context): Version {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return Version(PackageInfoCompat.getLongVersionCode(info), info.versionName ?: "")
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** Latest published version. */
    suspend fun latest(): Version = withContext(Dispatchers.IO) {
        val c = open("${BASE}version.json")
        if (c.responseCode != 200) throw IllegalStateException("Couldn't reach GitHub (HTTP ${c.responseCode}).")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        Version(j.getLong("versionCode"), j.optString("versionName"))
    }

    /** Downloads the phone APK, reporting progress 0..1. */
    suspend fun download(ctx: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, APK)
        val c = open(BASE + APK)
        if (c.responseCode != 200) throw IllegalStateException("Download failed (HTTP ${c.responseCode}).")
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    read += n
                    if (total > 0) onProgress(read.toFloat() / total)
                }
            }
        }
        out
    }

    /** True if Android will let this app open the installer (one-off "Install unknown apps" switch). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows RunPlan to install updates. */
    fun requestInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Hands the APK to Android's installer, which shows its own "Update" prompt. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
