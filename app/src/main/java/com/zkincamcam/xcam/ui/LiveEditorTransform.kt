package com.zkincamcam.xcam.ui

import kotlin.math.cos
import kotlin.math.sin

internal object LiveEditorTransform {
    fun buildMatrix(
        faceCenterX: Float,
        faceCenterY: Float,
        scale: Float,
        translateX: Float,
        translateY: Float,
        rotationDegrees: Float,
        mirrored: Boolean,
        autoLevelDegrees: Float = 0f,
        pivotX: Float = faceCenterX,
        pivotY: Float = faceCenterY
    ): FloatArray {
        val radians = Math.toRadians((rotationDegrees + autoLevelDegrees).toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        val scaleX = scale * if (mirrored) -1f else 1f
        val scaleY = scale
        // X' = R*S*(X - C) + C + T, where C is the pivot in clip space.
        // Rotation AND zoom anchor at the pivot (the eye midpoint when face
        // landmarks are available — the physiological neck axis). The pivot is
        // a FIXED POINT: levelling a tilted photo (or zooming) leaves the eyes
        // exactly where they were; the old formulation rotated about the face
        // bbox centre, which shifted the head as a side effect. Defaulting the
        // pivot to the face centre keeps the old behaviour for callers without
        // landmarks.
        val centerX = pivotX * 2f - 1f
        val centerY = 1f - pivotY * 2f

        return FloatArray(16).apply {
            this[0] = cos * scaleX
            this[1] = sin * scaleX
            this[4] = -sin * scaleY
            this[5] = cos * scaleY
            this[10] = 1f
            this[12] = centerX + translateX - (cos * scaleX * centerX - sin * scaleY * centerY)
            this[13] = centerY + translateY - (sin * scaleX * centerX + cos * scaleY * centerY)
            this[15] = 1f
        }
    }

    fun estimateFaceRollDegrees(
        leftEyeX: Float,
        leftEyeY: Float,
        rightEyeX: Float,
        rightEyeY: Float,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        require(imageWidth > 0 && imageHeight > 0)
        val dx = (rightEyeX - leftEyeX) * imageWidth
        val dy = (rightEyeY - leftEyeY) * imageHeight
        if (dx == 0f && dy == 0f) return 0f
        return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            .coerceIn(-30f, 30f)
    }
}
