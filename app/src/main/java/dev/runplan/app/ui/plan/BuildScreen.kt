package dev.runplan.app.ui.plan

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.runplan.app.RunPlanApp
import dev.runplan.app.ui.components.WorkoutShape
import dev.runplan.app.ui.components.bars
import dev.runplan.app.ui.components.rememberToday
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Amount
import dev.runplan.core.CustomWorkout
import dev.runplan.core.Fmt
import dev.runplan.core.Paces
import dev.runplan.core.StepKind
import dev.runplan.core.Zone

/** The last workout built, so the builder opens on it next time (while the app is running). */
object LastBuilt {
    var workout = CustomWorkout()
}

/** Build your own workout: warm-up, repeats of a run and a recovery jog, cool-down. */
@Composable
fun BuildScreen(onBack: () -> Unit, startRun: (String?) -> Unit) {
    val plan by RunPlanApp.store.plan.collectAsStateWithLifecycle()
    val state by RunPlanApp.store.state.collectAsStateWithLifecycle()
    val p = plan?.paces ?: state.profile?.paces
    val today = rememberToday()
    var c by remember { mutableStateOf(LastBuilt.workout) }
    val w = remember(c, today) { c.toWorkout(today) }
    // pace used to swap a time for a similar distance and back
    val easy = p?.centre(Zone.E) ?: 400.0
    val workPace = p?.let { pc -> c.zone?.let { pc.centre(it) } ?: pc.centre(Zone.E) } ?: 400.0

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Build a workout", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(4.dp))
            Text(Fmt.summary(w), style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
            if (p != null) {
                Spacer(Modifier.height(16.dp))
                WorkoutShape(bars(w, p), height = 64.dp)
                Spacer(Modifier.height(8.dp))
                val (km, min) = w.estimate(p)
                Text("Takes about ${Fmt.estimate(min * 60)}, %.1f km".format(km), style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
            }

            Heading("Warm-up", "Easy running before the main part.")
            AmountPicker(c.warmup, Amount.minutes(10), easy) { c = c.copy(warmup = it) }

            Heading("Main part", if (c.reps > 1) "Repeat a run and a recovery jog." else "One continuous run, or add repeats for intervals.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Repeats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Stepper(
                    text = if (c.reps == 1) "Once" else "${c.reps} times",
                    canDown = c.reps > 1, canUp = c.reps < CustomWorkout.MAX_REPS,
                    onDown = { c = c.copy(reps = c.reps - 1) }, onUp = { c = c.copy(reps = c.reps + 1) },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(if (c.reps > 1) "Each run" else "Run", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            AmountPicker(c.work, null, workPace) { a -> if (a != null) { c = c.copy(work = a) } }
            Spacer(Modifier.height(12.dp))
            Text("Pace", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            ZoneChoice(c.zone, p) { c = c.copy(zone = it) }
            if (c.reps > 1) {
                Spacer(Modifier.height(12.dp))
                Text("Recovery jog after each run", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                AmountPicker(c.recovery, Amount.minutes(2), easy) { c = c.copy(recovery = it) }
            }

            Heading("Cool-down", "Easy running to finish.")
            AmountPicker(c.cooldown, Amount.minutes(10), easy) { c = c.copy(cooldown = it) }
            Spacer(Modifier.height(24.dp))
        }
        Button(
            onClick = { LastBuilt.workout = c; startRun(c.id) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(20.dp).height(52.dp),
        ) { Text("Start workout") }
    }
}

@Composable
private fun Heading(title: String, help: String) {
    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = Palette.Line)
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(help, style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
    Spacer(Modifier.height(10.dp))
}

/**
 * Time or distance with a stepper. With a [fallback] the part can also be switched off ("None"),
 * and [fallback] is what it comes back as.
 */
@Composable
private fun AmountPicker(a: Amount?, fallback: Amount?, pace: Double, onChange: (Amount?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fallback != null) Chip("None", a == null) { onChange(null) }
        Chip("Time", a != null && !a.byDistance) { onChange(asKind(a ?: fallback, byDistance = false, pace)) }
        Chip("Distance", a != null && a.byDistance) { onChange(asKind(a ?: fallback, byDistance = true, pace)) }
    }
    if (a != null) {
        Spacer(Modifier.height(8.dp))
        val list = if (a.byDistance) Amount.DISTANCES else Amount.TIMES
        Stepper(
            text = Fmt.amount(a.step(StepKind.INTERVAL, null)),
            canDown = a.value > list.first(), canUp = a.value < list.last(),
            onDown = { onChange(a.nudge(-1)) }, onUp = { onChange(a.nudge(1)) },
        )
    }
}

/** [a] as a time or a distance, converting at [pace] if it's the other kind. */
private fun asKind(a: Amount?, byDistance: Boolean, pace: Double): Amount? =
    if (a == null || a.byDistance == byDistance) a else a.toggle(pace)

@Composable
private fun Stepper(text: String, canDown: Boolean, canUp: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onDown, enabled = canDown, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Box(Modifier.width(110.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleLarge)
        }
        OutlinedButton(onClick = onUp, enabled = canUp, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Palette.Ink else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

/** No target, or one of the training paces with the runner's own pace band beside it. */
@Composable
private fun ZoneChoice(zone: Zone?, p: Paces?, onChange: (Zone?) -> Unit) {
    val options = listOf<Zone?>(null) + Zone.entries
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)) {
        options.forEach { z ->
            val selected = z == zone
            Row(
                Modifier.fillMaxWidth().clickable { onChange(z) }
                    .background(if (selected) Palette.Ink else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                Box(Modifier.size(width = 6.dp, height = 28.dp).clip(RoundedCornerShape(3.dp)).background(Palette.zone(z)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(z?.label ?: "No pace target", style = MaterialTheme.typography.titleMedium, color = ink)
                    Text(z?.use ?: "Run by feel.",
                        style = MaterialTheme.typography.bodySmall, color = if (selected) Color.White.copy(alpha = 0.75f) else Palette.Slate)
                }
                if (z != null && p != null) {
                    val (f, s) = p.target(z)
                    Text(Fmt.band(f, s), style = MaterialTheme.typography.titleSmall, color = ink, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
