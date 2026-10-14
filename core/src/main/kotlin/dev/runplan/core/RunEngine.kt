package dev.runplan.core

import kotlin.math.max
import kotlin.math.min

/** A concrete step during a run (repeats expanded, pace band resolved). Band is NaN when there is no target. */
data class RunStep(
    val label: String,
    val kind: StepKind,
    val secs: Double?,
    val metres: Double?,
    val zone: Zone?,
    val fast: Double,
    val slow: Double,
    /** Typical pace for this step (zone centre), for estimating duration. NaN when there is no zone. */
    val typical: Double = Double.NaN,
    /** Uphill by effort (or the way back down for a recovery). No pace target. */
    val hill: Boolean = false,
) {
    /** Stored/sent zone code: the zone, "H" for a hill effort, "" otherwise. */
    val zoneCode: String get() = zone?.code ?: if (hill && kind == StepKind.INTERVAL) HILL_CODE else ""
    /** Zone used to colour and size the step in a workout shape: hill efforts show as hard. */
    val shapeZone: Zone? get() = zone ?: if (hill && kind == StepKind.INTERVAL) Zone.I else null
    val hasTarget: Boolean get() = zone != null && fast.isFinite()
    /** Only a "no faster than" limit (easy running): there is no too-slow. */
    val capOnly: Boolean get() = hasTarget && !slow.isFinite()
    val isOpen: Boolean get() = secs == null && metres == null

    fun describe(): String {
        val amt = when {
            metres != null -> Fmt.distance(metres)
            secs != null -> if (secs < 120 && secs % 60 != 0.0) "${secs.toLong()} s" else Fmt.minutes(secs)
            else -> "Open"
        }
        return when {
            hasTarget -> "$amt @ ${Fmt.band(fast, slow)}"
            kind == StepKind.READY -> "$amt countdown"
            hill && isOpen -> if (label == TO_HILL) "tap Next when you're there" else "tap Next at the bottom"
            hill && kind == StepKind.INTERVAL -> "$amt hard uphill"
            hill && kind == StepKind.RECOVERY -> "$amt rest"
            kind == StepKind.RECOVERY -> "$amt jog"
            else -> amt
        }
    }

    companion object {
        const val HILL_CODE = "H"
        const val TO_HILL = "To the hill"

        fun flatten(w: Workout, p: Paces): List<RunStep> {
            val out = ArrayList<RunStep>()
            fun add(st: Step, label: String) {
                val (f, s) = st.zone?.let { p.target(it) } ?: (Double.NaN to Double.NaN)
                out += RunStep(label, st.kind, st.secs, st.metres, st.zone, f, s, st.zone?.let { p.centre(it) } ?: Double.NaN, st.hill)
            }
            fun baseLabel(st: Step) = when (st.kind) {
                StepKind.WARMUP -> "Warm-up"
                StepKind.COOLDOWN -> "Cool-down"
                StepKind.RECOVERY -> "Recovery"
                StepKind.INTERVAL -> if (w.type == SessionType.FREE) "Free run" else st.zone?.label ?: "Run"
                StepKind.READY -> "Get ready"
            }
            for (b in w.blocks) when (b) {
                is Single -> add(b.step, if (b.step.hill && b.step.isOpen) TO_HILL else baseLabel(b.step))
                is Repeat -> for (i in 1..b.times) for (st in b.steps) {
                    val lbl = when (st.kind) {
                        StepKind.INTERVAL -> when {
                            !st.hill -> "Rep $i of ${b.times}"
                            w.type == SessionType.HILL_SPRINTS -> "Hill sprint $i of ${b.times}"
                            else -> "Hill rep $i of ${b.times}"
                        }
                        StepKind.RECOVERY -> when {
                            !st.hill -> "Recovery $i of ${b.times}"
                            st.isOpen -> "Back down"
                            else -> "Rest"
                        }
                        else -> baseLabel(st)
                    }
                    add(st, lbl)
                }
            }
            return out
        }
    }
}

