package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blur -> HD detail recovery. Recovering structure from a soft source needs more
 * than one unsharp radius: a fine band restores micro-texture, a coarse band
 * restores contour lost to defocus, and both need halo suppression or high
 * contrast edges ring. The amount applied also scales up in low-variance
 * (genuinely blurry) patches and backs off where the image is already sharp.
 */
class HdEnhanceTest {

    @Test
    fun levelZeroIsANoOp() {
        val p = HdEnhance.params(0f)
        assertEquals(0f, p.amount, 1e-4f)
        assertEquals(0f, p.clarity, 1e-4f)
        assertEquals(0f, p.adaptive, 1e-4f)
    }

    @Test
    fun everyCoefficientGrowsWithLevel() {
        val levels = listOf(0.25f, 0.5f, 0.75f, 1f).map { HdEnhance.params(it) }
        for (i in 1 until levels.size) {
            assertTrue(levels[i].amount > levels[i - 1].amount)
            assertTrue(levels[i].clarity >= levels[i - 1].clarity)
            assertTrue(levels[i].radius >= levels[i - 1].radius)
        }
    }

    @Test
    fun levelClampsAboveOneAndBelowZero() {
        assertEquals(HdEnhance.params(1f).amount, HdEnhance.params(9f).amount, 1e-6f)
        assertEquals(0f, HdEnhance.params(-3f).amount, 1e-6f)
    }

    @Test
    fun adaptiveGainBoostsBlurryPatchesAndHoldsSharpOnes() {
        // Low local variance (soft) must be lifted more than high variance (already sharp).
        val soft = HdEnhance.adaptiveGain(variance = 0.002f, level = 1f)
        val sharp = HdEnhance.adaptiveGain(variance = 0.05f, level = 1f)
        assertTrue("soft=$soft should beat sharp=$sharp", soft > sharp)
        assertTrue("sharp patch must not be over-sharpened", sharp <= 1.05f)
        assertTrue("soft patch must be lifted", soft >= 1.4f)
    }

    @Test
    fun adaptiveGainIsOneWhenDisabled() {
        assertEquals(1f, HdEnhance.adaptiveGain(variance = 0.0001f, level = 0f), 1e-4f)
    }

    @Test
    fun haloSuppressionFallsOffWithDetailMagnitude() {
        val flat = HdEnhance.haloFactor(detailMagnitude = 0f)
        val edge = HdEnhance.haloFactor(detailMagnitude = 0.6f)
        assertEquals(1f, flat, 1e-4f)
        assertTrue("strong edges must be damped", edge < 0.7f)
        assertTrue(edge > 0f)
    }
}
