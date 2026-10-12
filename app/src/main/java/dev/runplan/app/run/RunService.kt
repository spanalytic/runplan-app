package dev.runplan.app.run

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.runplan.app.MainActivity
import dev.runplan.app.R
import dev.runplan.app.RunPlanApp
import dev.runplan.app.data.RunRecord
import dev.runplan.app.data.Strava
import dev.runplan.core.Cue
import dev.runplan.core.Fmt
import dev.runplan.core.Odometer
import dev.runplan.core.RunEngine
import dev.runplan.core.RunStep
import dev.runplan.core.SessionType
import dev.runplan.core.StepKind
import dev.runplan.core.TrackPoint
import dev.runplan.core.WearPaths
import dev.runplan.core.Workout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Foreground service that records a run: GPS -> [Odometer] -> [RunEngine] -> speech, watch, UI.
 * Lifecycle: PREPARE (GPS warms up, READY) -> START -> PAUSE/RESUME/SKIP -> FINISH (saved) or DISCARD.
 */
class RunService : LifecycleService() {

    private val store get() = RunPlanApp.store
    private lateinit var speaker: Speaker
    private lateinit var watch: WatchLink
    private var engine: RunEngine? = null
    private var workoutId: String? = null
    private val odo = Odometer()
    private var phase = RunPhase.IDLE
    private var activeAccumMs = 0L
    private var resumedAtMs = 0L
    private var startWallMs = 0L
    private var startClockMs = 0L
    private val stepStarts = ArrayList<Long>()
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null

