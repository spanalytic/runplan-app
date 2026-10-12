package dev.runplan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.runplan.app.run.RunPhase
import dev.runplan.app.run.RunService
import dev.runplan.app.run.RunSession
import dev.runplan.app.ui.home.HomeScreen
import dev.runplan.app.ui.onboarding.OnboardingScreen
import dev.runplan.app.ui.plan.SessionScreen
import dev.runplan.app.ui.run.RunScreen
import dev.runplan.app.ui.run.SummaryScreen
import dev.runplan.app.ui.theme.RunPlanTheme

class MainActivity : ComponentActivity() {
    private val openRun = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra(EXTRA_OPEN_RUN, false) == true) openRun.value++
        setContent { RunPlanTheme { AppRoot(openRun.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_RUN, false)) openRun.value++
    }

    companion object {
        const val EXTRA_OPEN_RUN = "openRun"
    }
}

object Routes {
    const val ONBOARDING = "onboarding?edit={edit}"
    fun onboarding(edit: Boolean) = "onboarding?edit=$edit"
    const val HOME = "home"
    const val SESSION = "session/{id}"
    fun session(id: String) = "session/$id"
    const val RUN = "run"
    const val SUMMARY = "summary/{id}"
    fun summary(id: String) = "summary/$id"
}

@Composable
private fun AppRoot(openRunSignal: Int) {
    val nav = rememberNavController()
    val state by RunPlanApp.store.state.collectAsStateWithLifecycle()
    val session by RunSession.view.collectAsStateWithLifecycle()
    val runActive = session.phase in setOf(RunPhase.READY, RunPhase.RUNNING, RunPhase.PAUSED)
    // Fixed for the life of the graph; changing NavHost's start destination rebuilds the graph.
    val start = remember {
        when {
            runActive -> Routes.RUN
            state.profile == null -> Routes.onboarding(false)
            else -> Routes.HOME
        }
    }
    val startRun = rememberRunStarter(nav)

    LaunchedEffect(openRunSignal) {
        if (openRunSignal > 0 && runActive && nav.currentDestination?.route != Routes.RUN) nav.navigate(Routes.RUN)
    }

    NavHost(nav, startDestination = start) {
        composable(
            Routes.ONBOARDING,
            arguments = listOf(navArgument("edit") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val edit = entry.arguments?.getBoolean("edit") ?: false
            OnboardingScreen(
                existing = if (edit) state.profile else null,
                onDone = { profile ->
                    RunPlanApp.store.setProfile(profile)
                    nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                },
                onCancel = if (edit) ({ nav.popBackStack() }) else null,
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                openSession = { nav.navigate(Routes.session(it)) },
                openRun = { nav.navigate(Routes.summary(it)) },
                startRun = startRun,
                resumeRun = { nav.navigate(Routes.RUN) },
                editPlan = { nav.navigate(Routes.onboarding(true)) },
                newPlan = {
                    RunPlanApp.store.resetPlan()
                    nav.navigate(Routes.onboarding(false)) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.SESSION) { entry ->
            SessionScreen(
                id = entry.arguments?.getString("id") ?: "",
                onBack = { nav.popBackStack() },
                startRun = startRun,
            )
        }
        composable(Routes.RUN) {
            RunScreen(
                onSaved = { id ->
                    nav.navigate(Routes.summary(id)) { popUpTo(Routes.HOME) }
                    RunSession.clear()
                },
                onDiscarded = {
                    if (!nav.popBackStack(Routes.HOME, inclusive = false)) nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.SUMMARY) { entry ->
            SummaryScreen(
                id = entry.arguments?.getString("id") ?: "",
                onBack = {
                    if (!nav.popBackStack()) nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
    }
}

/** Asks for location (and notification) permission, then starts the service and opens the run screen. */
@Composable
private fun rememberRunStarter(nav: NavHostController): (String?) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun go(id: String?) {
        RunService.send(ctx, RunService.ACTION_PREPARE, id)
        nav.navigate(Routes.RUN)
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            go(pending)
        } else {
            Toast.makeText(ctx, "RunPlan needs precise location to measure pace and distance.", Toast.LENGTH_LONG).show()
        }
    }

    return { id ->
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            go(id)
        } else {
            pending = id
            location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}
