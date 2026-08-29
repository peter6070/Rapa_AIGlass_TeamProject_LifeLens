package com.example.kimchi_r1.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class RightFistOpenStateMachineTest {
  @Test
  fun threeFistsThenFourOpenPalmsTriggersOnce() {
    val machine = RightFistOpenStateMachine()
    assertEquals(RightLightGesturePhase.IDLE, machine.update(RightHandPose.FIST, 0L))
    assertEquals(RightLightGesturePhase.IDLE, machine.update(RightHandPose.FIST, 41L))
    assertEquals(RightLightGesturePhase.ARMED, machine.update(RightHandPose.FIST, 82L))
    assertEquals(RightLightGesturePhase.IDLE, machine.update(RightHandPose.OPEN_PALM, 123L))
    assertEquals(RightLightGesturePhase.IDLE, machine.update(RightHandPose.OPEN_PALM, 164L))
    assertEquals(RightLightGesturePhase.IDLE, machine.update(RightHandPose.OPEN_PALM, 205L))
    assertEquals(RightLightGesturePhase.TRIGGERED, machine.update(RightHandPose.OPEN_PALM, 246L))
    assertEquals(RightLightGesturePhase.IDLE, machine.update(RightHandPose.OPEN_PALM, 287L))
  }

  @Test
  fun openPalmWithoutFistDoesNotTrigger() {
    val machine = RightFistOpenStateMachine()
    repeat(10) { index ->
      assertEquals(
          RightLightGesturePhase.IDLE,
          machine.update(RightHandPose.OPEN_PALM, index * 41L),
      )
    }
  }

  @Test
  fun losingRightHandDisarmsSequence() {
    val machine = RightFistOpenStateMachine()
    repeat(3) { machine.update(RightHandPose.FIST, it * 41L) }
    repeat(12) { machine.update(RightHandPose.ABSENT, 200L + it * 41L) }
    repeat(4) { index ->
      assertEquals(
          RightLightGesturePhase.IDLE,
          machine.update(RightHandPose.OPEN_PALM, 800L + index * 41L),
      )
    }
  }
}
