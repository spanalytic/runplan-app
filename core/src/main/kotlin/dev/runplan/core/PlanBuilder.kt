package dev.runplan.core

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PlanException(message: String) : IllegalArgumentException(message)

/**
 * Rules-based periodised plan: Base -> Build -> Peak -> Taper.
 * Volume rises <=10%/week towards a peak with a 20% cut-back every 4th week.
 * Kept in lock-step with runplan.py (same rules, same rounding).
 */
object PlanBuilder {

    private enum class Role { Q1, Q2, LONG, EASY }

    /** Python-compatible round-half-to-even. */
    private fun pyRound(x: Double): Double = Math.rint(x)
    private fun r5(x: Double): Double = max(0.5, pyRound(x * 2) / 2)
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    fun weekday(epochDay: Long): Int = LocalDate.ofEpochDay(epochDay).dayOfWeek.value - 1

    /** Monday on or before [epochDay]. */
    fun mondayOf(epochDay: Long): Long = epochDay - weekday(epochDay)

    fun weeksBetween(startEpochDay: Long, raceEpochDay: Long): Int =
        ((raceEpochDay - mondayOf(startEpochDay)) / 7 + 1).toInt()

    private fun gap(a: Int, b: Int): Int = abs(a - b).let { min(it, 7 - it) }

    /** First day = Q1, last = long run, Q2 (4+ days) on the free day furthest from both. */
    private fun roles(days: List<Int>): Map<Int, Role> {
        val r = days.associateWith { Role.EASY }.toMutableMap()
        r[days.first()] = Role.Q1
        r[days.last()] = Role.LONG
        if (days.size >= 4) {
            val free = days.subList(1, days.size - 1)
            val q2 = free.maxBy { min(gap(it, days.first()), gap(it, days.last())) }
            r[q2] = Role.Q2
        }
        return r
    }

    /** Role of each weekday for display during onboarding. */
    fun roleLabels(days: List<Int>): Map<Int, String> {
        if (days.size < 3) return emptyMap()
        return roles(days.sorted()).mapValues {
            when (it.value) { Role.Q1 -> "Intervals"; Role.Q2 -> "Tempo"; Role.LONG -> "Long"; Role.EASY -> "Easy" }
        }
    }

    private fun phaseFor(i: Int, buildWeeks: Int): Pair<Phase, Double> {
        val base = max(1, pyRound(buildWeeks * 0.3).toInt())
        val build = max(1, pyRound(buildWeeks * 0.45).toInt())
        val peak = max(1, buildWeeks - base - build)
        for ((ph, len, start) in listOf(Triple(Phase.BASE, base, 0), Triple(Phase.BUILD, build, base), Triple(Phase.PEAK, peak, base + build))) {
            if (i < start + len) return ph to (i - start).toDouble() / max(1, len - 1)
        }
        return Phase.PEAK to 1.0
    }

    private fun s(kind: StepKind, secs: Double? = null, metres: Double? = null, zone: Zone? = null) =
        Step(kind, secs, metres, zone)

    private fun q1(phase: Phase, t: Double, goal: Goal, taper: Boolean): Pair<SessionType, List<Block>> {
        val wu = Single(s(StepKind.WARMUP, secs = 15 * 60.0, zone = Zone.E))
        val cd = Single(s(StepKind.COOLDOWN, secs = 10 * 60.0, zone = Zone.E))
        if (taper) return SessionType.INTERVALS to listOf(wu, Repeat(3, listOf(s(StepKind.INTERVAL, 180.0, zone = Zone.I), s(StepKind.RECOVERY, 120.0))), cd)
        if (phase == Phase.BASE) {
            val n = pyRound(lerp(6.0, 10.0, t)).toInt()
            return SessionType.STRIDES to listOf(wu, Repeat(n, listOf(s(StepKind.INTERVAL, 60.0, zone = Zone.R), s(StepKind.RECOVERY, 120.0))), cd)
        }
        val repMin = if (phase == Phase.BUILD) pyRound(lerp(3.0, 5.0, t)) else 5.0
        var total = if (phase == Phase.BUILD) lerp(12.0, 20.0, t) else lerp(20.0, 25.0, t)
        if ((goal == Goal.HALF || goal == Goal.MARATHON) && phase == Phase.PEAK) total = 20.0
        val n = max(3, pyRound(total / repMin).toInt())
        val rec = max(60.0, pyRound(repMin * 0.4) * 60)
        return SessionType.INTERVALS to listOf(wu, Repeat(n, listOf(s(StepKind.INTERVAL, repMin * 60, zone = Zone.I), s(StepKind.RECOVERY, rec))), cd)
    }

