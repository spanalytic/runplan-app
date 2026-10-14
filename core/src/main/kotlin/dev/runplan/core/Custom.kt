package dev.runplan.core

/** How much of something to run: [value] seconds, or metres when [byDistance]. */
data class Amount(val value: Double, val byDistance: Boolean) {
    fun step(kind: StepKind, zone: Zone?) =
        if (byDistance) Step(kind, metres = value, zone = zone) else Step(kind, secs = value, zone = zone)

    /** The next ([dir] = 1) or previous ([dir] = -1) choice on the stepper, staying within its list. */
    fun nudge(dir: Int): Amount {
        val list = if (byDistance) DISTANCES else TIMES
        val i = list.indexOfFirst { it >= value - 0.5 }.let { if (it < 0) list.lastIndex else it }
        // a value between two choices moves to the neighbour in that direction
        val exact = list[i] <= value + 0.5
        val j = when {
            dir > 0 -> if (exact) i + 1 else i
            else -> i - 1
        }
        return copy(value = list[j.coerceIn(0, list.lastIndex)])
    }

    /** Switch between time and distance, keeping roughly the same effort length at [pace] s/km. */
    fun toggle(pace: Double): Amount {
        val list = if (byDistance) TIMES else DISTANCES
        val target = if (byDistance) value / 1000 * pace else value / pace * 1000
        return Amount(list.minBy { kotlin.math.abs(it - target) }, !byDistance)
    }

    fun encode(): String = (if (byDistance) "m" else "t") + value.toLong()

    companion object {
        val TIMES: List<Double> = listOf(
            10, 15, 20, 30, 45, 60, 75, 90, 120, 150, 180, 240, 300, 360, 420, 480, 540, 600, 720, 900, 1200,
            1500, 1800, 2100, 2400, 2700, 3000, 3600, 4500, 5400, 6300, 7200, 9000, 10800,
        ).map { it.toDouble() }
        val DISTANCES: List<Double> = listOf(
            100, 150, 200, 300, 400, 500, 600, 800, 1000, 1200, 1500, 1600, 2000, 2500, 3000, 4000, 5000,
            6000, 7000, 8000, 9000, 10000, 12000, 14000, 15000, 16000, 18000, 20000, 21100, 25000, 30000, 35000, 42200,
        ).map { it.toDouble() }

        fun minutes(m: Int) = Amount(m * 60.0, false)
        fun metres(m: Int) = Amount(m.toDouble(), true)

        fun decode(s: String): Amount? {
            if (s.length < 2) return null
            val v = s.substring(1).toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            return when (s[0]) {
                't' -> Amount(v, false)
                'm' -> Amount(v, true)
                else -> null
            }
        }
    }
}

/**
 * A workout the runner builds themselves: optional warm-up, [reps] of [work] (at [zone], or no
 * target) with [recovery] jogs between them, and an optional cool-down. One rep is a continuous run.
 * It gets the same coaching as a planned session but isn't part of the plan, so it ticks nothing off.
 */
data class CustomWorkout(
    val warmup: Amount? = Amount.minutes(10),
    val reps: Int = 5,
    val work: Amount = Amount.minutes(3),
    val zone: Zone? = Zone.I,
    val recovery: Amount? = Amount.minutes(2),
    val cooldown: Amount? = Amount.minutes(10),
) {
    fun toWorkout(epochDay: Long): Workout {
        val blocks = ArrayList<Block>()
        warmup?.let { blocks += Single(it.step(StepKind.WARMUP, Zone.E)) }
        val rep = work.step(StepKind.INTERVAL, zone)
        blocks += when {
            reps <= 1 -> Single(rep)
            recovery == null -> Repeat(reps, listOf(rep))
            else -> Repeat(reps, listOf(rep, recovery.step(StepKind.RECOVERY, null)))
        }
        cooldown?.let { blocks += Single(it.step(StepKind.COOLDOWN, Zone.E)) }
        return Workout(epochDay, 0, Phase.BASE, SessionType.CUSTOM, blocks, note = title)
    }

    /** "5 × 3 min hard", "5 km tempo", "30 min run". */
    val title: String get() {
        val amt = Fmt.amount(work.step(StepKind.INTERVAL, null))
        val what = zone?.word ?: "run"
        return if (reps > 1) "$reps × $amt $what" else "$amt $what"
    }

    /** Passed to the run service as the workout id: "custom:w=t600;r=5;k=t180;z=I;c=t120;d=t600". */
    val id: String get() = PREFIX + listOf(
        "w=" + (warmup?.encode() ?: "-"),
        "r=$reps",
        "k=" + work.encode(),
        "z=" + (zone?.code ?: "-"),
        "c=" + (recovery?.encode() ?: "-"),
        "d=" + (cooldown?.encode() ?: "-"),
    ).joinToString(";")

    companion object {
        const val PREFIX = "custom:"
        const val MAX_REPS = 40

        fun isCustom(id: String?) = id?.startsWith(PREFIX) == true

        /** Reads an [id]; null if it isn't a custom workout or is damaged. */
        fun fromId(id: String?): CustomWorkout? {
            if (!isCustom(id)) return null
            val f = id!!.removePrefix(PREFIX).split(';').mapNotNull { kv ->
                kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            fun opt(k: String): Amount? = f[k]?.takeIf { it != "-" }?.let { Amount.decode(it) }
            val work = f["k"]?.let { Amount.decode(it) } ?: return null
            return CustomWorkout(
                warmup = opt("w"),
                reps = f["r"]?.toIntOrNull()?.coerceIn(1, MAX_REPS) ?: return null,
                work = work,
                zone = f["z"]?.let { z -> Zone.entries.firstOrNull { it.code == z } },
                recovery = opt("c"),
                cooldown = opt("d"),
            )
        }
    }
}
