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
    private val onLeftPinch: (phase: LeftPinchPhase) -> Unit = {},
    private val onRightLightGesture: (phase: RightLightGesturePhase) -> Unit = {},
) {
  private var lastFrameTimeMs = 0L
  private val leftPinchStateMachine = LeftPinchStateMachine()
  private val rightFistOpenStateMachine = RightFistOpenStateMachine()
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

  @Suppress("UNUSED_PARAMETER")
  private fun handleResult(
      result: HandLandmarkerResult,
      input: com.google.mediapipe.framework.image.MPImage,
  ) {
    val visibleHands =
        result.landmarks().mapIndexedNotNull { index, landmarks ->
          if (handSpan(landmarks) >= MIN_HAND_SPAN) index to landmarks else null
        }
    updateRightLightGesture(result, visibleHands)
    val hand = visibleHands.maxByOrNull { (_, landmarks) -> handSpan(landmarks) }?.second
    if (hand == null) {
      leftPinchStateMachine.reset()
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

    val leftHand =
        visibleHands.firstOrNull { (index, _) ->
          result.handedness().getOrNull(index)?.firstOrNull()?.let { handedness ->
            handedness.score() >= MIN_HANDEDNESS_CONFIDENCE &&
                handedness.categoryName().equals(LEFT_HAND_LABEL, ignoreCase = true)
          } == true
        }?.second
    if (leftHand == null) {
      leftPinchStateMachine.reset()
      return
    }
    val palmSize = maxOf(distance(leftHand[0], leftHand[9]), distance(leftHand[5], leftHand[17]))
    if (palmSize <= 0f) {
      leftPinchStateMachine.reset()
      return
    }
    onLeftPinch(
        leftPinchStateMachine.update(distance(leftHand[4], leftHand[8]) / palmSize, result.timestampMs())
    )
  }

  private fun updateRightLightGesture(
      result: HandLandmarkerResult,
      visibleHands: List<Pair<Int, List<NormalizedLandmark>>>,
  ) {
    val rightHand =
        visibleHands.firstOrNull { (index, _) ->
          result.handedness().getOrNull(index)?.firstOrNull()?.let { handedness ->
            handedness.score() >= MIN_HANDEDNESS_CONFIDENCE &&
                handedness.categoryName().equals(RIGHT_HAND_LABEL, ignoreCase = true)
          } == true
        }?.second
    val pose =
        if (rightHand == null) {
          RightHandPose.ABSENT
        } else {
          val thumb = isFingerExtended(rightHand, 4, 3)
          val index = isFingerExtended(rightHand, 8, 6)
          val middle = isFingerExtended(rightHand, 12, 10)
          val ring = isFingerExtended(rightHand, 16, 14)
          val pinky = isFingerExtended(rightHand, 20, 18)
          when {
            !thumb && !index && !middle && !ring && !pinky -> RightHandPose.FIST
            thumb && index && middle && ring && pinky && isRightPalmFacingCamera(rightHand) ->
                RightHandPose.OPEN_PALM
            else -> RightHandPose.OTHER
          }
        }
    val phase = rightFistOpenStateMachine.update(pose, result.timestampMs())
    if (phase != RightLightGesturePhase.IDLE) onRightLightGesture(phase)
  }

  private fun handSpan(hand: List<NormalizedLandmark>): Float {
    val spanX = hand.maxOf { it.x() } - hand.minOf { it.x() }
    val spanY = hand.maxOf { it.y() } - hand.minOf { it.y() }
    return maxOf(spanX, spanY)
  }

  private fun distance(first: NormalizedLandmark, second: NormalizedLandmark): Float =
      kotlin.math.hypot(first.x() - second.x(), first.y() - second.y())

  private fun isFingerExtended(hand: List<NormalizedLandmark>, tipIndex: Int, pipIndex: Int): Boolean {
    val wrist = hand[0]
    val tip = hand[tipIndex]
    val pip = hand[pipIndex]
    val tipDistance = kotlin.math.hypot(tip.x() - wrist.x(), tip.y() - wrist.y())
    val pipDistance = kotlin.math.hypot(pip.x() - wrist.x(), pip.y() - wrist.y())
    return tipDistance > pipDistance * EXTENDED_FINGER_RATIO
  }

  private fun isRightPalmFacingCamera(hand: List<NormalizedLandmark>): Boolean {
    val wrist = hand[0]
    val indexMcp = hand[5]
    val pinkyMcp = hand[17]
    val normalZ =
        (indexMcp.x() - wrist.x()) * (pinkyMcp.y() - wrist.y()) -
            (indexMcp.y() - wrist.y()) * (pinkyMcp.x() - wrist.x())
    return normalZ > PALM_FACING_MIN_NORMAL_Z
  }

  private companion object {
    const val MODEL_NAME = "hand_landmarker.task"
    const val FRAME_INTERVAL_MS = 41L
    const val MIN_HAND_SPAN = 0.18f
    const val MIN_HANDEDNESS_CONFIDENCE = 0.6f
    const val EXTENDED_FINGER_RATIO = 1.18f
    const val LEFT_HAND_LABEL = "Left"
    const val RIGHT_HAND_LABEL = "Right"
    const val PALM_FACING_MIN_NORMAL_Z = 0.005f
  }
}