enum class PaceStatus { NONE, IN_BAND, SLOW, FAST }

/** Things the phone should say or the watch should buzz for. */
sealed interface Cue {
    /** A new step begins. [prev] is the step just finished (null at the start of the run). */
    data class StepStart(
        val step: RunStep, val index: Int, val count: Int, val next: RunStep?, val prev: StepResult?,
        /** The step before was a [StepKind.READY] countdown that already announced this one. */
        val afterCountdown: Boolean = false,
    ) : Cue
    /** "10 seconds" / "200 metres to go", announcing what comes next. */
    data class Countdown(val left: String, val next: RunStep?) : Cue
    data class Remaining(val text: String) : Cue
    /** Halfway through a step of 2 minutes or more, with the pace so far. */
    data class Halfway(val pace: Double, val step: RunStep) : Cue
    data class PaceAlert(val tooSlow: Boolean, val pace: Double, val step: RunStep) : Cue
    data class Split(val km: Int, val secPerKm: Double) : Cue
    /**
     * The run is over. [lastLap] is the final step's result, null when the workout was a single
     * step (the summary already says it). [onTarget] of [targeted] paced steps were on or ahead of target.
     */
    data class Finished(
        val distanceM: Double,
        val sec: Double,
        val lastLap: StepResult?,
        val onTarget: Int = 0,
        val targeted: Int = 0,
    ) : Cue

    /** Text for text-to-speech. Targets are spoken as the middle of the band. */
    fun speech(): String = when (this) {
        is StepStart -> when {
            step.kind == StepKind.READY ->
                "Get ready." + (next?.let { " ${it.label} in ${spokenDuration(step.secs ?: 10.0)}: ${spokenStep(it)}." } ?: "")
            afterCountdown -> "Go!"
            step.hill && step.isOpen && step.label == RunStep.TO_HILL ->
                "Jog to the bottom of your hill and tap Next when you're there." + (prev?.let { " " + spokenResult(it) } ?: "")
            step.hill && step.isOpen ->
                if (next?.kind == StepKind.READY) "Jog back down. Tap Next when you're at the bottom."
                else "That was the last one. Jog back down and tap Next for the cool-down."
            step.hill && step.kind == StepKind.RECOVERY -> "Rest." + (prev?.let { " " + spokenResult(it) } ?: "")
            else -> buildString {
                append(if (index == 0) "Let's go." else opener(step)).append(' ')
                append(step.label).append(": ").append(spokenStep(step)).append('.')
                prev?.let { append(' ').append(spokenResult(it)) }
            }
        }
        // the 10 s rest at the top of a hill rep needs no announcing
        is Countdown -> left + if (next != null && !(next.hill && next.kind == StepKind.RECOVERY && !next.isOpen)) " Next: ${next.label}, ${spokenStep(next)}." else ""
        is Remaining -> text
        is Halfway -> "Halfway. Pace so far ${spokenPace(pace)} per kilometre" +
            (verdict(pace, step.fast, step.slow)?.let { ", $it" } ?: "") + "."
        is PaceAlert -> when {
            step.capOnly -> "Ease off. Pace ${spokenPace(pace)}, keep it slower than ${spokenPace(step.fast)}."
            else -> (if (tooSlow) "Speed up. " else "Slow down a little. ") +
                "Pace ${spokenPace(pace)}, target ${spokenPace((step.fast + step.slow) / 2)}."
        }
        is Split -> "Kilometre $km. ${spokenPace(secPerKm)} per kilometre."
        is Finished -> buildString {
            append("Workout complete, good job.")
            lastLap?.let { append(' ').append(spokenResult(it)) }
            append(" Workout summary: ").append(spokenDistance(distanceM)).append(" in ").append(spokenDuration(sec))
            if (distanceM >= 100) append(", average ").append(spokenPace(sec / (distanceM / 1000))).append(" per kilometre")
            append('.')
            if (targeted > 0) append(" $onTarget of $targeted paced ${if (targeted == 1) "step" else "steps"} on target.")
        }
    }

