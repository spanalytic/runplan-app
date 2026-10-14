package dev.runplan.app.ui.run

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.runplan.app.RunPlanApp
import dev.runplan.app.run.RunPhase
import dev.runplan.app.run.RunService
import dev.runplan.app.run.RunSession
import dev.runplan.app.ui.components.WorkoutShape
import dev.runplan.app.ui.components.bars
import dev.runplan.app.ui.components.progressOf
import dev.runplan.app.ui.theme.Condensed
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Fmt
import dev.runplan.core.PaceStatus
import dev.runplan.core.RunState
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay

private val Huge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 132.sp, lineHeight = 124.sp, fontFeatureSettings = "tnum")
private val Big = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 64.sp, fontFeatureSettings = "tnum")

@Composable
fun RunScreen(onSaved: (String) -> Unit, onDiscarded: () -> Unit) {
    val ctx = LocalContext.current
    val view by RunSession.view.collectAsStateWithLifecycle()
    val plan by RunPlanApp.store.plan.collectAsStateWithLifecycle()
    val s = view.state
    var confirmFinish by remember { mutableStateOf(false) }
    var startTimedOut by remember { mutableStateOf(false) }

    // keep the screen awake while this screen is showing
    val hostView = LocalView.current
    DisposableEffect(Unit) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }
    LaunchedEffect(view.phase, view.savedRunId) {
        if (view.phase == RunPhase.SAVED) view.savedRunId?.let(onSaved)
    }
    LaunchedEffect(Unit) { delay(4000); startTimedOut = true }

    val active = view.phase == RunPhase.RUNNING || view.phase == RunPhase.PAUSED
    val status = if (view.phase == RunPhase.RUNNING) s.status else PaceStatus.NONE
    val (fieldTarget, inkTarget) = Palette.field(status)
    val field by animateColorAsState(fieldTarget, tween(400), label = "field")
    val ink by animateColorAsState(inkTarget, tween(400), label = "ink")
    val sub = ink.copy(alpha = 0.7f)
    val shapeBars = remember(view.steps) { bars(view.steps, plan?.paces) }

    Box(Modifier.fillMaxSize().background(field)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            when (view.phase) {
                RunPhase.IDLE -> {
                    Spacer(Modifier.weight(1f))
                    Text(if (startTimedOut) "The run didn't start. Check location permission and try again." else "Starting…", style = MaterialTheme.typography.titleLarge, color = ink)
                    Spacer(Modifier.height(24.dp))
                    if (startTimedOut) TextButton(onClick = onDiscarded) { Text("Back", color = ink) }
                    Spacer(Modifier.weight(1f))
                }

                RunPhase.READY -> if (view.countdown > 0) {
                    Spacer(Modifier.weight(1f))
                    Text("Starting in", style = MaterialTheme.typography.headlineSmall, color = sub, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("${view.countdown}", style = Huge, color = ink, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { RunService.send(ctx, RunService.ACTION_DISCARD); onDiscarded() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Cancel", color = ink)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, style = MaterialTheme.typography.headlineMedium, color = ink, modifier = Modifier.weight(1f))
                        GpsBadge(s.gpsOk, ink)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (s.gpsOk) "Ready" else "Finding GPS", style = Big, color = ink)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (s.gpsOk) "Start when you're moving off. Open RunPlan on your watch to follow along."
                        else "Stand still outside with a clear view of the sky. You can start now, but distance will be missing until GPS locks.",
                        style = MaterialTheme.typography.bodyLarge, color = sub,
                    )
                    Spacer(Modifier.height(28.dp))
                    WorkoutShape(shapeBars, height = 56.dp, progress = 0f, marker = ink)
                    Spacer(Modifier.height(8.dp))
                    Text("First: ${view.steps.firstOrNull()?.let { "${it.label}, ${it.describe()}" } ?: ""}", style = MaterialTheme.typography.bodyMedium, color = sub)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { RunService.send(ctx, RunService.ACTION_START) },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Signal, contentColor = Color.White),
                    ) { Text("Start", style = MaterialTheme.typography.headlineSmall) }
                    TextButton(onClick = { RunService.send(ctx, RunService.ACTION_DISCARD); onDiscarded() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Cancel", color = ink)
                    }
                }

                else -> ActiveRun(s, view.phase, ink, sub, shapeBars, onFinish = { confirmFinish = true })
            }
        }
    }

    if (confirmFinish && active) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Finish this run?") },
            text = { Text("${Fmt.km(s.distanceM)} km in ${Fmt.duration(s.elapsedSec)}.") },
            confirmButton = {
                TextButton(onClick = { confirmFinish = false; RunService.send(ctx, RunService.ACTION_FINISH) }) { Text("Save run") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmFinish = false; RunService.send(ctx, RunService.ACTION_DISCARD); onDiscarded() }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { confirmFinish = false }) { Text("Keep going") }
                }
            },
        )
    }
}

