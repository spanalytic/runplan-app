package dev.runplan.core

import java.time.LocalDate

/** [READY] is a short countdown before a rep that you start with a tap (hill sessions). */
enum class StepKind { WARMUP, INTERVAL, RECOVERY, COOLDOWN, READY }

/**
 * One step. Exactly one of [secs]/[metres] is set, or neither for an open-ended step.
 * [hill] steps are run uphill by effort (no pace target); their recoveries go back down.
 * [walk] steps are walked (beginner plans): warm-up, cool-down and the breaks between runs.
 */
data class Step(
    val kind: StepKind,
    val secs: Double? = null,
    val metres: Double? = null,
    val zone: Zone? = null,
    val hill: Boolean = false,
    val walk: Boolean = false,
) {
    /** Zone used to colour and size the step in a workout shape: hill efforts show as hard, untargeted runs as easy. */
    val shapeZone: Zone? get() = shapeZoneOf(zone, kind, hill, walk)

    /** Open (ends with a tap). */
    val isOpen: Boolean get() = secs == null && metres == null

    /** Duration for estimates: an open hill step (getting to or back down the hill) counts as a minute. */
    val estSecs: Double? get() = secs ?: if (hill && isOpen) 60.0 else null
}

/** Shape colour for a step: hill efforts show as hard, untargeted running as easy, walking as nothing. */
internal fun shapeZoneOf(zone: Zone?, kind: StepKind, hill: Boolean, walk: Boolean): Zone? = zone ?: when {
    walk || kind != StepKind.INTERVAL -> null
    hill -> Zone.I
    else -> Zone.E
}

/** Brisk walking pace (s/km), for estimates only. */
const val WALK_PACE = 600.0

/** How much running someone has done. Beginners get a run/walk plan with no race time needed. */
enum class Level(val label: String, val help: String) {
    BEGINNER("New to running", "I can't run for 30 minutes without stopping yet"),
    INTERMEDIATE("Regular runner", "I run most weeks and can run 5K or more"),
    ADVANCED("Experienced", "I train for races and run several times a week"),
}

/** How hilly the goal race is. Decides how often hill repeats replace interval sessions. */
enum class Terrain(val label: String, val help: String) {
    FLAT("Flat", "Little or no climbing"),
    ROLLING("Rolling", "Some short climbs"),
    HILLY("Hilly", "Long or steep climbs"),
}

sealed interface Block
data class Single(val step: Step) : Block
data class Repeat(val times: Int, val steps: List<Step>) : Block

enum class Goal(val distance: Distance, val label: String, val peakKmAt5Days: Double, val longCapKm: Double, val taper: List<Double>) {
    K5(Distance.K5, "5K", 40.0, 13.0, listOf(0.65)),
    K10(Distance.K10, "10K", 45.0, 16.0, listOf(0.65)),
    HALF(Distance.HALF, "Half marathon", 52.0, 19.0, listOf(0.75, 0.5)),
    MARATHON(Distance.MARATHON, "Marathon", 68.0, 32.0, listOf(0.8, 0.65, 0.45));

    /** Minimum weeks from the start Monday to race week inclusive. */
    val minWeeks: Int get() = taper.size + 3
}

enum class Phase(val label: String) { BASE("Base"), BUILD("Build"), PEAK("Peak"), TAPER("Taper"), RACE("Race"), START("Start running") }

enum class SessionType(val label: String, val quality: Boolean) {
    STRIDES("Strides", true),
    HILL_SPRINTS("Hill sprints", true),
    HILL_REPEATS("Hill repeats", true),
    INTERVALS("Intervals", true),
    THRESHOLD("Tempo intervals", true),
    TEMPO("Tempo run", true),
    MARATHON_PACE("Marathon pace run", true),
    HALF_PACE("Half-marathon pace run", true),
    LONG("Long run", false),
    LONG_FAST_FINISH("Long run, fast finish", true),
    EASY("Easy run", false),
    RACE("Race day", true),
    FREE("Free run", false),
    /** Beginner plan: runs with walk breaks between them. */
    RUN_WALK("Run and walk", false),
    /** Beginner plan: running without walk breaks. */
    NON_STOP("Non-stop run", false),
    /** Built by the runner (see [CustomWorkout]); not part of the plan. */
    CUSTOM("Your own workout", false),
}

data class Workout(
    val epochDay: Long,
    val week: Int,
    val phase: Phase,
    val type: SessionType,
    val blocks: List<Block>,
    val note: String = "",
    /** The day the plan put it on. Stays put when the session is moved, so the id (and done flags) stay the same. */
    val planDay: Long = epochDay,
) {
    val id: String get() = "d$planDay"
    val moved: Boolean get() = epochDay != planDay
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)

    /** Steps with repeats expanded. */
    fun flatSteps(): List<Step> = blocks.flatMap { b ->
        when (b) {
            is Single -> listOf(b.step)
            is Repeat -> (1..b.times).flatMap { b.steps }
        }
    }

    /** Estimated (km, minutes). */
    fun estimate(p: Paces): Pair<Double, Double> {
        var km = 0.0
        var min = 0.0
        for (s in flatSteps()) {
            val pace = s.zone?.let { p.centre(it) } ?: if (s.walk) WALK_PACE else p.jog
            when {
                s.metres != null -> { km += s.metres / 1000; min += s.metres / 1000 * pace / 60 }
                s.estSecs != null -> { min += s.estSecs!! / 60; km += s.estSecs!! / pace }
            }
        }
        return km to min
    }

    companion object {
        fun free(epochDay: Long) = Workout(
            epochDay, 0, Phase.BASE, SessionType.FREE,
            listOf(Single(Step(StepKind.INTERVAL))),
        )
    }
}

/**
 * Everything the user tells us. The plan is a pure function of this, so this
 * (plus completion flags and history) is all that needs persisting.
 * Days are 0 = Monday .. 6 = Sunday.
 */
data class Profile(
    val recentDistance: Distance,
    val recentSec: Double,
    val goal: Goal,
    val startEpochDay: Long,
    val raceEpochDay: Long,
    val days: List<Int>,
    val currentKm: Double,
    val peakKm: Double? = null,
    val terrain: Terrain = Terrain.FLAT,
    /** Swap some interval and strides sessions for hill sessions. */
    val hills: Boolean = false,
    val level: Level = Level.INTERMEDIATE,
) {
    val paces: Paces get() = Paces.of(recentDistance, recentSec, goal.distance)
    /** A run/walk plan ([StartRunning]): no race, no pace targets, no fitness to set. */
    val beginner: Boolean get() = level == Level.BEGINNER
}

data class Plan(
    val profile: Profile,
    val paces: Paces,
    val workouts: List<Workout>,
    val weeks: Int,
) {
    fun byWeek(): Map<Int, List<Workout>> = workouts.groupBy { it.week }
    fun on(epochDay: Long): Workout? = workouts.firstOrNull { it.epochDay == epochDay }
    fun find(id: String): Workout? = workouts.firstOrNull { it.id == id }
}
