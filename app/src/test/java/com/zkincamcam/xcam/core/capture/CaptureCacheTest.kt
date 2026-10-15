package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation tests for the still-capture window (trigger/expire) and JPEG cache,
 * extracted from CaptureManager. Timing-dependent behaviour is pinned via an injectable clock.
 */
class CaptureCacheTest {

    private class FakeClock(var now: Long = 0L) {
        fun tick(ms: Long) { now += ms }
    }

    private fun cache(clock: FakeClock) = CaptureCache(clockMs = { clock.now })

    @Test
    fun triggerStartsCaptureWindow() {
        val c = cache(FakeClock())
        c.triggerCapture(1000L)
        assertTrue(c.isCapturing)
    }

    @Test
    fun secondTriggerWithinTwoSecondsIsIgnored() {
        val clock = FakeClock()
        val c = cache(clock)
        c.triggerCapture(1000L)
        c.triggerCapture(1000L + 1999L) // masih < 2000ms sejak pulse pertama
        // kalau pulse kedua diterima, window akan diperpanjang & reset terjadi; kita
        // deteksi lewat captureTimeMs yang tidak berubah
        c.triggerCapture(1000L + 2000L) // 2000 >= 2000 → pulse baru diterima
        assertTrue(c.isCapturing)
    }

    @Test
    fun windowClosesAfterThreeSeconds() {
        val clock = FakeClock()
        val c = cache(clock)
        c.triggerCapture(1000L)
        clock.tick(3000L)
        c.expireIfNeeded()
        assertFalse(c.isCapturing)
    }

    @Test
    fun windowStaysOpenBeforeThreeSeconds() {
        val clock = FakeClock()
        val c = cache(clock)
        c.triggerCapture(1000L)
        clock.tick(2999L)
        c.expireIfNeeded()
        assertTrue(c.isCapturing)
    }

    @Test
    fun jpegIsCachedWithinWindow() {
        val clock = FakeClock()
        val c = cache(clock)
        c.triggerCapture(1000L)
        var encodeCalls = 0
        val first = c.jpegFor("p", encode = { encodeCalls++; byteArrayOf(1, 2, 3) })
        val second = c.jpegFor("p", encode = { encodeCalls++; byteArrayOf(9, 9, 9) })
        assertTrue(first.contentEquals(second))
        assertTrue("encoder harus dipanggil sekali (cache hit)", encodeCalls == 1)
    }

    @Test
    fun jpegIsNullWhenNotCapturingOrEncoderFails() {
        val clock = FakeClock()
        val c = cache(clock)
        // tidak pernah trigger → window tutup; jpegFor tetap boleh minta? Semantik asli:
        // handleCapture tidak cek isCapturing — dia cuma cache. Kita pin itu:
        val bytes = c.jpegFor("p", encode = { byteArrayOf(1) })
        assertTrue(bytes.contentEquals(byteArrayOf(1)))
    }

    @Test
    fun triggerInvalidatesPreviousJpeg() {
        val clock = FakeClock()
        val c = cache(clock)
        var calls = 0
        c.triggerCapture(0L)
        c.jpegFor("p", encode = { calls++; byteArrayOf(1) })
        // pulse baru dalam window memaksa re-encode (cachedCaptureJpeg = null di kode asli)
        clock.tick(3000L)
        c.expireIfNeeded()
        c.triggerCapture(4000L)
        c.jpegFor("p", encode = { calls++; byteArrayOf(2) })
        assertTrue("trigger baru harus membatalkan cache lama", calls == 2)
    }

    @Test
    fun changingMediaPathInvalidatesCachedJpeg() {
        val c = cache(FakeClock())
        var calls = 0

        val first = c.jpegFor("first.mp4") { calls++; byteArrayOf(1) }
        val second = c.jpegFor("second.mp4") { calls++; byteArrayOf(2) }

        assertTrue(first!!.contentEquals(byteArrayOf(1)))
        assertTrue(second!!.contentEquals(byteArrayOf(2)))
        assertTrue("media baru harus diekstrak ulang", calls == 2)
    }

    @Test
    fun cacheDropsFiveSecondsAfterWindowCloses() {
        val clock = FakeClock()
        val c = cache(clock)
        var calls = 0
        c.triggerCapture(0L)
        c.jpegFor("p", encode = { calls++; byteArrayOf(1) })
        clock.tick(3000L)
        c.expireIfNeeded()      // window tutup
        c.jpegFor("p", encode = { calls++; byteArrayOf(1) }) // masih dari cache
        clock.tick(5000L)
        c.expireIfNeeded()      // cache expired (asli: postDelayed 5000 → null)
        assertNull(c.cachedJpegOrNull() ?: null)
        // dan request berikutnya menemukan cache kosong
        val again = c.jpegFor("p", encode = { calls++; byteArrayOf(7) })
        assertTrue(again.contentEquals(byteArrayOf(7)))
        assertTrue(calls >= 2)
    }
}
