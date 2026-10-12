package dev.runplan.app.ui.run

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.runplan.app.RunPlanApp
import dev.runplan.app.data.Exports
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Fmt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val date = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

@Composable
fun SummaryScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by RunPlanApp.store.state.collectAsStateWithLifecycle()
    val r = state.runs.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        if (r == null) {
            Text("This run was deleted.", Modifier.padding(24.dp))
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(LocalDate.ofEpochDay(r.epochDay).format(date), style = MaterialTheme.typography.titleSmall, color = Palette.Slate)
            Text(r.title, style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Big(Fmt.km(r.metres), "km")
                Big(Fmt.duration(r.sec), "time")
                Big(Fmt.pace(r.pace), "avg /km")
            }

            val targeted = r.steps.mapNotNull { it.hit }
            if (targeted.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("${targeted.count { it }} of ${targeted.size} paced steps on target", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(24.dp))
            Text("Steps", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Palette.Line)
            r.steps.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 6.dp, height = 32.dp).clip(RoundedCornerShape(3.dp)).background(Palette.zone(s.zoneCode)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${Fmt.duration(s.sec)}, ${Fmt.km(s.metres)} km" + if (s.fast.isFinite()) ", target ${Fmt.band(s.fast, s.slow)}" else "",
                            style = MaterialTheme.typography.bodySmall, color = Palette.Slate,
                        )
                    }
                    Text(Fmt.pace(s.pace), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(10.dp))
                    val hit = s.hit
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(
                            when (hit) { true -> Palette.Good; false -> Palette.Amber; null -> MaterialTheme.colorScheme.background }
                        ),
                    )
                }
                HorizontalDivider(color = Palette.Line)
            }

            if (r.splits.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Kilometre splits", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                r.splits.forEachIndexed { i, sec ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = Palette.Slate, modifier = Modifier.width(40.dp))
                        Text(Fmt.pace(sec), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete run", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { Exports.gpx(ctx, r, RunPlanApp.store.readTrack(r.id)) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(52.dp)) {
                Text("GPX")
            }
            OutlinedButton(onClick = { Exports.stepsCsv(ctx, r) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(52.dp)) {
                Text("CSV")
            }
            Button(onClick = onBack, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(52.dp)) { Text("Done") }
        }
    }

    if (confirmDelete && r != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this run?") },
            text = { Text("Its GPS track and step results will be removed.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; RunPlanApp.store.deleteRun(r.id); onBack() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Big(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.displaySmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
    }
}
