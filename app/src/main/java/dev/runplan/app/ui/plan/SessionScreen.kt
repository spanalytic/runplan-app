package dev.runplan.app.ui.plan

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.runplan.app.RunPlanApp
import dev.runplan.app.ui.components.WorkoutShape
import dev.runplan.app.ui.components.bars
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Fmt
import dev.runplan.core.Paces
import dev.runplan.core.Repeat
import dev.runplan.core.Single
import dev.runplan.core.Step
import dev.runplan.core.StepKind
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val date = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

@Composable
fun SessionScreen(id: String, onBack: () -> Unit, startRun: (String?) -> Unit) {
    val plan by RunPlanApp.store.plan.collectAsStateWithLifecycle()
    val state by RunPlanApp.store.state.collectAsStateWithLifecycle()
    val w = plan?.find(id)
    val p = plan?.paces
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        if (w == null || p == null) {
            Text("This session is no longer in your plan.", Modifier.padding(24.dp))
            return@Column
        }
        val done = w.id in state.done
        val (km, min) = w.estimate(p)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("${w.date.format(date)}, week ${w.week}, ${w.phase.label.lowercase()}", style = MaterialTheme.typography.titleSmall, color = Palette.Slate)
            Text(w.type.label, style = MaterialTheme.typography.displayMedium)
            if (w.note.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(w.note, style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
            }
            Spacer(Modifier.height(24.dp))
            WorkoutShape(bars(w, p), height = 96.dp)
            Spacer(Modifier.height(16.dp))
            Text("Takes about ${Fmt.estimate(min * 60)}, %.1f km".format(km), style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Palette.Line)
            w.blocks.forEach { b ->
                when (b) {
                    is Single -> StepLine(b.step, p, indent = false)
                    is Repeat -> {
                        Text("Repeat ${b.times} times", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                        b.steps.forEach { StepLine(it, p, indent = true) }
                        HorizontalDivider(color = Palette.Line)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { RunPlanApp.store.setDone(w.id, !done) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(52.dp),
            ) { Text(if (done) "Mark not done" else "Mark done") }
            Button(
                onClick = { startRun(w.id) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Start run") }
        }
    }
}

@Composable
private fun StepLine(s: Step, p: Paces, indent: Boolean) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 6.dp, height = 36.dp).clip(RoundedCornerShape(3.dp)).background(Palette.zone(s.zone)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val name = when (s.kind) {
                    StepKind.WARMUP -> "Warm-up"
                    StepKind.COOLDOWN -> "Cool-down"
                    StepKind.RECOVERY -> "Recovery jog"
                    StepKind.INTERVAL -> s.zone?.label ?: "Run"
                }
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(Fmt.amount(s), style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
            }
            s.zone?.let { z ->
                val (f, sl) = p.band(z)
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.band(f, sl), style = MaterialTheme.typography.headlineSmall)
                    Text("per km", style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
                }
            }
        }
        if (!indent) HorizontalDivider(color = Palette.Line)
    }
}
