package com.zkincamcam.xcam.core.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix as AndroidMatrix
import android.media.MediaMetadataRetriever
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.zkincamcam.xcam.data.Constants.DEFAULT_CAPTURE_HEIGHT
import com.zkincamcam.xcam.data.Constants.DEFAULT_CAPTURE_WIDTH
import com.zkincamcam.xcam.data.Constants.VIDEO_SEEK_GUARD_MS
import java.io.ByteArrayOutputStream

/**
 * Turns the configured media into frames that can be pushed into camera streams.
 *
 * [createFrameBitmap] is the primitive every other entry point builds on: callers that only need
 * JPEG bytes go through [createJpeg], while the YUV path consumes the bitmap directly and skips
 * a full JPEG encode/decode round trip per frame.
 */
object XCamCapture {

    private val FRAME_OPTIONS = intArrayOf(
        MediaMetadataRetriever.OPTION_CLOSEST,
        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
        MediaMetadataRetriever.OPTION_NEXT_SYNC,
    )

    fun createFrameBitmap(
        context: Context,
        path: String,
        targetW: Int,
        targetH: Int,
        rotation: Int,
        mirrored: Boolean,
        timeMs: Long = 1000L,
        printLog: (String) -> Unit,
    ): Bitmap? {
        val width = targetW.coerceAtLeast(1)
        val height = targetH.coerceAtLeast(1)
        val source = if (path.lowercase().endsWith(".mp4")) {
            extractVideoFrame(context, path, timeMs, printLog)
        } else {
            printLog("Capture Process: Decoding image from $path")
            try {
                context.contentResolver.openInputStream(path.toUri())?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Throwable) {
                printLog("Capture Process: Image decode failed: ${e.message}")
                null
            }
        }

        if (source == null) {
            printLog("Capture Process: FATAL - No frame retrieved from $path")
            return null
        }

        return try {
            printLog("Capture Process: Source Size ${source.width}x${source.height}")

            val rotatedW = if (rotation % 180 != 0) source.height else source.width
            val rotatedH = if (rotation % 180 != 0) source.width else source.height
            val scale = minOf(width.toFloat() / rotatedW, height.toFloat() / rotatedH)

            val matrix = AndroidMatrix()
            matrix.postScale(scale, scale)
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            if (mirrored) matrix.postScale(-1f, 1f)

            val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            try {
                val canvasBitmap = createBitmap(width, height)
                val canvas = Canvas(canvasBitmap)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(
                    transformed,
                    (width - transformed.width) / 2f,
                    (height - transformed.height) / 2f,
                    null,
                )
                printLog("Capture Process: SUCCESS. Frame ${canvasBitmap.width}x${canvasBitmap.height}")
                canvasBitmap
            } finally {
                if (transformed !== source) transformed.recycle()
            }
        } catch (e: Throwable) {
            printLog("Capture Process: FATAL - Transform failed: ${e.message}")
            null
        } finally {
            source.recycle()
        }
    }

    fun createJpeg(
        context: Context,
        path: String,
        targetW: Int,
        targetH: Int,
        rotation: Int,
        mirrored: Boolean,
        timeMs: Long = 1000L,
        printLog: (String) -> Unit,
    ): ByteArray? {
        printLog("Capture Process: Starting for $path (Time: $timeMs ms)")
        val frame = createFrameBitmap(
            context, path,
            targetW.coerceAtLeast(DEFAULT_CAPTURE_WIDTH),
            targetH.coerceAtLeast(DEFAULT_CAPTURE_HEIGHT),
            rotation, mirrored, timeMs, printLog,
        ) ?: return null

        return try {
            val out = ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, 100, out)
            val bytes = out.toByteArray()
            printLog("Capture Process: JPEG ${bytes.size} bytes")
            bytes
        } catch (e: Throwable) {
            printLog("createCaptureJpeg error: ${e.message}")
            null
        } finally {
            frame.recycle()
        }
    }

    private fun extractVideoFrame(
        context: Context,
        path: String,
        timeMs: Long,
        printLog: (String) -> Unit,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, path.toUri())

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val targetUs = seekTargetUs(timeMs, durationMs)
            printLog("Capture Process: Extracting frame at $targetUs us")

            for (attempt in FRAME_OPTIONS) {
                val frame = retriever.getFrameAtTime(targetUs, attempt)
                if (frame != null) {
                    if (attempt != FRAME_OPTIONS[0]) printLog("Capture Process: Frame served by option $attempt")
                    return frame
                }
                printLog("Capture Process: Option $attempt returned no frame, trying next")
            }
            return null
        } catch (e: Throwable) {
            printLog("Capture Process: Video extraction failed: ${e.message}")
            return null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {}
        }
    }

    internal fun seekTargetUs(timeMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return timeMs.coerceAtLeast(0L) * 1000L
        val looped = (timeMs.coerceAtLeast(0L) % durationMs)
        val guarded = if (looped > VIDEO_SEEK_GUARD_MS) looped - VIDEO_SEEK_GUARD_MS else looped
        return (guarded.coerceAtMost(durationMs - 1L)) * 1000L
    }
}