    companion object {
        private fun opener(st: RunStep): String = when (st.kind) {
            StepKind.WARMUP -> "Let's go."
            StepKind.COOLDOWN -> "Last one."
            StepKind.RECOVERY -> "Recover."
            StepKind.READY -> "Get ready."
            StepKind.INTERVAL -> if (st.hill || (st.zone != null && st.zone != Zone.E)) "Go!" else "Keep going."
        }

        /** "1 minute at 5 26 per kilometre", "2 minutes easy jog", "12 kilometres at 7 15 per kilometre". */
        fun spokenStep(st: RunStep): String {
            val amt = when {
                st.metres != null -> spokenDistance(st.metres)
                st.secs != null -> spokenDuration(st.secs)
                else -> "Run as you like"
            }
            return when {
                st.hill && st.isOpen -> if (st.label == RunStep.TO_HILL) "jog to the bottom of your hill" else "jog back down"
                st.hill && st.kind == StepKind.INTERVAL -> "$amt, hard uphill"
                st.hill && st.kind == StepKind.RECOVERY -> "$amt rest"
                st.kind == StepKind.READY -> "$amt countdown"
                st.capOnly -> "$amt, no faster than ${spokenPace(st.fast)} per kilometre"
                st.hasTarget -> "$amt at ${spokenPace((st.fast + st.slow) / 2)} per kilometre"
                st.kind == StepKind.RECOVERY -> "$amt easy jog"
                else -> amt
            }
        }

        /** "Last lap: 1 minute, 208 metres, 4 49 per kilometre, 37 seconds faster than target." */
        fun spokenResult(r: StepResult): String = buildString {
            append("Last lap: ").append(spokenDuration(r.sec))
            if (r.metres >= 10) {
                append(", ").append(spokenDistance(r.metres))
                if (r.pace.isFinite()) append(", ").append(spokenPace(r.pace)).append(" per kilometre")
                verdict(r.pace, r.fast, r.slow)?.let { append(", ").append(it) }
            }
            append('.')
        }

        /** "on target", "12 seconds faster than target", or null when there is no target. */
        fun verdict(pace: Double, fast: Double, slow: Double): String? {
            if (!fast.isFinite() || !pace.isFinite()) return null
            if (!slow.isFinite()) return if (pace < fast - 3) "${Math.round(fast - pace)} seconds faster than the easy limit" else "on target"
            return when {
                pace < fast - 3 -> "${Math.round(fast - pace)} seconds faster than target"
                pace > slow + 3 -> "${Math.round(pace - slow)} seconds slower than target"
                else -> "on target"
            }
        }

        fun spokenPace(s: Double): String {
            if (!s.isFinite()) return ""
            val r = Math.round(s)
            val sec = r % 60
            return "${r / 60} ${if (sec < 10) "oh $sec" else "$sec"}".replace(" oh 0", " flat")
        }

        fun spokenDuration(sec: Double): String {
            val s = Math.round(sec)
            val h = s / 3600
            val m = (s % 3600) / 60
            val ss = s % 60
            val parts = ArrayList<String>()
            if (h > 0) parts += "$h ${if (h == 1L) "hour" else "hours"}"
            if (m > 0) parts += "$m ${if (m == 1L) "minute" else "minutes"}"
            if (ss > 0 && h == 0L) parts += "$ss seconds"
            return parts.joinToString(" ").ifEmpty { "0 seconds" }
        }

        fun spokenDistance(m: Double): String = when {
            m < 1000 -> "${Math.round(m)} metres"
            m % 1000 == 0.0 -> "${(m / 1000).toLong()} kilometres".replace("^1 kilometres".toRegex(), "1 kilometre")
            else -> "%.1f kilometres".format(m / 1000)
        }
    }
}

