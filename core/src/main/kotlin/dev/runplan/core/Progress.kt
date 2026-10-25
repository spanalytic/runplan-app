package dev.runplan.core

import kotlin.math.roundToInt

/** What the progress page needs from a saved run. */
data class RunSummary(
    val epochDay: Long,
    val metres: Double,
    val sec: Double,
    val workoutId: String? = null,
    val steps: List<StepResult> = emptyList(),
)

/** Where the runner is in their plan. [week] is 0 before the plan's first week. */
data class PlanStatus(
    val week: Int,
    val weeks: Int,
    val startsIn: Long,
    val daysLeft: Long,
    val done: Int,
    val total: Int,
    val weekDone: Int,
    val weekTotal: Int,
) {
    val finished: Boolean get() = total > 0 && done == total || daysLeft < 0
}

data class Milestone(val title: String, val epochDay: Long)

/** How the last few paced sessions went against the plan's current paces. */
enum class PaceVerdict { NOT_ENOUGH, ON_TARGET, AHEAD, BEHIND }

/**
 * [ratio] is achieved pace over target pace across those sessions (below 1 = faster).
 * [suggestedSec] is a new recent race time for the profile when the paces should change.
 */
data class PaceCheck(val verdict: PaceVerdict, val sessions: Int, val ratio: Double = 1.0, val suggestedSec: Double? = null)

/** A week in a few plain sentences. [current] is true for this week, false for last week. */
data class WeekSummary(val startEpochDay: Long, val current: Boolean, val lines: List<String>)

object Progress {
    /** Stored zone code of a beginner's running step: no zone, not a walk. */
    private const val RUN_CODE = ""

    fun planStatus(plan: Plan, done: Set<String>, today: Long): PlanStatus {
        val week0 = PlanBuilder.mondayOf(plan.profile.startEpochDay)
        val week = if (today < week0) 0 else ((today - week0) / 7 + 1).toInt().coerceAtMost(plan.weeks)
        val monday = PlanBuilder.mondayOf(today)
        val thisWeek = plan.workouts.filter { it.epochDay in monday until monday + 7 }
        return PlanStatus(
            week = week,
            weeks = plan.weeks,
            startsIn = (plan.profile.startEpochDay - today).coerceAtLeast(0),
            daysLeft = plan.profile.raceEpochDay - today,
            done = plan.workouts.count { it.id in done },
            total = plan.workouts.size,
            weekDone = thisWeek.count { it.id in done },
            weekTotal = thisWeek.size,
        )
    }

    /** Running steps of a beginner session (not the walks). */
    private fun runSteps(r: RunSummary) = if (r.workoutId == null) emptyList() else r.steps.filter { it.zoneCode == RUN_CODE }

    /** Longest stretch of running without a walk break in beginner sessions, in seconds, and the day it was run. */
    fun longestNonStop(runs: List<RunSummary>): Pair<Double, Long>? =
        runs.flatMap { r -> runSteps(r).map { it.sec to r.epochDay } }
            .filter { it.first > 0 }
            .maxWithOrNull(compareBy<Pair<Double, Long>> { it.first }.thenByDescending { it.second })

    /** Longest run (not walk) in a session, in seconds. */
    fun longestRunStep(w: Workout): Double =
        w.flatSteps().filter { it.kind == StepKind.INTERVAL && !it.walk }.maxOfOrNull { it.secs ?: 0.0 } ?: 0.0

    /** The next session not yet done, from [today] on. */
    fun nextSession(plan: Plan, done: Set<String>, today: Long): Workout? =
        plan.workouts.filter { it.epochDay >= today && it.id !in done }.minByOrNull { it.epochDay }

    /**
     * Weeks in a row that count: every planned session done, or at least one run in a week with
     * nothing planned. This week counts once it's complete but never breaks the streak while it's under way.
     */
    fun streak(plan: Plan?, done: Set<String>, runDays: List<Long>, today: Long): Int {
        if (runDays.isEmpty() && done.isEmpty()) return 0
        fun counts(monday: Long): Boolean {
            val planned = plan?.workouts?.filter { it.epochDay in monday until monday + 7 }.orEmpty()
            return if (planned.isNotEmpty()) planned.all { it.id in done } else runDays.any { it in monday until monday + 7 }
        }
        val first = PlanBuilder.mondayOf(
            listOfNotNull(runDays.minOrNull(), plan?.workouts?.filter { it.id in done }?.minOfOrNull { it.epochDay }).min()
        )
        val thisMonday = PlanBuilder.mondayOf(today)
        var n = if (counts(thisMonday)) 1 else 0
        var m = thisMonday - 7
        while (m >= first && counts(m)) { n++; m -= 7 }
        return n
    }

    private val KM_TOTALS = listOf(50, 100, 250, 500, 1000, 1500, 2000, 3000, 4000, 5000)
    private val RUN_COUNTS = listOf(10, 25, 50, 100, 200, 300, 500)
    private val ONE_RUN = listOf(5000.0 to "First 5 km run", 10000.0 to "First 10 km run", 21097.5 to "First half marathon distance", 42195.0 to "First marathon distance")
    private val NON_STOP_MIN = listOf(5, 10, 20, 30)

