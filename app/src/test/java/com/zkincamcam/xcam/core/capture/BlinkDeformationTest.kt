package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the blink deformation: the original version used an
 * isotropic Gaussian with a fixed radius, so the NOSE (close to the eyes in
 * texcoord space, and closer still on small faces) shifted along with the
 * eyelids. The fixed vertex shader must show all three fixes.
 */
class BlinkDeformationTest {

    private val vertex: String = LivenessShaders.VERTEX

    @Test
    fun deformRadiusIsFaceAdaptive() {
        // Radius must scale with the eye-to-eye span, not a hardcoded constant.
        assertTrue(vertex.contains("eyeSpan"))
        assertTrue(!Regex("""exp\(-d[LR] \* d[LR] \* \d+\.\d+\)""").containsMatchIn(vertex))
    }

    @Test
    fun falloffIsAnisotropicEllipse() {
        // Horizontal extent wider than vertical: dx/sx vs dy/sy weighting present.
        assertTrue(Regex("""d[xy]\w* / """).containsMatchIn(vertex) || vertex.contains("/ sx"))
        assertTrue(vertex.contains("sx"))
        assertTrue(vertex.contains("sy"))
    }

    @Test
    fun verticalFalloffProtectsBelowEyeArea() {
        // Nose sits BELOW the eye line; the falloff must be asymmetric — pull-up
        // displacement only applies above/at the eye line, or decays faster below.
        assertTrue(vertex.contains("belowEye") || vertex.contains("asym"))
    }

    @Test
    fun stillUsesBlinkAndEyes() {
        assertTrue(vertex.contains("u_blink"))
        assertTrue(vertex.contains("u_left_eye"))
        assertTrue(vertex.contains("u_right_eye"))
    }
}
