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
            val pace = if (eng.current.hasTarget) eng.current.typical else plan.paces.jog
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
            val pace = if (eng.current.hasTarget) eng.current.typical else plan.paces.jog
            d += 1000 / pace
            eng.update(t, d, pace).forEach { spoken += it.speech() }
        }
        assertTrue(spoken.any { it.startsWith("10 seconds left. Next: Rep 1 of") }, spoken.joinToString("\n"))
        assertTrue(spoken.any { it.startsWith("Go! Rep 2 of") && it.contains("Last lap: 2 minutes") })
        assertTrue(spoken.any { it.startsWith("Halfway. Pace so far") })
        assertTrue(spoken.none { it.contains(" to ") && it.contains("per kilometre") && it.startsWith("Go!") }, "targets are spoken as one pace")
    }

    // ---- easy ceiling, end-of-workout and finish cues ------------------------------------

    /** Paces whose easy band is 7:20-8:13 (marathon pace 6:43). */
    private val easyPaces = Paces.fromVdot(30.8156, Distance.HALF)

    private fun easyRun(metres: Double = 4500.0) = Workout(0, 1, Phase.BASE, SessionType.EASY, listOf(Single(Step(StepKind.INTERVAL, metres = metres, zone = Zone.E))))

    /** Run the engine at a constant pace; returns (elapsed ms, cue) pairs. */
    private fun simulate(eng: RunEngine, pace: (RunEngine) -> Double): List<Pair<Long, Cue>> {
        val out = ArrayList<Pair<Long, Cue>>()
        eng.start().forEach { out += 0L to it }
        var t = 0L
        var d = 0.0
        while (!eng.finished && t < 4 * 3_600_000) {
            t += 1000
            val p = pace(eng)
            d += 1000 / p
            eng.update(t, d, p).forEach { out += t to it }
        }
        return out
    }

    @Test fun easyIsACeilingOnly() {
        val st = RunStep.flatten(easyRun(), easyPaces).single()
        assertTrue(st.capOnly)
        assertEquals("6:43", Fmt.pace(st.fast))
        assertEquals("4.5 km @ 6:43 or slower", st.describe())
        assertEquals("7:47", Fmt.pace(st.typical))   // still used to estimate duration
        // 7:11/km (today's run) and much slower are both fine: no nagging, step counts as on target
        for (pace in listOf(431.0, 560.0)) {
            val eng = RunEngine("Easy run", listOf(st))
            val cues = simulate(eng) { pace }
            assertTrue(cues.none { it.second is Cue.PaceAlert }, "no alerts at $pace")
            assertEquals(true, eng.results.single().hit)
            assertEquals(true, eng.results.single().onOrAhead)
        }
    }

    @Test fun easyAlertsOnlyWhenWellPastTheLimitAndBackOff() {
        val st = RunStep.flatten(easyRun(8000.0), easyPaces).single()
        val eng = RunEngine("Easy run", listOf(st))
        val alerts = simulate(eng) { 380.0 }.filter { it.second is Cue.PaceAlert }.map { it.first / 1000 }
        assertTrue(alerts.size >= 4, alerts.toString())
        val gaps = alerts.zipWithNext { a, b -> b - a }
        assertEquals(listOf(60L, 120L, 180L), gaps.take(3))
        assertEquals(false, eng.results.single().hit)
        assertTrue(simulate(RunEngine("x", listOf(st))) { 380.0 }.first { it.second is Cue.PaceAlert }.second.speech().startsWith("Ease off."))
    }

    @Test fun storedEasyResultKeepsItsCeiling() {
        // slow is +Inf in memory but read back from storage as NaN
        val r = StepResult("Easy", "E", 403.0, Double.NaN, 1938.0, 4500.0)
        assertTrue(r.capOnly)
        assertEquals(true, r.hit)
        assertEquals(false, r.copy(sec = 1600.0).hit)
        assertEquals("6:43 or slower", Fmt.band(r.fast, r.slow))
    }

    @Test fun lastStepAnnouncesEndOfWorkout() {
        val eng = RunEngine("Easy run", RunStep.flatten(easyRun(), easyPaces))
        val spoken = simulate(eng) { 431.0 }.map { it.second.speech() }
        assertTrue(spoken.first().startsWith("Let's go. Easy: 4.5 kilometres, no faster than 6 43 per kilometre"), spoken.first())
        assertTrue("200 metres left in the workout." in spoken, spoken.joinToString("\n"))
        assertTrue(spoken.none { it.startsWith("200 metres to go") })

        // a workout ending in a timed cool-down: one minute warning names the workout
        val plan = PlanBuilder.build(profile)
        val w = plan.workouts.first { it.type == SessionType.INTERVALS }
        val e2 = RunEngine("t", RunStep.flatten(w, plan.paces))
        val s2 = simulate(e2) { if (it.current.hasTarget) it.current.typical else plan.paces.jog }.map { it.second.speech() }
        assertEquals(1, s2.count { it == "One minute left in the workout." }, s2.joinToString("\n"))
    }

    @Test fun shortLastStepGetsNoEndWarning() {
        val w = Workout(0, 1, Phase.BASE, SessionType.STRIDES, listOf(
            Single(Step(StepKind.WARMUP, secs = 600.0, zone = Zone.E)),
            Repeat(2, listOf(Step(StepKind.INTERVAL, secs = 90.0, zone = Zone.R))),
        ))
        val spoken = simulate(RunEngine("t", RunStep.flatten(w, easyPaces))) { if (it.current.hasTarget) it.current.typical else easyPaces.jog }.map { it.second.speech() }
        assertTrue(spoken.none { it.contains("left in the workout") }, spoken.joinToString("\n"))
    }

    @Test fun finishSaysCompleteThenLastLapThenSummary() {
        // single step: no separate last lap, it would repeat the summary
        val one = simulate(RunEngine("Easy run", RunStep.flatten(easyRun(), easyPaces))) { 431.0 }.last().second
        val s1 = one.speech()
        assertTrue(one is Cue.Finished)
        assertTrue(s1.startsWith("Workout complete, good job. Workout summary: 4.5 kilometres in 32 minutes 20 seconds, average 7 11 per kilometre."), s1)
        assertTrue(s1.endsWith("1 of 1 paced step on target."), s1)
        assertTrue("Last lap" !in s1)

        // several steps: complete, then last lap, then summary
        val plan = PlanBuilder.build(profile)
        val w = plan.workouts.first { it.type == SessionType.STRIDES }
        val many = simulate(RunEngine("t", RunStep.flatten(w, plan.paces))) { if (it.current.hasTarget) it.current.typical else plan.paces.jog }.last().second.speech()
        val a = many.indexOf("Workout complete, good job.")
        val b = many.indexOf("Last lap:")
        val c = many.indexOf("Workout summary:")
        assertTrue(a == 0 && b > a && c > b, many)

        // ending early with Finish also gets the wrap-up
        val eng = RunEngine("Easy run", RunStep.flatten(easyRun(), easyPaces))
        eng.start(); eng.update(600_000, 1400.0, 430.0)
        assertTrue(eng.finish().single().speech().startsWith("Workout complete, good job. Workout summary: 1.4 kilometres in 10 minutes"))
    }

    @Test fun climbIgnoresGpsAltitudeJitter() {
        // 5 km at 7:00/km: a 20 m hill up and down in the middle, otherwise flat; GPS altitude
        // reported in whole metres with ±1.5 m jitter and slow drift, like a phone recording
        val rnd = java.util.Random(3)
        val mLon = 111_195.0 * kotlin.math.cos(Math.toRadians(53.3))
        val v = 1000 / 420.0
        val pts = ArrayList<TrackPoint>()
        var x = 0.0
        for (i in 0..2100) {
            val hill = when {
                x in 2000.0..2500.0 -> (x - 2000) / 500 * 20
                x in 2500.0..3000.0 -> (3000 - x) / 500 * 20
                else -> 0.0
            }
            val drift = 2.0 * kotlin.math.sin(i / 300.0)
            val alt = Math.round(100 + hill + drift + 1.5 * rnd.nextGaussian()).toDouble()
            pts += TrackPoint(i * 1000L, 53.3, -1.14 + x / mLon, alt, 4f, v.toFloat(), 0.3f, dist = x)
            x += v
        }
        val up = Analytics.ascent(pts)
        assertTrue(up in 18.0..28.0, "climb $up m")
        // and no hill at all: next to nothing
        val flat = pts.map { it.copy(alt = Math.round(100 + 2.0 * kotlin.math.sin(it.tMs / 300_000.0) + 1.5 * rnd.nextGaussian()).toDouble()) }
        assertTrue(Analytics.ascent(flat) < 6.0, "flat climb ${Analytics.ascent(flat)} m")
    }

    @Test fun climbUsesBarometerWhenRecorded() {
        // same 20 m hill; barometer altitude is steady but offset 40 m by the weather, GPS is noisy
        val rnd = java.util.Random(5)
        val v = 1000 / 420.0
        val pts = (0..2100).map { i ->
            val x = i * v
            val hill = when { x in 2000.0..2500.0 -> (x - 2000) / 500 * 20; x in 2500.0..3000.0 -> (3000 - x) / 500 * 20; else -> 0.0 }
            TrackPoint(i * 1000L, 53.3, -1.14, Math.round(100 + hill + 4 * rnd.nextGaussian()).toDouble(), 4f, dist = x,
                baroAlt = 60 + hill + 0.25 * rnd.nextGaussian())
        }
        assertTrue(Analytics.hasBarometer(pts))
        assertEquals(20.0, Analytics.ascent(pts), 2.0)
        // chart sits on the GPS altitude, not the weather-shifted barometer
        val sm = Analytics.smoothedAltitude(pts)
        assertEquals(100.0, sm[100], 2.0)
        assertTrue(!Analytics.hasBarometer(pts.map { it.copy(baroAlt = Double.NaN) }))
    }

    // ---- hill sessions -------------------------------------------------------------------

    @Test fun hillsOnlyWhenSwitchedOn() {
        val hillTypes = setOf(SessionType.HILL_SPRINTS, SessionType.HILL_REPEATS)
        assertTrue(PlanBuilder.build(profile.copy(terrain = Terrain.HILLY)).workouts.none { it.type in hillTypes })
    }

    @Test fun hillyPlanMatchesPythonRules() {
        // same as: runplan.py --goal half --race-date 2027-01-10 --start 2026-10-19 --days Tue,Wed,Fri,Sun --terrain hilly --hills
        val plan = PlanBuilder.build(profile.copy(terrain = Terrain.HILLY, hills = true))
        val q1 = plan.workouts.filter { PlanBuilder.weekday(it.epochDay) == 1 }.map { it.type }
        val S = SessionType.HILL_SPRINTS; val H = SessionType.HILL_REPEATS; val I = SessionType.INTERVALS
        assertEquals(listOf(S, SessionType.STRIDES, S, I, H, I, H, I, I, H, I, I), q1)
        val sprints = plan.workouts.first { it.type == S }
        assertTrue(sprints.note.startsWith("Find a short, steep hill"))
        assertTrue(plan.workouts.filter { it.phase == Phase.TAPER }.none { it.type == S || it.type == H })
        assertTrue(plan.workouts.any { it.type == SessionType.LONG && it.note.contains("hilly route") })
        // rolling: fewer hill repeats than hilly
        val rolling = PlanBuilder.build(profile.copy(terrain = Terrain.ROLLING, hills = true)).workouts.count { it.type == H }
        assertTrue(rolling < plan.workouts.count { it.type == H }, "rolling $rolling")
    }

    @Test fun hillStepsAreCoachedByEffort() {
        val plan = PlanBuilder.build(profile.copy(terrain = Terrain.HILLY, hills = true))
        for (type in listOf(SessionType.HILL_SPRINTS, SessionType.HILL_REPEATS)) {
            val w = plan.workouts.first { it.type == type }
            val sprints = type == SessionType.HILL_SPRINTS
            val reps = (w.blocks.single { it is Repeat } as Repeat).times
            val eng = RunEngine("t", RunStep.flatten(w, plan.paces))
            val word = if (sprints) "Hill sprint" else "Hill rep"
            assertEquals(listOf("Warm-up", RunStep.TO_HILL, "Get ready", "$word 1 of $reps", "Rest", "Back down", "Get ready"), eng.steps.take(7).map { it.label })
            assertTrue(eng.steps[1].isOpen && eng.steps[5].isOpen)

            // run it, tapping Next 40 s into each open step; hill steps far slower than any pace zone
            val spoken = ArrayList<String>()
            val cues = ArrayList<Cue>()
            eng.start().forEach { cues += it }
            var t = 0L; var d = 0.0; var openSince = -1L
            while (!eng.finished && t < 3_600_000) {
                t += 1000
                val p = if (eng.current.hill) 600.0 else eng.current.typical.takeIf(Double::isFinite) ?: plan.paces.jog
                d += 1000 / p
                cues += eng.update(t, d, p)
                if (eng.current.isOpen) { if (openSince < 0) openSince = t; if (t - openSince >= 40_000) { cues += eng.advance(); openSince = -1 } }
            }
            cues.forEach { spoken += it.speech() }
            val all = spoken.joinToString("\n")
            assertTrue(eng.finished, all)
            assertTrue(cues.none { it is Cue.PaceAlert })
            assertTrue(spoken.any { it.startsWith("Jog to the bottom of your hill and tap Next when you're there. Last lap:") }, all)
            val ready = spoken.indexOfFirst { it.startsWith("Get ready. $word 1 of $reps in 10 seconds:") && it.contains("hard uphill") }
            assertTrue(ready >= 0, all)
            assertEquals(listOf("3", "2", "1", "Go!"), spoken.subList(ready + 1, ready + 5), all)
            val rest = spoken.indexOfFirst { it.startsWith("Rest. Last lap:") }
            assertTrue(rest > ready + 4 && spoken.subList(ready + 5, rest).all { it == "10 seconds left." }, all)
            assertEquals("Jog back down. Tap Next when you're at the bottom.", spoken[rest + 1])
            assertTrue(spoken.any { it == "10 seconds left. Next: To the hill, jog to the bottom of your hill." }, all)
            assertTrue(spoken.none { it.contains("Next: Rest") || it.contains("Run as you like") }, all)
            assertEquals(1, spoken.count { it.startsWith("That was the last one.") }, all)
            assertEquals("H", eng.results.first { it.label.startsWith(word) }.zoneCode)
            assertTrue(eng.results.filter { it.zoneCode == "H" }.all { it.hit == null })
        }
        // reads as a short summary, without the countdowns
        val sp = plan.workouts.first { it.type == SessionType.HILL_SPRINTS }
        assertTrue(Fmt.summary(sp).startsWith("10 min easy, jog to the hill, 6 × (10 s uphill + 10 s rest + jog down), 10 min easy"), Fmt.summary(sp))
    }

    @Test fun movingSessionsWithinTheWeek() {
        val plan = PlanBuilder.build(profile)
        val week = plan.byWeek().getValue(2)
        val tue = week.first { PlanBuilder.weekday(it.epochDay) == 1 }
        val wed = week.first { PlanBuilder.weekday(it.epochDay) == 2 }
        val monday = PlanBuilder.mondayOf(tue.epochDay)

        // onto a rest day: the id stays the same so done flags and runs still match
        var moves = Moves.move(plan, emptyMap(), tue, monday)
        var moved = Moves.apply(plan, moves)
        assertEquals(tue.id, moved.on(monday)?.id)
        assertTrue(moved.on(monday)!!.moved)
        assertEquals(null, moved.on(tue.epochDay))
        assertEquals(moved.workouts.sortedBy { it.epochDay }, moved.workouts)
        assertEquals(plan.workouts.size, moved.workouts.size)

        // onto a day with a session: the two swap
        moves = Moves.move(moved, moves, moved.find(tue.id)!!, wed.epochDay)
        moved = Moves.apply(plan, moves)
        assertEquals(tue.id, moved.on(wed.epochDay)?.id)
        assertEquals(wed.id, moved.on(monday)?.id)

        // back to where the plan put them: no moves left
        moves = Moves.move(moved, moves, moved.find(tue.id)!!, tue.epochDay)
        moves = Moves.move(Moves.apply(plan, moves), moves, Moves.apply(plan, moves).find(wed.id)!!, wed.epochDay)
        assertEquals(emptyMap<String, Long>(), moves)
        assertEquals(plan, Moves.apply(plan, moves))

        // only the same week, never the race day, and the race itself stays put
        assertEquals((monday until monday + 7).toList(), Moves.choices(plan, tue))
        assertEquals(moves, Moves.move(plan, moves, tue, monday + 7))
        val race = plan.workouts.last()
        assertTrue(Moves.choices(plan, race).isEmpty())
        assertTrue(Moves.choices(plan, plan.byWeek().getValue(plan.weeks).first()).all { it < profile.raceEpochDay })

        // stale or clashing moves are ignored rather than doubling up a day
        assertEquals(plan, Moves.apply(plan, mapOf(tue.id to wed.epochDay)))
        assertEquals(plan, Moves.apply(plan, mapOf(tue.id to monday + 14, "d0" to monday)))
    }
}
