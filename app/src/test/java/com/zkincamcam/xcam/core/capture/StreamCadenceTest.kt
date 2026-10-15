package com.zkincamcam.xcam.core.capture

import com.zkincamcam.xcam.data.Constants.MAX_STREAM_FRAME_MS
import com.zkincamcam.xcam.data.Constants.MIN_STREAM_FRAME_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCadenceTest {

    @Test
    fun fastDecoderKeepsTopCadence() {
        assertEquals(MIN_STREAM_FRAME_MS, nextStreamDelayMs(0L))
        assertEquals(MIN_STREAM_FRAME_MS, nextStreamDelayMs(5L))
        assertEquals(MIN_STREAM_FRAME_MS, nextStreamDelayMs(16L))
    }

    @Test
    fun cadenceDoublesMeasuredDecodeCost() {
        assertEquals(100L, nextStreamDelayMs(50L))
        assertEquals(140L, nextStreamDelayMs(70L))
    }

    @Test
    fun verySlowDecoderIsStillCappedSoFramesKeepArriving() {
        assertEquals(MAX_STREAM_FRAME_MS, nextStreamDelayMs(500L))
        assertEquals(MAX_STREAM_FRAME_MS, nextStreamDelayMs(Long.MAX_VALUE / 2))
    }

    @Test
    fun seekWrapsInsideTheClipAndNeverLandsOnTheLastFrame() {
        val duration = 10_000L

        assertEquals(0L, XCamCapture.seekTargetUs(0L, duration))
        assertEquals(9_680L * 1000L, XCamCapture.seekTargetUs(duration - 200L, duration))
        assertEquals(880L * 1000L, XCamCapture.seekTargetUs(duration + 1_000L, duration))
        assertTrue(XCamCapture.seekTargetUs(9_999_999L, duration) < duration * 1000L)
    }

    @Test
    fun seekWithoutDurationFallsBackToRawTimestamp() {
        assertEquals(1_500L * 1000L, XCamCapture.seekTargetUs(1_500L, 0L))
        assertEquals(0L, XCamCapture.seekTargetUs(-100L, 0L))
    }
}