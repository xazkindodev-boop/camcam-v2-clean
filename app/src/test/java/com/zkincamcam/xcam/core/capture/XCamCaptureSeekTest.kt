package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Additional edge-case tests for XCamCapture's pure logic (seekTargetUs) — the parts that can
 * run on the JVM. The MediaMetadataRetriever fallback loop needs a device and is covered by
 * logcat-driven device tests instead.
 */
class XCamCaptureSeekTest {

    @Test
    fun negativeTimeMsClampsToZero() {
        assertEquals(0L, XCamCapture.seekTargetUs(-5_000L, 10_000L))
    }

    @Test
    fun negativeDurationBehavesLikeUnknownDuration() {
        assertEquals(1_000L * 1000L, XCamCapture.seekTargetUs(1_000L, -5L))
    }

    @Test
    fun timeExactlyAtDurationWrapsToGuardedStart() {
        // 10_000 % 10_000 = 0 → looped=0, guard tidak mengurangi (0 <= 120)
        assertEquals(0L, XCamCapture.seekTargetUs(10_000L, 10_000L))
    }

    @Test
    fun timeInsideGuardZoneClampsToZeroNotNegative() {
        // looped = 60 (< 120 guard) → guarded = 60 (tidak dikurangi), pos = 60ms
        assertEquals(60L * 1000L, XCamCapture.seekTargetUs(60L, 10_000L))
    }

    @Test
    fun timeInLastFrameGuardStaysBeforeEnd() {
        // looped = 9_950 → guarded = 9_950-120 = 9_830
        assertEquals(9_830L * 1000L, XCamCapture.seekTargetUs(9_950L, 10_000L))
    }

    @Test
    fun veryLargeTimeMsDoesNotOverflowUsRange() {
        val huge = Long.MAX_VALUE / 4
        val us = XCamCapture.seekTargetUs(huge, 10_000L)
        assertTrue("hasil harus dalam batas us wajar", us < 10_000L * 1000L)
        assertTrue(us >= 0L)
    }

    @Test
    fun oneFrameVideoStillReturnsFrameZero() {
        // duration 1ms: looped selalu 0 setelah modulo
        assertEquals(0L, XCamCapture.seekTargetUs(0L, 1L))
        assertEquals(0L, XCamCapture.seekTargetUs(500L, 1L))
    }
}
