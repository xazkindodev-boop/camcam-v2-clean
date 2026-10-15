package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity guard: the export shader source must contain every effect the preview
 * renderer shows, in one canonical order. Historically the two pipelines were
 * written separately and drifted (sharpness missing from export, beauty and
 * catchlight missing from preview). These tests pin the shared source.
 */
class LivenessShaderParityTest {

    private val fs = LivenessShaders.FRAGMENT

    @Test
    fun fragmentDeclaresAllSharedUniforms() {
        for (u in listOf(
            "u_sharpness", "u_texel_size", "u_beauty",
            "u_exposure", "u_contrast", "u_highlights", "u_shadows",
            "u_temperature", "u_tint", "u_saturation",
            "u_noise_intensity", "u_pulse_intensity", "u_use_lut",
            "u_eye_landmarks_detected",
        )) {
            assertTrue("missing uniform $u", fs.contains("uniform float $u;") || fs.contains("uniform vec2 $u;"))
        }
    }

    @Test
    fun fragmentHasBeautyAndCatchlightPasses() {
        assertTrue(fs.contains("applyBeauty"))
        assertTrue(fs.contains("enhanceEyeLiveliness"))
    }

    @Test
    fun eyeSpecificEffectsRequireDetectedEyeLandmarks() {
        assertTrue(LivenessShaders.VERTEX.contains("u_eye_landmarks_detected > 0.5"))
        assertTrue(fs.contains("u_eye_landmarks_detected < 0.5"))
        assertTrue(fs.contains("u_eye_landmarks_detected > 0.5"))
    }

    @Test
    fun fragmentHasSharpnessUnsharpMask() {
        // Unsharp mask taps neighbours using texel size — the effect that was
        // silently absent from exports.
        assertTrue(fs.contains("u_texel_size"))
        assertTrue(Regex("""rgb\s*\+\s*\(rgb\s*-\s*blur\)\s*\*\s*u_sharpness""").containsMatchIn(fs))
    }

    @Test
    fun canonicalEffectOrder() {
        val main = fs.substring(fs.indexOf("void main()"))
        fun idx(s: String) = main.indexOf(s)
        // Measure call sites inside main() — function declarations live above main().
        val beauty = idx("applyBeauty(")
        val catchlight = idx("enhanceEyeLiveliness(")
        val sharpness = idx("(rgb - blur) * u_sharpness")
        val color = idx("u_exposure")
        val lut = idx("sampleLUT(")
        val pulse = idx("u_pulse_intensity")
        val noise = idx("u_noise_intensity")
        assertTrue("beauty first", beauty in 0 until catchlight)
        assertTrue("catchlight before sharpness", catchlight < sharpness)
        assertTrue("sharpness before color", sharpness < color)
        assertTrue("color before lut", color < lut)
        assertTrue("lut before pulse", lut < pulse)
        assertTrue("pulse before noise", pulse < noise)
    }

    @Test
    fun vertexIsSharedBlinkMesh() {
        assertTrue(LivenessShaders.VERTEX.contains("u_blink"))
        assertTrue(LivenessShaders.VERTEX.contains("u_matrix"))
    }
}
