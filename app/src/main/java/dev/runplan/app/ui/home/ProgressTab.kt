package dev.runplan.app.ui.home

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.runplan.core.Plan
import dev.runplan.core.RunStat
import dev.runplan.core.Vdot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val shortDay = DateTimeFormatter.ofPattern("d/M", Locale.UK)
private val monthName = DateTimeFormatter.ofPattern("MMM", Locale.UK)

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
    val today = remember { LocalDate.now().toEpochDay() }
    val runs = state.runs

    Column(modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Progress", style = MaterialTheme.typography.headlineMedium)
        if (runs.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Your fitness, weekly distance and best efforts appear here once you've saved a run.", style = MaterialTheme.typography.bodyLarge, color = Palette.Slate)
            return@Column
        }

        Totals(runs, today)
        Fitness(runs, plan, today)
        Volume(runs, plan, today)
        Records(runs, openRun)

        Spacer(Modifier.height(24.dp))
        Row {
            TextButton(onClick = { Exports.weeklyCsv(ctx, runs, plan) }) { Text("Export weekly CSV") }
            TextButton(onClick = { Exports.effortsCsv(ctx, runs) }) { Text("Export best efforts CSV") }
        }
        Spacer(Modifier.height(24.dp))
    }
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
    val planScore = plan?.paces?.vdot
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
private fun Volume(runs: List<RunRecord>, plan: Plan?, today: Long) {
    var monthly by rememberSaveable { mutableStateOf(false) }
    Section("Distance")
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
                        .takeIf { it.isNotEmpty() }?.sumOf { it.estimate(pl.paces).first }
                }
            }
        }
        ColumnChart(
            values = periods.map { it.metres / 1000 },
            labels = periods.map { LocalDate.ofEpochDay(it.startEpochDay).format(shortDay) },
            planned = planned,
            yFormat = { "%.0f".format(it) },
            inspect = { i ->
                val p = periods[i]
                "Week of ${LocalDate.ofEpochDay(p.startEpochDay).format(dayMonth)}: %.1f km".format(p.metres / 1000) +
                    (planned[i]?.let { " of %.0f planned".format(it) } ?: "") +
                    ", ${p.runs} ${if (p.runs == 1) "run" else "runs"}, ${Fmt.duration(p.sec)}"
            },
        )
    } else {
        val periods = remember(stats, today) { Analytics.monthly(stats, today, 12) }
        ColumnChart(
            values = periods.map { it.metres / 1000 },
            labels = periods.map { LocalDate.ofEpochDay(it.startEpochDay).format(monthName) },
            yFormat = { "%.0f".format(it) },
            inspect = { i ->
                val p = periods[i]
                "${LocalDate.ofEpochDay(p.startEpochDay).let { "${it.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.year}" }}: " +
                    "%.1f km, ${p.runs} ${if (p.runs == 1) "run" else "runs"}, ${Fmt.duration(p.sec)}".format(p.metres / 1000)
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

