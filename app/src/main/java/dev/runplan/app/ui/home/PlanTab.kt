package dev.runplan.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.runplan.app.RunPlanApp
import dev.runplan.app.run.RunPhase
import dev.runplan.app.run.RunSession
import dev.runplan.app.ui.components.WorkoutShape
import dev.runplan.app.ui.components.bars
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Fmt
import dev.runplan.core.Plan
import dev.runplan.core.Workout
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val longDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val dow = DateTimeFormatter.ofPattern("EEE", Locale.UK)

@Composable
fun PlanTab(modifier: Modifier, openSession: (String) -> Unit, startRun: (String?) -> Unit, resumeRun: () -> Unit) {
    val session by RunSession.view.collectAsStateWithLifecycle()
    val runActive = session.phase in setOf(RunPhase.READY, RunPhase.RUNNING, RunPhase.PAUSED)
    val plan by RunPlanApp.store.plan.collectAsStateWithLifecycle()
    val state by RunPlanApp.store.state.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now().toEpochDay() }
    val p = plan
    if (p == null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("This plan can't be built with its current dates. Edit it in Settings.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val byWeek = remember(p) { p.byWeek() }
    val currentWeek = remember(p, today) { p.workouts.firstOrNull { it.epochDay >= today }?.week ?: p.weeks }
    var showEarlier by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize().statusBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        if (runActive) item {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = resumeRun).background(Palette.Signal).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Run in progress", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.weight(1f))
                Text("Return", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White)
            }
        }
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
                val weeksLeft = ((p.profile.raceEpochDay - today + 6) / 7).coerceAtLeast(0)
                Text(
                    if (today > p.profile.raceEpochDay) "${p.profile.goal.label} plan complete"
                    else "${p.profile.goal.label} in $weeksLeft ${if (weeksLeft == 1L) "week" else "weeks"}",
                    style = MaterialTheme.typography.titleSmall, color = Palette.Slate,
                )
                Text(LocalDate.ofEpochDay(today).format(longDate), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            TodayBlock(p, today, state.done, openSession, startRun)
        }
        for (week in currentWeek..p.weeks) {
            val ws = byWeek[week] ?: continue
            item(key = "h$week") { WeekHeader(p, week, ws) }
            items(ws, key = { it.id }) { w -> SessionRow(p, w, w.id in state.done, w.epochDay == today) { openSession(w.id) } }
        }
        if (currentWeek > 1) {
            item {
                TextButton(onClick = { showEarlier = !showEarlier }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(if (showEarlier) "Hide earlier weeks" else "Show earlier weeks")
                }
            }
            if (showEarlier) for (week in 1 until currentWeek) {
                val ws = byWeek[week] ?: continue
                item(key = "h$week") { WeekHeader(p, week, ws) }
                items(ws, key = { it.id }) { w -> SessionRow(p, w, w.id in state.done, false) { openSession(w.id) } }
            }
        }
    }
}

@Composable
private fun TodayBlock(p: Plan, today: Long, done: Set<String>, openSession: (String) -> Unit, startRun: (String?) -> Unit) {
    val w = p.on(today)
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(20.dp),
    ) {
        if (w == null) {
            val next = p.workouts.firstOrNull { it.epochDay > today }
            Text("Rest day", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(4.dp))
            Text(
                next?.let { "Next: ${it.type.label} on ${it.date.format(dow)}" } ?: "No more sessions in this plan.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Slate,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { startRun(null) }, shape = RoundedCornerShape(12.dp)) { Text("Go for a free run") }
            return@Column
        }
        val isDone = w.id in done
        val (km, min) = w.estimate(p.paces)
        Text(w.type.label, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(Fmt.summary(w), style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
        Spacer(Modifier.height(20.dp))
        WorkoutShape(bars(w, p.paces), height = 64.dp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Stat("%.1f".format(km), "km")
            if (min < 90) Stat("${min.roundToInt()}", "min")
            else Stat("${min.roundToInt() / 60}:${(min.roundToInt() % 60).toString().padStart(2, '0')}", "h")
            if (w.note.isNotEmpty()) Text(w.note, style = MaterialTheme.typography.bodySmall, color = Palette.Slate, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isDone) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.Good)
                Spacer(Modifier.width(6.dp))
                Text("Done", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { openSession(w.id) }) { Text("Details") }
            } else {
                Button(onClick = { startRun(w.id) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("Start run")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { openSession(w.id) }) { Text("Details") }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Stat(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(4.dp))
        Text(unit, style = MaterialTheme.typography.bodySmall, color = Palette.Slate, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun WeekHeader(p: Plan, week: Int, ws: List<Workout>) {
    val km = ws.sumOf { it.estimate(p.paces).first }
    val min = ws.sumOf { it.estimate(p.paces).second }
    Column {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
            Text("Week $week", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            Text(ws.first().phase.label, style = MaterialTheme.typography.bodyMedium, color = Palette.Slate, modifier = Modifier.weight(1f).padding(bottom = 2.dp))
            Text("${km.roundToInt()} km, ${Fmt.estimate(min * 60)}", style = MaterialTheme.typography.labelMedium, color = Palette.Slate, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Palette.Line)
    }
}

@Composable
private fun SessionRow(p: Plan, w: Workout, done: Boolean, isToday: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (isToday) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(40.dp)) {
            Text(w.date.format(dow), style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
            Text("${w.date.dayOfMonth}", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp).alpha(if (done) 0.55f else 1f)) {
            Text(w.type.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(Fmt.summary(w), style = MaterialTheme.typography.bodySmall, color = Palette.Slate, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = "Done", tint = Palette.Good, modifier = Modifier.size(22.dp))
            } else {
                WorkoutShape(bars(w, p.paces), height = 22.dp, gap = 1.dp)
            }
            Spacer(Modifier.height(4.dp))
            Text(Fmt.estimate(w.estimate(p.paces).second * 60), style = MaterialTheme.typography.labelMedium, color = Palette.Slate)
        }
    }
}
