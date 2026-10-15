package com.zkincamcam.xcam.core.capture

import java.nio.ByteBuffer

/**
 * Writes a planar channel into an [android.media.Image] plane buffer.
 *
 * Pure [ByteBuffer] logic so every vendor plane layout can be exercised without a device: I420,
 * NV12/NV21 (interleaved chroma with pixel stride 2), padded row strides, and truncated buffers.
 * Returns the number of bytes actually stored so callers can detect a partial write.
 */
internal object YuvPlaneWriter {

    fun write(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        values: ByteArray,
        width: Int,
        height: Int,
    ): Int {
        if (!canWrite(buffer, rowStride, pixelStride, values.size, width, height)) return 0

        val start = buffer.position()

        for (row in 0 until height) {
            val rowStart = start + row * rowStride
            val srcStart = row * width
            if (pixelStride == 1 && rowStride == width) {
                // Bulk ByteBuffer.put(int, byte[], int, int) is API 35+; use the
                // absolute single-byte put (available since API 1) so minSdk 28 is safe.
                for (i in 0 until width) {
                    buffer.put(rowStart + i, values[srcStart + i])
                }
                continue
            }

            for (col in 0 until width) {
                val position = rowStart + col * pixelStride
                buffer.put(position, values[srcStart + col])
            }
        }
        return width * height
    }

    fun canWrite(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        valuesSize: Int,
        width: Int,
        height: Int,
    ): Boolean {
        if (buffer.isReadOnly || width <= 0 || height <= 0 || rowStride < width || pixelStride < 1) return false
        if (valuesSize.toLong() < width.toLong() * height) return false

        val minimumRowStride = (width - 1L) * pixelStride + 1L
        if (height > 1 && rowStride.toLong() < minimumRowStride) return false

        val requiredBytes = (height - 1L) * rowStride + minimumRowStride
        return requiredBytes <= buffer.limit().toLong() - buffer.position()
    }
}