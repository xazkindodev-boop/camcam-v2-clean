package com.zkincamcam.xcam.core.capture

import kotlin.math.cos
import kotlin.math.sin

internal object LivenessAnimation {
    private const val BASE_FREQUENCY = 0.9817477

    fun blinkAmount(timeSeconds: Double, enabled: Boolean): Float {
        if (!enabled) return 0f
        val pulse = sin(timeSeconds * BASE_FREQUENCY * 3.0).toFloat()
        return if (pulse > 0.9f) (pulse - 0.9f) * 10f else 0f
    }

    fun motionMatrix(timeSeconds: Double): FloatArray {
        val breathingY = sin(timeSeconds * BASE_FREQUENCY) * 0.008
        val jitterX = sin(timeSeconds * BASE_FREQUENCY * 8.0) * 0.003 +
            cos(timeSeconds * BASE_FREQUENCY * 19.0) * 0.0015
        val jitterY = cos(timeSeconds * BASE_FREQUENCY * 7.0) * 0.003 +
            sin(timeSeconds * BASE_FREQUENCY * 15.0) * 0.0012
        val saccadeX = if (sin(timeSeconds * BASE_FREQUENCY * 3.0) > 0.9) {
            sin(timeSeconds * 30.0) * 0.004
        } else {
            0.0
        }
        val rotation = Math.toRadians(sin(timeSeconds * BASE_FREQUENCY * 4.0) * 0.15)
        val scale = 1.03f
        val cosRotation = cos(rotation).toFloat()
        val sinRotation = sin(rotation).toFloat()
        return FloatArray(16).apply {
            this[0] = scale * cosRotation
            this[1] = scale * sinRotation
            this[4] = -scale * sinRotation
            this[5] = scale * cosRotation
            this[10] = 1f
            this[12] = (scale * (jitterX + saccadeX)).toFloat()
            this[13] = (scale * (breathingY + jitterY)).toFloat()
            this[15] = 1f
        }
    }

    fun applyMotion(baseMatrix: FloatArray, timeSeconds: Double, enabled: Boolean): FloatArray {
        require(baseMatrix.size == 16) { "Expected a 4x4 transform matrix" }
        if (!enabled) return baseMatrix.copyOf()
        return multiply(motionMatrix(timeSeconds), baseMatrix)
    }

    private fun multiply(left: FloatArray, right: FloatArray): FloatArray =
        FloatArray(16).apply {
            for (column in 0..3) {
                for (row in 0..3) {
                    var value = 0f
                    for (index in 0..3) {
                        value += left[index * 4 + row] * right[column * 4 + index]
                    }
                    this[column * 4 + row] = value
                }
            }
        }
}
