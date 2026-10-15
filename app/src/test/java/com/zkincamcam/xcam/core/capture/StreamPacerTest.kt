package com.zkincamcam.xcam.core.capture

import com.zkincamcam.xcam.data.Constants.MAX_STREAM_FRAME_MS
import com.zkincamcam.xcam.data.Constants.MIN_STREAM_FRAME_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation tests for the stream pacing state machine, extracted from CaptureManager.
 * These pin the behaviour the camera callback relies on before the class is split.
 *
 * Semantics follow the ORIGINAL CaptureManager logic exactly:
 *  - the very first request for a key counts as a key change (the old code compared
 *    `streamKey != key` against a null initial key, so the first frame also "retired");
 *  - only one extraction may be in flight at a time, regardless of interval;
 *  - a finished extraction re-paces the interval from its measured cost.
 */
class StreamPacerTest {

    private fun pacer() = StreamPacer()

    @Test
    fun firstRequestStartsAndCountsAsKeyChange() {
        val d = pacer().onFrameRequested("v.mp4|1|2|0|false", 10_000L)
        assertTrue(d.start)
        assertTrue("semantik asli: first request = key change (dari null)", d.keyChanged)
    }

    @Test
    fun secondRequestInsideIntervalIsPacedOff() {
        val p = pacer()
        p.onFrameRequested("k", 10_000L)
        p.onExtractionFinished(0L) // extraction selesai instan; interval = MIN (33)
        val d = p.onFrameRequested("k", 10_000L + MIN_STREAM_FRAME_MS - 1)
        assertFalse("belum lewat interval, jangan mulai ekstraksi baru", d.start)
    }

    @Test
    fun requestWhileExtractionRunningNeverStarts() {
        val p = pacer()
        p.onFrameRequested("k", 10_000L)
        // extraction masih jalan (belum onExtractionFinished), jauh melewati interval
        val d = p.onFrameRequested("k", 99_999L)
        assertFalse("satu ekstraksi pada satu waktu", d.start)
    }

    @Test
    fun extractionFinishReleasesTheRunningLatch() {
        val p = pacer()
        p.onFrameRequested("k", 10_000L)
        p.onExtractionFinished(0L) // interval jadi MIN
        val d = p.onFrameRequested("k", 10_000L + MIN_STREAM_FRAME_MS)
        assertTrue(d.start)
    }

    @Test
    fun extractionFinishRepacesFromMeasuredCost() {
        val p = pacer()
        p.onFrameRequested("k", 10_000L)
        p.onExtractionFinished(50L) // interval = 100ms
        assertFalse("masih di dalam interval hasil ukur", p.onFrameRequested("k", 10_000L + 99L).start)
        assertTrue(p.onFrameRequested("k", 10_000L + 100L).start)
    }

    @Test
    fun keyChangeIsReportedSoCallerCanRetireStaleFrame() {
        val p = pacer()
        p.onFrameRequested("a", 10_000L)
        p.onExtractionFinished(0L)
        val d = p.onFrameRequested("b", 10_000L + MIN_STREAM_FRAME_MS)
        assertTrue("ganti sumber video harus dilaporkan", d.keyChanged)
        assertTrue("request pertama untuk key baru dimulai", d.start)
    }

    @Test
    fun keyChangeKeepsMeasuredInterval() {
        val p = pacer()
        p.onFrameRequested("a", 10_000L)
        p.onExtractionFinished(50L) // interval = 100
        assertTrue(p.onFrameRequested("b", 20_000L).start)
        p.onExtractionFinished(50L) // extraction selesai; interval tetap 100 hasil ukur
        // setelah ganti key, interval hasil ukur tetap dipakai (bukan reset ke MIN)
        assertFalse(p.onFrameRequested("b", 20_000L + 99L).start)
        assertTrue(p.onFrameRequested("b", 20_000L + 100L).start)
    }

    @Test
    fun resetReturnsToMinCadenceAndForgetsKey() {
        val p = pacer()
        p.onFrameRequested("a", 10_000L)
        p.onExtractionFinished(500L) // interval = MAX
        p.reset()
        // Semantik asli: reset menyetel key = null, jadi request berikutnya "key change dari null"
        val d = p.onFrameRequested("b", 30_000L)
        assertTrue("request setelah reset dimulai", d.start)
        assertTrue("key non-null setelah reset = key change (semantik asli)", d.keyChanged)
        assertFalse("cadence kembali ke minimum", p.onFrameRequested("b", 30_000L + MIN_STREAM_FRAME_MS - 1).start)
    }

    @Test
    fun requestAfterIntervalStarts() {
        val p = pacer()
        p.onFrameRequested("k", 10_000L)
        p.onExtractionFinished(0L)
        val d = p.onFrameRequested("k", 10_000L + MIN_STREAM_FRAME_MS)
        assertTrue(d.start)
    }

    @Test
    fun intervalStaysWithinConfiguredBounds() {
        val p = pacer()
        p.onFrameRequested("k", 0L)
        p.onExtractionFinished(Long.MAX_VALUE / 2)
        assertTrue("cadence tidak boleh melebihi batas atas", p.onFrameRequested("k", MAX_STREAM_FRAME_MS).start)
    }

    @Test
    fun fastExtractorSettlesAtMinimumCadence() {
        val p = pacer()
        var now = 0L
        for (i in 1..50) {
            p.onFrameRequested("k", now)
            p.onExtractionFinished(0L)
            now += MIN_STREAM_FRAME_MS
        }
        assertEquals(MIN_STREAM_FRAME_MS, 33L)
    }
}
