package com.zkincamcam.xcam.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveEditorTransformTest {
    @Test
    fun offCenterFaceStaysWhereItIs() {
        // Pivot semantics (post eye-pivot fix): the face centre is the anchor
        // and stays put — the matrix must NOT re-centre the face; positioning
        // is the user's job (translate/gestures).
        val matrix = LiveEditorTransform.buildMatrix(
            faceCenterX = 0.7f,
            faceCenterY = 0.3f,
            scale = 1f,
            translateX = 0f,
            translateY = 0f,
            rotationDegrees = 0f,
            mirrored = false
        )
        val cx = 0.7f * 2f - 1f
        val cy = 1f - 0.3f * 2f
        val x = matrix[0] * cx + matrix[4] * cy + matrix[12]
        val y = matrix[1] * cx + matrix[5] * cy + matrix[13]
        assertEquals(cx, x, 0.0001f)
        assertEquals(cy, y, 0.0001f)
    }

    @Test
    fun appliesManualRotationAndMirrorAroundFaceCentre() {
        // Rotation+mirror anchor at the pivot (default = face centre): the
        // face centre must be a fixed point even under 90° rotation + mirror.
        val matrix = LiveEditorTransform.buildMatrix(
            faceCenterX = 0.7f,
            faceCenterY = 0.3f,
            scale = 1f,
            translateX = 0f,
            translateY = 0f,
            rotationDegrees = 90f,
            mirrored = true
        )
        val cx = 0.7f * 2f - 1f
        val cy = 1f - 0.3f * 2f
        val x = matrix[0] * cx + matrix[4] * cy + matrix[12]
        val y = matrix[1] * cx + matrix[5] * cy + matrix[13]
        assertEquals(cx, x, 0.0001f)
        assertEquals(cy, y, 0.0001f)
    }

    @Test
    fun estimatesClockwiseFaceRollFromEyeLine() {
        val roll = LiveEditorTransform.estimateFaceRollDegrees(
            leftEyeX = 0.25f,
            leftEyeY = 0.45f,
            rightEyeX = 0.75f,
            rightEyeY = 0.55f,
            imageWidth = 400,
            imageHeight = 200
        )

        assertEquals(5.71f, roll, 0.02f)
    }

    @Test
    fun autoLevelRotationCancelsFaceRoll() {
        val rollRadians = Math.toRadians(10.0)
        val eyeVectorX = kotlin.math.cos(rollRadians).toFloat()
        val eyeVectorY = -kotlin.math.sin(rollRadians).toFloat()
        val matrix = LiveEditorTransform.buildMatrix(
            faceCenterX = 0.5f,
            faceCenterY = 0.5f,
            scale = 1f,
            translateX = 0f,
            translateY = 0f,
            rotationDegrees = 0f,
            mirrored = false,
            autoLevelDegrees = 10f
        )

        assertEquals(0.9848f, matrix[0], 0.0001f)
        assertEquals(0.1736f, matrix[1], 0.0001f)
        assertEquals(0f, matrix[1] * eyeVectorX + matrix[5] * eyeVectorY, 0.0001f)
    }
}