    private fun q2(phase: Phase, t: Double, goal: Goal, taper: Boolean): Pair<SessionType, List<Block>> {
        val wu = Single(s(StepKind.WARMUP, secs = 12 * 60.0, zone = Zone.E))
        val cd = Single(s(StepKind.COOLDOWN, secs = 10 * 60.0, zone = Zone.E))
        if (taper) return SessionType.THRESHOLD to listOf(wu, Repeat(2, listOf(s(StepKind.INTERVAL, 480.0, zone = Zone.T), s(StepKind.RECOVERY, 90.0))), cd)
        if (phase == Phase.BASE) return SessionType.THRESHOLD to listOf(wu, Repeat(3, listOf(s(StepKind.INTERVAL, 300.0, zone = Zone.T), s(StepKind.RECOVERY, 60.0))), cd)
        if (phase == Phase.BUILD) {
            val rep = pyRound(lerp(6.0, 10.0, t))
            val n = if (rep <= 8) 4 else 3
            return SessionType.THRESHOLD to listOf(wu, Repeat(n, listOf(s(StepKind.INTERVAL, rep * 60, zone = Zone.T), s(StepKind.RECOVERY, 90.0))), cd)
        }
        return when (goal) {
            Goal.MARATHON -> SessionType.MARATHON_PACE to listOf(wu, Single(s(StepKind.INTERVAL, metres = pyRound(lerp(8.0, 14.0, t)) * 1000, zone = Zone.M)), cd)
            Goal.HALF -> SessionType.HALF_PACE to listOf(wu, Single(s(StepKind.INTERVAL, metres = pyRound(lerp(5.0, 8.0, t)) * 1000, zone = Zone.HM)), cd)
            else -> SessionType.TEMPO to listOf(wu, Single(s(StepKind.INTERVAL, secs = pyRound(lerp(15.0, 25.0, t)) * 60, zone = Zone.T)), cd)
        }
    }

    private fun long(km: Double, phase: Phase, t: Double, goal: Goal, week: Int): Pair<SessionType, List<Block>> {
        if (goal == Goal.MARATHON && (phase == Phase.BUILD || phase == Phase.PEAK) && week % 2 == 0) {
            val fin = r5(min(km * 0.35, if (phase == Phase.PEAK) lerp(5.0, 12.0, t) else 5.0))
            return SessionType.LONG_FAST_FINISH to listOf(
                Single(s(StepKind.INTERVAL, metres = (km - fin) * 1000, zone = Zone.E)),
                Single(s(StepKind.INTERVAL, metres = fin * 1000, zone = Zone.M)),
            )
        }
        if (goal == Goal.HALF && phase == Phase.PEAK && week % 2 == 0) {
            val fin = r5(min(km * 0.25, lerp(3.0, 5.0, t)))
            return SessionType.LONG_FAST_FINISH to listOf(
                Single(s(StepKind.INTERVAL, metres = (km - fin) * 1000, zone = Zone.E)),
                Single(s(StepKind.INTERVAL, metres = fin * 1000, zone = Zone.HM)),
            )
        }
        return SessionType.LONG to listOf(Single(s(StepKind.INTERVAL, metres = km * 1000, zone = Zone.E)))
    }

