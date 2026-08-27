package com.example.kimchi_r1.speech

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Latest-wins Korean TTS for one-shot gesture command feedback. */
class GestureSpeechFeedback(context: Context) {
  private data class SpeechRequest(
      val text: String,
      val utteranceId: String,
      val onFinished: ((Boolean) -> Unit)? = null,
  )

  private val lock = Any()
  private var engine: TextToSpeech? = null
  private var isReady = false
  private var pendingRequest: SpeechRequest? = null
  private var activeRequest: SpeechRequest? = null

  init {
    engine = TextToSpeech(context.applicationContext) { status ->
      val requestToSpeak = synchronized(lock) {
        if (status != TextToSpeech.SUCCESS) {
          pendingRequest?.onFinished?.invoke(false)
          pendingRequest = null
          return@TextToSpeech
        }
        engine?.language = Locale.KOREAN
        engine?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
              override fun onStart(utteranceId: String) = Unit

              override fun onDone(utteranceId: String) = finish(utteranceId, true)

              @Deprecated("Deprecated by Android, but required on older TTS engines")
              override fun onError(utteranceId: String) = finish(utteranceId, false)

              override fun onError(utteranceId: String, errorCode: Int) =
                  finish(utteranceId, false)

              override fun onStop(utteranceId: String, interrupted: Boolean) =
                  finish(utteranceId, false)
            }
        )
        isReady = true
        pendingRequest.also { pendingRequest = null }
      }
      requestToSpeak?.let(::speakNow)
    }
  }

  fun speak(text: String) {
    val latest = text.trim().take(100)
    if (latest.isEmpty()) return
    enqueue(SpeechRequest(latest, nextUtteranceId()))
  }

  /** Resumes only after the TTS engine reports that audible playback has ended. */
  suspend fun speakAndAwait(text: String): Boolean =
      suspendCancellableCoroutine { continuation ->
        val latest = text.trim().take(100)
        if (latest.isEmpty()) {
          continuation.resume(false)
          return@suspendCancellableCoroutine
        }
        val request =
            SpeechRequest(latest, nextUtteranceId()) { completed ->
              if (continuation.isActive) continuation.resume(completed)
            }
        continuation.invokeOnCancellation { cancelRequest(request.utteranceId) }
        enqueue(request)
      }

  private fun enqueue(request: SpeechRequest) {
    var shouldSpeak = false
    val interrupted = synchronized(lock) {
      (pendingRequest ?: activeRequest).also {
        pendingRequest = if (isReady) null else request
        if (isReady) shouldSpeak = true
      }
    }
    interrupted?.onFinished?.invoke(false)
    if (shouldSpeak) speakNow(request)
  }

  fun cancel() {
    val interrupted = synchronized(lock) {
      (pendingRequest ?: activeRequest).also {
        pendingRequest = null
        activeRequest = null
      }
    }
    engine?.stop()
    interrupted?.onFinished?.invoke(false)
  }

  fun close() {
    cancel()
    engine?.shutdown()
    engine = null
    isReady = false
  }

  private fun speakNow(request: SpeechRequest) {
    // QUEUE_FLUSH cancels the current utterance so only the newest gesture is announced.
    synchronized(lock) { activeRequest = request }
    val result = engine?.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.utteranceId)
    if (result == TextToSpeech.ERROR) finish(request.utteranceId, false)
  }

  private fun finish(utteranceId: String, completed: Boolean) {
    val finished = synchronized(lock) {
      if (activeRequest?.utteranceId != utteranceId) return
      activeRequest.also { activeRequest = null }
    }
    finished?.onFinished?.invoke(completed)
  }

  private fun cancelRequest(utteranceId: String) {
    val shouldStop = synchronized(lock) {
      when (utteranceId) {
        pendingRequest?.utteranceId -> {
          pendingRequest = null
          false
        }
        activeRequest?.utteranceId -> {
          activeRequest = null
          true
        }
        else -> false
      }
    }
    if (shouldStop) engine?.stop()
  }

  private fun nextUtteranceId(): String = "gesture-${SystemClock.elapsedRealtimeNanos()}"
}
