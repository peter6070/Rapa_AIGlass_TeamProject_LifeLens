package com.example.kimchi_r1.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/** 샘플의 LIVE_STREAM 랜드마크 분석을 현재 앱에 맞게 단순화한 손 제스처 인식기. */
class HandGestureRecognizer(
    context: Context,
    private val onResult: (label: String, confidence: Int) -> Unit,
) {
  private var lastFrameTimeMs = 0L
  private val landmarker: HandLandmarker? =
      runCatching {
            HandLandmarker.createFromOptions(
                context,
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_NAME).build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.55f)
                    .setMinTrackingConfidence(0.55f)
                    .setResultListener(::handleResult)
                    .build(),
            )
          }
          .getOrNull()

  fun analyze(bitmap: Bitmap) {
    val timestamp = System.currentTimeMillis()
    val detector = landmarker ?: return
    if (timestamp - lastFrameTimeMs < FRAME_INTERVAL_MS) return
    lastFrameTimeMs = timestamp
    runCatching {
      // LIVE_STREAM은 추론 중인 오래된 프레임을 버려 실시간 화면보다 뒤처지지 않는다.
      detector.detectAsync(BitmapImageBuilder(bitmap).build(), timestamp)
    }
  }

  fun close() = landmarker?.close()

  private fun handleResult(result: HandLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
    val hand = result.landmarks().firstOrNull { landmarks ->
      val spanX = landmarks.maxOf { it.x() } - landmarks.minOf { it.x() }
      val spanY = landmarks.maxOf { it.y() } - landmarks.minOf { it.y() }
      maxOf(spanX, spanY) >= MIN_HAND_SPAN
    }
    if (hand == null) {
      onResult("손을 찾는 중", 0)
      return
    }

    val extended = listOf(4 to 3, 8 to 6, 12 to 10, 16 to 14, 20 to 18).map {
      (tip, pip) -> isFingerExtended(hand, tip, pip)
    }
    val (thumb, index, middle, ring, pinky) = extended
    val count = extended.count { it }
    val label =
        when {
          count == 0 -> "주먹"
          !thumb && index && middle && !ring && !pinky -> "브이 · 다음"
          thumb && index && !middle && !ring && !pinky -> "브이 · 이전"
          count == 1 -> "한 손가락"
          count == 2 -> "브이"
          count == 3 -> "세 손가락"
          else -> "손바닥 펼침"
        }
    onResult(label, 85)
  }

  private fun isFingerExtended(hand: List<NormalizedLandmark>, tipIndex: Int, pipIndex: Int): Boolean {
    val wrist = hand[0]
    val tip = hand[tipIndex]
    val pip = hand[pipIndex]
    val tipDistance = kotlin.math.hypot(tip.x() - wrist.x(), tip.y() - wrist.y())
    val pipDistance = kotlin.math.hypot(pip.x() - wrist.x(), pip.y() - wrist.y())
    return tipDistance > pipDistance * EXTENDED_FINGER_RATIO
  }

  private companion object {
    const val MODEL_NAME = "hand_landmarker.task"
    const val FRAME_INTERVAL_MS = 66L
    const val MIN_HAND_SPAN = 0.16f
    const val EXTENDED_FINGER_RATIO = 1.18f
  }
}
