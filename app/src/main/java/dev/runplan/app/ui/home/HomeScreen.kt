package dev.runplan.app.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.runplan.app.ui.theme.Palette

@Composable
fun HomeScreen(
    openSession: (String) -> Unit,
    openRun: (String) -> Unit,
    startRun: (String?) -> Unit,
    resumeRun: () -> Unit,
    editPlan: () -> Unit,
    newPlan: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Plan" to Icons.Outlined.CalendarMonth,
        "Runs" to Icons.Outlined.History,
        "Progress" to Icons.Outlined.Insights,
        "Settings" to Icons.Outlined.Tune,
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.Ink,
                            selectedTextColor = Palette.Ink,
                            indicatorColor = Palette.Paper,
                            unselectedIconColor = Palette.Slate,
                            unselectedTextColor = Palette.Slate,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            0 -> PlanTab(m, openSession, startRun, resumeRun)
            1 -> RunsTab(m, openRun)
            2 -> ProgressTab(m, openRun)
            else -> SettingsTab(m, editPlan, newPlan)
        }
    }
}
