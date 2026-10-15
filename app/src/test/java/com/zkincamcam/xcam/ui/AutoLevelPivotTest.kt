package com.zkincamcam.xcam.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the auto-level pivot: rotating the head to level it
 * must pivot around the EYE MIDPOINT (the physiological neck axis), not the
 * face bounding-box centre — otherwise levelling a tilted photo also shifts
 * the head position (eyes drift up/down).
 */
class AutoLevelPivotTest {

    private fun almost(a: Float, b: Float, eps: Float = 1e-4f) = Math.abs(a - b) < eps

    @Test
    fun eyeMidpointIsThePivotWhenProvided() {
        // Face bbox centre lower than the eye line (portrait norm), no tilt:
        // the eye midpoint must be a FIXED POINT — it stays exactly where it was.
        val m = LiveEditorTransform.buildMatrix(
            faceCenterX = 0.5f, faceCenterY = 0.54f,
            scale = 1f, translateX = 0f, translateY = 0f,
            rotationDegrees = 0f, mirrored = false,
            autoLevelDegrees = 0f,
            pivotX = 0.476f, pivotY = 0.474f,
        )
        val px = 0.476f * 2f - 1f
        val py = 1f - 0.474f * 2f
        val x = m[0] * px + m[4] * py + m[12]
        val y = m[1] * px + m[5] * py + m[13]
        assertEquals(px, x, 1e-4f)
        assertEquals(py, y, 1e-4f)
    }

    @Test
    fun levellingATiltedFaceDoesNotMoveTheEyes() {
        // The real-world case from the tilted ID photo: -7.5° roll. Levelling
        // must not displace the eye midpoint (identical output at 0° and 7.5°).
        val m0 = LiveEditorTransform.buildMatrix(
            faceCenterX = 0.488f, faceCenterY = 0.542f,
            scale = 1f, translateX = 0f, translateY = 0f,
            rotationDegrees = 0f, mirrored = false,
            autoLevelDegrees = 0f,
            pivotX = 0.476f, pivotY = 0.474f,
        )
        val m75 = LiveEditorTransform.buildMatrix(
            faceCenterX = 0.488f, faceCenterY = 0.542f,
            scale = 1f, translateX = 0f, translateY = 0f,
            rotationDegrees = 0f, mirrored = false,
            autoLevelDegrees = 7.5f,
            pivotX = 0.476f, pivotY = 0.474f,
        )
        val px = 0.476f * 2f - 1f
        val py = 1f - 0.474f * 2f
        val x0 = m0[0] * px + m0[4] * py + m0[12]
        val y0 = m0[1] * px + m0[5] * py + m0[13]
        val x75 = m75[0] * px + m75[4] * py + m75[12]
        val y75 = m75[1] * px + m75[5] * py + m75[13]
        assertEquals(x0, x75, 1e-4f)
        assertEquals(y0, y75, 1e-4f)
    }

    @Test
    fun withoutPivotFallsBackToFaceCentre() {
        // Backward compatibility: no pivot args -> old behaviour (bbox centre
        // is the fixed point).
        val m = LiveEditorTransform.buildMatrix(
            faceCenterX = 0.488f, faceCenterY = 0.542f,
            scale = 1f, translateX = 0f, translateY = 0f,
            rotationDegrees = 0f, mirrored = false, autoLevelDegrees = 0f,
        )
        val cx = 0.488f * 2f - 1f
        val cy = 1f - 0.542f * 2f
        val x = m[0] * cx + m[4] * cy + m[12]
        val y = m[1] * cx + m[5] * cy + m[13]
        assertEquals(cx, x, 1e-4f)
        assertEquals(cy, y, 1e-4f)
    }

    @Test
    fun scaleStillAnchorsAtPivot() {
        // Zoom must also anchor at the pivot, so the eyes stay put under zoom.
        val m = LiveEditorTransform.buildMatrix(
            faceCenterX = 0.5f, faceCenterY = 0.5f,
            scale = 2f, translateX = 0f, translateY = 0f,
            rotationDegrees = 0f, mirrored = false,
            pivotX = 0.476f, pivotY = 0.474f,
        )
        val px = 0.476f * 2f - 1f
        val py = 1f - 0.474f * 2f
        val x = m[0] * px + m[4] * py + m[12]
        val y = m[1] * px + m[5] * py + m[13]
        assertEquals(px, x, 1e-3f)
        assertEquals(py, y, 1e-3f)
    }
}
