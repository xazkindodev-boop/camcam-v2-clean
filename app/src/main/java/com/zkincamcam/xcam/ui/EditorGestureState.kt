package com.zkincamcam.xcam.ui

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure state + math for the editor's touch gestures (two-finger rotate, pinch
 * zoom, one-finger drag). No Android imports — the rotation accumulation rules
 * are unit-tested on the JVM, and MainActivity's touch listener only feeds
 * events in and applies the resulting values.
 */
internal class EditorGestureState {

    var fineRotationDegrees = 0f
        private set
    var isActive = false
        private set

    fun begin() { isActive = true }
    fun end() { isActive = false }

    /** Accumulates rotation, clamped to the fine-rotation slider range (-45..45). */
    fun addRotation(deltaDegrees: Float) {
        fineRotationDegrees = (fineRotationDegrees + deltaDegrees).coerceIn(-45f, 45f)
    }

    fun reset() {
        fineRotationDegrees = 0f
        isActive = false
    }

    companion object {
        /** Angle in degrees of the vector (x2-x1, y2-y1) in screen coords (+y down). */
        fun angleBetweenDegrees(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            if (dx == 0f && dy == 0f) return 0f
            return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        }

        /**
         * Shortest signed rotation from [fromDegrees] to [toDegrees], wrapped to
         * (-180, 180] so crossing the 180° boundary reads as +20°, not -340°.
         */
        fun normalizeDeltaDegrees(fromDegrees: Float, toDegrees: Float): Float {
            var d = (toDegrees - fromDegrees) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        /** Distance between two touch points (used to ignore micro-jitter). */
        fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()

        /** Squared distance — cheaper guard for "did the finger really move". */
        fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x2 - x1; val dy = y2 - y1
            return dx * dx + dy * dy
        }
    }
}
