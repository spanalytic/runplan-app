package dev.runplan.app.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.runplan.app.RunPlanApp
import dev.runplan.app.ui.theme.Palette
import dev.runplan.core.Moves
import dev.runplan.core.Plan
import dev.runplan.core.Workout
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayName = DateTimeFormatter.ofPattern("EEEE d", Locale.UK)
private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.UK)

/** Pick another day of the same week for [w]. A session already on that day swaps with it. */
@Composable
fun MoveDialog(plan: Plan, w: Workout, done: Set<String>, onDismiss: () -> Unit) {
    val days = Moves.choices(plan, w)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${w.type.label.lowercase()}") },
        text = {
            Column {
                Text("Pick a day this week. If there's already a session that day, the two swap.", style = MaterialTheme.typography.bodyMedium, color = Palette.Slate)
                Column(Modifier.padding(top = 12.dp)) {
                    days.forEach { day ->
                        val here = day == w.epochDay
                        val other = plan.on(day)?.takeIf { !here }
                        // a done session keeps its day, so it can't be swapped out
                        val enabled = !here && (other == null || other.id !in done)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (here) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)
                                .clickable(enabled = enabled) { RunPlanApp.store.moveSession(w, day); onDismiss() }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                LocalDate.ofEpochDay(day).format(dayName),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (enabled || here) MaterialTheme.colorScheme.onBackground else Palette.Slate,
                                modifier = Modifier.width(120.dp),
                            )
                            Text(
                                when {
                                    here -> "Now"
                                    other == null -> if (day == w.planDay) "Planned day" else "Rest day"
                                    other.id in done -> "${other.type.label}, done"
                                    else -> "Swap with ${other.type.label.lowercase()}"
                                },
                                style = MaterialTheme.typography.bodySmall, color = Palette.Slate,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Moved from Tuesday", or empty when the session is on the day the plan gave it. */
fun movedNote(w: Workout): String = if (w.moved) "Moved from ${LocalDate.ofEpochDay(w.planDay).format(weekday)}" else ""
