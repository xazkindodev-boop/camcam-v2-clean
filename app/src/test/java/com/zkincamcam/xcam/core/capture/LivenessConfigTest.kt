package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivenessConfigTest {

    @Test
    fun defaultsMatchPreviousCallSiteDefaults() {
        val c = LivenessConfig()
        assertEquals(0f, c.beautyLevel)
        assertEquals(0.5f, c.noiseLevel)
        assertEquals(0.5f, c.pulseLevel)
        assertFalse(c.eyeMovementEnabled)
        assertEquals(null, c.transformMatrix)
        assertEquals(null, c.lutBitmap)
        assertEquals(MediaPreprocessor.ColorAdjustments(), c.adjustments)
    }

    @Test
    fun valuesRoundTrip() {
        val c = LivenessConfig(
            beautyLevel = 0.7f,
            noiseLevel = 0.2f,
            pulseLevel = 0.9f,
            eyeMovementEnabled = true,
        )
        assertEquals(0.7f, c.beautyLevel)
        assertTrue(c.eyeMovementEnabled)
        assertEquals(0.2f, c.noiseLevel)
        assertEquals(0.9f, c.pulseLevel)
    }

    @Test
    fun isDataClassForEquality() {
        assertEquals(LivenessConfig(beautyLevel = 1f), LivenessConfig(beautyLevel = 1f))
    }
}
