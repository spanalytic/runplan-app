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

    @Test fun odometerIgnoresMultipathDrift() {
        // steady walk at 1.33 m/s (12:32/km) with GPS noise and a 90 m position wander, like a real recording
        val rnd = java.util.Random(7)
        val o = Odometer()
        val mPerDegLon = 111_195.0 * kotlin.math.cos(Math.toRadians(53.27))
        var x = 0.0
        val paces = ArrayList<Double>()
        for (i in 1..600) {
            x += 1.33
            var north = 2.0 * rnd.nextGaussian()
            if (i in 300..324) north += (i - 300) * 3.75
            val sp = (1.33 + 0.1 * rnd.nextGaussian()).toFloat()
            o.add(TrackPoint(i * 1000L, 53.27 + north / 111_195.0, -1.26 + (x + 2.0 * rnd.nextGaussian()) / mPerDegLon, 0.0, 6f, sp, 0.3f))
            if (i > 30) paces += o.pace(i * 1000L)
        }
        val truth = 1000 / 1.33
        // a 90 m wander in 24 s at walking pace is extreme; keep pace within ~16%
        assertTrue(paces.all { kotlin.math.abs(it - truth) < 120 }, "pace stayed within 120 s of truth")
        assertEquals(1.0, o.distanceM / 798.0, 0.03)
    }

    @Test fun bestEffortsFindFastestStretch() {
        // 1 km at 6:00/km then 5 km at 5:00/km: best 5K should be the 25:00 stretch, not from the start
        val o = Odometer()
        val mLon = 111_195.0 * kotlin.math.cos(Math.toRadians(53.27))
        var x = 0.0
        for (i in 1..1860) {
            val v = if (i <= 360) 1000 / 360.0 else 1000 / 300.0
            x += v
            o.add(TrackPoint(i * 1000L, 53.27, -1.26 + x / mLon, 50.0, 5f, v.toFloat(), 0.2f))
        }
        val e = Analytics.bestEfforts(o.points).associateBy { it.label }
        assertEquals(1500.0, e.getValue("5K").sec, 5.0)
        assertEquals(300.0, e.getValue("1 km").sec, 3.0)
        assertEquals("5K", Analytics.fitness(e.values.toList())?.label)
    }

    @Test fun weeklyBucketsAreMondayBased() {
        val today = LocalDate.parse("2026-10-14").toEpochDay()   // a Wednesday
        val w = Analytics.weekly(listOf(RunStat(today, 5000.0, 1500.0), RunStat(today - 9, 3000.0, 900.0)), today, 2)
        assertEquals(LocalDate.parse("2026-10-12").toEpochDay(), w.last().startEpochDay)
        assertEquals(5000.0, w.last().metres)
        assertEquals(3000.0, w.first().metres)
    }

    @Test fun cuesSpeakNextStepAndLastLap() {
        val plan = PlanBuilder.build(profile)
        val w = plan.workouts.first { it.type == SessionType.STRIDES }
        val eng = RunEngine("t", RunStep.flatten(w, plan.paces))
        val spoken = ArrayList<String>()
        eng.start().forEach { spoken += it.speech() }
        var t = 0L
        var d = 0.0
        while (!eng.finished && t < 3_600_000) {
            t += 1000
            val pace = if (eng.current.hasTarget) (eng.current.fast + eng.current.slow) / 2 else plan.paces.jog
            d += 1000 / pace
            eng.update(t, d, pace).forEach { spoken += it.speech() }
        }
        assertTrue(spoken.any { it.startsWith("10 seconds left. Next: Rep 1 of") }, spoken.joinToString("\n"))
        assertTrue(spoken.any { it.startsWith("Go! Rep 2 of") && it.contains("Last lap: 2 minutes") })
        assertTrue(spoken.any { it.startsWith("Halfway. Pace so far") })
        assertTrue(spoken.none { it.contains(" to ") && it.contains("per kilometre") && it.startsWith("Go!") }, "targets are spoken as one pace")
    }
}
