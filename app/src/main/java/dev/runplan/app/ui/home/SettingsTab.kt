package dev.runplan.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.runplan.app.ui.components.rememberToday
import dev.runplan.app.RunPlanApp
import dev.runplan.app.data.BugReport
import dev.runplan.app.data.Updater
import kotlinx.coroutines.launch
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Distance
import dev.runplan.core.Fmt
import dev.runplan.core.Vdot
import dev.runplan.core.Zone

@Composable
fun SettingsTab(modifier: Modifier, editPlan: () -> Unit, newPlan: () -> Unit) {
    val store = RunPlanApp.store
    val state by store.state.collectAsStateWithLifecycle()
    val plan by store.plan.collectAsStateWithLifecycle()
    var updating by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        val p = plan?.paces
        val profile = state.profile
        if (p != null && profile != null) {
            Section("Your paces")
            val goalM = profile.goal.distance.metres
            val goalName = profile.goal.label.let { if (it.endsWith("K")) it else it.lowercase() }
            val today = rememberToday()
            val weeksLeft = ((profile.raceEpochDay - today) / 7).toInt()
            val (lo, hi) = Vdot.projected(p.vdot, weeksLeft)
            Text(
                buildString {
                    append("Set from your ${profile.recentDistance.label} in ${Fmt.duration(profile.recentSec)}, so they match your fitness today.")
                    if (profile.recentDistance.metres != goalM) {
                        append(" That's about ${Fmt.duration(Vdot.roundTime(Vdot.predictTime(p.vdot, goalM), goalM))} for a $goalName now.")
                    }
                    if (weeksLeft >= 2) {
                        append(" Follow the plan and a realistic race-day target is ")
                        append(Fmt.duration(Vdot.roundTime(Vdot.predictTime(hi, goalM), goalM)))
                        append(" to ")
                        append(Fmt.duration(Vdot.roundTime(Vdot.predictTime(lo, goalM), goalM)))
                        append(". Your paces move up when you log a faster race or time trial.")
                    }
                },
                style = MaterialTheme.typography.bodyMedium, color = Palette.Slate,
            )
            Spacer(Modifier.height(8.dp))
            Zone.entries.forEach { z ->
                val (f, s) = p.target(z)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 6.dp, height = 30.dp).clip(RoundedCornerShape(3.dp)).background(Palette.zone(z)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(z.label, style = MaterialTheme.typography.titleSmall)
                        Text(z.use, style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
                    }
                    Text(Fmt.band(f, s), style = MaterialTheme.typography.headlineSmall)
                }
                HorizontalDivider(color = Palette.Line)
            }
            Spacer(Modifier.height(8.dp))
            Action("Update my fitness", "Ran a race or time trial? Enter it and every pace in the plan updates.") { updating = true }
            Action("Edit plan", "Change your goal, race hills, race day, run days or weekly distance.", editPlan)
        }

        Section("Coaching")
        Toggle("Voice coaching", "Spoken step changes and countdowns", state.settings.voice) { store.setSettings(state.settings.copy(voice = it)) }
        Toggle("Pace alerts", "\"Speed up\" or \"Ease off\" when you drift out of the target band", state.settings.paceAlerts) {
            store.setSettings(state.settings.copy(paceAlerts = it))
        }
        Toggle("Kilometre splits", "Announced on easy runs, long runs and long steps", state.settings.splits) { store.setSettings(state.settings.copy(splits = it)) }

        Section("Strava")
        Text(
            "Open a run and tap Upload to Strava. RunPlan saves it to Downloads as a TCX, with each step as a lap, " +
                "and opens Strava's uploader: choose the file there. No Strava subscription needed.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Slate,
        )

        Section("Watch")
        Text(
            "Install RunPlan on your Galaxy Watch and open it before you start. It shows your step, target and pace, buzzes on every change, and can pause or skip a step.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Slate,
        )

        Section("App")
        AppUpdate()
        ReportProblem()

        Section("Plan")
        Action("Start a new plan", "Clears this plan. Your run history is kept.") { confirmNew = true }
        Spacer(Modifier.height(32.dp))
    }

    if (updating && state.profile != null) UpdateFitnessDialog(onDismiss = { updating = false }) { d, sec ->
        store.setProfile(state.profile!!.copy(recentDistance = d, recentSec = sec))
        updating = false
    }
    if (confirmNew) AlertDialog(
        onDismissRequest = { confirmNew = false },
        title = { Text("Start a new plan?") },
        text = { Text("Your current plan and its completed sessions will be cleared. Runs stay in your history.") },
        confirmButton = { TextButton(onClick = { confirmNew = false; newPlan() }) { Text("Start new plan") } },
        dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Keep this plan") } },
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(32.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Action(title: String, help: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Signal)
        Text(help, style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
    }
}

@Composable
private fun Toggle(title: String, help: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(help, style = MaterialTheme.typography.bodySmall, color = Palette.Slate)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateFitnessDialog(onDismiss: () -> Unit, onSave: (Distance, Double) -> Unit) {
    var d by remember { mutableStateOf(Distance.K5) }
    var time by remember { mutableStateOf("") }
    val sec = time.split(':').mapNotNull { it.trim().toIntOrNull() }.fold(0) { acc, x -> acc * 60 + x }.toDouble()
    val v = if (sec > 0) Vdot.vdot(d.metres, sec) else Double.NaN
    val ok = v.isFinite() && v in 20.0..85.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update my fitness") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Distance.MILE, Distance.K5, Distance.K10, Distance.HALF, Distance.MARATHON).forEach {
                        FilterChip(selected = it == d, onClick = { d = it }, label = { Text(it.label) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = time, onValueChange = { time = it.filter { c -> c.isDigit() || c == ':' }.take(8) },
                    label = { Text("Time, e.g. 24:30 or 1:52:10") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (ok) "Predicts 5K ${Fmt.duration(Vdot.predictTime(v, 5000.0))}, 10K ${Fmt.duration(Vdot.predictTime(v, 10000.0))}, " +
                        "half ${Fmt.duration(Vdot.predictTime(v, 21097.5))}"
                    else if (sec > 0) "That time looks off for a ${d.label}." else "",
                    style = MaterialTheme.typography.bodyMedium, color = if (ok) Palette.Slate else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(d, sec) }, enabled = ok) { Text("Update paces") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val v: Updater.Version) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Ready(val apk: java.io.File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@Composable
private fun AppUpdate() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val installed = remember { Updater.installed(ctx) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            state = runCatching { Updater.latest() }.fold(
                { v -> if (v.code > installed.code) UpdateState.Available(v) else UpdateState.UpToDate },
                { e -> UpdateState.Failed(e.message ?: "Couldn't check for updates.") },
            )
        }
    }

    fun install(apk: java.io.File) {
        if (Updater.canInstall(ctx)) Updater.install(ctx, apk) else Updater.requestInstallPermission(ctx)
    }

    Text("Version ${installed.name}", style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
    when (val st = state) {
        UpdateState.Idle -> Action("Check for updates", "Downloads the latest build from GitHub") { check() }
        UpdateState.Checking -> Text("Checking…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
        UpdateState.UpToDate -> Action("You're up to date", "Tap to check again") { check() }
        is UpdateState.Available -> Action("Version ${st.v.name} is available", "Tap to download and install. Your plan and runs are kept.") {
            state = UpdateState.Downloading(0f)
            scope.launch {
                state = runCatching { Updater.download(ctx) { p -> state = UpdateState.Downloading(p) } }.fold(
                    { apk -> install(apk); UpdateState.Ready(apk) },
                    { e -> UpdateState.Failed(e.message ?: "Download failed.") },
                )
            }
        }
        is UpdateState.Downloading -> Text(
            "Downloading… ${(st.progress * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp),
        )
        is UpdateState.Ready -> Action(
            "Install update",
            if (Updater.canInstall(ctx)) "Android will ask you to confirm the update"
            else "First allow RunPlan to install apps on the screen that opens, then come back and tap again",
        ) { install(st.apk) }
        is UpdateState.Failed -> Action("Update failed", "${st.message} Tap to try again.") { check() }
    }
}

@Composable
private fun ReportProblem() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember { Updater.installed(ctx).name }
    var crash by remember { mutableStateOf(BugReport.lastCrash(ctx)) }
    Action(
        "Report a problem",
        if (crash != null) "Opens a GitHub issue with your version, phone and the crash from ${crash!!.whenText}"
        else "Opens a GitHub issue with your version and phone filled in",
    ) { BugReport.open(ctx, version) }
    if (crash != null) {
        Action("Clear crash report", "Once you've reported it") {
            BugReport.clearCrash(ctx)
            crash = null
        }
    }
}