/** Snapshot sent to the UI and the watch once a second. */
data class RunState(
    val title: String,
    val stepLabel: String,
    val stepIndex: Int,
    val stepCount: Int,
    val zoneCode: String,
    val fast: Double,
    val slow: Double,
    val pace: Double,
    val avgPace: Double,
    val status: PaceStatus,
    val stepRemaining: Double,        // seconds or metres, NaN for open steps
    val remainingIsDistance: Boolean,
    val stepProgress: Double,         // 0..1, NaN for open steps
    val elapsedSec: Double,
    val distanceM: Double,
    val paused: Boolean,
    val finished: Boolean,
    val nextLabel: String,
    val gpsOk: Boolean,
    val lapPace: Double = Double.NaN,  // average pace so far in this step
) {
    /** Compact wire format for the watch (no JSON dependency in core). */
    fun encode(): ByteArray = listOf(
        title, stepLabel, stepIndex, stepCount, zoneCode, fast, slow, pace, avgPace, status.name,
        stepRemaining, remainingIsDistance, stepProgress, elapsedSec, distanceM, paused, finished, nextLabel, gpsOk, lapPace,
    ).joinToString(SEP) { it.toString().replace(SEP, " ") }.toByteArray(Charsets.UTF_8)

    companion object {
        private const val SEP = "\u001F"

        fun decode(bytes: ByteArray): RunState? = runCatching {
            val f = String(bytes, Charsets.UTF_8).split(SEP)
            RunState(
                f[0], f[1], f[2].toInt(), f[3].toInt(), f[4], f[5].toDouble(), f[6].toDouble(),
                f[7].toDouble(), f[8].toDouble(), PaceStatus.valueOf(f[9]), f[10].toDouble(), f[11].toBoolean(),
                f[12].toDouble(), f[13].toDouble(), f[14].toDouble(), f[15].toBoolean(), f[16].toBoolean(), f[17], f[18].toBoolean(),
                f.getOrNull(19)?.toDoubleOrNull() ?: Double.NaN,   // older phones don't send it
            )
        }.getOrNull()

        val IDLE = RunState("", "Waiting for run", 0, 0, "", Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            PaceStatus.NONE, Double.NaN, false, Double.NaN, 0.0, 0.0, false, false, "", false)
    }
}

data class StepResult(
    val label: String,
    val zoneCode: String,
    val fast: Double,
    val slow: Double,
    val sec: Double,
    val metres: Double,
) {
    val pace: Double get() = if (metres > 10) sec / (metres / 1000) else Double.NaN
    /** Ceiling only (easy): slow is +Inf in memory and NaN once stored, so test "not finite". */
    val capOnly: Boolean get() = fast.isFinite() && !slow.isFinite()
    /** Inside the target band (3 s/km tolerance); for a ceiling, no faster than it. */
    val hit: Boolean? get() = when {
        !fast.isFinite() || !pace.isFinite() -> null
        capOnly -> pace >= fast - 3
        else -> pace in (fast - 3)..(slow + 3)
    }
    /** At target pace or faster; for a ceiling, kept under it. */
    val onOrAhead: Boolean? get() = when {
        !fast.isFinite() || !pace.isFinite() -> null
        capOnly -> hit
        else -> pace <= slow + 3
    }
}

/**
 * Pure step/cue logic. The phone's service feeds it active (non-paused) time and
 * cumulative distance; it advances steps and emits [Cue]s. No Android dependencies.
 */
class RunEngine(val title: String, val steps: List<RunStep>) {
    init { require(steps.isNotEmpty()) }

    var index = 0; private set
    var finished = false; private set
    private var stepStartMs = 0L
    private var stepStartM = 0.0
    private var elapsedMs = 0L
    private var distanceM = 0.0
    private var pace = Double.NaN
    private var outSinceMs = -1L
    private var lastAlertMs = -1_000_000L
    private var alertsThisStep = 0
    private val said = HashSet<String>()
    private val recent = ArrayDeque<Pair<Long, Double>>()   // (elapsed ms, distance) for the last 30 s
    private var nextSplitKm = 1
    private var lastSplitMs = 0L
    val results = ArrayList<StepResult>()
    val splits = ArrayList<Double>()

    val current: RunStep get() = steps[index]

