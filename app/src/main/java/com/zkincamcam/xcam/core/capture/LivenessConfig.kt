package com.zkincamcam.xcam.core.capture

import android.graphics.Bitmap

/**
 * Bundle of the liveness parameters forwarded from the editor UI to the export pipeline.
 *
 * Replaces the seven-argument DeepLivenessShader constructor call so call sites read as a
 * named list instead of positional floats, and so new parameters do not ripple through
 * every signature in the layer.
 */
data class LivenessConfig(
    val beautyLevel: Float = 0f,
    val sharpnessLevel: Float = 0f,
    val noiseLevel: Float = 0.5f,
    val pulseLevel: Float = 0.5f,
    val eyeMovementEnabled: Boolean = false,
    val transformMatrix: FloatArray? = null,
    val lutBitmap: Bitmap? = null,
    val beautyPreset: BeautyEnginePreset = BeautyEnginePreset.DEFAULT,
    val catchlightOverride: Float? = null,
    val hdLevel: Float = 0f,
    val adjustments: MediaPreprocessor.ColorAdjustments = MediaPreprocessor.ColorAdjustments(),
) {
    // FloatArray/Bitmap are not comparable, so equals/hashCode stay reference-based via the
    // component fields that matter for call-site tests.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LivenessConfig) return false
        return beautyLevel == other.beautyLevel &&
            sharpnessLevel == other.sharpnessLevel &&
            noiseLevel == other.noiseLevel &&
            pulseLevel == other.pulseLevel &&
            eyeMovementEnabled == other.eyeMovementEnabled
    }

    override fun hashCode(): Int {
        var result = beautyLevel.hashCode()
        result = 31 * result + sharpnessLevel.hashCode()
        result = 31 * result + noiseLevel.hashCode()
        result = 31 * result + pulseLevel.hashCode()
        result = 31 * result + eyeMovementEnabled.hashCode()
        return result
    }
}