    private val locationListener = LocationListener { loc: Location ->
        odo.add(
            TrackPoint(
                tMs = loc.elapsedRealtimeNanos / 1_000_000,
                lat = loc.latitude,
                lon = loc.longitude,
                alt = loc.altitude,
                acc = loc.accuracy,
                // GNSS Doppler speed: far steadier than speed derived from positions
                speed = if (loc.hasSpeed()) loc.speed else Float.NaN,
                speedAcc = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else Float.NaN,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        speaker = Speaker(this)
        watch = WatchLink(this, lifecycleScope) { cmd ->
            when (cmd) {
                WearPaths.CMD_PAUSE -> pause()
                WearPaths.CMD_RESUME -> resume()
                WearPaths.CMD_SKIP -> skip()
                WearPaths.CMD_START -> start()
            }
        }
        watch.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PREPARE -> prepare(intent.getStringExtra(EXTRA_WORKOUT))
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_SKIP -> skip()
            ACTION_FINISH -> finish(save = true)
            ACTION_DISCARD -> finish(save = false)
        }
        return START_NOT_STICKY
    }

    // ---- lifecycle ------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun prepare(id: String?) {
        if (phase != RunPhase.IDLE) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val plan = store.plan.value
        val today = LocalDate.now().toEpochDay()
        val workout: Workout = id?.let { plan?.find(it) } ?: Workout.free(today)
        val paces = plan?.paces ?: store.state.value.profile?.paces
        val steps = if (paces != null) RunStep.flatten(workout, paces)
            else listOf(RunStep("Free run", StepKind.INTERVAL, null, null, null, Double.NaN, Double.NaN))
        engine = RunEngine(workout.type.label, steps)
        workoutId = if (workout.type == SessionType.FREE) null else workout.id
        phase = RunPhase.READY
        odo.pause()   // keep listening for a fix, but don't count distance until START

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Getting GPS", "Tap to open"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunPlan:run").apply { acquire(6 * 60 * 60 * 1000L) }
        locationManager = getSystemService(LocationManager::class.java).also {
            it.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        }
        RunSession.set(SessionView(RunPhase.READY, workoutId, steps, engine!!.state(false, false)))
        ticker = lifecycleScope.launch {
            var lastPublish = 0L
            while (isActive) {
                tick()
                val now = SystemClock.elapsedRealtime()
                if (now - lastPublish >= 1000) { publish(); lastPublish = now }
                delay(250)
            }
        }
    }

    private fun start() {
        val e = engine ?: return
        if (phase != RunPhase.READY) return
        phase = RunPhase.RUNNING
        startWallMs = System.currentTimeMillis()
        startClockMs = SystemClock.elapsedRealtime()
        resumedAtMs = SystemClock.elapsedRealtime()
        odo.resume()
        handle(e.start())
        publish()
    }

    private fun pause() {
        if (phase != RunPhase.RUNNING) return
        activeAccumMs += SystemClock.elapsedRealtime() - resumedAtMs
        odo.pause()
        phase = RunPhase.PAUSED
        if (store.state.value.settings.voice) speaker.speak("Paused.", interrupt = true)
        publish()
    }

    private fun resume() {
        if (phase != RunPhase.PAUSED) return
        resumedAtMs = SystemClock.elapsedRealtime()
        odo.resume()
        phase = RunPhase.RUNNING
        if (store.state.value.settings.voice) speaker.speak("Resumed.", interrupt = true)
        publish()
    }

    private fun skip() {
        val e = engine ?: return
        if (phase != RunPhase.RUNNING && phase != RunPhase.PAUSED) return
        handle(e.advance())
        publish()
    }

    private fun activeMs(): Long = when (phase) {
        RunPhase.RUNNING -> activeAccumMs + (SystemClock.elapsedRealtime() - resumedAtMs)
        else -> activeAccumMs
    }

    private fun tick() {
        val e = engine ?: return
        if (phase != RunPhase.RUNNING) return
        val now = SystemClock.elapsedRealtime()
        handle(e.update(activeMs(), odo.distanceM, odo.pace(now)))
    }

    private fun handle(cues: List<Cue>) {
        val s = store.state.value.settings
        for (c in cues) {
            val speak = s.voice && when (c) {
                is Cue.PaceAlert -> s.paceAlerts
                is Cue.Split -> s.splits
                else -> true
            }
            if (speak) speaker.speak(c.speech(), interrupt = c is Cue.StepStart || c is Cue.Finished)
            if (c is Cue.StepStart) {
                stepStarts += SystemClock.elapsedRealtime()
                buzz(longArrayOf(0, 120, 80, 120))
            }
            if (c is Cue.Finished) {
                buzz(longArrayOf(0, 400))
                lifecycleScope.launch { delay(4000); finish(save = true) }
            }
        }
    }

    private fun publish() {
        val e = engine ?: return
        val now = SystemClock.elapsedRealtime()
        if (phase == RunPhase.RUNNING) handle(e.update(activeMs(), odo.distanceM, odo.pace(now)))
        val st = e.state(paused = phase == RunPhase.PAUSED, gpsOk = odo.gpsOk(now))
        val shown = if (phase == RunPhase.READY) st.copy(stepLabel = if (st.gpsOk) "Ready" else "Finding GPS") else st
        RunSession.update { it.copy(phase = phase, state = shown) }
        watch.send(shown)
        val title = if (phase == RunPhase.READY) shown.stepLabel else "${st.stepLabel}  ${Fmt.pace(st.pace)}/km"
        val text = "${Fmt.duration(st.elapsedSec)}   ${Fmt.km(st.distanceM)} km"
        notify(title, text)
    }

    private fun finish(save: Boolean) {
        if (phase == RunPhase.IDLE || phase == RunPhase.SAVED) return
        val e = engine
        if (phase == RunPhase.RUNNING) activeAccumMs += SystemClock.elapsedRealtime() - resumedAtMs
        var savedId: String? = null
        if (save && e != null && phase != RunPhase.READY) {
            e.finish()
            val id = startWallMs.toString()
            store.addRun(
                RunRecord(
                    id = id,
                    epochDay = LocalDate.now().toEpochDay(),
                    workoutId = workoutId,
                    title = e.title,
                    startMs = startWallMs,
                    sec = activeAccumMs / 1000.0,
                    metres = odo.distanceM,
                    steps = e.results.toList(),
                    splits = e.splits.toList(),
                    startClockMs = startClockMs,
                    stepStartsMs = stepStarts.toList(),
                ),
                odo.points.toList(),
            )
            savedId = id
            if (store.state.value.strava.let { it.connected && it.autoUpload }) {
                val run = store.state.value.runs.first { it.id == id }
                RunPlanApp.scope.launch { runCatching { Strava.upload(run) } }
            }
            watch.send(e.state(paused = false, gpsOk = true))
        }
        phase = if (savedId != null) RunPhase.SAVED else RunPhase.IDLE
        RunSession.update { it.copy(phase = phase, savedRunId = savedId) }
        if (savedId == null) RunSession.clear()
        stopEverything()
    }

    private fun stopEverything() {
        ticker?.cancel()
        runCatching { locationManager?.removeUpdates(locationListener) }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (phase == RunPhase.RUNNING || phase == RunPhase.PAUSED) finish(save = true)
        watch.stop()
        speaker.shutdown()
        super.onDestroy()
    }

    // ---- feedback -------------------------------------------------------------------------

    private fun buzz(pattern: LongArray) {
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_RUN, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, RunPlanApp.CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun notify(title: String, text: String) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(title, text))
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 7
        const val ACTION_PREPARE = "prepare"
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_SKIP = "skip"
        const val ACTION_FINISH = "finish"
        const val ACTION_DISCARD = "discard"
        const val EXTRA_WORKOUT = "workout"

        fun send(context: Context, action: String, workoutId: String? = null) {
            val i = Intent(context, RunService::class.java).setAction(action)
            workoutId?.let { i.putExtra(EXTRA_WORKOUT, it) }
            if (action == ACTION_PREPARE) ContextCompat.startForegroundService(context, i) else context.startService(i)
        }
    }
}
