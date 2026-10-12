package dev.runplan.app.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.runplan.app.RunPlanApp
import dev.runplan.app.ui.components.RouteMap
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Fmt
import dev.runplan.core.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-screen, pannable map of one run. */
@Composable
fun MapScreen(id: String, onBack: () -> Unit) {
    val state by RunPlanApp.store.state.collectAsStateWithLifecycle()
    val plan by RunPlanApp.store.plan.collectAsStateWithLifecycle()
    val r = state.runs.firstOrNull { it.id == id }
    val track by produceState(emptyList<TrackPoint>(), id) {
        value = withContext(Dispatchers.IO) { RunPlanApp.store.readTrack(id) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Paper)) {
        if (track.size >= 2) RouteMap(track, plan?.paces, height = maxHeight)
        FilledIconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(12.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Palette.Ink),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        if (r != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White)
                    .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Stat(Fmt.km(r.metres), "km")
                Stat(Fmt.duration(r.sec), "time")
                Stat(Fmt.pace(r.pace), "avg /km")
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
    }
}

