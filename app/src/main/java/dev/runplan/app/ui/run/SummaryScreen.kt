package dev.runplan.app.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import dev.runplan.app.data.RunRecord
import dev.runplan.app.data.Strava
import dev.runplan.app.ui.components.LineChart
import dev.runplan.app.ui.components.RouteMap
import dev.runplan.app.ui.components.SplitBars
import dev.runplan.core.Analytics
import dev.runplan.core.Effort
import dev.runplan.core.PaceZones
import dev.runplan.core.Paces
import dev.runplan.core.TrackPoint
import dev.runplan.core.Zone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Fmt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val date = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryScreen(id: String, onBack: () -> Unit, openMap: (String) -> Unit) {
    val ctx = LocalContext.current
    val state by RunPlanApp.store.state.collectAsStateWithLifecycle()
    val r = state.runs.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    val plan by RunPlanApp.store.plan.collectAsStateWithLifecycle()
    val track by produceState(emptyList<TrackPoint>(), id) {
        value = withContext(Dispatchers.IO) { RunPlanApp.store.readTrack(id) }
    }
    var stravaMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        if (r == null) {
            Text("This run was deleted.", Modifier.padding(24.dp))
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(LocalDate.ofEpochDay(r.epochDay).format(date), style = MaterialTheme.typography.titleSmall, color = Palette.Slate)
            Text(r.title, style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(16.dp))
            if (track.size >= 2) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))) {
                    RouteMap(track, plan?.paces, height = 240.dp)
                    // the preview doesn't take touches (it sits in a scrolling page); tap opens the full map
                    Box(Modifier.matchParentSize().clickable { openMap(r.id) })
                }
                Spacer(Modifier.height(8.dp))
                ZoneLegend(track, plan?.paces)
            }
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

            val paceData = remember(track) { Analytics.paceSeries(track) }
            if (paceData.count { it.second.isFinite() } > 10) {
                Spacer(Modifier.height(28.dp))
                Text("Pace", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                LineChart(
                    data = paceData,
                    colorAt = { i -> Palette.zone(plan?.paces?.let { PaceZones.of(paceData[i].second, it) } ?: Zone.E) },
                    invertY = true,
                    robust = true,
                    yTicks = listOf(15.0, 30.0, 60.0, 120.0, 300.0),
                    yFormat = { Fmt.pace(it) },
                    xFormat = { "${(it / 60).toInt()} min" },
                    inspect = { i -> "${Fmt.duration(paceData[i].first)}, ${Fmt.pace(paceData[i].second)}/km" },
                )
            }
            val elev = remember(track) { Analytics.elevationSeries(track) }
            val elevRange = elev.maxOfOrNull { it.second }?.minus(elev.minOf { it.second }) ?: 0.0
            if (elev.size > 10 && elevRange > 3) {
                Spacer(Modifier.height(28.dp))
                Text("Elevation", style = MaterialTheme.typography.headlineSmall)
                if (r.ascentM.isFinite()) Text("${r.ascentM.toInt()} m of climb", style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
                Spacer(Modifier.height(4.dp))
                LineChart(
                    data = elev,
                    height = 120.dp,
                    colorAt = { Palette.Slate },
                    area = true,
                    yFormat = { "${it.toInt()} m" },
                    xFormat = { "%.1f km".format(it) },
                    inspect = { i -> "%.2f km, %d m".format(elev[i].first, elev[i].second.toInt()) },
                )
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
                Spacer(Modifier.height(28.dp))
                Text("Kilometre splits", style = MaterialTheme.typography.headlineSmall)
                Text("Longer bar is faster; the dot marks your fastest.", style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
                Spacer(Modifier.height(10.dp))
                SplitBars(r.splits) { sec -> Palette.zone(plan?.paces?.let { PaceZones.of(sec, it) } ?: Zone.E) }
            }

            val efforts = r.efforts.orEmpty()
            if (efforts.isNotEmpty()) {
                val bests = remember(state.runs) {
                    state.runs.flatMap { run -> run.efforts.orEmpty().map { it to run } }
                        .groupBy { it.first.label }
                        .mapValues { (_, xs) -> xs.minWith(compareBy<Pair<Effort, RunRecord>> { it.first.sec }.thenBy { it.second.startMs }).second.id }
                }
                Spacer(Modifier.height(28.dp))
                Text("Best efforts", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                efforts.forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(80.dp))
                        Text(Fmt.duration(e.sec), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        Text("${Fmt.pace(e.pace)}/km", style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
                        if (bests[e.label] == r.id) {
                            Spacer(Modifier.width(10.dp))
                            Text("Best ever", style = MaterialTheme.typography.labelMedium, color = Palette.Signal)
                        }
                    }
                    HorizontalDivider(color = Palette.Line)
                }
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete run", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
            Text("Export", style = MaterialTheme.typography.bodyMedium, color = Palette.Slate, modifier = Modifier.align(Alignment.CenterVertically).padding(start = 8.dp, end = 4.dp))
            TextButton(onClick = { Exports.gpx(ctx, r, track) }) { Text("GPX") }
            TextButton(onClick = { Exports.tcx(ctx, r, track) }) { Text("TCX") }
            TextButton(onClick = { Exports.stepsCsv(ctx, r) }) { Text("Steps CSV") }
            TextButton(onClick = { Exports.trackCsv(ctx, r, RunPlanApp.store.trackFile(r.id)) }) { Text("GPS CSV") }
        }
        StravaRow(
            run = r,
            hasTrack = track.size >= 2,
            message = stravaMessage,
            onUpload = {
                stravaMessage = runCatching { Strava.sendViaWeb(ctx, r, track) }.fold(
                    { name -> "Saved $name to Downloads. On the Strava page, choose it as the file to upload. If you see Strava's mobile page instead, switch your browser to Desktop site." },
                    { e -> e.message ?: "Couldn't save the file." },
                )
            },
        )
        Button(onClick = onBack, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).height(52.dp)) {
            Text("Done")
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

@Composable
private fun StravaRow(run: RunRecord, hasTrack: Boolean, message: String?, onUpload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        if (hasTrack) {
            OutlinedButton(onClick = onUpload, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (run.sentToStrava) "Upload to Strava again" else "Upload to Strava", color = StravaOrange)
            }
        } else {
            Text("No GPS track, so there's nothing to upload to Strava.", style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
        }
        if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = Palette.Slate, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
    }
}

private val StravaOrange = androidx.compose.ui.graphics.Color(0xFFFC4C02)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneLegend(track: List<TrackPoint>, paces: Paces?) {
    if (paces == null) return
    val zones = remember(track, paces) {
        track.mapNotNull { p -> if (p.fSpeed.isFinite() && p.fSpeed > 0.5) PaceZones.of(1000 / p.fSpeed, paces) else null }.toSet()
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Zone.entries.filter { it in zones }.forEach { z ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.zone(z)))
                Spacer(Modifier.width(5.dp))
                Text(z.label, style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
            }
        }
    }
}
