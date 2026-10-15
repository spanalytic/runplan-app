package dev.runplan.app.data

import android.content.Context
import dev.runplan.core.Analytics
import dev.runplan.core.Distance
import dev.runplan.core.Effort
import dev.runplan.core.Fmt
import dev.runplan.core.Goal
import dev.runplan.core.Kept
import dev.runplan.core.Moves
import dev.runplan.core.Level
import dev.runplan.core.Plan
import dev.runplan.core.PlanBuilder
import dev.runplan.core.Profile
import dev.runplan.core.StepResult
import dev.runplan.core.Terrain
import dev.runplan.core.TrackPoint
import dev.runplan.core.Phase
import dev.runplan.core.Repeat
import dev.runplan.core.SessionType
import dev.runplan.core.Single
import dev.runplan.core.Step
import dev.runplan.core.StepKind
import dev.runplan.core.Workout
import dev.runplan.core.Zone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

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
    val startClockMs: Long = 0,                 // elapsedRealtime at start, same clock as track points
    val stepStartsMs: List<Long> = emptyList(), // elapsedRealtime at the start of each step
    val sentToStrava: Boolean = false,
    val efforts: List<Effort>? = null,          // null until computed from the track
    val ascentM: Double = Double.NaN,
) {
    val pace: Double get() = if (metres > 50) sec / (metres / 1000) else Double.NaN
}

data class AppState(
    val profile: Profile? = null,
    val done: Set<String> = emptySet(),
    val settings: Settings = Settings(),
    val runs: List<RunRecord> = emptyList(),
    /** Bumped when the derived numbers (best efforts, climb) are calculated differently. */
    val analyticsVersion: Int = 0,
    /** Sessions moved to another day of their week: workout id -> day. */
    val moves: Map<String, Long> = emptyMap(),
    /** Done sessions as they were when done (workout id -> workout), so editing the plan doesn't change them. */
    val kept: Map<String, Workout> = emptyMap(),
)

/**
 * Single JSON file of app state plus one CSV per GPS track.
 * The plan itself is never stored: it is rebuilt from the profile, with any moved sessions laid on top.
 */