    fun build(profile: Profile): Plan {
        val goal = profile.goal
        val paces = profile.paces
        val days = profile.days.distinct().sorted()
        if (days.size !in 3..6) throw PlanException("Pick 3 to 6 run days.")
        val start = profile.startEpochDay
        val race = profile.raceEpochDay
        val week0 = mondayOf(start)
        val nWeeks = weeksBetween(start, race)
        val taperN = goal.taper.size
        val buildWeeks = nWeeks - taperN
        if (race < start || buildWeeks < 3) throw PlanException("A ${goal.label} plan needs at least ${goal.minWeeks} weeks.")

        val roles = roles(days)
        val peak = max(profile.peakKm ?: (goal.peakKmAt5Days * days.size / 5.0), profile.currentKm)
        val longShare = when (days.size) { 3 -> 0.40; 4 -> 0.36; 5 -> 0.32; else -> 0.28 }

        val vols = ArrayList<Double>()
        var v = profile.currentKm
        for (i in 0 until buildWeeks) {
            if (i > 0 && i % 4 != 0) v = min(peak, v * 1.10)
            vols += v * (if (i % 4 == 3 && i < buildWeeks - 1) 0.8 else 1.0)
        }
        goal.taper.forEach { vols += peak * it }

        val out = ArrayList<Workout>()
        for (i in 0 until nWeeks) {
            val monday = week0 + 7L * i
            val taper = i >= buildWeeks
            val (phase, t) = if (taper) Phase.TAPER to 1.0 else phaseFor(i, buildWeeks)
            val isDown = !taper && i % 4 == 3 && i < buildWeeks - 1
            val raceWeek = race in monday until monday + 7
            val weekKm = vols[i]
            var longKm = r5(min(goal.longCapKm, max(6.0, weekKm * longShare)))
            if (taper) longKm = r5(min(longKm, goal.longCapKm * (if (i < nWeeks - 1) 0.6 else 0.0)))

            var sessions = ArrayList<Workout>()
            for (d in days) {
                val day = monday + d
                if (day < start || day > race) continue
                var role = roles.getValue(d)
                if (role == Role.Q2 && (isDown || raceWeek)) role = Role.EASY
                val (type, blocks) = when (role) {
                    Role.Q1 -> q1(phase, t, goal, taper)
                    Role.Q2 -> q2(phase, t, goal, taper)
                    Role.LONG -> if (longKm <= 0.5) continue else long(longKm, phase, t, goal, i + 1)
                    Role.EASY -> SessionType.EASY to emptyList()
                }
                sessions += Workout(day, i + 1, phase, type, blocks)
            }

            if (raceWeek) {
                sessions = ArrayList(sessions.filter { it.epochDay != race })
                val raceW = Workout(
                    race, i + 1, Phase.RACE, SessionType.RACE,
                    listOf(
                        Single(s(StepKind.WARMUP, secs = 600.0, zone = Zone.E)),
                        Single(s(StepKind.INTERVAL, metres = goal.distance.metres, zone = Zone.G)),
                    ),
                    note = "Predicted ${Fmt.duration(Vdot.predictTime(paces.vdot, goal.distance.metres))}",
                )
                sessions += raceW
                sessions = ArrayList(sessions.filter { !((race - it.epochDay) in 1..2 && it.type != SessionType.EASY) })
            }

            val fixed = sessions.filter { it.type != SessionType.EASY }.sumOf { it.estimate(paces).first }
            val nEasy = sessions.count { it.type == SessionType.EASY }
            val filled = sessions.map { w ->
                if (w.type != SessionType.EASY) return@map w
                val remaining = max(0.0, weekKm - fixed)
                var each = r5(min(max(4.0, remaining / nEasy), max(6.0, longKm * 0.75)))
                if (taper) each = r5(max(4.0, min(each, 6.0)))
                var km = each
                var note = ""
                if ((race - w.epochDay) in 1..2) { km = min(km, 4.0); note = "Shakeout: add 4 x 20s strides" }
                w.copy(blocks = listOf(Single(s(StepKind.INTERVAL, metres = km * 1000, zone = Zone.E))), note = note)
            }.map { if (isDown) it.copy(note = listOf(it.note, "Cut-back week").filter(String::isNotEmpty).joinToString(". ")) else it }

            out += filled.sortedBy { it.epochDay }
        }
        return Plan(profile, paces, out, nWeeks)
    }
}
