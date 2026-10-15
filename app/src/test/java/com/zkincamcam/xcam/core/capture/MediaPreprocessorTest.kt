package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPreprocessorTest {
    @Test
    fun zeroSharpnessPreservesOriginalPixels() {
        val pixels = intArrayOf(
            0xFF000000.toInt(), 0xFF202020.toInt(), 0xFF404040.toInt(),
            0xFF606060.toInt(), 0xFF808080.toInt(), 0xFFA0A0A0.toInt(),
            0xFFC0C0C0.toInt(), 0xFFE0E0E0.toInt(), 0xFFFFFFFF.toInt()
        )

        assertArrayEquals(pixels, MediaPreprocessor.sharpenArgbPixels(pixels, 3, 3, 0f))
    }

    @Test
    fun positiveSharpnessIncreasesLocalEdgeContrastWithoutDarkBorder() {
        val pixels = IntArray(9) { 0xFF646464.toInt() }
        pixels[4] = 0xFFB4B4B4.toInt()

        val sharpened = MediaPreprocessor.sharpenArgbPixels(pixels, 3, 3, 2f)

        assertTrue(sharpened[4] > pixels[4])
        assertEquals(pixels[0], sharpened[0])
    }

    @Test
    fun autoExposureRaisesDarkImagesAndLimitsCorrection() {
        val darkImage = IntArray(100) { 0xFF202020.toInt() }
        val brightImage = IntArray(100) { 0xFFE0E0E0.toInt() }

        assertTrue(MediaPreprocessor.suggestExposure(darkImage) > 0f)
        assertTrue(MediaPreprocessor.suggestExposure(brightImage) < 0f)
        assertEquals(1.5f, MediaPreprocessor.suggestExposure(intArrayOf(0xFF000000.toInt())), 0f)
    }
}
