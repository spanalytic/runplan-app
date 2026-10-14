package dev.runplan.wear

import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dev.runplan.core.PaceStatus
import dev.runplan.core.RunState
import dev.runplan.core.WearPaths
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Watch display for a run recorded on the phone. Receives [RunState] once a second,
 * buzzes on step changes and when pace leaves the target band, and sends pause/skip/start back.
 */
class MainActivity : ComponentActivity() {
    private val state = mutableStateOf(RunState.IDLE)
    private val lastMessageMs = mutableLongStateOf(0L)
    private var lastStatus = PaceStatus.NONE
    private var lastStep = -1
    private var lastFinished = false
    private var lastLabel = ""
    private var lastBuzzMs = 0L

    private val listener = MessageClient.OnMessageReceivedListener { ev ->
        if (ev.path != WearPaths.STATE) return@OnMessageReceivedListener
        val s = RunState.decode(ev.data) ?: return@OnMessageReceivedListener
        runOnUiThread { onState(s) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp(
                state = state.value,
                lastMessageMs = lastMessageMs.longValue,
                send = ::send,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(listener)
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(listener)
        super.onPause()
    }

    private fun onState(s: RunState) {
        state.value = s
        lastMessageMs.longValue = SystemClock.elapsedRealtime()
        val counting = s.stepLabel.startsWith("Starting in ")
        val running = s.stepCount > 0 && !s.finished && !s.paused && s.stepLabel != "Ready" && s.stepLabel != "Finding GPS" && !counting
        if (counting && s.stepLabel != lastLabel) buzz(longArrayOf(0, 60))   // tick each second of the start countdown
        lastLabel = s.stepLabel

        // keep the display on only while a run is live
        if (s.stepCount > 0 && !s.finished) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (running && lastStep != -1 && s.stepIndex != lastStep) buzz(longArrayOf(0, 180, 100, 180, 100, 180))
        if (s.finished && !lastFinished) buzz(longArrayOf(0, 600))
        val now = SystemClock.elapsedRealtime()
        if (running && s.status != lastStatus && now - lastBuzzMs > 10_000) {
            when (s.status) {
                PaceStatus.SLOW -> { buzz(longArrayOf(0, 90, 90, 90)); lastBuzzMs = now }   // two taps: speed up
                PaceStatus.FAST -> { buzz(longArrayOf(0, 400)); lastBuzzMs = now }          // one long: ease off
                else -> {}
            }
        }
        lastStep = s.stepIndex
        lastStatus = s.status
        lastFinished = s.finished
    }

    private fun buzz(pattern: LongArray) {
        runCatching { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun send(cmd: String) {
        lifecycleScope.launch {
            runCatching {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                nodes.forEach { Wearable.getMessageClient(this@MainActivity).sendMessage(it.id, WearPaths.COMMAND, cmd.toByteArray()).await() }
            }
        }
        buzz(longArrayOf(0, 40))
    }
}
