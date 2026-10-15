package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LivenessAnimationTest {

    @Test
    fun blinkIsDisabledWhenEyeMovementIsOff() {
        assertEquals(0f, LivenessAnimation.blinkAmount(0.53, enabled = false), 0f)
    }

    @Test
    fun blinkUsesTheSharedAnimationCurveWhenEnabled() {
        val t = Math.PI / (2.0 * 0.9817477 * 3.0)
        assertEquals(1f, LivenessAnimation.blinkAmount(t, enabled = true), 0.001f)
    }

    @Test
    fun disabledMotionPreservesTheUserTransform() {
        val matrix = FloatArray(16).apply {
            this[0] = 1f
            this[5] = 1f
            this[10] = 1f
            this[12] = 0.2f
            this[15] = 1f
        }

        val result = LivenessAnimation.applyMotion(matrix, 1.25, enabled = false)

        assertEquals(matrix.toList(), result.toList())
    }

    @Test
    fun enabledMotionAppliesDeterministicFrameMovement() {
        val identity = FloatArray(16).apply {
            this[0] = 1f
            this[5] = 1f
            this[10] = 1f
            this[15] = 1f
        }

        val first = LivenessAnimation.applyMotion(identity, 1.25, enabled = true)
        val repeated = LivenessAnimation.applyMotion(identity, 1.25, enabled = true)

        assertNotEquals(identity.toList(), first.toList())
        assertEquals(first.toList(), repeated.toList())
    }
}
