package dev.runplan.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreTest {
    private fun day(s: String) = LocalDate.parse(s).toEpochDay()

    private val profile = Profile(
        recentDistance = Distance.K10, recentSec = 55 * 60.0, goal = Goal.HALF,
        startEpochDay = day("2026-10-19"), raceEpochDay = day("2027-01-10"),
        days = listOf(1, 2, 4, 6), currentKm = 25.0,
    )

    @Test fun vdot50MatchesDanielsTable() {
        val p = Paces.fromVdot(50.0, Distance.K10)
        assertEquals("4:15", Fmt.pace(p.threshold))
        assertEquals("3:55", Fmt.pace(p.interval))
        assertEquals("3:40", Fmt.pace(p.rep))
        assertEquals("4:31", Fmt.pace(p.marathon))
        assertEquals(50.0, Vdot.vdot(5000.0, 19 * 60 + 57.0), 0.1)
    }

    @Test fun planShape() {
        val plan = PlanBuilder.build(profile)
        assertEquals(12, plan.weeks)
        assertEquals(SessionType.RACE, plan.workouts.last().type)
        assertEquals(day("2027-01-10"), plan.workouts.last().epochDay)
        // cut-back week drops the second quality session
        assertTrue(plan.byWeek().getValue(4).count { it.type.quality } == 1)
        // nothing hard in the two days before the race
        assertTrue(plan.workouts.none { (profile.raceEpochDay - it.epochDay) in 1..2 && it.type != SessionType.EASY })
    }

    @Test fun everyGoalAndDayCountBuilds() {
        for (g in Goal.entries) for (n in 3..6) {
            val p = PlanBuilder.build(profile.copy(goal = g, days = (1..n).toList(), raceEpochDay = profile.startEpochDay + 7 * 16 + 6))
            assertEquals(SessionType.RACE, p.workouts.last().type, "$g $n")
            assertTrue(p.workouts.all { it.blocks.isNotEmpty() })
        }
    }

    @Test fun tooShortThrows() {
        assertFailsWith<PlanException> { PlanBuilder.build(profile.copy(raceEpochDay = profile.startEpochDay + 14)) }
    }

    @Test fun engineRunsIntervalSession() {
        val plan = PlanBuilder.build(profile)
        val w = plan.workouts.first { it.type == SessionType.INTERVALS && it.week == 5 }
        val eng = RunEngine("t", RunStep.flatten(w, plan.paces))
        eng.start()
        var t = 0L
        var d = 0.0
        val cues = ArrayList<Cue>()
        while (!eng.finished && t < 3_600_000) {
            t += 1000
            val pace = if (eng.current.hasTarget) (eng.current.fast + eng.current.slow) / 2 else plan.paces.jog
            d += 1000 / pace
            cues += eng.update(t, d, pace)
        }
        assertTrue(eng.finished)
        assertEquals(eng.steps.size, eng.results.size)
        assertTrue(cues.none { it is Cue.PaceAlert })
        assertTrue(eng.results.filter { it.label.startsWith("Rep") }.all { it.hit == true })
    }

    @Test fun stateRoundTrips() {
        val s = RunState.IDLE.copy(title = "Intervals", pace = 301.0, fast = 300.0, slow = 310.0, status = PaceStatus.IN_BAND)
        val r = RunState.decode(s.encode())!!
        assertEquals(s.title, r.title)
        assertEquals(s.pace, r.pace)
        assertEquals(s.status, r.status)
    }
}
