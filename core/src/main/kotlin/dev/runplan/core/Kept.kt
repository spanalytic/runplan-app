package dev.runplan.core

import kotlin.math.abs

/**
 * Sessions already done keep the workout they were done as. The plan is rebuilt from the profile
 * whenever it is edited, so without this an edit (say, adding hills) would rewrite finished sessions.
 * Kept workouts are laid on the built plan before any moves.
 */
object Kept {

    /**
     * [plan] (as built, before moves) with each of [kept] in place of what the plan now has on its day.
     * Kept sessions outside the plan's dates, or the race, are left out.
     */
    fun apply(plan: Plan, kept: Collection<Workout>): Plan {
        val start = plan.profile.startEpochDay
        val race = plan.profile.raceEpochDay
        val week0 = PlanBuilder.mondayOf(start)
        val fit = kept.filter { it.type != SessionType.RACE && it.planDay in start until race && it.epochDay in start until race }
        if (fit.isEmpty()) return plan
        val taken = fit.flatMap { listOf(it.planDay, it.epochDay) }.toSet()
        val phase = plan.byWeek().mapValues { it.value.first().phase }
        val placed = fit.map { w ->
            val week = ((PlanBuilder.mondayOf(w.planDay) - week0) / 7 + 1).toInt()
            w.copy(week = week, phase = phase[week] ?: w.phase, epochDay = w.planDay)
        }
        val rest = plan.workouts.filter { it.planDay !in taken }
        return plan.copy(workouts = (rest + placed).sortedBy { it.epochDay })
    }

    /**
     * For saves from before sessions were kept: kept workouts for done sessions [ids]. Picks the plan
     * they were most likely done from: the current one, or a plan built with the hill settings changed
     * (the usual edit) when that matches the recorded runs better. A plan matches when every run's
     * title is its session's name; among those, the current plan wins unless another is over 1 km
     * closer on distance. [runs] maps workout id to the (title, metres) of a run recorded for it.
     */
    fun recover(profile: Profile, moves: Map<String, Long>, ids: Set<String>, runs: Map<String, Pair<String, Double>>): Map<String, Workout> {
        if (ids.isEmpty()) return emptyMap()
        val ran = runs.filterKeys { it in ids }
        fun shown(p: Profile): Plan? = runCatching { Moves.apply(PlanBuilder.build(p), moves) }.getOrNull()
        fun matches(plan: Plan) = ran.all { (id, r) -> plan.find(id)?.type?.label == r.first }
        fun kmOff(plan: Plan) = ran.entries.sumOf { (id, r) -> abs(plan.find(id)!!.estimate(plan.paces).first - r.second / 1000) }
        val current = shown(profile)
        val other = listOf(false, true).flatMap { h -> Terrain.entries.map { t -> profile.copy(hills = h, terrain = t) } }
            .filter { it != profile }.mapNotNull { shown(it) }.filter { matches(it) }.minByOrNull { kmOff(it) }
        val pick = when {
            current == null -> other
            other == null -> current
            !matches(current) || kmOff(other) < kmOff(current) - 1.0 -> other
            else -> current
        } ?: return emptyMap()
        return ids.mapNotNull { id -> pick.find(id)?.let { id to it } }.toMap()
    }
}