    fun start(): List<Cue> = listOf(Cue.StepStart(current, 0, steps.size, steps.getOrNull(1), null))

    private fun stepSec() = (elapsedMs - stepStartMs) / 1000.0
    private fun stepM() = distanceM - stepStartM

    /** Seconds or metres left in the current step (NaN if open). */
    private fun remaining(): Double = when {
        current.metres != null -> current.metres!! - stepM()
        current.secs != null -> current.secs!! - stepSec()
        else -> Double.NaN
    }

    /** Average pace since the step started (NaN for the first 10 s / 20 m). */
    fun lapPace(): Double = if (stepSec() >= 10 && stepM() >= 20) stepSec() / (stepM() / 1000) else Double.NaN

    /**
     * Pace used for on/off-target decisions: distance over the last 20 s (or since the step
     * started), which is far steadier than the second-by-second display.
     */
    private fun steadyPace(): Double {
        val since = maxOf(stepStartMs, elapsedMs - 20_000)
        val start = recent.firstOrNull { it.first >= since } ?: return pace
        val sec = (elapsedMs - start.first) / 1000.0
        val m = distanceM - start.second
        return if (sec >= 12 && m >= 15) sec / (m / 1000) else pace
    }

    fun status(): PaceStatus {
        val st = current
        val p = steadyPace()
        if (!st.hasTarget || !p.isFinite()) return PaceStatus.NONE
        return when {
            p > st.slow + 3 -> PaceStatus.SLOW
            p < st.fast - 3 -> PaceStatus.FAST
            else -> PaceStatus.IN_BAND
        }
    }

    /**
     * @param elapsedMs active time since start (excludes pauses)
     * @param distanceM cumulative distance
     * @param pace smoothed current pace s/km (NaN if unknown)
     */
    fun update(elapsedMs: Long, distanceM: Double, pace: Double): List<Cue> {
        if (finished) return emptyList()
        this.elapsedMs = elapsedMs
        this.distanceM = distanceM
        this.pace = pace
        recent.addLast(elapsedMs to distanceM)
        while (recent.size > 2 && elapsedMs - recent.first().first > 30_000) recent.removeFirst()
        val cues = ArrayList<Cue>()

        // km splits (only on longer continuous steps, so reps aren't cluttered)
        while (distanceM >= nextSplitKm * 1000.0) {
            val sec = (elapsedMs - lastSplitMs) / 1000.0
            splits += sec
            lastSplitMs = elapsedMs
            val st = current
            val longStep = (st.metres ?: 0.0) >= 2000 || (st.secs ?: 0.0) >= 480 || (st.isOpen && !st.hill)
            if (longStep) cues += Cue.Split(nextSplitKm, sec)
            nextSplitKm++
        }

        val rem = remaining()
        // countdown to a tap-started rep: "3", "2", "1", then the rep starts with "Go!"
        if (current.kind == StepKind.READY) for (n in 3 downTo 1) if (rem <= n && rem > 0 && said.add("r$n")) cues += Cue.Remaining("$n")
        if (rem.isFinite() && rem <= 0) {
            cues += advance()
            return cues
        }

        // halfway, countdowns
        val st = current
        val next = steps.getOrNull(index + 1)
        val half = when {
            st.secs != null && st.secs >= 120 -> stepSec() >= st.secs / 2
            st.metres != null && st.metres >= 1000 -> stepM() >= st.metres / 2
            else -> false
        }
        if (half && said.add("half")) {
            val sofar = if (stepM() >= 20) stepSec() / (stepM() / 1000) else Double.NaN
            if (sofar.isFinite()) cues += Cue.Halfway(sofar, st)
        }
        // Near the end of the workout the last step's cue says so. Steps too short for it to fit
        // without clashing with the step's own start or halfway cues get none.
        val last = next == null
        if (st.secs != null && st.secs >= 180 && rem <= 60 && rem > 15 && said.add("60"))
            cues += Cue.Remaining(if (last) "One minute left in the workout." else "One minute left.")
        if (st.secs != null && st.secs >= 30 && rem <= 10 && said.add("10")) cues += Cue.Countdown("10 seconds left.", next)
        if (st.metres != null && st.metres >= (if (last) 500.0 else 1000.0) && rem <= 200 && said.add("200"))
            cues += if (last) Cue.Remaining("200 metres left in the workout.") else Cue.Countdown("200 metres to go.", next)

        // pace alerts: settle 30 s into a step, 8 s out of band, then at most every 30 s.
        // Easy-limit alerts back off (1, 2, then 3 minutes apart) so they don't nag.
        val s = status()
        val alertable = st.hasTarget && st.kind == StepKind.INTERVAL &&
            stepSec() >= 30 && (st.zone != Zone.E || s == PaceStatus.FAST)
        if (alertable && (s == PaceStatus.SLOW || s == PaceStatus.FAST)) {
            if (outSinceMs < 0) outSinceMs = elapsedMs
            val gap = if (st.capOnly) 60_000L * alertsThisStep.coerceIn(1, 3) else 30_000L
            if (elapsedMs - outSinceMs >= 8_000 && elapsedMs - lastAlertMs >= gap) {
                cues += Cue.PaceAlert(s == PaceStatus.SLOW, steadyPace(), st)
                lastAlertMs = elapsedMs
                alertsThisStep++
            }
        } else {
            outSinceMs = -1
        }
        return cues
    }

