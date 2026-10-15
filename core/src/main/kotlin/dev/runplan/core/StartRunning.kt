package dev.runplan.core

/**
 * The beginner plan: 9 weeks of 3 run/walk sessions that build to 30 minutes of running
 * without stopping (the NHS Couch to 5K progression). Every session starts with a 5-minute
 * brisk walk and ends with a 5-minute walk. Runs have no pace target: run slowly enough to talk.
 *
 * Sessions are laid one after another on the runner's 3 days from the start day, so starting
 * mid-week doesn't skip any. Kept in lock-step with runplan.py (--beginner).
 */
object StartRunning {
    const val WEEKS = 9
    const val PER_WEEK = 3
    const val SESSIONS = WEEKS * PER_WEEK

    /** Fitness used for estimates only (a 40-minute 5K); beginner sessions have no pace targets. */
    val NOMINAL_DISTANCE = Distance.K5
    const val NOMINAL_SEC = 2400.0

    private const val WALK_SEC = 300.0

    private fun run(sec: Int) = Step(StepKind.INTERVAL, secs = sec.toDouble())
    private fun walk(sec: Int) = Step(StepKind.RECOVERY, secs = sec.toDouble(), walk = true)

    /** The running part of programme week [week] (1..9), session [n] (1..3). */
    fun main(week: Int, n: Int): List<Block> = when (week) {
        1 -> listOf(Repeat(8, listOf(run(60), walk(90))))
        2 -> listOf(Repeat(6, listOf(run(90), walk(120))))
        3 -> listOf(Repeat(2, listOf(run(90), walk(90), run(180), walk(180))))
        4 -> listOf(run(180), walk(90), run(300), walk(150), run(180), walk(90), run(300)).map(::Single)
        5 -> when (n) {
            1 -> listOf(Repeat(3, listOf(run(300), walk(180))))
            2 -> listOf(run(480), walk(300), run(480)).map(::Single)
            else -> listOf(Single(run(1200)))
        }
        6 -> when (n) {
            1 -> listOf(run(300), walk(180), run(480), walk(180), run(300)).map(::Single)
            2 -> listOf(run(600), walk(180), run(600)).map(::Single)
            else -> listOf(Single(run(1500)))
        }
        7 -> listOf(Single(run(1500)))
        8 -> listOf(Single(run(1680)))
        else -> listOf(Single(run(1800)))
    }

    fun blocks(week: Int, n: Int): List<Block> =
        listOf(Single(Step(StepKind.WARMUP, secs = WALK_SEC, walk = true))) +
            main(week, n) +
            Single(Step(StepKind.COOLDOWN, secs = WALK_SEC, walk = true))

    private fun note(week: Int, n: Int, nonStop: Boolean): String = when {
        week == 1 && n == 1 -> "Your first session. Run slowly enough that you could still talk, and walk briskly in between."
        week == WEEKS && n == PER_WEEK -> "The last run of the plan: 30 minutes without stopping."
        nonStop && (week == 5 || week == 6) -> "Your longest run yet. Go slower than feels necessary: it's about time, not speed."
        else -> "Run slowly enough that you could still talk."
    }

    /** Days of the sessions, in order: the runner's [days] (0 = Monday) from [start] on. */
    fun dates(start: Long, days: List<Int>): List<Long> {
        val out = ArrayList<Long>()
        var d = start
        while (out.size < SESSIONS) {
            if (PlanBuilder.weekday(d) in days) out += d
            d++
        }
        return out
    }

    /** The Sunday the plan ends on, stored as the profile's race day. */
    fun endDay(start: Long, days: List<Int>): Long = PlanBuilder.mondayOf(dates(start, days).last()) + 6

    fun build(profile: Profile): Plan {
        val days = profile.days.distinct().sorted()
        if (days.size != PER_WEEK) throw PlanException("Pick 3 run days.")
        val start = profile.startEpochDay
        val week0 = PlanBuilder.mondayOf(start)
        val workouts = dates(start, days).mapIndexed { i, day ->
            val week = i / PER_WEEK + 1
            val n = i % PER_WEEK + 1
            val main = main(week, n)
            val nonStop = main.size == 1 && main[0] is Single
            Workout(
                day, ((day - week0) / 7 + 1).toInt(), Phase.START,
                if (nonStop) SessionType.NON_STOP else SessionType.RUN_WALK,
                blocks(week, n), note(week, n, nonStop),
            )
        }
        val end = PlanBuilder.mondayOf(workouts.last().epochDay) + 6
        val p = profile.copy(days = days, raceEpochDay = end)
        return Plan(p, p.paces, workouts, workouts.last().week)
    }
}
