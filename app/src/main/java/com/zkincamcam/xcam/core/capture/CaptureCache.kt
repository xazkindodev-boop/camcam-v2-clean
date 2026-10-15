package com.zkincamcam.xcam.core.capture

/**
 * Still-capture window + JPEG cache, extracted from CaptureManager.
 *
 * Original semantics (pinned by CaptureCacheTest):
 *  - a capture pulse opens a 3-second window; pulses closer than 2 seconds are ignored;
 *  - a new pulse invalidates any cached JPEG so the next encode reflects the new target frame;
 *  - the cached JPEG survives 5 seconds past the window closing, then is dropped.
 */
internal class CaptureCache(private val clockMs: () -> Long) {

    var isCapturing: Boolean = false
        private set

    private var lastCapturePulseTime = Long.MIN_VALUE / 2
    private var windowOpenedAtMs = 0L
    private var captureTimeMs = 0L
    private var cachedJpeg: ByteArray? = null
    private var cachedPath: String? = null

    /** Target frame time (ms into the virtual clip) captured at pulse time. */
    val targetFrameTimeMs: Long get() = captureTimeMs

    /**
     * Records a capture pulse. Returns true when the pulse opened a fresh window;
     * pulses within 2 seconds of the previous one are ignored (debounce).
     */
    fun triggerCapture(targetFrameTimeMs: Long): Boolean {
        val now = clockMs()
        if (now - lastCapturePulseTime < PULSE_DEBOUNCE_MS) return false
        lastCapturePulseTime = now

        captureTimeMs = targetFrameTimeMs
        isCapturing = true
        cachedJpeg = null
        cachedPath = null
        windowOpenedAtMs = now
        return true
    }

    /** Expires the capture window (3 s) and the JPEG cache (5 s after that). Call periodically. */
    fun expireIfNeeded() {
        val now = clockMs()
        if (isCapturing && now - windowOpenedAtMs >= WINDOW_OPEN_MS) {
            isCapturing = false
        }
        if (!isCapturing && cachedJpeg != null && now - windowOpenedAtMs >= WINDOW_OPEN_MS + CACHE_TTL_MS) {
            cachedJpeg = null
            cachedPath = null
        }
    }

    /**
     * Returns the cached JPEG for [path], encoding it via [encode] on first request.
     * Mirrors the original handleCapture: no isCapturing check here — the window only
     * controls cache lifetime.
     */
    fun jpegFor(path: String, encode: () -> ByteArray?): ByteArray? {
        if (path.isEmpty()) return null
        if (cachedPath != path) {
            cachedJpeg = null
            cachedPath = path
        } else {
            cachedJpeg?.let { return it }
        }
        val jpeg = encode()
        cachedJpeg = jpeg
        return jpeg
    }

    /** Direct cache inspection for tests/diagnostics. */
    fun cachedJpegOrNull(): ByteArray? = cachedJpeg

    private companion object {
        const val PULSE_DEBOUNCE_MS = 2000L
        const val WINDOW_OPEN_MS = 3000L
        const val CACHE_TTL_MS = 5000L
    }
}
