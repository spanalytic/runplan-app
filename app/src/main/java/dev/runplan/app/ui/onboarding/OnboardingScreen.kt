package dev.runplan.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Distance
import dev.runplan.core.Fmt
import dev.runplan.core.Goal
import dev.runplan.core.PlanBuilder
import dev.runplan.core.Profile
import dev.runplan.core.Terrain
import dev.runplan.core.Vdot
import java.time.LocalDate
import kotlin.math.roundToInt

private const val PAGES = 6
private const val DAY_MS = 86_400_000L
private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val INPUT_DISTANCES = listOf(Distance.MILE, Distance.K3, Distance.K5, Distance.K10, Distance.HALF, Distance.MARATHON)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(existing: Profile?, onDone: (Profile) -> Unit, onCancel: (() -> Unit)?) {
    val today = remember { LocalDate.now().toEpochDay() }
    var page by rememberSaveable { mutableIntStateOf(0) }

    // answers
    var distance by rememberSaveable { mutableStateOf(existing?.recentDistance ?: Distance.K5) }
    val secs = existing?.recentSec?.roundToInt() ?: 0
    var hh by rememberSaveable { mutableStateOf(if (secs >= 3600) (secs / 3600).toString() else "") }
    var mm by rememberSaveable { mutableStateOf(if (secs > 0) ((secs % 3600) / 60).toString() else "") }
    var ss by rememberSaveable { mutableStateOf(if (secs > 0) (secs % 60).toString().padStart(2, '0') else "") }
    var goal by rememberSaveable { mutableStateOf(existing?.goal ?: Goal.HALF) }
    var terrain by rememberSaveable { mutableStateOf(existing?.terrain ?: Terrain.FLAT) }
    var hills by rememberSaveable { mutableStateOf(existing?.hills ?: false) }
    var race by rememberSaveable { mutableStateOf(existing?.raceEpochDay ?: (today + 7 * 12)) }
    var days by rememberSaveable { mutableStateOf(existing?.days ?: listOf(1, 2, 4, 6)) }
    var km by rememberSaveable { mutableFloatStateOf(existing?.currentKm?.toFloat() ?: 25f) }
    val start = existing?.startEpochDay?.coerceAtMost(race) ?: today

    val timeSec = (hh.toIntOrNull() ?: 0) * 3600 + (mm.toIntOrNull() ?: 0) * 60 + (ss.toIntOrNull() ?: 0)
    val vdot = if (timeSec > 0) Vdot.vdot(distance.metres, timeSec.toDouble()) else Double.NaN
    val vdotOk = vdot.isFinite() && vdot in 20.0..85.0
    val weeks = PlanBuilder.weeksBetween(start, race)

    val valid = when (page) {
        0 -> vdotOk
        1 -> true
        2 -> true
        3 -> race >= start && weeks >= goal.minWeeks
        4 -> days.size in 3..6
        else -> true
    }

    fun back() { if (page > 0) page-- else onCancel?.invoke() }
    BackHandler(enabled = page > 0) { back() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding(),
    ) {
        // header: back + progress through the questions
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0 || onCancel != null) {
                IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            Row(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(PAGES) { i ->
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (i <= page) Palette.Ink else Palette.Line),
                    )
                }
            }
            Spacer(Modifier.size(48.dp))
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            when (page) {
                0 -> {
                    Question("What's a recent race or all-out effort?", "Your training paces come from this. A parkrun or time trial from the last couple of months works best.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        INPUT_DISTANCES.forEach { d ->
                            FilterChip(selected = d == distance, onClick = { distance = d }, label = { Text(d.label) })
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TimeBox(hh, { hh = it.filter(Char::isDigit).take(2) }, "hours", Modifier.weight(1f))
                        TimeBox(mm, { mm = it.filter(Char::isDigit).take(2) }, "min", Modifier.weight(1f))
                        TimeBox(ss, { ss = it.filter(Char::isDigit).take(2) }, "sec", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(28.dp))
                    if (vdotOk) {
                        Text("That predicts", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            listOf(Distance.K5, Distance.K10, Distance.HALF, Distance.MARATHON).forEach { d ->
                                Column {
                                    Text(Fmt.duration(Vdot.predictTime(vdot, d.metres)), style = MaterialTheme.typography.headlineSmall)
                                    Text(d.label, style = MaterialTheme.typography.labelSmall, color = Palette.Slate)
                                }
                            }
                        }
                    } else if (timeSec > 0) {
                        Text("That time looks off for a ${distance.label}. Check the hours, minutes and seconds.", color = MaterialTheme.colorScheme.error)
                    }
                }

                1 -> {
                    Question("What are you training for?", "Predicted times are for your current fitness. The plan aims to improve on them.")
                    Goal.entries.forEach { g ->
                        val selected = g == goal
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Palette.Ink else Color.Transparent)
                                .clickable { goal = g }
                                .padding(horizontal = 16.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g.label, style = MaterialTheme.typography.headlineSmall, color = if (selected) Color.White else Palette.Ink, modifier = Modifier.weight(1f))
                            if (vdotOk) Text(
                                Fmt.duration(Vdot.predictTime(vdot, g.distance.metres)),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selected) Color.White.copy(alpha = 0.8f) else Palette.Slate,
                            )
                        }
                        HorizontalDivider(color = Palette.Line)
                    }
                }

                2 -> {
                    Question("Is your race hilly?", "Hill sessions build strength for climbs, and help on any course.")
                    Terrain.entries.forEach { tr ->
                        val selected = tr == terrain
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Palette.Ink else Color.Transparent)
                                .clickable { terrain = tr; hills = tr != Terrain.FLAT }
                                .padding(horizontal = 16.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tr.label, style = MaterialTheme.typography.headlineSmall, color = if (selected) Color.White else Palette.Ink, modifier = Modifier.weight(1f))
                            Text(tr.help, style = MaterialTheme.typography.titleMedium, color = if (selected) Color.White.copy(alpha = 0.8f) else Palette.Slate)
                        }
                        HorizontalDivider(color = Palette.Line)
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth().clickable { hills = !hills }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Add hill sessions", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Hill sprints in place of some strides early on, then hill repeats in place of " +
                                    when (terrain) { Terrain.FLAT -> "one in four"; Terrain.ROLLING -> "one in three"; Terrain.HILLY -> "every other" } +
                                    " interval session" + if (terrain == Terrain.HILLY) "." else "s.",
                                style = MaterialTheme.typography.bodyMedium, color = Palette.Slate,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = hills, onCheckedChange = { hills = it })
                    }
                }

                3 -> {
                    Question("When's race day?", "A ${goal.label.lowercase()} plan needs at least ${goal.minWeeks} weeks.")
                    val minDay = start + (goal.minWeeks - 1) * 7L
                    val state = rememberDatePickerState(
                        initialSelectedDateMillis = race * DAY_MS,
                        selectableDates = object : SelectableDates {
                            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MS >= minDay
                            override fun isSelectableYear(year: Int) = year >= LocalDate.now().year
                        },
                    )
                    LaunchedEffect(state.selectedDateMillis) { state.selectedDateMillis?.let { race = it / DAY_MS } }
                    Text(
                        if (weeks >= goal.minWeeks) "$weeks weeks of training, race on ${LocalDate.ofEpochDay(race).let { "${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.year}" }}"
                        else "Pick a later date",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (weeks >= goal.minWeeks) Palette.Ink else MaterialTheme.colorScheme.error,
                    )
                    DatePicker(
                        state = state,
                        title = null,
                        headline = null,
                        showModeToggle = false,
                        colors = DatePickerDefaults.colors(containerColor = Color.Transparent),
                    )
                }

                4 -> {
                    Question("Which days can you run?", "Pick 3 to 6. Your first day is intervals and your last is the long run.")
                    val roles = PlanBuilder.roleLabels(days)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_LETTERS.forEachIndexed { i, letter ->
                            val on = i in days
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(8.dp))
                                        .background(if (on) Palette.Ink else Color.Transparent)
                                        .border(1.dp, if (on) Palette.Ink else Palette.Line, RoundedCornerShape(8.dp))
                                        .clickable { days = if (on) days - i else (days + i).sorted() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(letter, style = MaterialTheme.typography.headlineSmall, color = if (on) Color.White else Palette.Ink)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(roles[i] ?: "", style = MaterialTheme.typography.labelSmall, color = Palette.Slate, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                    }
                    if (days.size !in 3..6) {
                        Spacer(Modifier.height(16.dp))
                        Text(if (days.size < 3) "Pick at least 3 days." else "6 days is the maximum; keep one rest day.", color = MaterialTheme.colorScheme.error)
                    }
                }

                else -> {
                    Question("How much are you running now?", "Your average over the last month. The plan builds from here by no more than 10% a week.")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${km.roundToInt()}", style = MaterialTheme.typography.displayLarge)
                        Spacer(Modifier.width(8.dp))
                        Text("km a week", style = MaterialTheme.typography.titleMedium, color = Palette.Slate, modifier = Modifier.padding(bottom = 14.dp))
                    }
                    Slider(value = km, onValueChange = { km = it }, valueRange = 5f..100f, steps = 94)
                    val peak = maxOf(goal.peakKmAt5Days * days.size / 5.0, km.toDouble())
                    Spacer(Modifier.height(8.dp))
                    Text("Builds towards about ${peak.roundToInt()} km a week before the taper.", style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        Button(
            onClick = {
                if (page < PAGES - 1) page++ else onDone(
                    Profile(distance, timeSec.toDouble(), goal, start, race, days.sorted(), km.roundToInt().toDouble(), existing?.peakKm, terrain, hills),
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().padding(24.dp).height(56.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (page < PAGES - 1) "Continue" else if (existing != null) "Update my plan" else "Build my plan")
        }
    }
}

@Composable
private fun Question(title: String, help: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(help, style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun TimeBox(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center),
        label = { Text(label) },
        placeholder = { Text("0", style = MaterialTheme.typography.displaySmall, color = Palette.Line, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(10.dp),
    )
}
