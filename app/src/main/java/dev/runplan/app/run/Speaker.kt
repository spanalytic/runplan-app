package dev.runplan.app.run

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text-to-speech that ducks music/podcasts while it talks.
 *
 * Starts of sentences were being clipped: when speech begins, the podcast is still fading down
 * and Bluetooth headphones need a moment to switch streams. So each burst of speech starts with
 * a short silent lead-in after audio focus is granted. Cues queue rather than interrupt, so the
 * "10 seconds, next…" warning is never cut off by the step change that follows it.
 */
class Speaker(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setWillPauseWhenDucked(false)
        .build()
    private val pending = AtomicInteger(0)
    @Volatile private var lastDoneMs = 0L
    @Volatile private var ready = false
    @Volatile private var held = false
    private val queued = ArrayList<Pair<String, Boolean>>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            onReady()
        }
    }

    private fun onReady() {
        tts.language = Locale.UK
        tts.setAudioAttributes(attrs)
        tts.setSpeechRate(0.95f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = release(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = release(id)
            override fun onStop(id: String?, interrupted: Boolean) = release(id)
        })
        synchronized(queued) {
            queued.forEach { (t, f) -> speak(t, f) }
            queued.clear()
        }
    }

    private fun release(id: String?) {
        if (id == null || id.startsWith("lead")) return
        lastDoneMs = SystemClock.elapsedRealtime()
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /** @param interrupt drop anything still queued (only for pause/resume). */
    fun speak(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        if (!ready) { synchronized(queued) { queued += text to interrupt }; return }
        if (interrupt) {
            tts.stop()
            pending.set(0)
            held = false   // the hold's count went with the reset
        }
        val idle = pending.get() == 0
        pending.incrementAndGet()
        if (idle) {
            audio.requestAudioFocus(focus)
            // give the podcast time to duck and Bluetooth audio time to wake before the first word
            val lead = if (SystemClock.elapsedRealtime() - lastDoneMs < 3_000) LEAD_SHORT_MS else LEAD_MS
            tts.playSilentUtterance(lead, TextToSpeech.QUEUE_ADD, "lead${System.nanoTime()}")
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
    }

    /**
     * Keep audio focus (music ducked) across several short utterances, e.g. the start countdown,
     * so the music doesn't bob up and down between words and each word plays without a lead-in.
     */
    fun hold() {
        if (held || !ready) return
        held = true
        val idle = pending.get() == 0
        pending.incrementAndGet()
        if (idle) {
            audio.requestAudioFocus(focus)
            tts.playSilentUtterance(LEAD_MS, TextToSpeech.QUEUE_ADD, "lead${System.nanoTime()}")
        }
    }

    /** End a [hold]; focus is released once anything still queued has been spoken. */
    fun unhold() {
        if (!held) return
        held = false
        release("hold")
    }

    /**
     * Shut down once everything queued has been spoken (or after [maxWaitMs]). The service stops a
     * few seconds after the run ends, and stopping TTS immediately cut the finish message off.
     */
    fun shutdownWhenQuiet(maxWaitMs: Long = 30_000) {
        unhold()
        val main = Handler(Looper.getMainLooper())
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        main.post(object : Runnable {
            override fun run() {
                val busy = synchronized(queued) { queued.isNotEmpty() } ||
                    (ready && (pending.get() > 0 || runCatching { tts.isSpeaking }.getOrDefault(false)))
                if (busy && SystemClock.elapsedRealtime() < deadline) main.postDelayed(this, 250) else shutdown()
            }
        })
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        audio.abandonAudioFocusRequest(focus)
    }

    private companion object {
        const val LEAD_MS = 700L
        const val LEAD_SHORT_MS = 250L
    }
}
