package dev.runplan.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.runplan.app.ui.components.rememberToday
import dev.runplan.app.RunPlanApp
import dev.runplan.app.data.Exports
import dev.runplan.app.data.RunRecord
import dev.runplan.app.ui.components.ColumnChart
import dev.runplan.app.ui.components.LineChart
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Analytics
import dev.runplan.core.Distance
import dev.runplan.core.Effort
import dev.runplan.core.Fmt
import dev.runplan.core.PaceVerdict
import dev.runplan.core.Plan
import dev.runplan.core.Progress
import dev.runplan.core.RunSummary
import dev.runplan.core.RunStat
import dev.runplan.core.Vdot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val shortDay = DateTimeFormatter.ofPattern("d/M", Locale.UK)
private val monthName = DateTimeFormatter.ofPattern("MMM", Locale.UK)
private val weekDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/** Effort label -> the Distance used for plan paces. 1 km is too short to predict race times from. */
private val effortDistance = mapOf(
    "1 mile" to Distance.MILE, "5K" to Distance.K5, "10K" to Distance.K10,
    "Half" to Distance.HALF, "Marathon" to Distance.MARATHON,
)

private data class Dated(val effort: Effort, val run: RunRecord)

@Composable
fun ProgressTab(modifier: Modifier, openRun: (String) -> Unit) {
    val ctx = LocalContext.current
    val state by RunPlanApp.store.state.collectAsStateWithLifecycle()
    val plan by RunPlanApp.store.plan.collectAsStateWithLifecycle()
    val today = rememberToday()
    val runs = state.runs

    val beginner = plan?.profile?.beginner == true
    val summaries = remember(runs) { runs.map { RunSummary(it.epochDay, it.metres, it.sec, it.workoutId, it.steps) } }

    Column(modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Progress", style = MaterialTheme.typography.headlineMedium)
        plan?.let { PlanCard(it, state.done, summaries, today) }
        if (runs.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                if (beginner) "Your weekly time, streak and milestones appear here once you've saved a run."
                else "Your fitness, weekly distance and best efforts appear here once you've saved a run.",
                style = MaterialTheme.typography.bodyLarge, color = Palette.Slate,
            )
            return@Column
        }

        WeekCard(plan, state.done, summaries, today)
        Streak(plan, state.done, summaries, today)
        if (beginner) {
            BeginnerTotals(summaries, today)
            Volume(runs, plan, today, time = true)
        } else {
            Totals(runs, today)
            plan?.let { PaceCheckSection(it, summaries, today) }
            Fitness(runs, plan, today)
            EasyTrend(summaries, today)
            Volume(runs, plan, today, time = false)
            Records(runs, openRun)
        }

        Spacer(Modifier.height(24.dp))
        Row {
            TextButton(onClick = { Exports.weeklyCsv(ctx, runs, plan) }) { Text("Export weekly CSV") }
            if (!beginner) TextButton(onClick = { Exports.effortsCsv(ctx, runs) }) { Text("Export best efforts CSV") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
        content()
    }
}

/** A thin bar filled to [fraction]. */
@Composable
private fun Bar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Line)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Signal))
    }
}

private fun daysWord(n: Long) = if (n == 1L) "1 day" else "$n days"

