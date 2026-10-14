package dev.runplan.core

import java.time.LocalDate

enum class StepKind { WARMUP, INTERVAL, RECOVERY, COOLDOWN }

/**
 * One step. Exactly one of [secs]/[metres] is set, or neither for an open-ended step.
 * [hill] steps are run uphill by effort (no pace target); their recoveries go back down.
 */
data class Step(
    val kind: StepKind,
    val secs: Double? = null,
    val metres: Double? = null,
    val zone: Zone? = null,
    val hill: Boolean = false,
) {
    /** Zone used to colour and size the step in a workout shape: hill efforts show as hard. */
    val shapeZone: Zone? get() = zone ?: if (hill && kind == StepKind.INTERVAL) Zone.I else null
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

enum class Phase(val label: String) { BASE("Base"), BUILD("Build"), PEAK("Peak"), TAPER("Taper"), RACE("Race") }

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
}

data class Workout(
    val epochDay: Long,
    val week: Int,
    val phase: Phase,
    val type: SessionType,
    val blocks: List<Block>,
    val note: String = "",
) {
    val id: String get() = "d$epochDay"
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
            val pace = s.zone?.let { p.centre(it) } ?: p.jog
            when {
                s.metres != null -> { km += s.metres / 1000; min += s.metres / 1000 * pace / 60 }
                s.secs != null -> { min += s.secs / 60; km += s.secs / pace }
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
) {
    val paces: Paces get() = Paces.of(recentDistance, recentSec, goal.distance)
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