    /** Milestones reached, oldest first. Beginners get time-based ones instead of distances. */
    fun milestones(runs: List<RunSummary>, plan: Plan?, done: Set<String>): List<Milestone> {
        val rs = runs.sortedBy { it.epochDay }
        if (rs.isEmpty()) return emptyList()
        val beginner = plan?.profile?.beginner == true
        val out = ArrayList<Milestone>()
        out += Milestone("Your first run", rs.first().epochDay)
        RUN_COUNTS.forEach { n -> if (rs.size >= n) out += Milestone("$n runs", rs[n - 1].epochDay) }
        if (beginner) {
            NON_STOP_MIN.forEach { m ->
                rs.firstOrNull { r -> runSteps(r).any { it.sec >= m * 60 - 5 } }?.let { out += Milestone("Ran $m minutes without stopping", it.epochDay) }
            }
        } else {
            ONE_RUN.forEach { (m, title) -> rs.firstOrNull { it.metres >= m }?.let { out += Milestone(title, it.epochDay) } }
            var total = 0.0
            val left = KM_TOTALS.toMutableList()
            for (r in rs) {
                total += r.metres / 1000
                while (left.isNotEmpty() && total >= left.first()) out += Milestone("${left.removeAt(0)} km in total", r.epochDay)
            }
        }
        if (plan != null && plan.workouts.isNotEmpty() && plan.workouts.all { it.id in done }) {
            out += Milestone(if (beginner) "Finished the Couch to 5K plan" else "Finished your plan", plan.workouts.maxOf { it.epochDay })
        }
        return out.sortedBy { it.epochDay }
    }

    /** The next milestone to aim for, in words. */
    fun nextMilestone(runs: List<RunSummary>, plan: Plan?): String? {
        if (plan?.profile?.beginner == true) {
            val best = longestNonStop(runs)?.first ?: 0.0
            val next = NON_STOP_MIN.firstOrNull { best < it * 60 - 5 } ?: return null
            return "Run $next minutes without stopping"
        }
        val total = runs.sumOf { it.metres } / 1000
        val next = KM_TOTALS.firstOrNull { total < it } ?: return null
        return "$next km in total: ${(next - total).roundToInt().coerceAtLeast(1)} km to go"
    }

    /** Paced steps worth judging: a race-type zone (not easy, not short fast reps), at least a minute long. */
    private val CHECKED_ZONES = setOf(Zone.M, Zone.HM, Zone.T, Zone.I, Zone.G)

    private fun pacedSteps(r: RunSummary): List<Pair<StepResult, Zone>> = r.steps.mapNotNull { s ->
        val z = Zone.entries.firstOrNull { it.code == s.zoneCode }?.takeIf { it in CHECKED_ZONES } ?: return@mapNotNull null
        if (s.sec >= 60 && s.metres > 200 && s.pace.isFinite()) s to z else null
    }

    /**
     * Looks at the last 3 sessions with paced work in the past 6 weeks, judged against the
     * plan's current paces (so results from before a pace change are judged fairly).
     * Ahead: at least two sessions mostly faster than the band and none mostly slower. Behind: the reverse.
     */
    fun paceCheck(runs: List<RunSummary>, profile: Profile, paces: Paces, today: Long): PaceCheck {
        val sessions = runs.filter { it.epochDay in (today - 41)..today }
            .map { it to pacedSteps(it) }.filter { it.second.size >= 2 }
            .sortedByDescending { it.first.epochDay }.take(3)
        if (sessions.size < 2) return PaceCheck(PaceVerdict.NOT_ENOUGH, sessions.size)
        var ahead = 0
        var behind = 0
        val ratios = ArrayList<Double>()
        for ((_, steps) in sessions) {
            var fast = 0
            var slow = 0
            for ((s, z) in steps) {
                val (lo, hi) = paces.band(z)
                if (s.pace < lo - 3) fast++
                if (s.pace > hi + 3) slow++
                ratios += s.pace / paces.centre(z)
            }
            if (fast * 3 >= steps.size * 2) ahead++
            if (slow * 2 >= steps.size) behind++
        }
        val ratio = ratios.sorted()[ratios.size / 2]
        val verdict = when {
            ahead >= 2 && behind == 0 && ratio < 0.99 -> PaceVerdict.AHEAD
            behind >= 2 && ahead == 0 && ratio > 1.01 -> PaceVerdict.BEHIND
            else -> PaceVerdict.ON_TARGET
        }
        val suggested = if (verdict == PaceVerdict.AHEAD || verdict == PaceVerdict.BEHIND) profile.recentSec * ratio.coerceIn(0.97, 1.03) else null
        return PaceCheck(verdict, sessions.size, ratio, suggested)
    }

