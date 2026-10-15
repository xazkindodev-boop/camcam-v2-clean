package com.zkincamcam.xcam.core.capture

/**
 * A named, built-in color grade for the editor. A preset is a pure data bundle
 * of [MediaPreprocessor.ColorAdjustments] plus the liveness sliders it sets
 * when applied; the UI maps the fields onto the existing sliders so a preset is
 * just a starting point the user can keep tuning.
 *
 * `none` is the neutral reset: applying it returns every slider to defaults.
 */
data class FilterPreset(
    val id: String,
    val label: String,
    val adjustments: MediaPreprocessor.ColorAdjustments,
    val noiseLevel: Float = 0.5f,
    val pulseLevel: Float = 0.5f,
) {
    companion object {
        /** All shipped presets, in picker order. First entry is the neutral reset. */
        val BUILT_INS: List<FilterPreset> = listOf(
            FilterPreset(
                id = "none",
                label = "None",
                adjustments = MediaPreprocessor.ColorAdjustments(),
            ),
            FilterPreset(
                id = "portrait",
                label = "Portrait",
                adjustments = MediaPreprocessor.ColorAdjustments(
                    exposure = 0.15f,
                    contrast = 0.95f,
                    highlights = -0.1f,
                    shadows = 0.2f,
                    temperature = 0.12f,
                    tint = 0.05f,
                    saturation = 1.05f
                ),
                noiseLevel = 0.3f,
                pulseLevel = 0.5f,
            ),
            FilterPreset(
                id = "night",
                label = "Night",
                adjustments = MediaPreprocessor.ColorAdjustments(
                    exposure = 0.3f,
                    contrast = 0.9f,
                    highlights = -0.25f,
                    shadows = 0.35f,
                    temperature = -0.1f,
                    tint = -0.05f,
                    saturation = 0.85f
                ),
                noiseLevel = 0.7f,
                pulseLevel = 0.6f,
            ),
            FilterPreset(
                id = "cinematic",
                label = "Cinematic",
                adjustments = MediaPreprocessor.ColorAdjustments(
                    exposure = -0.05f,
                    contrast = 1.25f,
                    highlights = -0.15f,
                    shadows = -0.1f,
                    temperature = -0.2f,
                    tint = 0.1f,
                    saturation = 0.9f
                ),
                noiseLevel = 0.45f,
                pulseLevel = 0.5f,
            ),
            FilterPreset(
                id = "vivid",
                label = "Vivid",
                adjustments = MediaPreprocessor.ColorAdjustments(
                    exposure = 0.05f,
                    contrast = 1.15f,
                    highlights = 0.05f,
                    shadows = 0.05f,
                    temperature = 0.05f,
                    tint = 0f,
                    saturation = 1.45f
                ),
                noiseLevel = 0.4f,
                pulseLevel = 0.5f,
            ),
        )
    }
}
