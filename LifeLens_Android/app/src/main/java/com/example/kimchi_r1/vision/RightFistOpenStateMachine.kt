package com.example.kimchi_r1.vision

internal enum class RightHandPose { ABSENT, FIST, OPEN_PALM, OTHER }

enum class RightLightGesturePhase { IDLE, ARMED, TRIGGERED }

/** Right-hand-only fist -> open-palm recognizer with frame confirmation and automatic disarming. */
internal class RightFistOpenStateMachine {
  private var armed = false
  private var fistFrames = 0
  private var openFrames = 0
  private var absentFrames = 0
  private var lastTriggeredAtMs = Long.MIN_VALUE

  fun update(pose: RightHandPose, timestampMs: Long): RightLightGesturePhase {
    when (pose) {
      RightHandPose.FIST -> {
        absentFrames = 0
        openFrames = 0
        fistFrames += 1
        if (!armed && fistFrames >= FIST_CONFIRM_FRAMES) {
          armed = true
          return RightLightGesturePhase.ARMED
        }
      }

      RightHandPose.OPEN_PALM -> {
        absentFrames = 0
        fistFrames = 0
        openFrames += 1
        if (
            armed &&
                openFrames >= OPEN_CONFIRM_FRAMES &&
                (lastTriggeredAtMs == Long.MIN_VALUE ||
                    timestampMs - lastTriggeredAtMs >= TOGGLE_COOLDOWN_MS)
        ) {
          armed = false
          openFrames = 0
          lastTriggeredAtMs = timestampMs
          return RightLightGesturePhase.TRIGGERED
        }
      }

      RightHandPose.ABSENT -> {
        fistFrames = 0
        openFrames = 0
        absentFrames += 1
        if (absentFrames >= DISARM_ABSENT_FRAMES) armed = false
      }

      RightHandPose.OTHER -> {
        absentFrames = 0
        fistFrames = 0
        openFrames = 0
      }
    }
    return RightLightGesturePhase.IDLE
  }

  private companion object {
    const val FIST_CONFIRM_FRAMES = 3
    const val OPEN_CONFIRM_FRAMES = 4
    const val DISARM_ABSENT_FRAMES = 12
    const val TOGGLE_COOLDOWN_MS = 2_500L
  }
}
