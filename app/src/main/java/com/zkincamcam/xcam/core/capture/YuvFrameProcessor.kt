package com.zkincamcam.xcam.core.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import androidx.core.graphics.scale
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Feeds captured media into camera streams that expect YUV_420_888.
 *
 * Colour conversion runs on a dedicated worker thread so the camera callback thread is never
 * blocked: an injection always writes the most recent conversion that has already finished, and a
 * newer request supersedes any conversion still pending. Bitmaps handed in by the producer are
 * recycled only once no conversion can still observe them. Nothing thrown here is allowed to
 * escape into the hooked application.
 */
internal class YuvFrameProcessor(
    private val colorMatrix: YuvConverter.ColorMatrix = YuvConverter.ColorMatrix.BT601,
    private val colorRange: YuvConverter.ColorRange = YuvConverter.ColorRange.LIMITED,
    private val logAction: (String) -> Unit = {},
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xcam-yuv").apply {
            isDaemon = true
        }
    }

    private val lock = Any()
    private val published = HashMap<FrameKey, PublishedFrame>()
    private val announced = HashSet<FrameKey>()
    private val layoutsReported = HashSet<FrameKey>()
    private val inFlight = HashSet<Any>()
    private val retired = ArrayDeque<Bitmap>()
    private var requestedSource: Any? = null
    private var requestedKey: FrameKey? = null
    private var convertingSource: Any? = null
    private var conversionScheduled = false
    private var writeFailureReported = false
    private var sourceGeneration = 0L

    private class PublishedFrame(val source: Any, val frame: YuvFrame)

    private data class FrameKey(val width: Int, val height: Int)

    fun injectFromBitmap(image: Image, bitmap: Bitmap): Boolean = inject(image, bitmap)

    fun injectFromJpeg(image: Image, jpeg: ByteArray): Boolean = inject(image, jpeg)

    fun releaseWhenIdle(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(lock) { retired.addLast(bitmap) }
    }

    fun flush() {
        synchronized(lock) {
            sourceGeneration++
            published.clear()
            requestedSource?.let { pending ->
                if (pending !== convertingSource) inFlight.remove(pending)
            }
            requestedSource = null
            requestedKey = null
            drainRetiredLocked()
        }
    }

    fun shutdown() {
        flush()
        worker.shutdownNow()
    }

    private fun inject(image: Image, source: Any): Boolean {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return false
        if (image.planes.size < 3) return false

        return try {
            val key = FrameKey(width, height)
            var schedule = false
            val available: YuvFrame?

            synchronized(lock) {
                val current = published[key]
                available = current?.frame
                if (current == null || current.source !== source) {
                    requestedSource?.let { previous ->
                        if (previous !== source && previous !== convertingSource) inFlight.remove(previous)
                    }
                    requestedSource = source
                    requestedKey = key
                    inFlight.add(source)
                    if (!conversionScheduled) {
                        conversionScheduled = true
                        schedule = true
                    }
                }
            }
            if (schedule) worker.execute(::drainConversions)

            val frame = available ?: return false
            writeFrame(image, frame, key)
        } catch (e: Throwable) {
            logAction("[YUV] injection failed for ${image.width}x${image.height}: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun drainConversions() {
        while (true) {
            val source: Any
            val key: FrameKey
            val generation: Long

            synchronized(lock) {
                val pending = requestedSource
                val pendingKey = requestedKey
                if (pending == null || pendingKey == null) {
                    conversionScheduled = false
                    drainRetiredLocked()
                    return
                }
                source = pending
                key = pendingKey
                generation = sourceGeneration
                convertingSource = source
                requestedSource = null
                requestedKey = null
            }

            val frame = try {
                convert(source, key)
            } catch (e: Throwable) {
                logAction("[YUV] conversion failed for ${key.width}x${key.height}: ${e.javaClass.simpleName}: ${e.message}")
                null
            }

            val firstConversion = synchronized(lock) {
                inFlight.remove(source)
                if (convertingSource === source) convertingSource = null
                if (frame != null && generation == sourceGeneration) {
                    published[key] = PublishedFrame(source, frame)
                }
                announced.add(key)
            }
            if (firstConversion && frame != null) logAction("[YUV] conversion ready ${frame.describe()}")
            drainRetired()
        }
    }

    private fun drainRetired() = synchronized(lock) { drainRetiredLocked() }

    private fun drainRetiredLocked() {
        val iterator = retired.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (inFlight.contains(candidate)) continue
            iterator.remove()
            recycle(candidate)
        }
    }

    private fun recycle(bitmap: Bitmap) {
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (e: Throwable) {
            logAction("[YUV] failed to recycle frame: ${e.message}")
        }
    }

    private fun convert(source: Any, key: FrameKey): YuvFrame? = when (source) {
        is Bitmap -> convertBitmap(source, key)
        is ByteArray -> convertJpeg(source, key)
        else -> {
            logAction("[YUV] unsupported frame source: ${source.javaClass.simpleName}")
            null
        }
    }

    private fun convertBitmap(bitmap: Bitmap, key: FrameKey): YuvFrame? {
        if (bitmap.isRecycled) return null

        val width = key.width
        val height = key.height
        val pixels = IntArray(width * height)

        if (bitmap.width == width && bitmap.height == height) {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        } else {
            val crop = YuvConverter.fitCrop(bitmap.width, bitmap.height, width, height)
            val matrix = Matrix().apply {
                postScale(width.toFloat() / crop.width, height.toFloat() / crop.height)
            }
            val scaled = Bitmap.createBitmap(bitmap, crop.x, crop.y, crop.width, crop.height, matrix, true)
            var fitted: Bitmap? = null
            try {
                val fittedBitmap = if (scaled.width == width && scaled.height == height) {
                    scaled
                } else {
                    scaled.scale(width, height)
                }
                fitted = fittedBitmap
                fittedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            } finally {
                if (fitted != null && fitted !== scaled && !fitted.isRecycled) {
                    fitted.recycle()
                }
                scaled.recycle()
            }
        }

        val frame = YuvConverter.createFrame(width, height)
        YuvConverter.argbToYuv420(pixels, width, height, frame, colorMatrix, colorRange)
        return frame
    }

    private fun convertJpeg(jpeg: ByteArray, key: FrameKey): YuvFrame? {
        if (jpeg.isEmpty()) return null
        val decoded = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (e: Throwable) {
            logAction("[YUV] JPEG decode failed: ${e.message}")
            null
        } ?: return null

        return try {
            convertBitmap(decoded, key)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun writeFrame(image: Image, frame: YuvFrame, key: FrameKey): Boolean {
        val planes = image.planes
        if (planes.size < 3) return false

        val reportLayout = synchronized(lock) { layoutsReported.add(key) }
        if (reportLayout) {
            logAction(
                "[YUV] plane layout ${key.width}x${key.height}: " +
                    "y(${planes[0].rowStride}/${planes[0].pixelStride}) " +
                    "u(${planes[1].rowStride}/${planes[1].pixelStride}) " +
                    "v(${planes[2].rowStride}/${planes[2].pixelStride})"
            )
        }

        val writable = canWritePlane(planes[0], frame.y.size, frame.width, frame.height) &&
            canWritePlane(planes[1], frame.u.size, frame.chromaWidth, frame.chromaHeight) &&
            canWritePlane(planes[2], frame.v.size, frame.chromaWidth, frame.chromaHeight)
        if (!writable) {
            val firstFailure = synchronized(lock) {
                if (writeFailureReported) false else {
                    writeFailureReported = true
                    true
                }
            }
            if (firstFailure) {
                logAction("[YUV] failed to write ${key.width}x${key.height}: buffers read-only or smaller than the frame")
            }
            return false
        }

        val lumaWritten = writePlane(planes[0], frame.y, frame.width, frame.height)
        val uWritten = writePlane(planes[1], frame.u, frame.chromaWidth, frame.chromaHeight)
        val vWritten = writePlane(planes[2], frame.v, frame.chromaWidth, frame.chromaHeight)
        return lumaWritten == frame.y.size && uWritten == frame.u.size && vWritten == frame.v.size
    }

    private fun canWritePlane(plane: Image.Plane, valueSize: Int, width: Int, height: Int): Boolean {
        val buffer = plane.buffer ?: return false
        return YuvPlaneWriter.canWrite(buffer, plane.rowStride, plane.pixelStride, valueSize, width, height)
    }

    private fun writePlane(plane: Image.Plane, values: ByteArray, width: Int, height: Int): Int {
        val buffer = plane.buffer ?: return 0
        if (buffer.isReadOnly) return 0
        return YuvPlaneWriter.write(buffer, plane.rowStride, plane.pixelStride, values, width, height)
    }
}