package dev.runplan.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import dev.runplan.app.ui.components.rememberToday
import dev.runplan.app.RunPlanApp
import dev.runplan.app.data.RunRecord
import dev.runplan.app.run.RunPhase
import dev.runplan.app.run.RunSession
import dev.runplan.app.ui.components.WorkoutShape
import dev.runplan.app.ui.components.bars
import dev.runplan.app.ui.plan.MoveDialog
import dev.runplan.app.ui.plan.movedNote
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Fmt
import dev.runplan.core.Moves
import dev.runplan.core.Vdot
import dev.runplan.core.Plan
import dev.runplan.core.Workout
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val longDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val dow = DateTimeFormatter.ofPattern("EEE", Locale.UK)

@Composable
fun PlanTab(modifier: Modifier, openSession: (String) -> Unit, openRun: (String) -> Unit, startRun: (String?) -> Unit, buildWorkout: () -> Unit, resumeRun: () -> Unit) {
    val session by RunSession.view.collectAsStateWithLifecycle()
    val runActive = session.phase in setOf(RunPhase.READY, RunPhase.RUNNING, RunPhase.PAUSED)
    val plan by RunPlanApp.store.plan.collectAsStateWithLifecycle()
    val state by RunPlanApp.store.state.collectAsStateWithLifecycle()
    val today = rememberToday()
    val p = plan
    if (p == null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("This plan can't be built with its current dates. Edit it in Settings.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val byWeek = remember(p) { p.byWeek() }
    // a completed workout opens its recorded run; otherwise the workout details
    val runFor = remember(state.runs) {
        state.runs.filter { it.workoutId != null }.groupBy { it.workoutId!! }.mapValues { (_, rs) -> rs.maxBy { it.startMs } }
    }
    val open: (String) -> Unit = { id -> runFor[id]?.let { openRun(it.id) } ?: openSession(id) }
    // a done session with a recorded run shows what was actually run
    val ran: (Workout) -> RunRecord? = { w -> runFor[w.id]?.takeIf { w.id in state.done } }
    val currentWeek = remember(p, today) { p.workouts.firstOrNull { it.epochDay >= today }?.week ?: p.weeks }
    var showEarlier by remember { mutableStateOf(false) }
    // long-press a session to move it to another day of its week
    var moving by remember { mutableStateOf<String?>(null) }
    val movable: (Workout) -> Boolean = { w -> w.id !in state.done && Moves.choices(p, w).size > 1 }

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
                if (weeksLeft >= 2) {
                    val goalM = p.profile.goal.distance.metres
                    val (lo, hi) = Vdot.projected(p.paces.vdot, weeksLeft.toInt())
                    Text(
                        "Race-day target: ${Fmt.duration(Vdot.roundTime(Vdot.predictTime(hi, goalM), goalM))} to " +
                            Fmt.duration(Vdot.roundTime(Vdot.predictTime(lo, goalM), goalM)),
                        style = MaterialTheme.typography.titleSmall, color = Palette.Slate,
                    )
                }
                Text(LocalDate.ofEpochDay(today).format(longDate), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            TodayBlock(p, today, state.done, p.on(today)?.let(ran), open, startRun, buildWorkout)
        }
        for (week in currentWeek..p.weeks) {
            val ws = byWeek[week] ?: continue
            item(key = "h$week") { WeekHeader(p, week, ws) }
            items(ws, key = { it.id }) { w ->
                SessionRow(p, w, w.id in state.done, ran(w), w.epochDay == today, onLongClick = { moving = w.id }.takeIf { movable(w) }) { open(w.id) }
            }
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
                items(ws, key = { it.id }) { w -> SessionRow(p, w, w.id in state.done, ran(w), false) { open(w.id) } }
            }
        }
    }
    moving?.let { id -> p.find(id)?.let { w -> MoveDialog(p, w, state.done) { moving = null } } }
}

@Composable
private fun TodayBlock(p: Plan, today: Long, done: Set<String>, run: RunRecord?, openSession: (String) -> Unit, startRun: (String?) -> Unit, buildWorkout: () -> Unit) {
    val w = p.on(today)
    // on a planned day, starting asks: today's session, or one you build yourself?
    var choosing by remember { mutableStateOf(false) }
    if (choosing && w != null) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Start a workout") },
            text = {
                Text(
                    "Your plan has a ${w.type.label.lowercase()} today: ${Fmt.summary(w)}. Do that, or build your own?",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { choosing = false; startRun(w.id) }) { Text("Planned workout") } },
            dismissButton = { TextButton(onClick = { choosing = false; buildWorkout() }) { Text("Build my own") } },
        )
    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = buildWorkout, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("Build a workout")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { startRun(null) }) { Text("Free run") }
            }
            return@Column
        }
        val isDone = w.id in done
        val (km, min) = w.estimate(p.paces)
        Text(run?.title ?: w.type.label, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(run?.let { ranNote(it) } ?: Fmt.summary(w), style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
        Spacer(Modifier.height(20.dp))
        WorkoutShape(bars(w, p.paces), height = 64.dp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Stat(if (run != null) Fmt.km(run.metres) else "%.1f".format(km), "km")
            if (run != null) Stat(Fmt.duration(run.sec), "time")
            else if (min < 90) Stat("${min.roundToInt()}", "min")
            else Stat("${min.roundToInt() / 60}:${(min.roundToInt() % 60).toString().padStart(2, '0')}", "h")
            if (w.note.isNotEmpty()) Text(w.note, style = MaterialTheme.typography.bodySmall, color = Palette.Slate, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isDone) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.Good)
                Spacer(Modifier.width(6.dp))
                Text("Done", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { openSession(w.id) }) { Text("View run") }
                TextButton(onClick = buildWorkout) { Text("Build a workout") }
            } else {
                Button(onClick = { choosing = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("Start workout")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { openSession(w.id) }) { Text("Details") }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** "Ran 4.51 km in 32:22". */
private fun ranNote(r: RunRecord) = "Ran ${Fmt.km(r.metres)} km in ${Fmt.duration(r.sec)}"

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(p: Plan, w: Workout, done: Boolean, run: RunRecord?, isToday: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .background(if (isToday) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(40.dp)) {
            Text(w.date.format(dow), style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
            Text("${w.date.dayOfMonth}", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp).alpha(if (done) 0.55f else 1f)) {
            Text(run?.title ?: w.type.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(run?.let { ranNote(it) } ?: Fmt.summary(w), style = MaterialTheme.typography.bodySmall, color = Palette.Slate, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (w.moved) Text(movedNote(w), style = MaterialTheme.typography.labelSmall, color = Palette.Slate, maxLines = 1)
        }
        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = "Done", tint = Palette.Good, modifier = Modifier.size(22.dp))
            } else {
                WorkoutShape(bars(w, p.paces), height = 22.dp, gap = 1.dp)
            }
            Spacer(Modifier.height(4.dp))
            Text(run?.let { Fmt.duration(it.sec) } ?: Fmt.estimate(w.estimate(p.paces).second * 60), style = MaterialTheme.typography.labelMedium, color = Palette.Slate)
        }
    }
}
