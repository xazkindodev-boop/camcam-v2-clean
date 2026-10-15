package com.zkincamcam.xcam.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization + behavior tests for the two-finger rotate gesture math.
 * The class is pure logic (no Android imports) so the rotation accumulation
 * rules can be verified on the JVM.
 */
class EditorGestureStateTest {

    private fun degrees(x1: Float, y1: Float, x2: Float, y2: Float) =
        EditorGestureState.angleBetweenDegrees(x1, y1, x2, y2)

    @Test
    fun angleZeroForHorizontalVector() {
        assertEquals(0f, degrees(0f, 0f, 10f, 0f), 0.001f)
    }

    @Test
    fun angle90ForVerticalDownVector() {
        // Screen coords: +y is down.
        assertEquals(90f, degrees(0f, 0f, 0f, 10f), 0.001f)
    }

    @Test
    fun angleNegativeForUpVector() {
        assertEquals(-90f, degrees(0f, 0f, 0f, -10f), 0.001f)
    }

    @Test
    fun deltaWrapsAcross180Boundary() {
        // from 170deg to -170deg is +20deg of rotation, not -340.
        val delta = EditorGestureState.normalizeDeltaDegrees(170f, -170f)
        assertEquals(20f, delta, 0.001f)
    }

    @Test
    fun deltaNegativeDirection() {
        val delta = EditorGestureState.normalizeDeltaDegrees(-170f, 170f)
        assertEquals(-20f, delta, 0.001f)
    }

    @Test
    fun rotationAccumulatesAndClamps() {
        val s = EditorGestureState()
        s.addRotation(15f)
        s.addRotation(20f)
        assertEquals(35f, s.fineRotationDegrees, 0.001f)
        s.addRotation(200f)
        assertEquals(45f, s.fineRotationDegrees, 0.001f)
    }

    @Test
    fun resetRestoresDefaults() {
        val s = EditorGestureState()
        s.addRotation(30f)
        s.reset()
        assertEquals(0f, s.fineRotationDegrees, 0.001f)
        assertTrue(s.isActive.not())
    }
}
