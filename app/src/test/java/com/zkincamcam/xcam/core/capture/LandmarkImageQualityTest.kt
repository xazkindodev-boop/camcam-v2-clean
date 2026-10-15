package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkImageQualityTest {

    @Test
    fun flatImageIsClassifiedAsSoft() {
        val pixels = IntArray(16 * 16) { 128 }

        val score = LandmarkImageQuality.meanLaplacianMagnitude(pixels, 16, 16)

        assertTrue(LandmarkImageQuality.needsSharpening(score))
    }

    @Test
    fun crispEdgesHaveHigherSharpnessThanBlurredEdges() {
        val crisp = IntArray(32 * 32) { index ->
            val x = index % 32
            if (x < 16) 0 else 255
        }
        val blurred = IntArray(32 * 32) { index ->
            val x = index % 32
            when {
                x < 14 -> 0
                x < 18 -> (x - 14) * 64
                else -> 255
            }
        }

        val crispScore = LandmarkImageQuality.meanLaplacianMagnitude(crisp, 32, 32)
        val blurredScore = LandmarkImageQuality.meanLaplacianMagnitude(blurred, 32, 32)

        assertTrue(crispScore > blurredScore)
        assertFalse(LandmarkImageQuality.needsSharpening(crispScore))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidImageDimensionsAreRejected() {
        LandmarkImageQuality.meanLaplacianMagnitude(IntArray(4), 2, 2)
    }

    @Test
    fun nonFiniteSharpnessDoesNotTriggerRetry() {
        assertFalse(LandmarkImageQuality.needsSharpening(Float.NaN))
        assertFalse(LandmarkImageQuality.needsSharpening(Float.POSITIVE_INFINITY))
    }
}