class Store(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "state.json")
    private val tracks = File(dir, "tracks").apply { mkdirs() }
    /** Copy of state.json as it was when the app last started and read it cleanly. */
    private val backup = File(dir, "state.json.bak")
    /** Progress of the run being recorded, saved every few seconds; gone once the run is saved or discarded. */
    private val checkpoint = File(dir, "run-in-progress.json")

    /** Short messages for the user about what happened while loading (restored data, recovered run). */
    val notices = ArrayList<String>()
    /** Set if state.json couldn't be read or copied aside: then it is never written over. */
    private var keepOffStateFile = false

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var basePlan: Plan? = buildPlan(_state.value.profile)
    private val _plan = MutableStateFlow(shownPlan(_state.value))
    val plan: StateFlow<Plan?> = _plan.asStateFlow()

    init {
        recoverKept()
        recoverInterruptedRun()
    }

    private fun buildPlan(p: Profile?): Plan? = p?.let { runCatching { PlanBuilder.build(it) }.getOrNull() }

    /** The plan as shown: built from the profile, done sessions kept as they were, then moves. */
    private fun shownPlan(s: AppState): Plan? = basePlan?.let { Moves.apply(Kept.apply(it, s.kept.values), s.moves) }

    @Synchronized
    fun update(f: (AppState) -> AppState) {
        val old = _state.value
        val new = keepDone(f(old))
        _state.value = new
        if (new.profile != old.profile) basePlan = buildPlan(new.profile)
        if (new.profile != old.profile || new.moves != old.moves || new.kept != old.kept) _plan.value = shownPlan(new)
        save(new)
    }

    /**
     * Takes a copy of each newly done session from the plan as shown before this change, and lets go
     * of sessions no longer done, so only sessions not yet done are rebuilt when the plan is edited.
     */
    private fun keepDone(s: AppState): AppState {
        val shown = _plan.value
        val add = (s.done - s.kept.keys).mapNotNull { id -> shown?.find(id)?.let { id to it } }
        val kept = (s.kept + add).filterKeys { it in s.done }
        return if (kept == s.kept) s else s.copy(kept = kept)
    }

    /**
     * Saves from before done sessions were kept: the plan may already have been rebuilt under them
     * (for example after adding hills), so work out which plan they were done from.
     */
    private fun recoverKept() {
        val st = _state.value
        val profile = st.profile ?: return
        val missing = st.done - st.kept.keys
        if (missing.isEmpty()) return
        val runs = st.runs.filter { it.workoutId != null }.groupBy { it.workoutId!! }
            .mapValues { (_, rs) -> rs.maxBy { it.startMs }.let { it.title to it.metres } }
        val found = runCatching { Kept.recover(profile, st.moves, missing, runs) }.getOrDefault(emptyMap())
        if (found.isNotEmpty()) update { it.copy(kept = it.kept + found) }
    }

    fun setProfile(p: Profile?) = update { it.copy(profile = p) }
    fun setDone(id: String, done: Boolean) = update { it.copy(done = if (done) it.done + id else it.done - id) }
    /** Puts [w] on [day] (same week); a session already there swaps onto [w]'s day. */
    fun moveSession(w: Workout, day: Long) = update { st ->
        val shown = _plan.value ?: return@update st
        st.copy(moves = Moves.move(shown, st.moves, w, day))
    }
    fun setSettings(s: Settings) = update { it.copy(settings = s) }
    /** Derived numbers for a track: best efforts and climb. */
    fun analyse(points: List<TrackPoint>): Pair<List<Effort>, Double> =
        Analytics.bestEfforts(points) to Analytics.ascent(points)

    /**
     * Fills in best efforts / climb for runs saved before analytics existed, and recalculates
     * every run's climb once after the method changes. Call off the main thread.
     */
    fun backfillAnalytics() {
        val redo = _state.value.analyticsVersion < ANALYTICS_VERSION
        val missing = _state.value.runs.filter { it.efforts == null || redo }
        if (missing.isEmpty()) return
        val done = missing.associate { r ->
            val pts = readTrack(r.id)
            r.id to ((r.efforts ?: Analytics.bestEfforts(pts)) to if (pts.isEmpty()) r.ascentM else Analytics.ascent(pts))
        }
        update { st ->
            st.copy(
                runs = st.runs.map { r -> done[r.id]?.let { (e, a) -> r.copy(efforts = e, ascentM = a) } ?: r },
                analyticsVersion = ANALYTICS_VERSION,
            )
        }
    }

    fun markSentToStrava(runId: String) = update { st ->
        st.copy(runs = st.runs.map { if (it.id == runId) it.copy(sentToStrava = true) else it })
    }

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

    fun resetPlan() = update { it.copy(profile = null, done = emptySet(), moves = emptyMap(), kept = emptyMap()) }

    // ---- run in progress ------------------------------------------------------------------

    /**
     * Saves the progress of the run being recorded, so a crash or the phone killing the app
     * loses at most a few seconds. The track goes where the finished run's track will go.
     */
    @Synchronized
    fun saveCheckpoint(r: RunRecord, points: List<TrackPoint>) {
        writeTrack(r.id, points)
        writeAtomically(checkpoint, runToJson(r).toString())
    }

    /** The run was saved or discarded: drop its progress (and, if discarded, its track). */
    @Synchronized
    fun clearCheckpoint(discardTrackOf: String? = null) {
        discardTrackOf?.let { File(tracks, "$it.csv").delete() }
        checkpoint.delete()
    }

    /**
     * A progress file left behind means the app stopped mid-run (crash, or the phone closed it).
     * Called at start-up, before any run can be recording: keeps the run up to the last save.
     */
    private fun recoverInterruptedRun() {
        if (!checkpoint.exists()) return
        val r = runCatching { runFromJson(JSONObject(checkpoint.readText())) }.getOrNull()
        when {
            r == null -> runCatching { checkpoint.copyTo(File(dir, "run-in-progress.damaged-${System.currentTimeMillis()}.json")) }
            _state.value.runs.any { it.id == r.id } -> Unit   // it was saved after all
            r.sec < 1 -> File(tracks, "${r.id}.csv").delete()
            else -> runCatching {
                val pts = readTrack(r.id)
                val (efforts, ascent) = analyse(pts)
                addRun(r.copy(efforts = efforts, ascentM = ascent), pts)
                notices += "Your last run was interrupted. It has been saved up to ${Fmt.km(r.metres, 1)} km."
            }
        }
        checkpoint.delete()
    }

    // ---- tracks ---------------------------------------------------------------------------

    private fun writeTrack(id: String, points: List<TrackPoint>) {
        val f = File(tracks, "$id.csv")
        val tmp = File(tracks, "$id.csv.tmp")
        tmp.bufferedWriter().use { w ->
            w.write("t_ms,lat,lon,alt,acc,speed,speed_acc,dist,f_lat,f_lon,f_speed,baro_alt\n")
            points.forEach {
                w.write("${it.tMs},${it.lat},${it.lon},${it.alt},${it.acc},${it.speed},${it.speedAcc},${it.dist},${it.fLat},${it.fLon},${it.fSpeed},${it.baroAlt}\n")
            }
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    fun trackFile(id: String): File = File(tracks, "$id.csv")

    fun readTrack(id: String): List<TrackPoint> {
        val f = File(tracks, "$id.csv")
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { line ->
            val c = line.split(',')
            runCatching {
                TrackPoint(
                    c[0].toLong(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toFloat(),
                    c.getOrNull(5)?.toFloatOrNull() ?: Float.NaN, c.getOrNull(6)?.toFloatOrNull() ?: Float.NaN,
                    c.getOrNull(7)?.toDoubleOrNull() ?: Double.NaN, c.getOrNull(8)?.toDoubleOrNull() ?: Double.NaN,
                    c.getOrNull(9)?.toDoubleOrNull() ?: Double.NaN, c.getOrNull(10)?.toDoubleOrNull() ?: Double.NaN,
                    c.getOrNull(11)?.toDoubleOrNull() ?: Double.NaN,
                )
            }.getOrNull()
        }
    }

    // ---- JSON -----------------------------------------------------------------------------

    private fun save(s: AppState) {
        if (!keepOffStateFile) writeAtomically(file, toJson(s).toString())
    }

    /** Write to a temporary file, flush it to disk, then swap it in, so a crash never leaves half a file. */
    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        tmp.renameTo(f)
    }

    /**
     * Reads state.json. If it can't be read, it is never overwritten or silently replaced by an
     * empty state: it is kept aside as state.json.damaged-<time> and the backup taken at the last
     * clean start is used instead. Only if that fails too does the app start empty.
     */
    private fun load(): AppState {
        if (!file.exists()) {
            // a missing file with a backup present means something went wrong, not a fresh install
            return if (backup.exists()) restoreBackup("Your saved data was missing.") else AppState()
        }
        val ok = runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
        if (ok != null) {
            runCatching { file.copyTo(backup, overwrite = true) }
            return ok
        }
        val kept = runCatching { file.copyTo(File(dir, "state.json.damaged-${System.currentTimeMillis()}")) }.isSuccess
        if (!kept) keepOffStateFile = true
        return restoreBackup("Your saved data couldn't be read.")
    }

    private fun restoreBackup(problem: String): AppState {
        val restored = runCatching { fromJson(JSONObject(backup.readText())) }.getOrNull()
        if (restored != null) {
            notices += "$problem It has been restored from a backup, so recent changes may be missing."
            runCatching { save(restored) }
            return restored
        }
        notices += if (keepOffStateFile) "$problem It has been left as it is, so changes made now won't be saved."
            else "$problem A copy has been kept, but the app is starting fresh."
        return AppState()
    }

    companion object {
        /** 1: climb from distance-smoothed altitude, barometer when recorded (was GPS, 15 s smoothing, 2 m step). */
        const val ANALYTICS_VERSION = 1

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
                    put("terrain", p.terrain.name)
                    put("hills", p.hills)
                    put("level", p.level.name)
                })
            }
            put("done", JSONArray(s.done.toList()))
            put("analyticsVersion", s.analyticsVersion)
            put("moves", JSONObject().apply { s.moves.forEach { (id, day) -> put(id, day) } })
            put("kept", JSONObject().apply { s.kept.forEach { (id, w) -> put(id, workoutToJson(w)) } })
            put("settings", JSONObject().apply {
                put("voice", s.settings.voice)
                put("paceAlerts", s.settings.paceAlerts)
                put("splits", s.settings.splits)
            })
            put("runs", JSONArray().apply {
                s.runs.forEach { put(runToJson(it)) }
            })
        }

        fun runToJson(r: RunRecord): JSONObject = JSONObject().apply {
            put("id", r.id)
            put("epochDay", r.epochDay)
            put("workoutId", r.workoutId ?: JSONObject.NULL)
            put("title", r.title)
            put("startMs", r.startMs)
            put("sec", r.sec)
            put("metres", r.metres)
            put("splits", JSONArray().apply { r.splits.forEach { put(num(it)) } })
            put("startClockMs", r.startClockMs)
            put("stepStartsMs", JSONArray(r.stepStartsMs))
            put("sentToStrava", r.sentToStrava)
            put("ascentM", num(r.ascentM))
            r.efforts?.let { es ->
                put("efforts", JSONArray().apply {
                    es.forEach { e -> put(JSONObject().put("label", e.label).put("metres", e.metres).put("sec", e.sec)) }
                })
            }
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
                    terrain = runCatching { Terrain.valueOf(p.optString("terrain", "FLAT")) }.getOrDefault(Terrain.FLAT),
                    hills = p.optBoolean("hills", false),
                    level = runCatching { Level.valueOf(p.optString("level", "INTERMEDIATE")) }.getOrDefault(Level.INTERMEDIATE),
                )
            }
            val done = o.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            val settings = o.optJSONObject("settings")?.let {
                Settings(it.optBoolean("voice", true), it.optBoolean("paceAlerts", true), it.optBoolean("splits", true))
            } ?: Settings()
            val runs = o.optJSONArray("runs")?.let { a ->
                (0 until a.length()).map { i -> runFromJson(a.getJSONObject(i)) }
            } ?: emptyList()
            val moves = o.optJSONObject("moves")?.let { m -> m.keys().asSequence().associateWith { m.getLong(it) } } ?: emptyMap()
            // a kept workout that can't be read is dropped: it is taken again from the plan
            val kept = o.optJSONObject("kept")?.let { k ->
                k.keys().asSequence().mapNotNull { id -> runCatching { id to workoutFromJson(k.getJSONObject(id)) }.getOrNull() }.toMap()
            } ?: emptyMap()
            return AppState(profile, done, settings, runs, o.optInt("analyticsVersion", 0), moves, kept)
        }

        fun workoutToJson(w: Workout): JSONObject = JSONObject().apply {
            put("epochDay", w.epochDay)
            put("planDay", w.planDay)
            put("week", w.week)
            put("phase", w.phase.name)
            put("type", w.type.name)
            put("note", w.note)
            fun step(s: Step) = JSONObject().apply {
                put("kind", s.kind.name)
                put("secs", s.secs ?: JSONObject.NULL)
                put("metres", s.metres ?: JSONObject.NULL)
                put("zone", s.zone?.name ?: JSONObject.NULL)
                put("hill", s.hill)
                put("walk", s.walk)
            }
            put("blocks", JSONArray().apply {
                w.blocks.forEach { b ->
                    put(when (b) {
                        is Single -> JSONObject().put("step", step(b.step))
                        is Repeat -> JSONObject().put("times", b.times).put("steps", JSONArray().apply { b.steps.forEach { put(step(it)) } })
                    })
                }
            })
        }

        fun workoutFromJson(o: JSONObject): Workout {
            fun step(s: JSONObject) = Step(
                kind = StepKind.valueOf(s.getString("kind")),
                secs = if (s.isNull("secs")) null else s.getDouble("secs"),
                metres = if (s.isNull("metres")) null else s.getDouble("metres"),
                zone = if (s.isNull("zone")) null else Zone.valueOf(s.getString("zone")),
                hill = s.optBoolean("hill", false),
                walk = s.optBoolean("walk", false),
            )
            val blocks = o.getJSONArray("blocks").let { a ->
                (0 until a.length()).map { i ->
                    val b = a.getJSONObject(i)
                    if (b.has("step")) Single(step(b.getJSONObject("step")))
                    else Repeat(b.getInt("times"), b.getJSONArray("steps").let { sa -> (0 until sa.length()).map { step(sa.getJSONObject(it)) } })
                }
            }
            return Workout(
                epochDay = o.getLong("epochDay"),
                week = o.getInt("week"),
                phase = Phase.valueOf(o.getString("phase")),
                type = SessionType.valueOf(o.getString("type")),
                blocks = blocks,
                note = o.optString("note", ""),
                planDay = o.optLong("planDay", o.getLong("epochDay")),
            )
        }

        fun runFromJson(r: JSONObject): RunRecord {
            val steps = r.getJSONArray("steps").let { sa ->
                (0 until sa.length()).map { j ->
                    val s = sa.getJSONObject(j)
                    StepResult(s.getString("label"), s.getString("zone"), s.dbl("fast"), s.dbl("slow"), s.getDouble("sec"), s.getDouble("metres"))
                }
            }
            val splits = r.getJSONArray("splits").let { sa -> (0 until sa.length()).map { j -> if (sa.isNull(j)) Double.NaN else sa.getDouble(j) } }
            return RunRecord(
                id = r.getString("id"),
                epochDay = r.getLong("epochDay"),
                workoutId = if (r.isNull("workoutId")) null else r.getString("workoutId"),
                title = r.getString("title"),
                startMs = r.getLong("startMs"),
                sec = r.getDouble("sec"),
                metres = r.getDouble("metres"),
                steps = steps,
                splits = splits,
                startClockMs = r.optLong("startClockMs", 0),
                stepStartsMs = r.optJSONArray("stepStartsMs")?.let { sa -> (0 until sa.length()).map { j -> sa.getLong(j) } } ?: emptyList(),
                sentToStrava = r.optBoolean("sentToStrava") || !r.isNull("stravaId"),
                ascentM = if (r.isNull("ascentM")) Double.NaN else r.getDouble("ascentM"),
                efforts = r.optJSONArray("efforts")?.let { ea ->
                    (0 until ea.length()).map { k ->
                        val e = ea.getJSONObject(k)
                        Effort(e.getString("label"), e.getDouble("metres"), e.getDouble("sec"))
                    }
                },
            )
        }
    }
}
