package dev.runplan.app.run

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
    private var ready = false
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

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        audio.abandonAudioFocusRequest(focus)
    }

    private companion object {
        const val LEAD_MS = 700L
        const val LEAD_SHORT_MS = 250L
    }
}
