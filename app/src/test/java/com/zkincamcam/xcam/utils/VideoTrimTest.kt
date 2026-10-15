package com.zkincamcam.xcam.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTrimTest {

    @Test
    fun computesDurationMsFromUs() {
        assertEquals(6500L, VideoTrim(0L, 6_500_000L).durationMs())
    }

    @Test
    fun rangeWithoutDurationIsTakenAsIs() {
        val t = VideoTrim.of(0L, 10_000_000L)
        assertFalse(t.isNoOp())
    }

    @Test
    fun startBeyondDurationYieldsNoOp() {
        val t = VideoTrim.of(11_000_000L, 12_000_000L, durationUs = 10_000_000L)
        // clamped start=end=10s -> empty
        assertTrue(t.isNoOp())
    }

    @Test
    fun partialRangeTrims() {
        val t = VideoTrim.of(1_000_000L, 4_000_000L, durationUs = 10_000_000L)
        assertFalse(t.isNoOp())
        assertEquals(1_000_000L, t.startUs)
        assertEquals(4_000_000L, t.endUs)  // end is exclusive position, not length
    }

    @Test
    fun clampsToEndOfMedia() {
        val t = VideoTrim.of(9_000_000L, 99_000_000L, durationUs = 10_000_000L)
        assertEquals(9_000_000L, t.startUs)
        assertEquals(10_000_000L, t.endUs)
    }

    @Test
    fun startAfterEndYieldsNoOp() {
        val t = VideoTrim.of(5_000_000L, 1_000_000L)
        assertTrue(t.isNoOp())
    }

    @Test
    fun zeroLengthYieldsNoOp() {
        val t = VideoTrim.of(3_000_000L, 3_000_000L)
        assertTrue(t.isNoOp())
    }

    @Test
    fun negativeStartClampsToZero() {
        val t = VideoTrim.of(-500L, 4_000_000L)
        assertEquals(0L, t.startUs)
    }
}
