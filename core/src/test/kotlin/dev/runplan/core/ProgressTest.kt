package dev.runplan.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressTest {
    private fun day(s: String) = LocalDate.parse(s).toEpochDay()

    private val profile = Profile(
        recentDistance = Distance.K10, recentSec = 55 * 60.0, goal = Goal.HALF,
        startEpochDay = day("2026-10-19"), raceEpochDay = day("2027-01-10"),
        days = listOf(1, 2, 4, 6), currentKm = 25.0,
    )
    private val plan = PlanBuilder.build(profile)

    private val beginner = Profile(
        recentDistance = StartRunning.NOMINAL_DISTANCE, recentSec = StartRunning.NOMINAL_SEC, goal = Goal.K5,
        startEpochDay = day("2026-10-21"), raceEpochDay = 0, days = listOf(0, 2, 5), currentKm = 0.0,
        level = Level.BEGINNER,
    )

    private fun easy(sec: Double, pace: Double) = StepResult("Easy", "E", 330.0, Double.NaN, sec, sec / pace * 1000)
    private fun rep(z: Zone, pace: Double, p: Paces = plan.paces, sec: Double = 300.0): StepResult {
        val (f, s) = p.band(z)
        return StepResult("Rep", z.code, f, s, sec, sec / pace * 1000)
    }
    private fun walk(sec: Double) = StepResult("Walk", "W", Double.NaN, Double.NaN, sec, sec / 600 * 1000)
    private fun run(sec: Double) = StepResult("Run", "", Double.NaN, Double.NaN, sec, sec / 420 * 1000)

    @Test fun planStatusCountsWeeksAndSessions() {
        val week3 = plan.workouts.filter { it.week == 3 }
        val today = week3.first().epochDay
        val done = plan.workouts.filter { it.epochDay < today }.map { it.id }.toSet() + week3.first().id
        val s = Progress.planStatus(plan, done, today)
        assertEquals(3, s.week)
        assertEquals(12, s.weeks)
        assertEquals(1, s.weekDone)
        assertEquals(week3.size, s.weekTotal)
        assertEquals(done.size, s.done)
        assertEquals(plan.workouts.size, s.total)
        assertEquals(profile.raceEpochDay - today, s.daysLeft)
        assertTrue(!s.finished)
        // before the plan starts
        val early = Progress.planStatus(plan, emptySet(), day("2026-10-10"))
        assertEquals(0, early.week)
        assertEquals(9L, early.startsIn)
    }

    @Test fun streakNeedsEveryPlannedSessionButNotThisWeekYet() {
        val weeks = plan.byWeek()
        // weeks 1-3 fully done, week 4 under way with one session done
        val done = (weeks.getValue(1) + weeks.getValue(2) + weeks.getValue(3)).map { it.id }.toSet() + weeks.getValue(4).first().id
        val runDays = done.mapNotNull { plan.find(it)?.epochDay }
        val today = weeks.getValue(4).first().epochDay
        assertEquals(3, Progress.streak(plan, done, runDays, today))
        // finishing week 4 adds it straight away
        val all4 = done + weeks.getValue(4).map { it.id }
        assertEquals(4, Progress.streak(plan, all4, runDays, weeks.getValue(4).last().epochDay))
        // a missed session in week 2 breaks it
        val missed = done - weeks.getValue(2).first().id
        assertEquals(1, Progress.streak(plan, missed, runDays, today))
        // with no plan, a week with a run counts
        val mondays = listOf(day("2026-09-28"), day("2026-10-05"), day("2026-10-12"))
        assertEquals(3, Progress.streak(null, emptySet(), mondays.map { it + 2 }, day("2026-10-19")))
        assertEquals(0, Progress.streak(null, emptySet(), emptyList(), day("2026-10-19")))
    }

    @Test fun milestonesForRacersAndBeginners() {
        val runs = (0 until 12).map { RunSummary(day("2026-09-01") + it * 3, if (it == 5) 10500.0 else 5000.0, 1800.0) }
        val ms = Progress.milestones(runs, plan, emptySet()).map { it.title }
        assertTrue("Your first run" in ms, ms.toString())
        assertTrue("First 5 km run" in ms && "First 10 km run" in ms, ms.toString())
        assertTrue("50 km in total" in ms && "10 runs" in ms, ms.toString())
        assertTrue("100 km in total" !in ms, ms.toString())
        assertEquals("100 km in total: 35 km to go", Progress.nextMilestone(runs, plan))

        val bp = PlanBuilder.build(beginner)
        val bRuns = listOf(
            RunSummary(day("2026-10-21"), 3000.0, 1800.0, "d1", listOf(walk(300.0), run(60.0), walk(90.0), walk(300.0))),
            RunSummary(day("2026-11-20"), 4000.0, 2100.0, "d2", listOf(walk(300.0), run(600.0), walk(180.0), run(600.0), walk(300.0))),
        )
        val bms = Progress.milestones(bRuns, bp, emptySet()).map { it.title }
        assertTrue("Ran 10 minutes without stopping" in bms, bms.toString())
        assertTrue(bms.none { it.contains("km") }, bms.toString())
        assertEquals("Run 20 minutes without stopping", Progress.nextMilestone(bRuns, bp))
        assertEquals(600.0, Progress.longestNonStop(bRuns)!!.first)
        // free runs don't count as non-stop running
        assertEquals(null, Progress.longestNonStop(listOf(RunSummary(1, 5000.0, 1800.0, null, listOf(run(1800.0))))))
        // finishing every session
        val all = bp.workouts.map { it.id }.toSet()
        assertTrue("Finished the Couch to 5K plan" in Progress.milestones(bRuns, bp, all).map { it.title })
    }

    @Test fun paceCheckSuggestsQuickerOrEasierPaces() {
        val p = plan.paces
        val today = day("2026-11-20")
        fun session(d: Long, pace: Double) = RunSummary(d, 8000.0, 2400.0, "d$d", listOf(easy(600.0, 360.0), rep(Zone.T, pace), rep(Zone.T, pace), rep(Zone.T, pace)))
        val t = p.threshold

        assertEquals(PaceVerdict.NOT_ENOUGH, Progress.paceCheck(listOf(session(today - 2, t)), profile, p, today).verdict)
        val onTarget = Progress.paceCheck(listOf(session(today - 9, t), session(today - 2, t + 2)), profile, p, today)
        assertEquals(PaceVerdict.ON_TARGET, onTarget.verdict)
        assertEquals(null, onTarget.suggestedSec)

        val fast = Progress.paceCheck(listOf(session(today - 9, t - 12), session(today - 2, t - 14)), profile, p, today)
        assertEquals(PaceVerdict.AHEAD, fast.verdict)
        assertTrue(fast.suggestedSec!! < profile.recentSec && fast.suggestedSec!! >= profile.recentSec * 0.97, fast.toString())
        val quicker = profile.copy(recentSec = fast.suggestedSec!!).paces
        assertTrue(Progress.paceShift(p, quicker) < 0)

        val slow = Progress.paceCheck(listOf(session(today - 9, t + 15), session(today - 2, t + 20)), profile, p, today)
        assertEquals(PaceVerdict.BEHIND, slow.verdict)
        assertTrue(slow.suggestedSec!! > profile.recentSec && slow.suggestedSec!! <= profile.recentSec * 1.03, slow.toString())

        // one fast and one slow session: no change suggested
        assertEquals(PaceVerdict.ON_TARGET, Progress.paceCheck(listOf(session(today - 9, t - 14), session(today - 2, t + 20)), profile, p, today).verdict)
        // older than 6 weeks doesn't count
        assertEquals(PaceVerdict.NOT_ENOUGH, Progress.paceCheck(listOf(session(today - 60, t - 14), session(today - 50, t - 14)), profile, p, today).verdict)
        // after the paces are updated, the same runs are judged against the new paces
        val after = Progress.paceCheck(listOf(session(today - 9, t - 12), session(today - 2, t - 14)), profile, quicker, today)
        assertTrue(after.verdict != PaceVerdict.AHEAD || after.ratio > fast.ratio, after.toString())
    }

    @Test fun easyTrendIsWeeklyAndCalm() {
        val today = day("2026-11-22")
        val monday = PlanBuilder.mondayOf(today)
        // a week at 6:30 then weeks at 6:10
        val runs = (0 until 6).map { k ->
            RunSummary(monday - 7L * (5 - k) + 1, 6000.0, 2400.0, "x", listOf(easy(1800.0, if (k == 0) 390.0 else 370.0), rep(Zone.T, 300.0)))
        }
        val t = Progress.easyTrend(runs, today)
        assertEquals(6, t.size)
        assertEquals(390.0, t.first().second, 0.5)
        // the second week averages with the first, so the line eases down rather than dropping
        assertTrue(t[1].second in 371.0..389.0, t.toString())
        assertEquals(370.0, t.last().second, 0.5)
        val line = Progress.easyTrendLine(t, "October")!!
        assertTrue(line.startsWith("Your easy running is 20 s/km quicker than in October"), line)
        assertEquals(null, Progress.easyTrendLine(t.take(2), "October"))
        // short easy bits and non-easy steps are ignored
        assertTrue(Progress.easyTrend(listOf(RunSummary(today, 1000.0, 300.0, "x", listOf(easy(200.0, 360.0)))), today).isEmpty())
    }

    @Test fun weekSummaryInPlainWords() {
        val weeks = plan.byWeek()
        val w3 = weeks.getValue(3)
        val today = w3.last().epochDay + 1   // Monday of week 4: nothing run yet, so last week is summarised
        val runs = w3.map { w ->
            RunSummary(w.epochDay, 8000.0, 2700.0, w.id, listOf(easy(900.0, 370.0), rep(Zone.T, plan.paces.threshold), rep(Zone.T, plan.paces.threshold + 30)))
        } + weeks.getValue(2).map { RunSummary(it.epochDay, 6000.0, 2100.0, it.id) }
        val done = w3.map { it.id }.toSet()
        val s = Progress.weekSummary(plan, done, runs, today)!!
        assertTrue(!s.current)
        val text = s.lines.joinToString(" ")
        assertTrue(text.startsWith("All ${w3.size} sessions done."), text)
        assertTrue("%.1f km in".format(w3.size * 8.0) in text, text)
        assertTrue("more than the week before" in text, text)
        assertTrue("${w3.size} of ${w3.size * 2} paced efforts were on or ahead of target." in text, text)
        assertTrue("Easy running stayed easy." in text, text)

        // this week, part done: no comparison yet
        val w4 = weeks.getValue(4)
        val now = Progress.weekSummary(plan, done + w4.first().id, runs + RunSummary(w4.first().epochDay, 5000.0, 1800.0, w4.first().id), w4.first().epochDay)!!
        assertTrue(now.current)
        assertEquals("1 of ${w4.size} sessions done so far.", now.lines.first())
        assertTrue(now.lines.none { "week before" in it }, now.lines.toString())

        // beginners hear time, not km or pace
        val bp = PlanBuilder.build(beginner)
        val b1 = bp.workouts.take(2)
        val bRuns = b1.map { RunSummary(it.epochDay, 3000.0, 1800.0, it.id, listOf(walk(300.0), run(90.0), walk(120.0), walk(300.0))) }
        val bs = Progress.weekSummary(bp, b1.map { it.id }.toSet(), bRuns, b1.last().epochDay)!!
        val bt = bs.lines.joinToString(" ")
        assertTrue(bs.lines.none { "km" in it || "pace" in it }, bt)
        assertTrue("1 h of running and walking over 2 runs." in bt, bt)
        assertTrue("Longest run without stopping: 2 min." in bt, bt)
    }

    @Test fun nextBeginnerSessionAndItsLongestRun() {
        val bp = PlanBuilder.build(beginner)
        val next = Progress.nextSession(bp, setOf(bp.workouts[0].id), bp.workouts[0].epochDay)!!
        assertEquals(bp.workouts[1].id, next.id)
        assertEquals(60.0, Progress.longestRunStep(bp.workouts[0]))
        assertEquals(1800.0, Progress.longestRunStep(bp.workouts.last()))
    }
}
