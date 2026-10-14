package dev.runplan.wear

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.runplan.core.Fmt
import dev.runplan.core.PaceStatus
import dev.runplan.core.RunState
import dev.runplan.core.WearPaths
import kotlinx.coroutines.delay

private val Ink = Color(0xFF16202E)
private val Amber = Color(0xFFE2A93B)
private val Signal = Color(0xFF2457F5)

private val Condensed = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight = FontWeight.SemiBold) =
    TextStyle(fontFamily = Condensed, fontWeight = weight, fontSize = size.sp, lineHeight = (size + 2).sp, fontFeatureSettings = "tnum", textAlign = TextAlign.Center)

private fun zoneColor(code: String): Color = when (code) {
    "E" -> Color(0xFF43A86F)
    "M", "HM" -> Color(0xFF3C7DD9)
    "T" -> Color(0xFFD99A1C)
    "I" -> Color(0xFFE2512E)
    "R" -> Color(0xFFA447C9)
    "G" -> Signal
    else -> Color(0xFFC3CAD3)
}

@Composable
fun WearApp(state: RunState, lastMessageMs: Long, send: (String) -> Unit) {
    // re-evaluate staleness every second even if no messages arrive
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = SystemClock.elapsedRealtime() } }
    val stale = lastMessageMs == 0L || (now - lastMessageMs > 6_000 && !state.finished)

    val countdown = state.stepLabel.takeIf { it.startsWith("Starting in ") }?.removePrefix("Starting in ")
    val ready = state.stepLabel == "Ready" || state.stepLabel == "Finding GPS" || countdown != null
    val status = if (stale || ready || state.paused) PaceStatus.NONE else state.status
    val (fieldTarget, inkTarget) = when (status) {
        PaceStatus.SLOW -> Amber to Ink
        PaceStatus.FAST -> Signal to Color.White
        else -> Ink to Color.White
    }
    val field by animateColorAsState(fieldTarget, label = "field")
    val ink by animateColorAsState(inkTarget, label = "ink")

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(field), contentAlignment = Alignment.Center) {
            when {
                stale && !state.finished -> Message(
                    if (lastMessageMs == 0L) "Start a run in RunPlan on your phone" else "Phone not connected",
                    ink,
                )

                state.finished -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Done", style = style(34, FontWeight.Bold), color = ink)
                    Text("${Fmt.km(state.distanceM)} km", style = style(22), color = ink)
                    Text(Fmt.duration(state.elapsedSec), style = style(18), color = ink.copy(alpha = 0.7f))
                }

                countdown != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Starting in", style = style(18), color = ink.copy(alpha = 0.7f))
                    Text(countdown, style = style(72, FontWeight.Bold), color = ink)
                }

                ready -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.title, style = style(16), color = ink.copy(alpha = 0.7f))
                    Text(state.stepLabel, style = style(28, FontWeight.Bold), color = ink)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { send(WearPaths.CMD_START) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Signal, contentColor = Color.White),
                        modifier = Modifier.size(ButtonDefaults.LargeButtonSize),
                    ) { Text("Start", style = style(18, FontWeight.Bold)) }
                }

                else -> Active(state, ink, send)
            }
        }
    }
}

@Composable
private fun Message(text: String, ink: Color) {
    Text(text, style = style(18), color = ink, modifier = Modifier.padding(28.dp))
}

@Composable
private fun Active(s: RunState, ink: Color, send: (String) -> Unit) {
    val sub = ink.copy(alpha = 0.72f)
    if (s.stepProgress.isFinite()) {
        CircularProgressIndicator(
            progress = s.stepProgress.toFloat(),
            modifier = Modifier.fillMaxSize().padding(3.dp),
            startAngle = 300f,
            endAngle = 240f,
            indicatorColor = zoneColor(s.zoneCode),
            trackColor = ink.copy(alpha = 0.15f),
            strokeWidth = 6.dp,
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (s.paused) "Paused" else s.stepLabel, style = style(16), color = sub, maxLines = 1)
        Text(Fmt.pace(s.pace), style = style(54, FontWeight.Bold), color = ink)
        Text(
            if (s.fast.isFinite()) Fmt.band(s.fast, s.slow) else "${Fmt.km(s.distanceM)} km",
            style = style(16), color = ink,
        )
        if (s.lapPace.isFinite()) Text("lap ${Fmt.pace(s.lapPace)}", style = style(14), color = sub)
        if (s.stepRemaining.isFinite()) {
            Text(
                if (s.remainingIsDistance) "${s.stepRemaining.coerceAtLeast(0.0).toInt()} m" else Fmt.duration(s.stepRemaining),
                style = style(26, FontWeight.Bold), color = ink,
            )
        } else {
            Text(Fmt.duration(s.elapsedSec), style = style(26, FontWeight.Bold), color = ink)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            Button(
                onClick = { send(if (s.paused) WearPaths.CMD_RESUME else WearPaths.CMD_PAUSE) },
                colors = ButtonDefaults.buttonColors(backgroundColor = ink, contentColor = if (ink == Color.White) Ink else Color.White),
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) { Text(if (s.paused) "Go" else "II", style = style(16, FontWeight.Bold)) }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { send(WearPaths.CMD_SKIP) },
                colors = ButtonDefaults.buttonColors(backgroundColor = ink.copy(alpha = 0.18f), contentColor = ink),
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) { Text(">|", style = style(16, FontWeight.Bold)) }
        }
    }
}

