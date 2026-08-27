/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraViewModel - DAT camera lifecycle, capture, and recording
//
// Drives the camera screen by exercising the SDK's camera lifecycle as explicit steps: create and
// start a DeviceSession, add and start a Stream, capture a photo or record video, stop the stream,
// end the session. Owns the screen-scoped pieces — the DeviceSession, its Stream, the on-device
// HEVC preview decoder, and the passthrough video recorder. The UI binds to the SDK's own
// DeviceSessionState / StreamState so the sample shows the real state machine.

package com.example.kimchi_r1.camera

import android.app.Application
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationManager
import android.location.Geocoder
import android.util.Log
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.example.kimchi_r1.R
import com.example.kimchi_r1.lifelog.LifeLogSyncer
import com.example.kimchi_r1.speech.GestureSpeechFeedback
import com.example.kimchi_r1.stream.AudioInputHandler
import com.example.kimchi_r1.stream.HevcDecoder
import com.example.kimchi_r1.stream.HevcParameterSetCollector
import com.example.kimchi_r1.stream.RecordingResult
import com.example.kimchi_r1.stream.StreamingService
import com.example.kimchi_r1.stream.VideoRecorder
import com.example.kimchi_r1.vision.HandGestureRecognizer
import com.example.kimchi_r1.vision.GestureControlSettings
import com.example.kimchi_r1.vision.LeftPinchPhase
import com.example.kimchi_r1.vision.OpenCvI420FrameConverter
import com.example.kimchi_r1.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CameraViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:CameraViewModel"
    private const val FRAME_RATE = 30
    private const val KEYFRAME_WAIT_STEP_MS = 25L
    private const val KEYFRAME_WAIT_MAX_MS = 500L
    // Bitmap conversion allocates a full RGBA frame. 15 fps keeps the live view responsive while
    // leaving CPU time for MediaPipe and prevents the frame collector from building up latency.
    private const val RAW_FRAME_INTERVAL_MS = 66L
    private const val AUTO_SESSION_RETRY_MS = 3_000L
    private const val STREAM_RECOVERY_DELAY_MS = 1_500L
    private const val MAX_STREAM_RECOVERY_ATTEMPTS = 3
    private const val GESTURE_STABLE_MS = 250L
    private const val GESTURE_SEQUENCE_WINDOW_MS = 2_000L
    private const val GESTURE_SHORTCUT_COOLDOWN_MS = 1_200L
    private const val PINCH_CAPTURE_DELAY_MS = 1_000L
    private const val TTS_COMPLETION_TIMEOUT_MS = 5_000L
    private const val PHOTO_UPLOAD_MAX_SIDE = 1_600
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector

  private val _uiState = MutableStateFlow(CameraUiState())
  val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
  private val _isSessionEnabled = MutableStateFlow(true)
  val isSessionEnabled: StateFlow<Boolean> = _isSessionEnabled.asStateFlow()

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null

  // Recording pieces. The single compressed-HEVC stream feeds both the on-screen decoder and the
  // passthrough MP4 writer.
  private val audioInputHandler = AudioInputHandler(application)
  private val videoRecorder = VideoRecorder(application, viewModelScope)
  private val gestureSpeechFeedback = GestureSpeechFeedback(application)
  private val gestureControlSettings = GestureControlSettings(application)

  // Per-frame work (byte copy, NAL parsing, MediaMuxer writes, decoder feed) runs at frame rate and
  // must stay off the main thread. A single-threaded dispatcher keeps frames serialized so the
  // MediaMuxer/MediaCodec see in-order calls from one consistent thread.
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
  private val photoDispatcher: ExecutorCoroutineDispatcher =
      Executors.newSingleThreadExecutor { task ->
            Thread(task, "LifeLens-photo-worker").apply { priority = Thread.MIN_PRIORITY }
          }
          .asCoroutineDispatcher()

  // Guards decoder create/teardown so a frame can't bind a new decoder to a Surface that
  // setSurface(null) just released — the @Volatile refs alone can't fix that check-then-act.
  private val decoderLock = Any()

  // @Volatile: single refs shared by the frame-collector and main threads, nulled at teardown.
  @Volatile private var hevcDecoder: HevcDecoder? = null
  @Volatile private var decoderSurface: Surface? = null
  private val i420FrameConverter = OpenCvI420FrameConverter()
  private val handGestureRecognizer =
      HandGestureRecognizer(
          application,
          onResult = ::onHandGesture,
          onLeftPinch = ::onLeftPinch,
      )
  private var lastRawFrameAtMs = 0L
  private var lastAutoSessionAttemptAtMs = 0L
  private var streamRecoveryAttempts = 0
  private val gestureShortcutLock = Any()
  private var candidateGesture = ""
  private var candidateSinceMs = 0L
  private var armedFistAtMs = 0L
  private var lastShortcutAtMs = 0L
  private var pinchCaptureJob: Job? = null

  // Accumulates the stream's HEVC parameter sets (VPS/SPS/PPS) so a recording (or decoder) started
  // mid-stream can be primed with a complete format. The SDK emits the VPS once at stream start, so
  // a partial set yields an unfinalizable file ("Missing codec specific data"). Internally
  // synchronized.
  private val csdCollector = HevcParameterSetCollector()

  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var videoJob: Job? = null
  private var streamStateJob: Job? = null
  private var streamErrorJob: Job? = null

  /** Every shortcut is one-shot: a stable fist arms it, then a stable target gesture fires it. */
  private fun onHandGesture(label: String, confidence: Int) {
    val now = System.currentTimeMillis()
    var shortcut: String? = null
    synchronized(gestureShortcutLock) {
      if (label != candidateGesture) {
        candidateGesture = label
        candidateSinceMs = now
      }
      if (now - armedFistAtMs > GESTURE_SEQUENCE_WINDOW_MS) armedFistAtMs = 0L
      if (confidence < 70 || now - candidateSinceMs < GESTURE_STABLE_MS) {
        _uiState.update { it.copy(gestureName = label, gestureConfidence = confidence) }
        return
      }
      when (label) {
        "주먹" -> if (armedFistAtMs == 0L) armedFistAtMs = now
        "손바닥 펼침" -> if (
            gestureControlSettings.isEnabled(GestureControlSettings.POWER) &&
                armedFistAtMs != 0L &&
                now - lastShortcutAtMs >= GESTURE_SHORTCUT_COOLDOWN_MS
        ) {
          shortcut = "toggle_lights"
          armedFistAtMs = 0L
          lastShortcutAtMs = now
        }
        "브이 · 다음" -> if (
            gestureControlSettings.isEnabled(GestureControlSettings.PRESENTATION) &&
                armedFistAtMs != 0L &&
                now - lastShortcutAtMs >= GESTURE_SHORTCUT_COOLDOWN_MS
        ) {
          shortcut = "presentation_next"
          armedFistAtMs = 0L
          lastShortcutAtMs = now
        }
        "브이 · 이전" -> if (
            gestureControlSettings.isEnabled(GestureControlSettings.PRESENTATION) &&
                armedFistAtMs != 0L &&
                now - lastShortcutAtMs >= GESTURE_SHORTCUT_COOLDOWN_MS
        ) {
          shortcut = "presentation_previous"
          armedFistAtMs = 0L
          lastShortcutAtMs = now
        }
        "브이" -> if (armedFistAtMs != 0L && now - lastShortcutAtMs >= GESTURE_SHORTCUT_COOLDOWN_MS) {
          shortcut = "cycle_light_color"
          armedFistAtMs = 0L
          lastShortcutAtMs = now
        }
      }
    }
    _uiState.update {
      it.copy(
          gestureName = if (label == "주먹" && armedFistAtMs != 0L) "주먹 · 장전됨" else label,
          gestureConfidence = confidence,
          iotShortcutAction = shortcut ?: it.iotShortcutAction,
          iotShortcutSequence = if (shortcut != null) it.iotShortcutSequence + 1 else it.iotShortcutSequence,
      )
    }
  }

  private fun onLeftPinch(phase: LeftPinchPhase) {
    if (!gestureControlSettings.isEnabled(GestureControlSettings.PINCH)) return
    val phaseLabel =
        when (phase) {
          LeftPinchPhase.ARMING -> "왼손 핀치 · 장전 중"
          LeftPinchPhase.ARMED -> "왼손 핀치 · 장전됨"
          LeftPinchPhase.CONTACTED -> "왼손 핀치 · 접촉"
          LeftPinchPhase.RELEASED -> "왼손 핀치 · 해제"
          LeftPinchPhase.IDLE -> return
        }
    _uiState.update { it.copy(gestureName = phaseLabel, gestureConfidence = 100) }
    if (phase != LeftPinchPhase.RELEASED || pinchCaptureJob?.isActive == true) return
    if (!_uiState.value.isStreaming || _uiState.value.isCapturingPhoto) return

    pinchCaptureJob =
        viewModelScope.launch {
          try {
            withTimeoutOrNull(TTS_COMPLETION_TIMEOUT_MS) {
              gestureSpeechFeedback.speakAndAwait("사진으로 기록합니다")
            }
            delay(PINCH_CAPTURE_DELAY_MS)
            if (gestureControlSettings.isEnabled(GestureControlSettings.PINCH)) capturePhoto()
          } finally {
            pinchCaptureJob = null
          }
        }
  }

  init {
    videoRecorder.setAudioInputHandler(audioInputHandler)

    // 등록된 활성 글래스가 보이면 화면 진입 즉시 세션과 스트림을 시작한다.
    viewModelScope.launch {
      wearablesViewModel.uiState.collect { startSessionAutomaticallyIfReady() }
    }

    // Mirror the recorder's intent/elapsed into UI state.
    viewModelScope.launch {
      videoRecorder.isRecording.collect { recording ->
        _uiState.update { it.copy(isRecording = recording) }
      }
    }
    viewModelScope.launch {
      videoRecorder.recordingElapsedSeconds.collect { seconds ->
        _uiState.update { it.copy(recordingElapsedSeconds = seconds) }
      }
    }
    // Stop a recording gracefully if the mic is interrupted (e.g. a phone call).
    viewModelScope.launch {
      audioInputHandler.wasInterrupted.collect { interrupted ->
        if (interrupted && _uiState.value.isRecording) {
          Log.w(TAG, "Audio interrupted — stopping recording")
          stopVideoRecording()
        }
      }
    }
  }

  // MARK: - Surface

  fun setSurface(surface: Surface?) {
    synchronized(decoderLock) {
      decoderSurface = surface
      if (surface == null) {
        hevcDecoder?.stop()
        hevcDecoder = null
      }
    }
  }

  // MARK: - Lifecycle step 1: session

  /** Creates and starts a [DeviceSession] (no stream yet). */
  fun startSession() {
    if (_uiState.value.hasSession) return
    Wearables.createSession(deviceSelector)
        .onSuccess { created ->
          session = created
          // Subscribe before start() so no initial transitions are missed.
          observeSession(created)
          _uiState.update { it.copy(sessionState = DeviceSessionState.STARTING) }
          created.start()
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to start session: ${error.description}")
          wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
          cleanupSession()
        }
  }

  private fun startSessionAutomaticallyIfReady() {
    val wearableState = wearablesViewModel.uiState.value
    val now = System.currentTimeMillis()
    if (
        _isSessionEnabled.value &&
            wearableState.isRegistered &&
            wearableState.hasActiveDevice &&
            !_uiState.value.hasSession &&
            now - lastAutoSessionAttemptAtMs >= AUTO_SESSION_RETRY_MS
    ) {
      lastAutoSessionAttemptAtMs = now
      startSession()
    }
  }

  /**
   * Ends the device session. The stream and any in-progress recording are not torn down here
   * directly — stopping the session drives the SDK's stream to a terminal state, which the
   * stream-state collector observes (see [onStreamTerminated]) to stop recording and release stream
   * resources.
   */
  fun endSession() {
    val current = session ?: return
    _uiState.update { it.copy(sessionState = DeviceSessionState.STOPPING) }
    current.stop()
  }

  private fun observeSession(session: DeviceSession) {
    sessionStateJob = viewModelScope.launch {
      session.state.collect { state ->
        _uiState.update { it.copy(sessionState = state) }
        if (state == DeviceSessionState.STARTED) {
          startStreaming()
        }
        if (state == DeviceSessionState.STOPPED) {
          cleanupSession()
          startSessionAutomaticallyIfReady()
        }
      }
    }
    sessionErrorJob = viewModelScope.launch {
      session.errors.collect { error ->
        // All session errors surface through the snackbar, including
        // DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED, which the SDK delivers as a one-shot event.
        Log.e(TAG, "Session error: ${error.description}")
        wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
      }
    }
  }

  private fun cleanupSession() {
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    session = null
  }

  // MARK: - Lifecycle step 2: stream (preview)

  /**
   * Starts the camera stream (preview). Requires an active session. Camera permission is checked
   * first (a query, no redirect); if it isn't granted, the actual request — which redirects to the
   * Meta AI app — is deferred to [confirmCameraPermissionRedirect] so the app-switch is confirmed.
   */
  fun startStreaming() {
    if (!_uiState.value.isSessionActive) {
      wearablesViewModel.setRecentError(
          getApplication<Application>().getString(R.string.error_start_session_first)
      )
      return
    }
    if (stream != null || _uiState.value.isStartingStream) return
    _uiState.update { it.copy(isStartingStream = true) }
    viewModelScope.launch {
      try {
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onSuccess { status ->
              if (status == PermissionStatus.Granted) {
                beginStream()
              } else {
                _uiState.update { it.copy(showCameraPermissionRedirectConfirm = true) }
              }
            }
            .onFailure { error, _ ->
              Log.e(TAG, "Failed to check camera permission: ${error.description}")
              wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
            }
      } finally {
        _uiState.update { it.copy(isStartingStream = false) }
      }
    }
  }

  /** Confirmed from the permission prompt: requests camera access, then starts the stream. */
  fun confirmCameraPermissionRedirect(
      requestPermission: suspend (Permission) -> PermissionStatus,
  ) {
    _uiState.update { it.copy(showCameraPermissionRedirectConfirm = false) }
    if (!_uiState.value.isSessionActive || stream != null) return
    viewModelScope.launch {
      val status = requestPermission(Permission.CAMERA)
      if (status == PermissionStatus.Granted) {
        beginStream()
      } else {
        wearablesViewModel.setRecentError(
            getApplication<Application>().getString(R.string.error_camera_permission_denied)
        )
      }
    }
  }

  fun cancelCameraPermissionRedirect() {
    _uiState.update { it.copy(showCameraPermissionRedirectConfirm = false) }
  }

  /** Enables or disables the DAT camera session and its gesture stream. */
  fun toggleSessionEnabled() {
    val enabled = !_isSessionEnabled.value
    _isSessionEnabled.value = enabled
    if (enabled) startSessionAutomaticallyIfReady() else endSession()
  }

  /** Starts the automatic session and 30fps stream from the web Home control. */
  fun startLifeLensSession() {
    _isSessionEnabled.value = true
    startSessionAutomaticallyIfReady()
    if (_uiState.value.isSessionActive) startStreaming()
  }

  /** 스트림과 제스처 인식은 유지하고 카메라 화면만 표시/숨김 전환한다. */
  fun togglePreviewVisibility() {
    _uiState.update { it.copy(isPreviewVisible = !it.isPreviewVisible) }
  }

  /** Live Vision always opens with the camera feed visible. */
  fun showPreview() {
    _uiState.update { it.copy(isPreviewVisible = true) }
  }

  private fun beginStream() {
    val current = session ?: return
    if (stream != null) return
    // Foreground service keeps the stream/recording alive while backgrounded.
    StreamingService.start(getApplication())
    current
        .addCamera(
            StreamConfiguration(
                videoQuality = VideoQuality.HIGH,
                frameRate = FRAME_RATE,
                // Raw I420 frames avoid an extra HEVC decode / Surface capture path and feed
                // OpenCV + MediaPipe directly for stable real-time hand recognition.
                compressVideo = false,
            )
        )
        .onSuccess { addedCamera ->
          camera = addedCamera
          val added = addedCamera.stream
          stream = added
          // Subscribe before start() so no initial transitions are missed.
          setupStreamListeners(added)
          _uiState.update { it.copy(streamState = StreamState.STARTING) }
          added.start().onFailure { error, _ ->
            Log.e(TAG, "Failed to start stream: ${error.description}")
            wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
            // A failed start leaves the stream attached and the FGS running — tear both down so the
            // UI doesn't stick at STARTING, matching the addCamera() failure path below.
            clearStreamResources()
          }
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to add camera: ${error.description}")
          StreamingService.stop(getApplication())
          wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
        }
  }

  /** Stops the camera stream but keeps the [DeviceSession] connected. */
  fun stopStreaming() {
    val current = camera ?: return
    _uiState.update { it.copy(streamState = StreamState.STOPPING) }
    current.stop()
    // The stream-state collector converges teardown when STOPPED/CLOSED arrives.
  }

  private fun setupStreamListeners(stream: Stream) {
    videoJob =
        viewModelScope.launch(frameDispatcher) {
          // After a brief capture/transport stall, keep only the newest frame instead of replaying
          // stale frames and making the live view appear to buffer.
          stream.videoStream.conflate().collect { handleVideoFrame(it) }
        }
    streamStateJob = viewModelScope.launch {
      // state replays its current value (STOPPED) on subscribe, and we subscribe before start().
      var hasBeenActive = false
      stream.state.collect { state ->
        _uiState.update { it.copy(streamState = state) }
        if (state == StreamState.STREAMING) streamRecoveryAttempts = 0
        val isTerminal = state == StreamState.STOPPED || state == StreamState.CLOSED
        if (!isTerminal) {
          hasBeenActive = true
        } else if (hasBeenActive) {
          hasBeenActive = false
          onStreamTerminated()
        }
      }
    }
    streamErrorJob = viewModelScope.launch {
      stream.errorStream.collect { error ->
        Log.e(TAG, "Stream error: ${error.description}")
        wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
      }
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    if (!videoFrame.isCompressed) {
      handleRawI420Frame(videoFrame)
      return
    }

    val buffer = videoFrame.buffer
    val width = videoFrame.width
    val height = videoFrame.height
    val presentationTimeUs = videoFrame.presentationTimeUs

    val byteArray = ByteArray(buffer.remaining())
    val originalPosition = buffer.position()
    buffer.get(byteArray)
    buffer.position(originalPosition)

    // Accumulate the parameter sets so a recording (or decoder) started after stream start can be
    // primed with a complete VPS+SPS+PPS set.
    csdCollector.offer(byteArray)

    // Append to the recorder (no-op unless recording); keeps writing while backgrounded.
    videoRecorder.writeCompressedFrame(
        byteArray,
        presentationTimeUs,
        width,
        height,
        videoFrame.isCodecConfig,
    )

    // Lazily create the decoder once a Surface is available; it renders directly to it. Prime it
    // with the cached config in case the surface arrived after the config frame. Guarded so a
    // concurrent setSurface(null) can't leave a decoder bound to a released Surface.
    synchronized(decoderLock) {
      val surface = decoderSurface
      if (hevcDecoder == null && surface != null) {
        hevcDecoder =
            HevcDecoder().also { decoder ->
              decoder.start(width, height, surface)
              csdCollector.complete()?.let { decoder.decodeFrame(it, 0) }
            }
      }
      // Feed under the lock so teardown can't null the decoder between check and feed.
      hevcDecoder?.decodeFrame(byteArray, presentationTimeUs)
    }

    if (!videoFrame.isCodecConfig && !_uiState.value.hasReceivedFirstFrame) {
      _uiState.update { it.copy(hasReceivedFirstFrame = true) }
    }
  }

  /** Processes DAT's raw I420 buffer directly. This replaces unstable HEVC Surface snapshots. */
  private fun handleRawI420Frame(videoFrame: VideoFrame) {
    val now = System.currentTimeMillis()
    if (now - lastRawFrameAtMs < RAW_FRAME_INTERVAL_MS) return
    lastRawFrameAtMs = now

    val buffer = videoFrame.buffer
    val data = ByteArray(buffer.remaining())
    val originalPosition = buffer.position()
    buffer.get(data)
    buffer.position(originalPosition)
    val bitmap = i420FrameConverter.toBitmap(data, videoFrame.width, videoFrame.height) ?: return
    _uiState.update {
      it.copy(videoFrame = bitmap, hasReceivedFirstFrame = true)
    }
    handGestureRecognizer.analyze(bitmap)
  }

  private fun onStreamTerminated() {
    // Finalize an in-progress recording before releasing the foreground service / wake lock, so the
    // MP4 mux on Dispatchers.IO isn't cut off when the stream stops while backgrounded.
    viewModelScope.launch {
      if (_uiState.value.isRecording) {
        stopVideoRecording()
      }
      clearStreamResources()
      if (_isSessionEnabled.value && _uiState.value.isSessionActive && streamRecoveryAttempts < MAX_STREAM_RECOVERY_ATTEMPTS) {
        streamRecoveryAttempts += 1
        Log.w(TAG, "Stream ended unexpectedly; recovery $streamRecoveryAttempts/$MAX_STREAM_RECOVERY_ATTEMPTS")
        delay(STREAM_RECOVERY_DELAY_MS)
        startStreaming()
      }
    }
  }

  private fun clearStreamResources() {
    pinchCaptureJob?.cancel()
    pinchCaptureJob = null
    videoJob?.cancel()
    videoJob = null
    streamStateJob?.cancel()
    streamStateJob = null
    streamErrorJob?.cancel()
    streamErrorJob = null
    synchronized(decoderLock) {
      hevcDecoder?.stop()
      hevcDecoder = null
    }
    csdCollector.reset()
    StreamingService.stop(getApplication())
    // STOPPED is restartable, so only stop() detaches the capability; without it the next
    // addCamera() fails with "a capability of this type is already active". Stopping the camera
    // cascades to its stream child.
    stopCamera(camera)
    camera = null
    stream = null
    _uiState.update {
      it.copy(
          streamState = StreamState.STOPPED,
          hasReceivedFirstFrame = false,
          gestureName = "분석 대기 중",
          gestureConfidence = 0,
          videoFrame = null,
      )
    }
  }

  /**
   * Stops the camera by closing it. Accepting a [java.io.Closeable] parameter satisfies the
   * AutoCloseableUse detector, which skips methods that take an AutoCloseable argument — the camera
   * outlives any single `use {}` block, so it is stopped explicitly here rather than auto-closed.
   */
  private fun stopCamera(capability: java.io.Closeable?) {
    capability?.close()
  }

  // MARK: - Capture

  fun capturePhoto() {
    if (_uiState.value.isCapturingPhoto || !_uiState.value.isStreaming) return
    _uiState.update { it.copy(isCapturingPhoto = true) }
    val capturedAt = System.currentTimeMillis()
    val capturedLocation = lastKnownLocation()
    viewModelScope.launch {
      stream
          ?.capturePhoto()
          ?.onSuccess { photoData ->
            // Decode/rotate is CPU-bound and blocking; keep it off the main thread so capture
            // doesn't jank the UI. Resumes on main for the state update.
            val bitmap = withContext(photoDispatcher) { decodePhoto(photoData) }
            if (bitmap != null) {
              // Captures are never written to the device gallery. Keep the stream visible and
              // upload this in-memory frame directly to the shared LifeLens server.
              _uiState.update { it.copy(isCapturingPhoto = false) }
              val uploaded = withContext(photoDispatcher) {
                try {
                  val locationName = resolveLocationName(capturedLocation)
                  LifeLogSyncer.syncPhoto(
                      getApplication(),
                      com.example.kimchi_r1.lifelog.PhotoRecord(
                          id = 0,
                          clientPhotoId = java.util.UUID.randomUUID().toString(),
                          uri = "",
                          createdAtMillis = capturedAt,
                          latitude = capturedLocation?.latitude,
                          longitude = capturedLocation?.longitude,
                          locationName = locationName,
                      ),
                      bitmap,
                  )
                } finally {
                  bitmap.recycle()
                }
              }
              Log.i(TAG, "Photo captured; uploadedToServer=$uploaded")
              if (!uploaded) {
                wearablesViewModel.setRecentError("사진을 서버에 저장하지 못했습니다")
              }
            } else {
              _uiState.update { it.copy(isCapturingPhoto = false) }
              wearablesViewModel.setRecentError(
                  getApplication<Application>().getString(R.string.error_photo_capture_failed)
              )
            }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Failed to capture photo: ${error.description}")
            _uiState.update { it.copy(isCapturingPhoto = false) }
            wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
          } ?: _uiState.update { it.copy(isCapturingPhoto = false) }
    }
  }

  // MARK: - Recording

  fun toggleRecording(requestRecordAudioPermission: suspend () -> Boolean) {
    if (_uiState.value.isRecording) {
      viewModelScope.launch { stopVideoRecording() }
    } else {
      startVideoRecording(requestRecordAudioPermission)
    }
  }

  fun startVideoRecording(requestRecordAudioPermission: suspend () -> Boolean) {
    if (!_uiState.value.isStreaming || _uiState.value.isRecording) return
    viewModelScope.launch {
      // Request the mic permission only when sound-in-video is on; record video-only if it's off or
      // the user declines. The prompt appears in context on the first record with the mic on.
      val includeAudio = _uiState.value.includeAudioInStream && requestRecordAudioPermission()
      if (!_uiState.value.isStreaming || _uiState.value.isRecording) return@launch
      // If the user wanted sound but denied the mic, reflect it so the mic icon doesn't stay "on".
      if (_uiState.value.includeAudioInStream && !includeAudio) {
        _uiState.update { it.copy(includeAudioInStream = false) }
      }
      videoRecorder.setIncludeAudio(includeAudio)
      videoRecorder.startRecording(csdCollector.complete())
    }
  }

  suspend fun stopVideoRecording() {
    if (!_uiState.value.isRecording) return
    // The writer starts on the first keyframe. If stop lands just before that frame, wait briefly
    // so even a quick recording finalizes to a file instead of being discarded.
    var waited = 0L
    while (!videoRecorder.hasStartedWriting.value && waited < KEYFRAME_WAIT_MAX_MS) {
      delay(KEYFRAME_WAIT_STEP_MS)
      waited += KEYFRAME_WAIT_STEP_MS
    }
    when (val result = videoRecorder.stopRecording()) {
      is RecordingResult.Completed ->
          _uiState.update { it.copy(activePreview = CapturePreview.Video(result.uri)) }
      RecordingResult.NoRecording ->
          wearablesViewModel.setRecentError(
              getApplication<Application>().getString(R.string.error_recording_too_short)
          )
      RecordingResult.Failed ->
          wearablesViewModel.setRecentError(
              getApplication<Application>().getString(R.string.error_recording_save_failed)
          )
    }
  }

  fun toggleMic() {
    if (!_uiState.value.isStreaming || _uiState.value.isRecording) return
    _uiState.update { it.copy(includeAudioInStream = !it.includeAudioInStream) }
  }

  // MARK: - Dismissers

  fun dismissCapturePreview() {
    val preview = _uiState.value.activePreview
    _uiState.update { it.copy(activePreview = null) }
    if (preview is CapturePreview.Video) {
      // The clip lives in the cache dir, exposed as a FileProvider content URI; delete through the
      // resolver so it resolves back to the real cache file (the URI's path is the provider
      // mapping,
      // not a filesystem path). Photos are held in memory — nothing on disk to clean up.
      viewModelScope.launch(Dispatchers.IO) {
        runCatching {
          getApplication<Application>().contentResolver.delete(preview.uri, null, null)
        }
            .onFailure { Log.w(TAG, "Failed to delete temp recording", it) }
      }
    }
  }

  // MARK: - Photo decoding

  private fun decodePhoto(photo: PhotoData): Bitmap? =
      when (photo) {
        is PhotoData.Bitmap -> scalePhotoForUpload(photo.bitmap)
        is PhotoData.HEIC -> decodeWithOrientation(photo.data)
      }

  private fun scalePhotoForUpload(bitmap: Bitmap): Bitmap {
    val longestSide = maxOf(bitmap.width, bitmap.height)
    if (longestSide <= PHOTO_UPLOAD_MAX_SIDE) return bitmap
    val scale = PHOTO_UPLOAD_MAX_SIDE.toFloat() / longestSide
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt(),
        (bitmap.height * scale).toInt(),
        true,
    )
  }

  private fun lastKnownLocation(): Location? {
    val app = getApplication<Application>()
    val hasFine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null
    val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return runCatching {
      listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
          .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
          .maxByOrNull { it.time }
    }.getOrNull()
  }

  /** Converts GPS coordinates to a short human-readable Korean place name for LifeLog. */
  private fun resolveLocationName(location: Location?): String? {
    if (location == null || !Geocoder.isPresent()) return null
    return runCatching {
      @Suppress("DEPRECATION")
      Geocoder(getApplication<Application>(), Locale.KOREAN)
          .getFromLocation(location.latitude, location.longitude, 1)
          ?.firstOrNull()
          ?.let { address ->
            listOfNotNull(address.adminArea, address.locality, address.subLocality)
                .distinct()
                .joinToString(" ")
                .trim()
                .ifBlank { null }
          }
    }.onFailure { Log.w(TAG, "Reverse geocoding failed", it) }.getOrNull()
  }

  // The glasses store orientation in an EXIF tag that BitmapFactory/ImageDecoder don't apply for
  // HEIC, so read TAG_ORIENTATION and rotate — otherwise the preview and shared image are sideways.
  private fun decodeWithOrientation(data: ByteBuffer): Bitmap? {
    val buffer = data.duplicate().apply { rewind() }
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    // Decode close to upload size instead of materializing a full-resolution HEIC bitmap first.
    // This avoids the large allocation/GC spike that used to stall raw live-frame conversion.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > PHOTO_UPLOAD_MAX_SIDE * 2 ||
            bounds.outHeight / sampleSize > PHOTO_UPLOAD_MAX_SIDE * 2
    ) {
      sampleSize *= 2
    }
    val bitmap =
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
      bitmap?.recycle()
      Log.e(TAG, "Failed to decode captured photo")
      return null
    }

    val matrix = exifOrientationMatrix(bytes)
    val oriented =
        if (matrix.isIdentity) {
          bitmap
        } else {
          // Rotating allocates a second bitmap; fall back to the unrotated image on low memory.
          try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
              bitmap.recycle()
            }
          } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Failed to rotate captured photo", e)
            bitmap
          }
        }
    return scalePhotoForUpload(oriented).also { scaled ->
      if (scaled !== oriented) oriented.recycle()
    }
  }

  private fun exifOrientationMatrix(bytes: ByteArray): Matrix {
    val orientation =
        try {
          ByteArrayInputStream(bytes).use { input ->
            ExifInterface(input)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
          }
        } catch (e: IOException) {
          Log.w(TAG, "Failed to read EXIF orientation", e)
          ExifInterface.ORIENTATION_NORMAL
        }
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    return matrix
  }

  override fun onCleared() {
    super.onCleared()
    handGestureRecognizer.close()
    gestureSpeechFeedback.close()
    clearStreamResources()
    session?.stop()
    cleanupSession()
    audioInputHandler.cleanup()
    videoRecorder.close()
    photoDispatcher.close()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST")
        return CameraViewModel(application, wearablesViewModel) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
