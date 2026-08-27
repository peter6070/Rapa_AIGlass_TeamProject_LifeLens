package com.example.kimchi_r1.vision

enum class LeftPinchPhase { IDLE, ARMING, ARMED, CONTACTED, RELEASED }

/** Deliberate near-pinch -> contact -> release recognizer with jitter-resistant thresholds. */
internal class LeftPinchStateMachine {
  private var state = LeftPinchPhase.IDLE
  private var armingSinceMs = 0L

  fun update(normalizedTipDistance: Float, timestampMs: Long): LeftPinchPhase {
    state = when (state) {
      LeftPinchPhase.IDLE -> if (normalizedTipDistance in CONTACT_DISTANCE..ARM_DISTANCE) {
        armingSinceMs = timestampMs
        LeftPinchPhase.ARMING
      } else LeftPinchPhase.IDLE
      LeftPinchPhase.ARMING -> when {
        normalizedTipDistance < CONTACT_DISTANCE || normalizedTipDistance > ARM_DISTANCE -> resetState()
        timestampMs - armingSinceMs >= ARM_HOLD_MS -> LeftPinchPhase.ARMED
        else -> LeftPinchPhase.ARMING
      }
      LeftPinchPhase.ARMED -> when {
        normalizedTipDistance <= CONTACT_DISTANCE -> LeftPinchPhase.CONTACTED
        normalizedTipDistance > ARM_DISTANCE -> resetState()
        else -> LeftPinchPhase.ARMED
      }
      LeftPinchPhase.CONTACTED -> if (normalizedTipDistance >= RELEASE_DISTANCE) {
        state = LeftPinchPhase.IDLE
        return LeftPinchPhase.RELEASED
      } else LeftPinchPhase.CONTACTED
      LeftPinchPhase.RELEASED -> resetState()
    }
    return state
  }

  fun reset() {
    state = LeftPinchPhase.IDLE
    armingSinceMs = 0L
  }

  private fun resetState(): LeftPinchPhase {
    armingSinceMs = 0L
    return LeftPinchPhase.IDLE
  }

  private companion object {
    const val ARM_HOLD_MS = 150L
    const val CONTACT_DISTANCE = 0.22f
    const val RELEASE_DISTANCE = 0.38f
    const val ARM_DISTANCE = 0.65f
  }
}
