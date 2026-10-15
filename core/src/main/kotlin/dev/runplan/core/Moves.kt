package dev.runplan.core

/**
 * Sessions the user has moved to another day of the same week, stored as workout id -> day.
 * The plan is still built from the profile; moves are laid on top, and any that no longer fit
 * (a different week, the race, or a clash) are ignored rather than breaking the plan.
 */
object Moves {

    /**
     * Days [w] can go to: its own Monday to Sunday, never the race day or after it, never before the plan starts.
     * A beginner plan has no race: its "race day" is the Sunday it ends, which is free to use.
     */
    fun choices(plan: Plan, w: Workout): List<Long> {
        if (w.type == SessionType.RACE) return emptyList()
        val monday = PlanBuilder.mondayOf(w.planDay)
        val last = plan.profile.raceEpochDay - if (plan.profile.beginner) 0 else 1
        return (monday until monday + 7).filter { it >= plan.profile.startEpochDay && it <= last }
    }

    /** [plan] with [moves] applied, sorted by day. */
    fun apply(plan: Plan, moves: Map<String, Long>): Plan {
        if (moves.isEmpty()) return plan
        val base = plan.workouts
        val wanted = base.associate { w ->
            val to = moves[w.id]?.takeIf { it in choices(plan, w) }
            w.id to (to ?: w.planDay)
        }
        // Two sessions on one day: drop the moves involved and keep the planned days.
        val clash = wanted.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val workouts = base.map { w ->
            val day = wanted.getValue(w.id)
            if (day in clash || day == w.planDay) w.copy(epochDay = w.planDay) else w.copy(epochDay = day)
        }
        // A dropped move can free a day that another move now clashes with, so check once more.
        val counts = workouts.groupingBy { it.epochDay }.eachCount()
        val safe = if (counts.values.any { it > 1 }) base else workouts
        return plan.copy(workouts = safe.sortedBy { it.epochDay })
    }

    /**
     * New moves after putting [w] (from a plan with moves applied) on [to]. A session already on that
     * day swaps onto [w]'s current day. Moving a session back to its planned day clears the entry.
     */
    fun move(plan: Plan, moves: Map<String, Long>, w: Workout, to: Long): Map<String, Long> {
        if (to !in choices(plan, w) || to == w.epochDay) return moves
        val out = moves.toMutableMap()
        fun put(x: Workout, day: Long) { if (day == x.planDay) out.remove(x.id) else out[x.id] = day }
        plan.on(to)?.let { other -> put(other, w.epochDay) }
        put(w, to)
        // drop entries for sessions no longer in the plan
        val ids = plan.workouts.map { it.id }.toSet()
        return out.filterKeys { it in ids }
    }
}
