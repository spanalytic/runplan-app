package dev.runplan.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.runplan.app.RunPlanApp
import dev.runplan.core.ActivityFiles
import dev.runplan.core.Fmt
import dev.runplan.core.Lap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class StravaException(message: String) : Exception(message)

/**
 * Strava upload using the user's own API application (free at strava.com/settings/api).
 * OAuth: browser/Strava app -> runplan://localhost/strava?code=... -> token exchange.
 * Uploads a TCX with one lap per workout step, then waits for Strava to process it.
 */
object Strava {
    const val REDIRECT = "runplan://localhost/strava"
    private const val API = "https://www.strava.com/api/v3"
    private val store get() = RunPlanApp.store

    fun authorize(context: Context) {
        val auth = store.state.value.strava
        val uri = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
            .appendQueryParameter("client_id", auth.clientId.trim())
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("approval_prompt", "auto")
            .appendQueryParameter("scope", "activity:write,read")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Handles the OAuth redirect. Returns null on success, or an error message. */
    suspend fun handleRedirect(uri: Uri): String? {
        uri.getQueryParameter("error")?.let { return "Strava said: $it" }
        val code = uri.getQueryParameter("code") ?: return "No authorisation code in the Strava redirect."
        val scope = uri.getQueryParameter("scope") ?: ""
        if (!scope.contains("activity:write")) return "Allow \"Upload your activities\" when connecting, or RunPlan can't upload."
        return runCatching {
            val auth = store.state.value.strava
            val j = tokenRequest(mapOf("client_id" to auth.clientId.trim(), "client_secret" to auth.clientSecret.trim(), "code" to code, "grant_type" to "authorization_code"))
            saveTokens(j)
            null
        }.getOrElse { it.message ?: "Couldn't connect to Strava." }
    }

    fun disconnect() = store.setStrava { it.copy(accessToken = "", refreshToken = "", expiresAt = 0, athlete = "") }

    private fun saveTokens(j: JSONObject) {
        val athlete = j.optJSONObject("athlete")?.let { "${it.optString("firstname")} ${it.optString("lastname")}".trim() }
        store.setStrava {
            it.copy(
                accessToken = j.getString("access_token"),
                refreshToken = j.getString("refresh_token"),
                expiresAt = j.getLong("expires_at"),
                athlete = athlete ?: it.athlete,
            )
        }
    }

    private suspend fun token(): String {
        val a = store.state.value.strava
        if (!a.connected) throw StravaException("Connect Strava in Settings first.")
        if (a.expiresAt - System.currentTimeMillis() / 1000 > 300) return a.accessToken
        val j = tokenRequest(mapOf("client_id" to a.clientId.trim(), "client_secret" to a.clientSecret.trim(), "refresh_token" to a.refreshToken, "grant_type" to "refresh_token"))
        saveTokens(j)
        return j.getString("access_token")
    }

    private suspend fun tokenRequest(form: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("https://www.strava.com/oauth/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.toByteArray()) }
        read(c)
    }

    private fun read(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code == 401) throw StravaException("Strava rejected the login. Disconnect and connect again in Settings.")
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
            throw StravaException("Strava error $code${if (msg.isNotEmpty()) ": $msg" else ""}")
        }
        return JSONObject(body)
    }

    /** Uploads [r] and returns the Strava activity id. */
    suspend fun upload(r: RunRecord): Long {
        val points = withContext(Dispatchers.IO) { store.readTrack(r.id) }
        if (points.size < 2) throw StravaException("This run has no GPS track to upload.")
        val laps = r.steps.mapIndexedNotNull { i, s ->
            val start = r.stepStartsMs.getOrNull(i) ?: return@mapIndexedNotNull null
            Lap(start, s.sec, s.metres, s.label.startsWith("Recovery"))
        }
        val clock0 = if (r.startClockMs > 0) r.startClockMs else points.first().tMs
        val tcx = ActivityFiles.tcx(r.startMs, clock0, points, laps, description(r))
        val bearer = token()

        val id = withContext(Dispatchers.IO) {
            val boundary = "runplan${System.nanoTime()}"
            val c = URL("$API/uploads").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $bearer")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val out = ByteArrayOutputStream()
            fun field(name: String, value: String) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("data_type", "tcx")
            field("name", r.title)
            field("description", description(r))
            field("external_id", "runplan-${r.id}.tcx")
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"runplan-${r.id}.tcx\"\r\nContent-Type: application/xml\r\n\r\n".toByteArray())
            out.write(tcx.toByteArray())
            out.write("\r\n--$boundary--\r\n".toByteArray())
            c.outputStream.use { it.write(out.toByteArray()) }
            read(c).getLong("id")
        }

        // Strava processes uploads asynchronously; poll until it has an activity id
        repeat(20) {
            delay(2000)
            val j = withContext(Dispatchers.IO) {
                val c = URL("$API/uploads/$id").openConnection() as HttpURLConnection
                c.setRequestProperty("Authorization", "Bearer $bearer")
                read(c)
            }
            val err = j.optString("error").takeIf { it.isNotEmpty() && it != "null" }
            if (err != null) {
                // "... duplicate of <a href='/activities/123'>..." - treat as already uploaded
                Regex("activities/(\\d+)").find(err)?.let { m -> return m.groupValues[1].toLong().also { store.setStravaId(r.id, it) } }
                throw StravaException(err.replace(Regex("<[^>]+>"), ""))
            }
            if (!j.isNull("activity_id")) return j.getLong("activity_id").also { store.setStravaId(r.id, it) }
        }
        throw StravaException("Strava is still processing the upload. Check Strava in a minute.")
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

    fun open(context: Context, activityId: Long) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/$activityId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
