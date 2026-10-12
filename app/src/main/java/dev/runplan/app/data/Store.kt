package dev.runplan.app.data

import android.content.Context
import dev.runplan.core.Distance
import dev.runplan.core.Goal
import dev.runplan.core.Plan
import dev.runplan.core.PlanBuilder
import dev.runplan.core.Profile
import dev.runplan.core.StepResult
import dev.runplan.core.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Settings(
    val voice: Boolean = true,
    val paceAlerts: Boolean = true,
    val splits: Boolean = true,
)

data class RunRecord(
    val id: String,
    val epochDay: Long,
    val workoutId: String?,
    val title: String,
    val startMs: Long,
    val sec: Double,
    val metres: Double,
    val steps: List<StepResult>,
    val splits: List<Double>,
) {
    val pace: Double get() = if (metres > 50) sec / (metres / 1000) else Double.NaN
}

data class AppState(
    val profile: Profile? = null,
    val done: Set<String> = emptySet(),
    val settings: Settings = Settings(),
    val runs: List<RunRecord> = emptyList(),
)

/**
 * Single JSON file of app state plus one CSV per GPS track.
 * The plan itself is never stored: it is rebuilt from the profile.
 */
class Store(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "state.json")
    private val tracks = File(dir, "tracks").apply { mkdirs() }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _plan = MutableStateFlow(buildPlan(_state.value.profile))
    val plan: StateFlow<Plan?> = _plan.asStateFlow()

    private fun buildPlan(p: Profile?): Plan? = p?.let { runCatching { PlanBuilder.build(it) }.getOrNull() }

    @Synchronized
    fun update(f: (AppState) -> AppState) {
        val old = _state.value
        val new = f(old)
        _state.value = new
        if (new.profile != old.profile) _plan.value = buildPlan(new.profile)
        save(new)
    }

    fun setProfile(p: Profile?) = update { it.copy(profile = p) }
    fun setDone(id: String, done: Boolean) = update { it.copy(done = if (done) it.done + id else it.done - id) }
    fun setSettings(s: Settings) = update { it.copy(settings = s) }

    fun addRun(r: RunRecord, points: List<TrackPoint>) {
        writeTrack(r.id, points)
        update { st ->
            st.copy(
                runs = listOf(r) + st.runs.filter { it.id != r.id },
                done = r.workoutId?.let { st.done + it } ?: st.done,
            )
        }
    }

    fun deleteRun(id: String) {
        File(tracks, "$id.csv").delete()
        update { st ->
            val wid: String? = st.runs.firstOrNull { it.id == id }?.workoutId
            val stillDone = wid != null && st.runs.any { it.id != id && it.workoutId == wid }
            st.copy(
                runs = st.runs.filter { it.id != id },
                done = if (wid != null && !stillDone) st.done - wid else st.done,
            )
        }
    }

    fun resetPlan() = update { it.copy(profile = null, done = emptySet()) }

    // ---- tracks ---------------------------------------------------------------------------

    private fun writeTrack(id: String, points: List<TrackPoint>) {
        File(tracks, "$id.csv").bufferedWriter().use { w ->
            w.write("t_ms,lat,lon,alt,acc\n")
            points.forEach { w.write("${it.tMs},${it.lat},${it.lon},${it.alt},${it.acc}\n") }
        }
    }

    fun readTrack(id: String): List<TrackPoint> {
        val f = File(tracks, "$id.csv")
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { line ->
            val c = line.split(',')
            runCatching { TrackPoint(c[0].toLong(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toFloat()) }.getOrNull()
        }
    }

    // ---- JSON -----------------------------------------------------------------------------

    private fun save(s: AppState) {
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(toJson(s).toString())
        tmp.renameTo(file)
    }

    private fun load(): AppState = runCatching {
        if (!file.exists()) AppState() else fromJson(JSONObject(file.readText()))
    }.getOrElse { AppState() }

    companion object {
        /** org.json rejects NaN, so non-finite numbers are stored as null. */
        private fun num(d: Double): Any = if (d.isFinite()) d else JSONObject.NULL
        private fun JSONObject.dbl(k: String): Double = if (isNull(k)) Double.NaN else getDouble(k)

        fun toJson(s: AppState): JSONObject = JSONObject().apply {
            s.profile?.let { p ->
                put("profile", JSONObject().apply {
                    put("recentDistance", p.recentDistance.name)
                    put("recentSec", p.recentSec)
                    put("goal", p.goal.name)
                    put("startEpochDay", p.startEpochDay)
                    put("raceEpochDay", p.raceEpochDay)
                    put("days", JSONArray(p.days))
                    put("currentKm", p.currentKm)
                    put("peakKm", p.peakKm ?: JSONObject.NULL)
                })
            }
            put("done", JSONArray(s.done.toList()))
            put("settings", JSONObject().apply {
                put("voice", s.settings.voice)
                put("paceAlerts", s.settings.paceAlerts)
                put("splits", s.settings.splits)
            })
            put("runs", JSONArray().apply {
                s.runs.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("epochDay", r.epochDay)
                        put("workoutId", r.workoutId ?: JSONObject.NULL)
                        put("title", r.title)
                        put("startMs", r.startMs)
                        put("sec", r.sec)
                        put("metres", r.metres)
                        put("splits", JSONArray().apply { r.splits.forEach { put(num(it)) } })
                        put("steps", JSONArray().apply {
                            r.steps.forEach { st ->
                                put(JSONObject().apply {
                                    put("label", st.label)
                                    put("zone", st.zoneCode)
                                    put("fast", num(st.fast))
                                    put("slow", num(st.slow))
                                    put("sec", st.sec)
                                    put("metres", st.metres)
                                })
                            }
                        })
                    })
                }
            })
        }

        fun fromJson(o: JSONObject): AppState {
            val profile = o.optJSONObject("profile")?.let { p ->
                val days = p.getJSONArray("days").let { a -> (0 until a.length()).map { a.getInt(it) } }
                Profile(
                    recentDistance = Distance.valueOf(p.getString("recentDistance")),
                    recentSec = p.getDouble("recentSec"),
                    goal = Goal.valueOf(p.getString("goal")),
                    startEpochDay = p.getLong("startEpochDay"),
                    raceEpochDay = p.getLong("raceEpochDay"),
                    days = days,
                    currentKm = p.getDouble("currentKm"),
                    peakKm = if (p.isNull("peakKm")) null else p.getDouble("peakKm"),
                )
            }
            val done = o.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            val settings = o.optJSONObject("settings")?.let {
                Settings(it.optBoolean("voice", true), it.optBoolean("paceAlerts", true), it.optBoolean("splits", true))
            } ?: Settings()
            val runs = o.optJSONArray("runs")?.let { a ->
                (0 until a.length()).map { i ->
                    val r = a.getJSONObject(i)
                    val steps = r.getJSONArray("steps").let { sa ->
                        (0 until sa.length()).map { j ->
                            val s = sa.getJSONObject(j)
                            StepResult(s.getString("label"), s.getString("zone"), s.dbl("fast"), s.dbl("slow"), s.getDouble("sec"), s.getDouble("metres"))
                        }
                    }
                    val splits = r.getJSONArray("splits").let { sa -> (0 until sa.length()).map { j -> if (sa.isNull(j)) Double.NaN else sa.getDouble(j) } }
                    RunRecord(
                        id = r.getString("id"),
                        epochDay = r.getLong("epochDay"),
                        workoutId = if (r.isNull("workoutId")) null else r.getString("workoutId"),
                        title = r.getString("title"),
                        startMs = r.getLong("startMs"),
                        sec = r.getDouble("sec"),
                        metres = r.getDouble("metres"),
                        steps = steps,
                        splits = splits,
                    )
                }
            } ?: emptyList()
            return AppState(profile, done, settings, runs)
        }
    }
}
