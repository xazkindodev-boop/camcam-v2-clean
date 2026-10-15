package com.zkincamcam.xcam.core.capture

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.zkincamcam.xcam.data.Constants.DEFAULT_CAPTURE_HEIGHT
import com.zkincamcam.xcam.data.Constants.DEFAULT_CAPTURE_WIDTH
import java.util.concurrent.Executors

/**
 * Keeps one decoded frame ready for the camera stream and one JPEG ready for the still capture.
 *
 * Stream frames come from a single background producer paced by how long decoding actually takes,
 * and consumers always receive the newest available frame, so a slow decoder costs smoothness
 * instead of blocking the camera callback.
 */
class CaptureManager(
    private val contextProvider: () -> Context?,
    private val refreshSettingsAction: (Context) -> Unit,
    private val onBitmapRetired: (Bitmap) -> Unit = {},
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val streamExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xcam-stream").apply {
            isDaemon = true
        }
    }

    // Still-capture window + JPEG cache live in CaptureCache; stream pacing in StreamPacer.
    // This class owns the stream bitmap cache, the executor, and the retirement callback.
    private val captureCache = CaptureCache(clockMs = { System.currentTimeMillis() })
    private val streamPacer = StreamPacer()

    @Volatile private var cachedStreamBitmap: Bitmap? = null
    private var streamGeneration = 0L

    val isCapturing: Boolean get() = captureCache.isCapturing

    private fun log(msg: String) = logAction("[CAPTURE] $msg")

    fun triggerCaptureState(currentPositionProvider: () -> Long) {
        val targetFrameTimeMs = try { currentPositionProvider() } catch (_: Throwable) { 0 }
        val opened = captureCache.triggerCapture(targetFrameTimeMs)
        if (!opened) return

        log("[*] Pulse detected! Target Frame Time: $targetFrameTimeMs ms")
        contextProvider()?.let { refreshSettingsAction(it) }

        // Drive the window/cache expiry on the same wall-clock schedule as the original
        // postDelayed chain: window closes after 3 s, cache drops 5 s later.
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({
            captureCache.expireIfNeeded()
        }, 3000L)
        uiHandler.postDelayed({
            captureCache.expireIfNeeded()
        }, 8000L)
    }

    fun handleCapture(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
    ): ByteArray? {
        if (HookGuard.isIgnoring() || path == null) return null
        val context = contextProvider() ?: return null

        return synchronized(this) {
            captureCache.jpegFor(path) {
                try {
                    HookGuard.withGuard {
                        XCamCapture.createJpeg(
                            context, path,
                            width.coerceAtLeast(DEFAULT_CAPTURE_WIDTH),
                            height.coerceAtLeast(DEFAULT_CAPTURE_HEIGHT),
                            rotationAngle, isMirrored, captureCache.targetFrameTimeMs, logAction,
                        )
                    }
                } catch (e: Throwable) {
                    log("Extraction failed: ${e.message}")
                    null
                }.also { if (it != null) log("[+] Capture extraction successful") }
            }
        }
    }

    fun handleStreamFrame(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        currentPositionProvider: () -> Long,
    ): Bitmap? {
        if (HookGuard.isIgnoring()) return null
        val context = contextProvider() ?: return null
        val actualPath = path ?: return null

        val now = SystemClock.elapsedRealtime()
        val key = "$actualPath|$width|$height|$rotationAngle|$isMirrored"

        synchronized(this) {
            val decision = streamPacer.onFrameRequested(key, now)
            if (decision.keyChanged) {
                // A new stream key means the old frame would show the wrong clip.
                streamGeneration++
                retireStreamFrame()
            }

            if (decision.start) {
                val frameTimeMs = try { currentPositionProvider() } catch (_: Throwable) { 0 }
                val generation = streamGeneration

                streamExecutor.execute {
                    val startedAt = SystemClock.elapsedRealtime()
                    val frame = try {
                        HookGuard.withGuard {
                            XCamCapture.createFrameBitmap(
                                context, actualPath, width, height,
                                rotationAngle, isMirrored, frameTimeMs, logAction,
                            )
                        }
                    } catch (e: Throwable) {
                        log("Stream extraction failed: ${e.message}")
                        null
                    }
                    val durationMs = SystemClock.elapsedRealtime() - startedAt

                    synchronized(this@CaptureManager) {
                        if (frame != null && generation == streamGeneration) {
                            retireStreamFrame()
                            cachedStreamBitmap = frame
                        } else if (frame != null) {
                            onBitmapRetired(frame)
                        }
                        streamPacer.onExtractionFinished(durationMs)
                    }
                }
            }
        }

        return cachedStreamBitmap
    }

    fun resetStream() {
        synchronized(this) {
            streamGeneration++
            streamPacer.reset()
            retireStreamFrame()
        }
    }

    private fun retireStreamFrame() {
        val stale = cachedStreamBitmap
        cachedStreamBitmap = null
        if (stale != null) onBitmapRetired(stale)
    }
}
