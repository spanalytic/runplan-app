package dev.runplan.app.run

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import dev.runplan.core.Cue
import dev.runplan.core.CustomWorkout
import dev.runplan.core.Dates
import dev.runplan.core.Fmt
import dev.runplan.core.Odometer
import dev.runplan.core.RunEngine
import dev.runplan.core.RunStep
import dev.runplan.core.SessionType
import dev.runplan.core.StepKind
import dev.runplan.core.StepResult
import dev.runplan.core.TrackPoint
import dev.runplan.core.WearPaths
import dev.runplan.core.Workout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Foreground service that records a run: GPS -> [Odometer] -> [RunEngine] -> speech, watch, UI.
 * Lifecycle: PREPARE (GPS warms up, READY) -> START -> PAUSE/RESUME/SKIP -> FINISH (saved) or DISCARD.
 * While recording, progress is saved every [CHECKPOINT_MS] so a crash keeps the run (see Store.saveCheckpoint).
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
    private var countdownJob: Job? = null
    private var countdown = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var checkpointJob: Job? = null
    private var lastCheckpointMs = 0L
    /** Guards [checkpointsOn] so a save in flight can't bring back a run that was just saved or discarded. */
    private val checkpointLock = Any()
    private var checkpointsOn = false

    /** Barometric pressure (hPa), lightly smoothed; NaN on phones without a barometer. Climb uses it. */
    @Volatile private var pressureHpa = Float.NaN
    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values.firstOrNull() ?: return
            val p = pressureHpa
            pressureHpa = if (p.isNaN()) v else p + 0.3f * (v - p)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

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
                baroAlt = pressureHpa.let { p ->
                    if (p.isFinite()) SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p).toDouble() else Double.NaN
                },
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
            // restarted by the system after the app was closed mid-run: the run was already
            // recovered from its last saved progress when the app started, so just stop
            null -> if (phase == RunPhase.IDLE) stopSelf(startId)
        }
        // ask to be restarted if killed, so an interrupted run is recovered straight away
        return START_STICKY
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
        val custom = CustomWorkout.fromId(id)
        val workout: Workout = custom?.toWorkout(today) ?: id?.let { plan?.find(it) } ?: Workout.free(today)
        val paces = plan?.paces ?: store.state.value.profile?.paces
        val steps = if (paces != null) RunStep.flatten(workout, paces)
            else listOf(RunStep("Free run", StepKind.INTERVAL, null, null, null, Double.NaN, Double.NaN))
        engine = RunEngine(custom?.title?.replaceFirstChar { it.uppercase() } ?: workout.type.label, steps)
        // a free run or a workout you built yourself doesn't tick off a planned session
        workoutId = if (workout.type == SessionType.FREE || workout.type == SessionType.CUSTOM) null else workout.id
        phase = RunPhase.READY
        odo.pause()   // keep listening for a fix, but don't count distance until START

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Getting GPS", "Tap to open"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunPlan:run").apply { acquire(6 * 60 * 60 * 1000L) }
        sensorManager = getSystemService(SensorManager::class.java)?.also { sm ->
            sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(pressureListener, it, 1_000_000) }
        }
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
                if (now - lastCheckpointMs >= CHECKPOINT_MS) checkpoint()
                delay(250)
            }
        }
    }

    /** Start pressed (phone or watch): count down 5 seconds, then start recording. */
    private fun start() {
        if (engine == null || phase != RunPhase.READY || countdownJob != null) return
        countdownJob = lifecycleScope.launch {
            val voice = store.state.value.settings.voice
            if (voice) speaker.hold()
            for (n in COUNTDOWN_SEC downTo 1) {
                countdown = n
                RunSession.update { it.copy(countdown = n) }
                publish()
                if (voice) speaker.speak(n.toString())
                buzz(longArrayOf(0, 60))
                delay(1000)
            }
            countdown = 0
            RunSession.update { it.copy(countdown = 0) }
            countdownJob = null
            begin()
            if (voice) speaker.unhold()
        }
    }

    private fun begin() {
        val e = engine ?: return
        if (phase != RunPhase.READY) return
        phase = RunPhase.RUNNING
        startWallMs = System.currentTimeMillis()
        startClockMs = SystemClock.elapsedRealtime()
        resumedAtMs = SystemClock.elapsedRealtime()
        odo.resume()
        synchronized(checkpointLock) { checkpointsOn = true }
        handle(e.start())
        publish()
        checkpoint()
    }

    private fun pause() {
        if (phase != RunPhase.RUNNING) return
        activeAccumMs += SystemClock.elapsedRealtime() - resumedAtMs
        odo.pause()
        phase = RunPhase.PAUSED
        if (store.state.value.settings.voice) speaker.speak("Paused.", interrupt = true)
        publish()
        checkpoint()
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
            if (speak) speaker.speak(c.speech())
            if (c is Cue.StepStart) {
                stepStarts += SystemClock.elapsedRealtime()
                buzz(longArrayOf(0, 120, 80, 120))
            }
            if (c is Cue.Finished) {
                buzz(longArrayOf(0, 400))
                // stop counting now: the saved run must match the summary just spoken
                odo.pause()
                runCatching { locationManager?.removeUpdates(locationListener) }
                lifecycleScope.launch { finish(save = true) }
            }
        }
    }

    private fun publish() {
        val e = engine ?: return
        val now = SystemClock.elapsedRealtime()
        if (phase == RunPhase.RUNNING) handle(e.update(activeMs(), odo.distanceM, odo.pace(now)))
        val st = e.state(paused = phase == RunPhase.PAUSED, gpsOk = odo.gpsOk(now))
        val shown = if (phase == RunPhase.READY) st.copy(stepLabel = when {
            countdown > 0 -> "$STARTING_IN$countdown"
            st.gpsOk -> "Ready"
            else -> "Finding GPS"
        }) else st
        RunSession.update { it.copy(phase = phase, state = shown) }
        watch.send(shown)
        val title = if (phase == RunPhase.READY) shown.stepLabel else "${st.stepLabel}  ${Fmt.pace(st.pace)}/km"
        val text = "${Fmt.duration(st.elapsedSec)}   ${Fmt.km(st.distanceM)} km"
        notify(title, text)
    }

    /** The run as it stands, without ending it. */
    private fun record(e: RunEngine, sec: Double, steps: List<StepResult>) = RunRecord(
        id = startWallMs.toString(),
        // filed under the day it started, even if it ran past midnight
        epochDay = Dates.epochDay(startWallMs),
        workoutId = workoutId,
        title = e.title,
        startMs = startWallMs,
        sec = sec,
        metres = odo.distanceM,
        steps = steps,
        splits = e.splits.toList(),
        startClockMs = startClockMs,
        stepStartsMs = stepStarts.toList(),
    )

    /** Save progress in the background; skipped if the last save is still being written. */
    private fun checkpoint() {
        val e = engine ?: return
        if (phase != RunPhase.RUNNING && phase != RunPhase.PAUSED) return
        if (checkpointJob?.isActive == true) return
        lastCheckpointMs = SystemClock.elapsedRealtime()
        val r = record(e, activeMs() / 1000.0, e.resultsSoFar())
        val pts = odo.points.toList()
        checkpointJob = lifecycleScope.launch(Dispatchers.IO) {
            synchronized(checkpointLock) {
                if (checkpointsOn) runCatching { store.saveCheckpoint(r, pts) }
            }
        }
    }

    private fun finish(save: Boolean) {
        if (phase == RunPhase.IDLE || phase == RunPhase.SAVED) return
        val e = engine
        val recording = phase != RunPhase.READY
        synchronized(checkpointLock) { checkpointsOn = false }
        if (phase == RunPhase.RUNNING) activeAccumMs += SystemClock.elapsedRealtime() - resumedAtMs
        var savedId: String? = null
        if (save && e != null && phase != RunPhase.READY) {
            // ended from the Finish button: catch the engine up to now and say the same wrap-up
            // as a completed workout (nothing when the engine already finished on its own: it
            // spoke it then, and its time and distance stay frozen at that moment)
            val caughtUp = e.update(activeAccumMs, odo.distanceM, Double.NaN).filterIsInstance<Cue.Finished>()
            val wrapUp = caughtUp + e.finish()
            if (store.state.value.settings.voice) wrapUp.forEach { speaker.speak(it.speech()) }
            val (efforts, ascent) = store.analyse(odo.points)
            val r = record(e, e.elapsedMs / 1000.0, e.results.toList()).copy(metres = e.distanceM, efforts = efforts, ascentM = ascent)
            store.addRun(r, odo.points.toList())
            store.clearCheckpoint()
            savedId = r.id
            watch.send(e.state(paused = false, gpsOk = true))
        } else if (recording) {
            store.clearCheckpoint(discardTrackOf = startWallMs.toString())
        }
        phase = if (savedId != null) RunPhase.SAVED else RunPhase.IDLE
        RunSession.update { it.copy(phase = phase, savedRunId = savedId) }
        if (savedId == null) RunSession.clear()
        stopEverything()
    }

    private fun stopEverything() {
        countdownJob?.cancel()
        countdownJob = null
        ticker?.cancel()
        runCatching { locationManager?.removeUpdates(locationListener) }
        runCatching { sensorManager?.unregisterListener(pressureListener) }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (phase == RunPhase.RUNNING || phase == RunPhase.PAUSED) finish(save = true)
        watch.stop()
        speaker.shutdownWhenQuiet()
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
        private const val COUNTDOWN_SEC = 5
        private const val CHECKPOINT_MS = 15_000L
        /** Step label prefix while counting down; the watch matches on it. */
        const val STARTING_IN = "Starting in "
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