@Composable
private fun ActiveRun(
    s: RunState,
    phase: RunPhase,
    ink: Color,
    sub: Color,
    shapeBars: List<dev.runplan.app.ui.components.Bar>,
    onFinish: () -> Unit,
) {
    val ctx = LocalContext.current
    val paused = phase == RunPhase.PAUSED
    Column(Modifier.fillMaxSize()) {
        // step header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (paused) "Paused" else s.stepLabel, style = MaterialTheme.typography.headlineMedium, color = ink)
                Text("Step ${s.stepIndex + 1} of ${s.stepCount}", style = MaterialTheme.typography.labelMedium, color = sub)
            }
            GpsBadge(s.gpsOk, ink)
        }
        Spacer(Modifier.weight(1f))

        // current pace, the thing you glance at
        Text(Fmt.pace(s.pace), style = Huge, color = ink)
        Text(
            when {
                s.fast.isFinite() -> "Target ${Fmt.band(s.fast, s.slow)}"
                else -> "Pace per km"
            },
            style = MaterialTheme.typography.titleLarge, color = ink,
        )
        val verdict = when (s.status) {
            PaceStatus.SLOW -> "Speed up"
            PaceStatus.FAST -> "Ease off"
            PaceStatus.IN_BAND -> "On pace"
            PaceStatus.NONE -> ""
        }
        val lap = if (s.lapPace.isFinite()) "Lap average ${Fmt.pace(s.lapPace)}" else ""
        val line = listOf(lap, if (paused) "" else verdict).filter { it.isNotEmpty() }.joinToString(". ")
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.titleMedium, color = sub)

        Spacer(Modifier.height(28.dp))
        // time / distance left in this step
        if (s.stepRemaining.isFinite()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (s.remainingIsDistance) "${s.stepRemaining.coerceAtLeast(0.0).toInt()}" else Fmt.duration(s.stepRemaining),
                    style = Big, color = ink,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (s.remainingIsDistance) "m left" else "left", style = MaterialTheme.typography.titleMedium, color = sub, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Total(Fmt.duration(s.elapsedSec), "time", ink, sub)
            Total(Fmt.km(s.distanceM), "km", ink, sub)
            Total(Fmt.pace(s.avgPace), "avg /km", ink, sub)
        }
        Spacer(Modifier.weight(1f))

        WorkoutShape(shapeBars, height = 44.dp, progress = progressOf(shapeBars, s.stepIndex, s.stepProgress), marker = ink)
        Spacer(Modifier.height(8.dp))
        if (s.nextLabel.isNotEmpty()) Text("Next: ${s.nextLabel}", style = MaterialTheme.typography.bodyMedium, color = sub, maxLines = 1)
        Spacer(Modifier.height(20.dp))

        // controls
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Filled.Stop, "Finish", ink, small = true, onClick = onFinish)
            RoundButton(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                if (paused) "Resume" else "Pause",
                ink,
                onClick = { RunService.send(ctx, if (paused) RunService.ACTION_RESUME else RunService.ACTION_PAUSE) },
            )
            RoundButton(Icons.Filled.SkipNext, "Skip step", ink, small = true) { RunService.send(ctx, RunService.ACTION_SKIP) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ink: Color, small: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(if (small) 56.dp else 84.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ink, contentColor = if (ink == Color.White) Palette.Ink else Color.White),
        ) { Icon(icon, contentDescription = label, modifier = Modifier.size(if (small) 26.dp else 40.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f))
    }
}

@Composable
private fun Total(value: String, label: String, ink: Color, sub: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), color = ink)
        Text(label, style = MaterialTheme.typography.labelSmall, color = sub)
    }
}

@Composable
private fun GpsBadge(ok: Boolean, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Palette.Good else Palette.Amber))
        Spacer(Modifier.width(6.dp))
        Text(if (ok) "GPS" else "No GPS", style = MaterialTheme.typography.labelMedium, color = ink)
    }
}
