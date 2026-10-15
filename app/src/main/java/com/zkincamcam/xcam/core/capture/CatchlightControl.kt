package com.zkincamcam.xcam.core.capture

/**
 * Manual eye-catchlight override for the editor.
 *
 * A source photo with no specular reflection in the eyes leaves the shader's
 * catchlight effect with nothing to amplify — the user can force it. A `null`
 * override means "follow the active beauty preset"; an explicit value wins and 0
 * disables the effect entirely. Values are clamped to [0, 2].
 */
data class CatchlightControl(val userOverride: Float? = null) {

    /** The boost the shader should use, given the active preset's own value. */
    fun resolve(presetBoost: Float): Float =
        (userOverride ?: presetBoost).coerceIn(0f, MAX_BOOST)

    companion object {
        const val MAX_BOOST = 2f

        /**
         * Builds a control from a slider reading. Until the user actually moves the
         * slider, its value merely mirrors the preset, so an untouched slider must
         * not create an override (otherwise switching presets would stop working).
         */
        fun fromSlider(value: Float, wasTouched: Boolean): CatchlightControl =
            CatchlightControl(if (wasTouched) value.coerceIn(0f, MAX_BOOST) else null)
    }
}
