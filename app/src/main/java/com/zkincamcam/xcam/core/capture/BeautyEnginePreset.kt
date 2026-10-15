package com.zkincamcam.xcam.core.capture

/**
 * Named tuning profile for the variance-guided beauty pass. The beauty slider
 * stays the master amount; a preset shapes HOW that amount behaves:
 *
 *  - [theta] (shader variance knee): lower = only very flat skin smooths.
 *  - [smoothCeiling]: max blend fraction into the smoothed colour.
 *  - [restoreGain]: how much high-frequency skin texture is added back.
 *  - [whitening]: subtle luminance lift on the skin mask (0 = off).
 *  - [catchlightBoost]: multiplier for the eye catchlight effect.
 *
 * Pure data, JVM-tested; the renderer maps the fields onto shader uniforms.
 */
data class BeautyEnginePreset(
    val id: String,
    val label: String,
    val theta: Float,
    val smoothCeiling: Float,
    val restoreGain: Float,
    val whitening: Float = 0f,
    val catchlightBoost: Float = 1f,
) {
    companion object {
        /** All shipped engine presets, in picker order. */
        val BUILT_INS: List<BeautyEnginePreset> = listOf(
            BeautyEnginePreset(
                id = "natural",
                label = "Natural",
                theta = 0.07f,
                smoothCeiling = 0.55f,
                restoreGain = 0.55f,
                whitening = 0f,
                catchlightBoost = 1f,
            ),
            BeautyEnginePreset(
                id = "studio",
                label = "Studio",
                theta = 0.1f,
                smoothCeiling = 0.72f,
                restoreGain = 0.4f,
                whitening = 0f,
                catchlightBoost = 1.1f,
            ),
            BeautyEnginePreset(
                id = "glam",
                label = "Glam",
                theta = 0.13f,
                smoothCeiling = 0.9f,
                restoreGain = 0.25f,
                whitening = 0.12f,
                catchlightBoost = 1.35f,
            ),
            BeautyEnginePreset(
                id = "soft_focus",
                label = "Soft Focus",
                theta = 0.16f,
                smoothCeiling = 0.95f,
                restoreGain = 0.12f,
                whitening = 0.06f,
                catchlightBoost = 1.15f,
            ),
        )

        /** Default selection when no preset has been chosen yet. */
        val DEFAULT: BeautyEnginePreset = BUILT_INS.first { it.id == "studio" }
    }
}