    /**
     * Easy running pace (s/km) by week, oldest first, each averaged with the two weeks before so
     * the line stays calm. Counts easy steps of at least 5 minutes from plan and built sessions.
     */
    fun easyTrend(runs: List<RunSummary>, today: Long, weeks: Int = 12): List<Pair<Long, Double>> {
        val byWeek = HashMap<Long, Pair<Double, Double>>()   // monday -> (sec, metres)
        for (r in runs) for (s in r.steps) {
            if (s.zoneCode != Zone.E.code || s.sec < 300 || s.metres < 500) continue
            val m = PlanBuilder.mondayOf(r.epochDay)
            val (a, b) = byWeek[m] ?: (0.0 to 0.0)
            byWeek[m] = (a + s.sec) to (b + s.metres)
        }
        val thisMonday = PlanBuilder.mondayOf(today)
        return (weeks - 1 downTo 0).mapNotNull { k ->
            val m = thisMonday - 7L * k
            if (m !in byWeek) return@mapNotNull null
            val window = (0..2).mapNotNull { byWeek[m - 7L * it] }
            m to window.sumOf { it.first } / (window.sumOf { it.second } / 1000)
        }
    }

    /** One line on the easy trend, or null with too little to go on. */
    fun easyTrendLine(trend: List<Pair<Long, Double>>, since: String): String? {
        if (trend.size < 3) return null
        val diff = (trend.first().second - trend.last().second).roundToInt()
        return when {
            diff >= 3 -> "Your easy running is $diff s/km quicker than in $since, at the same easy effort. That's your fitness growing."
            diff <= -3 -> "Your easy running is ${-diff} s/km slower than in $since. Heat, hills and tiredness all play a part, so keep it easy."
            else -> "Your easy pace has held steady since $since."
        }
    }

    /**
     * This week in plain words, or last week while this week has no runs yet.
     * Beginners hear about time, not distance or pace.
     */
    fun weekSummary(plan: Plan?, done: Set<String>, runs: List<RunSummary>, today: Long): WeekSummary? {
        val thisMonday = PlanBuilder.mondayOf(today)
        fun inWeek(m: Long) = runs.filter { it.epochDay in m until m + 7 }
        val current = inWeek(thisMonday).isNotEmpty()
        val monday = if (current) thisMonday else thisMonday - 7
        val week = inWeek(monday)
        if (week.isEmpty()) return null
        val beginner = plan?.profile?.beginner == true
        val lines = ArrayList<String>()

        val planned = plan?.workouts?.filter { it.epochDay in monday until monday + 7 }.orEmpty()
        if (planned.isNotEmpty()) {
            val n = planned.count { it.id in done }
            val left = planned.any { it.id !in done && it.epochDay >= today }
            lines += when {
                n == planned.size -> if (planned.size == 1) "Your session is done." else "All ${planned.size} sessions done."
                current && left -> "$n of ${planned.size} sessions done so far."
                else -> "$n of ${planned.size} sessions done."
            }
        }

        val sec = week.sumOf { it.sec }
        val runsWord = if (week.size == 1) "1 run" else "${week.size} runs"
        if (beginner) {
            lines += "${Fmt.estimate(sec)} of running and walking over $runsWord."
            longestNonStop(week)?.let { (s, _) -> lines += "Longest run without stopping: ${Fmt.minutes(((s / 60).roundToInt() * 60).toDouble())}." }
            return WeekSummary(monday, current, lines)
        }

        val km = week.sumOf { it.metres } / 1000
        lines += "%.1f km in ${Fmt.duration(sec)} over $runsWord.".format(km)
        if (!current) {
            val before = inWeek(monday - 7).sumOf { it.metres } / 1000
            if (before > 0) lines += when {
                km - before >= 0.5 -> "%.1f km more than the week before.".format(km - before)
                before - km >= 0.5 -> "%.1f km less than the week before.".format(before - km)
                else -> "About the same distance as the week before."
            }
        }
        if (week.size > 1) week.maxBy { it.metres }.let { lines += "Longest run: %.1f km.".format(it.metres / 1000) }

        val paced = week.flatMap { pacedSteps(it) }.mapNotNull { it.first.onOrAhead }
        if (paced.isNotEmpty()) {
            val ok = paced.count { it }
            lines += when (ok) {
                paced.size -> if (paced.size == 1) "Your paced effort was on or ahead of target." else "All ${paced.size} paced efforts were on or ahead of target."
                else -> "$ok of ${paced.size} paced efforts were on or ahead of target."
            }
        }
        val easy = week.flatMap { r -> r.steps.filter { it.zoneCode == Zone.E.code && it.sec >= 300 } }.mapNotNull { it.hit }
        if (easy.isNotEmpty()) {
            val tooFast = easy.count { !it }
            lines += if (tooFast == 0) "Easy running stayed easy." else "Some easy running was quicker than easy pace. Slower is fine on easy days."
        }
        return WeekSummary(monday, current, lines)
    }

    /** How much a pace change moves a pace, in s/km (negative = quicker). */
    fun paceShift(from: Paces, to: Paces): Int = (to.threshold - from.threshold).roundToInt()
}
