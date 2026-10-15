package com.zkincamcam.xcam.core.capture

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlMatrixTransformation

/**
 * Cinema-Grade Seamless Motion Engine.
 * Uses harmonic frequencies synchronized with a 6.4s duration to achieve 100% 
 * seamless loops without temporal jump artifacts.
 */
@OptIn(UnstableApi::class)
class LivenessMotionEffect : GlMatrixTransformation {

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray {
        val t = presentationTimeUs / 1000000.0

        val f0 = 0.9817477

        val breathingY = Math.sin(t * f0) * 0.008

        val jitterX = (Math.sin(t * f0 * 8.0) * 0.003 + Math.cos(t * f0 * 19.0) * 0.0015)
        val jitterY = (Math.cos(t * f0 * 7.0) * 0.003 + Math.sin(t * f0 * 15.0) * 0.0012)

        val saccadeX = if (Math.sin(t * f0 * 3.0) > 0.9) Math.sin(t * 30.0) * 0.004 else 0.0

        val finalTx = (jitterX + saccadeX).toFloat()
        val finalTy = (breathingY + jitterY).toFloat()

        val rotationAngle = (Math.sin(t * f0 * 4.0) * 0.15).toFloat()

        val matrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(matrix, 0)
        android.opengl.Matrix.scaleM(matrix, 0, 1.03f, 1.03f, 1.00f)
        android.opengl.Matrix.translateM(matrix, 0, finalTx, finalTy, 0.0f)
        android.opengl.Matrix.rotateM(matrix, 0, rotationAngle, 0.0f, 0.0f, 1.0f)

        return matrix
    }
}
