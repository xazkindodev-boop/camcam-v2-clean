package com.zkincamcam.xcam.core.capture

import android.opengl.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class LivenessAnimationDeviceTest {

    @Test
    fun sharedMotionMatrixMatchesAndroidOpenGlTransformComposition() {
        val timeSeconds = 2.31
        val frequency = 0.9817477
        val breathingY = sin(timeSeconds * frequency) * 0.008
        val jitterX = sin(timeSeconds * frequency * 8.0) * 0.003 +
            cos(timeSeconds * frequency * 19.0) * 0.0015
        val jitterY = cos(timeSeconds * frequency * 7.0) * 0.003 +
            sin(timeSeconds * frequency * 15.0) * 0.0012
        val saccadeX = if (sin(timeSeconds * frequency * 3.0) > 0.9) {
            sin(timeSeconds * 30.0) * 0.004
        } else {
            0.0
        }
        val rotation = (sin(timeSeconds * frequency * 4.0) * 0.15).toFloat()
        val expected = FloatArray(16)
        Matrix.setIdentityM(expected, 0)
        Matrix.scaleM(expected, 0, 1.03f, 1.03f, 1f)
        Matrix.translateM(expected, 0, (jitterX + saccadeX).toFloat(), (breathingY + jitterY).toFloat(), 0f)
        Matrix.rotateM(expected, 0, rotation, 0f, 0f, 1f)

        val actual = LivenessAnimation.motionMatrix(timeSeconds)

        expected.indices.forEach { index ->
            assertEquals("matrix element $index", expected[index], actual[index], 0.000001f)
        }
    }
}
