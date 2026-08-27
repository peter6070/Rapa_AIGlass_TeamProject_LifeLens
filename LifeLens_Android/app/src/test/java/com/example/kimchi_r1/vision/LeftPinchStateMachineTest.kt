package com.example.kimchi_r1.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class LeftPinchStateMachineTest {
  @Test
  fun `stable near pinch contact and release completes sequence`() {
    val machine = LeftPinchStateMachine()
    assertEquals(LeftPinchPhase.ARMING, machine.update(0.5f, 0L))
    assertEquals(LeftPinchPhase.ARMED, machine.update(0.5f, 150L))
    assertEquals(LeftPinchPhase.CONTACTED, machine.update(0.15f, 200L))
    assertEquals(LeftPinchPhase.RELEASED, machine.update(0.4f, 300L))
  }

  @Test
  fun `contact without arming does not release`() {
    val machine = LeftPinchStateMachine()
    assertEquals(LeftPinchPhase.IDLE, machine.update(0.15f, 0L))
    assertEquals(LeftPinchPhase.ARMING, machine.update(0.4f, 100L))
    assertEquals(LeftPinchPhase.IDLE, machine.update(0.15f, 120L))
  }
}