    /** Close the current step and move on (also used for "skip"). */
    fun advance(): List<Cue> {
        if (finished) return emptyList()
        val done = StepResult(current.label, current.zoneCode, current.fast, current.slow, stepSec(), stepM())
        results += done
        stepStartMs = elapsedMs
        stepStartM = distanceM
        said.clear()
        outSinceMs = -1
        lastAlertMs = -1_000_000L
        alertsThisStep = 0
        return if (index + 1 >= steps.size) {
            finished = true
            listOf(finishedCue(done))
        } else {
            index++
            listOf(Cue.StepStart(current, index, steps.size, steps.getOrNull(index + 1), done, afterCountdown = steps[index - 1].kind == StepKind.READY))
        }
    }

    /** End early: record the partial step. */
    fun finish(): List<Cue> {
        if (finished) return emptyList()
        val done = StepResult(current.label, current.zoneCode, current.fast, current.slow, stepSec(), stepM())
        results += done
        finished = true
        return listOf(finishedCue(done))
    }

    private fun finishedCue(last: StepResult): Cue.Finished {
        val paced = results.mapNotNull { it.onOrAhead }
        return Cue.Finished(
            distanceM, elapsedMs / 1000.0,
            lastLap = if (steps.size > 1) last else null,
            onTarget = paced.count { it }, targeted = paced.size,
        )
    }

    fun state(paused: Boolean, gpsOk: Boolean): RunState {
        val st = current
        val rem = if (finished) 0.0 else remaining()
        val total = st.metres ?: st.secs
        val progress = if (total != null && total > 0) min(1.0, max(0.0, 1 - rem / total)) else Double.NaN
        return RunState(
            title = title,
            stepLabel = if (finished) "Done" else st.label,
            stepIndex = index,
            stepCount = steps.size,
            zoneCode = st.zoneCode,
            fast = st.fast,
            slow = st.slow,
            pace = pace,
            avgPace = if (distanceM > 50) elapsedMs / 1000.0 / (distanceM / 1000) else Double.NaN,
            status = if (finished) PaceStatus.NONE else status(),
            stepRemaining = rem,
            remainingIsDistance = st.metres != null,
            stepProgress = if (finished) 1.0 else progress,
            elapsedSec = elapsedMs / 1000.0,
            distanceM = distanceM,
            paused = paused,
            finished = finished,
            nextLabel = steps.getOrNull(index + 1)?.let { "${it.label}, ${it.describe()}" } ?: "",
            gpsOk = gpsOk,
            lapPace = if (finished) Double.NaN else lapPace(),
        )
    }
}