@Composable
private fun PlanCard(plan: Plan, done: Set<String>, runs: List<RunSummary>, today: Long) {
    val s = remember(plan, done, today) { Progress.planStatus(plan, done, today) }
    val beginner = plan.profile.beginner
    Card {
        when {
            s.finished -> Text("Plan finished", style = MaterialTheme.typography.headlineSmall)
            s.week == 0 -> Text(if (s.startsIn <= 1) "Your plan starts tomorrow" else "Your plan starts in ${daysWord(s.startsIn)}", style = MaterialTheme.typography.headlineSmall)
            else -> Text("Week ${s.week} of ${s.weeks}", style = MaterialTheme.typography.headlineSmall)
        }
        if (!s.finished && s.week > 0) {
            val left = s.daysLeft
            val text = when {
                beginner && left >= 14 -> "${left / 7} weeks to go"
                beginner -> "${daysWord(left)} to go"
                left >= 14 -> "${left / 7} weeks to race day"
                left > 1 -> "${daysWord(left)} to race day"
                left == 1L -> "Race day is tomorrow"
                else -> "Race day is today"
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
        }
        Spacer(Modifier.height(12.dp))
        Bar(if (s.total == 0) 0f else s.done.toFloat() / s.total)
        Spacer(Modifier.height(6.dp))
        Text(
            "${s.done} of ${s.total} sessions done" + if (s.weekTotal > 0 && !s.finished) ", ${s.weekDone} of ${s.weekTotal} this week" else "",
            style = MaterialTheme.typography.labelMedium, color = Palette.Slate,
        )
        if (beginner) {
            val best = remember(runs) { Progress.longestNonStop(runs)?.first ?: 0.0 }
            Spacer(Modifier.height(16.dp))
            Text("Longest run without stopping", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Bar((best / 1800).toFloat())
            Spacer(Modifier.height(6.dp))
            Text(
                if (best <= 0) "Building to 30 minutes" else "${(best / 60).roundToInt()} of 30 minutes",
                style = MaterialTheme.typography.labelMedium, color = Palette.Slate,
            )
        }
        Progress.nextSession(plan, done, today)?.let { w ->
            Spacer(Modifier.height(14.dp))
            val whenText = if (w.epochDay == today) "Today" else if (w.epochDay == today + 1) "Tomorrow" else w.date.format(weekDay)
            Text("Next: $whenText, ${w.type.label.lowercase()}", style = MaterialTheme.typography.titleSmall)
            Text(Fmt.summary(w), style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
        }
    }
}

@Composable
private fun WeekCard(plan: Plan?, done: Set<String>, runs: List<RunSummary>, today: Long) {
    val s = remember(plan, done, runs, today) { Progress.weekSummary(plan, done, runs, today) } ?: return
    Card {
        Text(if (s.current) "This week" else "Last week", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        s.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Streak(plan: Plan?, done: Set<String>, runs: List<RunSummary>, today: Long) {
    val weeks = remember(plan, done, runs, today) { Progress.streak(plan, done, runs.map { it.epochDay }, today) }
    val reached = remember(plan, done, runs) { Progress.milestones(runs, plan, done) }
    val next = remember(plan, runs) { Progress.nextMilestone(runs, plan) }
    Section("Streak", if (plan != null) "Weeks in a row with every planned session done" else "Weeks in a row with at least one run")
    if (weeks > 0) {
        Text(if (weeks == 1) "1 week" else "$weeks weeks", style = MaterialTheme.typography.displaySmall)
    } else {
        Text(
            if (plan != null) "Finish this week's sessions to start a streak." else "Run this week to start a streak.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Slate,
        )
    }
    if (reached.isEmpty()) return
    Spacer(Modifier.height(20.dp))
    Text("Milestones", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = Palette.Line)
    reached.takeLast(4).reversed().forEach { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(LocalDate.ofEpochDay(m.epochDay).format(dayMonth), style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
        }
        HorizontalDivider(color = Palette.Line)
    }
    if (next != null) {
        Spacer(Modifier.height(8.dp))
        Text("Next: $next", style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
    }
}

@Composable
private fun BeginnerTotals(runs: List<RunSummary>, today: Long) {
    val monday = Analytics.mondayOf(today)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Stat("${(runs.filter { it.epochDay >= monday }.sumOf { it.sec } / 60).roundToInt()}", "minutes this week")
        Stat("${runs.size}", if (runs.size == 1) "run" else "runs")
    }
}

@Composable
private fun PaceCheckSection(plan: Plan, runs: List<RunSummary>, today: Long) {
    val profile = plan.profile
    val check = remember(runs, plan, today) { Progress.paceCheck(runs, profile, plan.paces, today) }
    Section("Pace check", "From your last few tempo and interval sessions")
    val n = check.sessions
    when (check.verdict) {
        PaceVerdict.NOT_ENOUGH -> Text(
            "After a couple of tempo or interval sessions, you'll see here whether your paces suit you.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Slate,
        )
        PaceVerdict.ON_TARGET -> {
            Text("On target", style = MaterialTheme.typography.titleMedium)
            Text("Your last $n paced sessions were close to their targets. Your paces suit you.", style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
        }
        PaceVerdict.AHEAD, PaceVerdict.BEHIND -> {
            val ahead = check.verdict == PaceVerdict.AHEAD
            val newSec = check.suggestedSec ?: return
            val newPaces = remember(newSec, profile) { profile.copy(recentSec = newSec).paces }
            Text(if (ahead) "Faster than your plan" else "Finding the paces hard?", style = MaterialTheme.typography.titleMedium)
            Text(
                (if (ahead) "Your last $n paced sessions were quicker than their targets, so your paces can move on."
                else "Your last $n paced sessions were slower than their targets. Easing off a little makes these sessions work better, and they'll still build fitness.") +
                    " Tempo would go from ${Fmt.pace(plan.paces.threshold)} to ${Fmt.pace(newPaces.threshold)} per km.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Slate,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { RunPlanApp.store.setProfile(profile.copy(recentSec = newSec)) },
                shape = RoundedCornerShape(12.dp),
            ) { Text(if (ahead) "Make my paces quicker" else "Ease my paces") }
        }
    }
}

@Composable
private fun EasyTrend(runs: List<RunSummary>, today: Long) {
    val trend = remember(runs, today) { Progress.easyTrend(runs, today) }
    Section("Easy runs", "Your average easy pace, each week taken with the two before it")
    if (trend.size < 2) {
        Text("After a few weeks of easy runs from your plan, you'll see here whether your easy pace is getting quicker.", style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
        return
    }
    Progress.easyTrendLine(trend, LocalDate.ofEpochDay(trend.first().first).format(dayMonth))?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
        Spacer(Modifier.height(12.dp))
    }
    val series = trend.map { (d, p) -> d.toDouble() to p }
    LineChart(
        data = series,
        height = 140.dp,
        colorAt = { Palette.Good },
        dots = true,
        invertY = true,
        minSpan = 60.0,   // at least 1 min/km top to bottom, so small changes look small
        yFormat = { Fmt.pace(it) },
        xFormat = { LocalDate.ofEpochDay(it.toLong()).format(dayMonth) },
        inspect = { i -> "Week of ${LocalDate.ofEpochDay(series[i].first.toLong()).format(dayMonth)}: ${Fmt.pace(series[i].second)}/km" },
    )
}

@Composable
private fun Section(title: String, sub: String? = null) {
    Spacer(Modifier.height(32.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall)
    if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Totals(runs: List<RunRecord>, today: Long) {
    val monday = Analytics.mondayOf(today)
    val yearStart = LocalDate.ofEpochDay(today).withDayOfYear(1).toEpochDay()
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Stat("%.1f".format(runs.filter { it.epochDay >= monday }.sumOf { it.metres } / 1000), "km this week")
        Stat("%.0f".format(runs.filter { it.epochDay >= yearStart }.sumOf { it.metres } / 1000), "km this year")
        Stat("${runs.size}", if (runs.size == 1) "run" else "runs")
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.displaySmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
    }
}

@Composable
private fun Fitness(runs: List<RunRecord>, plan: Plan?, today: Long) {
    // fitness is tracked internally as VDOT but always shown as a predicted race time
    val goal = plan?.profile?.goal?.distance ?: Distance.K5
    val goalName = plan?.profile?.goal?.label?.lowercase()?.replace("5k", "5K")?.replace("10k", "10K") ?: "5K"
    fun predicted(v: Double) = Vdot.predictTime(v, goal.metres)

    val points = remember(runs) {
        runs.mapNotNull { r -> Analytics.fitness(r.efforts.orEmpty())?.let { Dated(it, r) } }.sortedBy { it.run.epochDay }
    }
    Section("Fitness", "Your predicted $goalName time, from your best effort of a mile or more in the last 6 weeks")
    val recent = points.filter { it.run.epochDay > today - 42 }.maxByOrNull { it.effort.vdot }
    // a beginner plan's paces are a placeholder, not something to compare with
    val planScore = plan?.takeIf { !it.profile.beginner }?.paces?.vdot
    if (recent == null) {
        Text(
            "Run something hard over a mile or more, like a race, time trial or parkrun, and your predicted race time shows here.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Slate,
        )
    } else {
        Text(Fmt.duration(predicted(recent.effort.vdot)), style = MaterialTheme.typography.displayMedium)
        Text(
            "Based on your ${recent.effort.label} in ${Fmt.duration(recent.effort.sec)} on ${LocalDate.ofEpochDay(recent.run.epochDay).format(dayMonth)}." +
                (planScore?.let { " Your plan's paces are set for ${Fmt.duration(predicted(it))}." } ?: ""),
            style = MaterialTheme.typography.bodyMedium, color = Palette.Slate,
        )
        val dist = effortDistance[recent.effort.label]
        val profile = plan?.profile
        if (planScore != null && profile != null && dist != null && recent.effort.vdot > planScore + 0.5) {
            Spacer(Modifier.height(12.dp))
            Text("You're running faster than your plan expects.", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { RunPlanApp.store.setProfile(profile.copy(recentDistance = dist, recentSec = recent.effort.sec)) },
                shape = RoundedCornerShape(12.dp),
            ) { Text("Update my paces") }
        }
    }

    // rolling best, sampled weekly since the first estimate
    if (points.size >= 2) {
        val first = Analytics.mondayOf(points.first().run.epochDay)
        val days = generateSequence(first + 6) { it + 7 }.takeWhile { it <= today + 6 }.toList()
        val series = remember(points, today, goal) {
            Analytics.rollingBest(points.map { it.run.epochDay to it.effort.vdot }, days)
                .map { (d, v) -> d.toDouble() to predicted(v) }
        }
        if (series.size >= 2) {
            Spacer(Modifier.height(16.dp))
            LineChart(
                data = series,
                height = 140.dp,
                colorAt = { Palette.Signal },
                dots = series.size <= 26,
                invertY = true,
                yTicks = listOf(10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0),
                yFormat = { Fmt.duration(it) },
                xFormat = { LocalDate.ofEpochDay(it.toLong()).format(dayMonth) },
                inspect = { i -> "Week to ${LocalDate.ofEpochDay(series[i].first.toLong()).format(dayMonth)}: ${Fmt.duration(series[i].second)}" },
            )
        }
    }
}

@Composable
private fun Volume(runs: List<RunRecord>, plan: Plan?, today: Long, time: Boolean) {
    var monthly by rememberSaveable { mutableStateOf(false) }
    // beginners see time, not km
    fun amount(p: dev.runplan.core.Period) = if (time) p.sec / 60 else p.metres / 1000
    fun amountText(p: dev.runplan.core.Period) = if (time) "${(p.sec / 60).roundToInt()} min" else "%.1f km".format(p.metres / 1000)
    Section(if (time) "Time" else "Distance", if (time) "Minutes of running and walking" else null)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !monthly, onClick = { monthly = false }, label = { Text("Weekly") })
        FilterChip(selected = monthly, onClick = { monthly = true }, label = { Text("Monthly") })
    }
    Spacer(Modifier.height(10.dp))
    val stats = remember(runs) { runs.map { RunStat(it.epochDay, it.metres, it.sec) } }
    if (!monthly) {
        val periods = remember(stats, today) { Analytics.weekly(stats, today, 12) }
        val planned = remember(plan, periods) {
            periods.map { p ->
                plan?.let { pl ->
                    pl.workouts.filter { it.epochDay in p.startEpochDay until p.startEpochDay + 7 }
                        .takeIf { it.isNotEmpty() }?.sumOf { w -> w.estimate(pl.paces).let { if (time) it.second else it.first } }
                }
            }
        }
        ColumnChart(
            values = periods.map { amount(it) },
            labels = periods.map { LocalDate.ofEpochDay(it.startEpochDay).format(shortDay) },
            planned = planned,
            yFormat = { "%.0f".format(it) },
            inspect = { i ->
                val p = periods[i]
                "Week of ${LocalDate.ofEpochDay(p.startEpochDay).format(dayMonth)}: ${amountText(p)}" +
                    (planned[i]?.let { " of %.0f${if (time) " min" else ""} planned".format(it) } ?: "") +
                    ", ${p.runs} ${if (p.runs == 1) "run" else "runs"}" + if (time) "" else ", ${Fmt.duration(p.sec)}"
            },
        )
    } else {
        val periods = remember(stats, today) { Analytics.monthly(stats, today, 12) }
        ColumnChart(
            values = periods.map { amount(it) },
            labels = periods.map { LocalDate.ofEpochDay(it.startEpochDay).format(monthName) },
            yFormat = { "%.0f".format(it) },
            inspect = { i ->
                val p = periods[i]
                "${LocalDate.ofEpochDay(p.startEpochDay).let { "${it.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.year}" }}: " +
                    "${amountText(p)}, ${p.runs} ${if (p.runs == 1) "run" else "runs"}" + if (time) "" else ", ${Fmt.duration(p.sec)}"
            },
        )
    }
}

@Composable
private fun Records(runs: List<RunRecord>, openRun: (String) -> Unit) {
    val bests = remember(runs) {
        runs.flatMap { r -> r.efforts.orEmpty().map { Dated(it, r) } }
            .groupBy { it.effort.label }
            .mapValues { (_, xs) -> xs.minWith(compareBy<Dated> { it.effort.sec }.thenBy { it.run.startMs }) }
    }
    if (bests.isEmpty()) return
    Section("Best efforts", "Fastest stretch of each distance inside any run")
    HorizontalDivider(color = Palette.Line)
    Analytics.EFFORTS.mapNotNull { (label, _) -> bests[label] }.forEach { d ->
        Row(
            Modifier.fillMaxWidth().clickable { openRun(d.run.id) }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(d.effort.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(84.dp))
            Column(Modifier.weight(1f)) {
                Text(Fmt.duration(d.effort.sec), style = MaterialTheme.typography.headlineSmall)
                Text("${Fmt.pace(d.effort.pace)}/km", style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
            }
            Text(LocalDate.ofEpochDay(d.run.epochDay).format(dayMonth), style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
        }
        HorizontalDivider(color = Palette.Line)
    }
}

