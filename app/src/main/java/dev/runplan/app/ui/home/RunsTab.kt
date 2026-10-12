package dev.runplan.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import dev.runplan.app.ui.components.RouteThumb
import dev.runplan.app.ui.components.downsample
import dev.runplan.core.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

@Composable
fun RunsTab(modifier: Modifier, openRun: (String) -> Unit) {
    val ctx = LocalContext.current
    val state by RunPlanApp.store.state.collectAsStateWithLifecycle()
    val runs = state.runs.sortedByDescending { it.startMs }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Runs", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (runs.isNotEmpty()) TextButton(onClick = { Exports.runsCsv(ctx, runs) }) { Text("Export CSV") }
        }
        if (runs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Finished runs show up here with your pace for every step.", style = MaterialTheme.typography.bodyLarge, color = Palette.Slate)
            }
            return@Column
        }
        val totalKm = runs.sumOf { it.metres } / 1000
        Text("${runs.size} runs, %.0f km".format(totalKm), style = MaterialTheme.typography.bodyMedium, color = Palette.Slate, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(runs, key = { it.id }) { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { openRun(r.id) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val shape by produceState(emptyList<TrackPoint>(), r.id) {
                        value = withContext(Dispatchers.IO) { downsample(RunPlanApp.store.readTrack(r.id), 150) }
                    }
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
                        RouteThumb(shape, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            LocalDate.ofEpochDay(r.epochDay).format(dateFmt) + if (r.sentToStrava) ", sent to Strava" else "",
                            style = MaterialTheme.typography.bodySmall, color = Palette.Slate,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${Fmt.km(r.metres)} km", style = MaterialTheme.typography.headlineSmall)
                        Text("${Fmt.duration(r.sec)}, ${Fmt.pace(r.pace)}/km", style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Palette.Line)
            }
        }
    }
}
