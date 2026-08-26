package com.example.kimchi_r1.speech

import android.app.Application
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kimchi_r1.lifelog.LifeLogRepository
import com.example.kimchi_r1.lifelog.LifeLogSyncer
import com.example.kimchi_r1.lifelog.SpeechRecord
import com.example.kimchi_r1.stream.StreamingService
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpeechToTextUiState(
    val isListening: Boolean = false,
    val partialText: String = "",
    val transcripts: List<SpeechRecord> = emptyList(),
    val error: String? = null,
)

/** Phone-microphone STT session. It restarts after each utterance while the mic is enabled. */
class SpeechToTextViewModel(application: Application) : AndroidViewModel(application) {
  private val lifeLogRepository = LifeLogRepository(application)
  private val _uiState = MutableStateFlow(SpeechToTextUiState())
  val uiState: StateFlow<SpeechToTextUiState> = _uiState.asStateFlow()

  private var recognizer: SpeechRecognizer? = null
  private var shouldListen = false
  private var isStarting = false
  private var pendingPartialText = ""

  fun toggleListening() {
    if (shouldListen) stopListening() else startListening()
  }

  fun startListening() {
    if (shouldListen || isStarting) return
    if (!SpeechRecognizer.isRecognitionAvailable(getApplication())) {
      _uiState.value = _uiState.value.copy(error = "이 기기에서 음성 인식을 사용할 수 없습니다")
      return
    }
    shouldListen = true
    StreamingService.start(getApplication())
    startRecognition()
  }

  fun stopListening() {
    appendTranscript(pendingPartialText)
    shouldListen = false
    isStarting = false
    recognizer?.stopListening()
    _uiState.value = _uiState.value.copy(isListening = false, partialText = "")
  }

  fun clearTranscript() {
    _uiState.value = _uiState.value.copy(transcripts = emptyList(), partialText = "", error = null)
  }

  private fun startRecognition() {
    if (!shouldListen || isStarting) return
    isStarting = true
    val speechRecognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(getApplication()).also {
      recognizer = it
      it.setRecognitionListener(listener)
    }
    _uiState.value = _uiState.value.copy(isListening = true, error = null)
    speechRecognizer.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    )
    isStarting = false
  }

  private fun restartRecognition() {
    if (!shouldListen) return
    viewModelScope.launch {
      delay(RESTART_DELAY_MS)
      startRecognition()
    }
  }

  private fun appendTranscript(text: String) {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return
    pendingPartialText = ""
    viewModelScope.launch {
      val record = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        lifeLogRepository.insert(cleaned).also { LifeLogSyncer.syncRecord(getApplication(), it) }
      }
      _uiState.value = _uiState.value.copy(
          transcripts = (_uiState.value.transcripts + record).takeLast(MAX_TRANSCRIPTS),
          partialText = "",
          error = null,
      )
    }
  }

  private val listener = object : RecognitionListener {
    override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
      isStarting = false
      if (!shouldListen) return
      // Some recognizers expose only partial results before finishing with NO_MATCH/TIMEOUT.
      // Preserve the visible subtitle as a timestamped life-log record in that case.
      appendTranscript(pendingPartialText)
      _uiState.value = _uiState.value.copy(partialText = "", error = errorText(error))
      restartRecognition()
    }

    override fun onResults(results: android.os.Bundle?) {
      isStarting = false
      results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::appendTranscript)
      restartRecognition()
    }

    override fun onPartialResults(partialResults: android.os.Bundle?) {
      val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
      pendingPartialText = text
      _uiState.value = _uiState.value.copy(partialText = text)
    }

    override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
  }

  override fun onCleared() {
    shouldListen = false
    recognizer?.destroy()
    recognizer = null
    super.onCleared()
  }

  private fun errorText(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "마이크 입력 오류"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요합니다"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "음성 인식 네트워크 오류"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식기를 다시 시작합니다"
    else -> "다음 음성을 기다리는 중"
  }

  private companion object {
    const val MAX_TRANSCRIPTS = 50
    const val RESTART_DELAY_MS = 350L
  }
}
