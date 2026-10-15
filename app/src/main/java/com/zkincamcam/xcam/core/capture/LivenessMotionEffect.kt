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
        return LivenessAnimation.motionMatrix(presentationTimeUs / 1_000_000.0)
    }
}
