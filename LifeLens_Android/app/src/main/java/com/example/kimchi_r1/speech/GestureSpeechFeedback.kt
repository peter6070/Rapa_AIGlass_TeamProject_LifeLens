package com.example.kimchi_r1.speech

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Latest-wins Korean TTS for one-shot gesture command feedback. */
class GestureSpeechFeedback(context: Context) {
  private val lock = Any()
  private var engine: TextToSpeech? = null
  private var isReady = false
  private var pendingText: String? = null

  init {
    engine = TextToSpeech(context.applicationContext) { status ->
      val textToSpeak = synchronized(lock) {
        if (status != TextToSpeech.SUCCESS) {
          pendingText = null
          return@TextToSpeech
        }
        engine?.language = Locale.KOREAN
        isReady = true
        pendingText.also { pendingText = null }
      }
      textToSpeak?.let(::speakNow)
    }
  }

  fun speak(text: String) {
    val latest = text.trim().take(100)
    if (latest.isEmpty()) return
    synchronized(lock) {
      pendingText = latest
      if (!isReady) return
      pendingText = null
    }
    speakNow(latest)
  }

  fun cancel() {
    synchronized(lock) { pendingText = null }
    engine?.stop()
  }

  fun close() {
    cancel()
    engine?.shutdown()
    engine = null
    isReady = false
  }

  private fun speakNow(text: String) {
    // QUEUE_FLUSH cancels the current utterance so only the newest gesture is announced.
    engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gesture-${SystemClock.elapsedRealtime()}")
  }
}
