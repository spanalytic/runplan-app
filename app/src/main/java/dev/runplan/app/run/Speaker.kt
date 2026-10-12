package dev.runplan.app.run

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Text-to-speech that briefly ducks music while it talks. */
class Speaker(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val pending = AtomicInteger(0)
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
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = release()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = release()
        })
        synchronized(queued) {
            queued.forEach { (t, f) -> speak(t, f) }
            queued.clear()
        }
    }

    private fun release() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /** @param interrupt drop anything still queued (used for step changes). */
    fun speak(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        if (!ready) { synchronized(queued) { queued += text to interrupt }; return }
        if (pending.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        if (interrupt) pending.set(1)
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        audio.abandonAudioFocusRequest(focus)
    }
}
