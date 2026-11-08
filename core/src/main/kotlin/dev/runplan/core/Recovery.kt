package dev.runplan.core

import kotlin.math.max
import kotlin.math.min

/**
 * Easy weeks before a new plan, to recover after finishing one (usually with a race).
 * Two rest days after the start day, then an easy run on each of the runner's days: about 60% of
 * their usual week over one recovery week, or 50% then 75% over two. The plan proper starts on
 * the Monday nearest [Profile.recoveryWeeks] weeks after the start day, and its weeks are
 * numbered on from the recovery weeks. Kept in lock-step with runplan.py (--recovery-weeks).
 */
object Recovery {
    const val MAX_WEEKS = 2
    /** Rest days after the start day (the race, or the day the last plan finished) before the first run. */
    const val REST_DAYS = 2
    const val NOTE = "Recovery week: keep it short and easy."

    /** The Monday the plan proper starts: the one nearest to [weeks] weeks after [start], and after it. */
    fun planStart(start: Long, weeks: Int): Long = max(
        PlanBuilder.mondayOf(start + 7L * weeks + 3),
        PlanBuilder.mondayOf(start + REST_DAYS + 1) + 7,
    )

    /**
     * Start day for a plan that follows a finished one, set up on [today]. With recovery weeks it's
     * today (they begin with rest days); without, it's next Monday, so nothing lands on the day
     * the last plan finished (often race day) and week 1 is a whole week.
     */
    fun nextPlanStart(today: Long, weeks: Int): Long = if (weeks > 0) today else PlanBuilder.mondayOf(today) + 7

    private fun r5(x: Double): Double = max(0.5, Math.rint(x * 2) / 2)

    fun build(profile: Profile): Plan {
        val weeks = profile.recoveryWeeks.coerceIn(1, MAX_WEEKS)
        val start = profile.startEpochDay
        val planStart = planStart(start, weeks)
        val days = profile.days.distinct().sorted()
        val main = try {
            PlanBuilder.build(profile.copy(startEpochDay = planStart, recoveryWeeks = 0))
        } catch (e: PlanException) {
            throw PlanException("${e.message} The recovery ${if (weeks == 1) "week comes" else "weeks come"} before that.")
        }

        val first = start + REST_DAYS + 1
        val week0 = PlanBuilder.mondayOf(first)
        val nWeeks = ((planStart - week0) / 7).toInt()
        // the volume they had been running: the old plan's peak isn't known here, so their current week
        val usual = max(profile.currentKm, 10.0)
        val easy = ArrayList<Workout>()
        for (d in first until planStart) {
            if (PlanBuilder.weekday(d) !in days) continue
            val k = ((d - week0) / 7).toInt()
            val share = if (nWeeks == 1) 0.6 else 0.5 + 0.25 * k / (nWeeks - 1)
            val km = r5(min(8.0, max(3.0, usual * share / days.size)))
            easy += Workout(
                d, k + 1, Phase.RECOVER, SessionType.EASY,
                listOf(Single(Step(StepKind.INTERVAL, metres = km * 1000, zone = Zone.E))), NOTE,
            )
        }
        val shifted = main.workouts.map { it.copy(week = it.week + nWeeks) }
        return Plan(
            profile.copy(raceEpochDay = main.profile.raceEpochDay, days = main.profile.days),
            main.paces, easy + shifted, main.weeks + nWeeks,
        )
    }
}
