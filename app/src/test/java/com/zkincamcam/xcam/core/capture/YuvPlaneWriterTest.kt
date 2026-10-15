package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class YuvPlaneWriterTest {

    private fun frame(width: Int, height: Int, matrix: YuvConverter.ColorMatrix, range: YuvConverter.ColorRange): YuvFrame {
        val pixels = IntArray(width * height) { index ->
            if ((index / width + index % width) % 2 == 0) 0xFF204080.toInt() else 0xFFE0C080.toInt()
        }
        val frame = YuvConverter.createFrame(width, height)
        YuvConverter.argbToYuv420(pixels, width, height, frame, matrix, range)
        return frame
    }

    @Test
    fun contiguousPlaneRoundTripsEveryByte() {
        val values = ByteArray(12) { (it * 7 + 3).toByte() }
        val buffer = ByteBuffer.allocateDirect(12)

        val written = YuvPlaneWriter.write(buffer, 4, 1, values, 4, 3)

        assertEquals(12, written)
        for (index in 0 until 12) {
            assertEquals(values[index].toInt(), buffer.get(index).toInt())
        }
    }

    @Test
    fun paddedRowStrideSkipsPaddingBytes() {
        val values = ByteArray(6) { (it + 1).toByte() }
        val buffer = ByteBuffer.allocateDirect(20)

        val written = YuvPlaneWriter.write(buffer, 4, 1, values, 3, 2)

        assertEquals(6, written)
        assertEquals(1, buffer.get(0).toInt())
        assertEquals(2, buffer.get(1).toInt())
        assertEquals(3, buffer.get(2).toInt())
        assertEquals(0, buffer.get(3).toInt())
        assertEquals(4, buffer.get(4).toInt())
        assertEquals(5, buffer.get(5).toInt())
        assertEquals(6, buffer.get(6).toInt())
        assertEquals(0, buffer.get(7).toInt())
    }

    @Test
    fun interleavedChromaUsesPixelStrideOfTwo() {
        val values = ByteArray(4) { (it * 10 + 1).toByte() }
        val buffer = ByteBuffer.allocateDirect(8)

        val written = YuvPlaneWriter.write(buffer, 4, 2, values, 4, 1)

        assertEquals(4, written)
        assertEquals(1, buffer.get(0).toInt())
        assertEquals(0, buffer.get(1).toInt())
        assertEquals(11, buffer.get(2).toInt())
        assertEquals(0, buffer.get(3).toInt())
        assertEquals(21, buffer.get(4).toInt())
        assertEquals(0, buffer.get(5).toInt())
        assertEquals(31, buffer.get(6).toInt())
        assertEquals(0, buffer.get(7).toInt())
    }

    @Test
    fun truncatedBufferIsRejectedWithoutPartialWrite() {
        val values = ByteArray(12) { 1 }
        val buffer = ByteBuffer.allocateDirect(7)

        val written = YuvPlaneWriter.write(buffer, 4, 1, values, 4, 3)

        assertEquals(0, written)
        for (index in 0 until buffer.capacity()) {
            assertEquals(0, buffer.get(index).toInt())
        }
    }

    @Test
    fun readOnlyBufferIsRejectedWithoutThrowing() {
        val buffer = ByteBuffer.allocateDirect(4).asReadOnlyBuffer()

        assertEquals(0, YuvPlaneWriter.write(buffer, 2, 1, byteArrayOf(1, 2), 2, 1))
    }

    @Test
    fun invalidLayoutIsRejectedWithoutTouchingTheBuffer() {
        val values = ByteArray(4) { 9 }
        val buffer = ByteBuffer.allocateDirect(4)

        assertEquals(0, YuvPlaneWriter.write(buffer, 2, 1, values, 4, 1))
        assertEquals(0, YuvPlaneWriter.write(buffer, 4, 0, values, 4, 1))
        assertEquals(0, YuvPlaneWriter.write(buffer, 4, 1, values, 0, 1))
        assertEquals(0, buffer.get(0).toInt())
    }

    @Test
    fun writeStartsAtBufferPosition() {
        val values = byteArrayOf(42, 43)
        val buffer = ByteBuffer.allocateDirect(4)
        buffer.position(2)

        assertEquals(2, YuvPlaneWriter.write(buffer, 2, 1, values, 2, 1))
        assertEquals(42, buffer.get(2).toInt())
        assertEquals(43, buffer.get(3).toInt())
    }

    @Test
    fun semiPlanarChromaCarriesTheSameSamplesAsPlanar() {
        val frame = frame(8, 8, YuvConverter.ColorMatrix.BT601, YuvConverter.ColorRange.LIMITED)
        val chromaWidth = frame.chromaWidth

        val planar = ByteBuffer.allocateDirect(chromaWidth * frame.chromaHeight)
        YuvPlaneWriter.write(planar, chromaWidth, 1, frame.u, chromaWidth, frame.chromaHeight)

        val interleaved = ByteBuffer.allocateDirect(chromaWidth * frame.chromaHeight * 2)
        YuvPlaneWriter.write(interleaved, chromaWidth * 2, 2, frame.u, chromaWidth, frame.chromaHeight)

        for (index in 0 until chromaWidth * frame.chromaHeight) {
            assertEquals(planar.get(index).toInt(), interleaved.get(index * 2).toInt())
            assertEquals(0, interleaved.get(index * 2 + 1).toInt())
        }
    }

    @Test
    fun oddSizedFrameFillsEveryChromaSample() {
        val frame = frame(3, 5, YuvConverter.ColorMatrix.BT601, YuvConverter.ColorRange.LIMITED)
        val chromaBuffer = ByteBuffer.allocateDirect(frame.chromaWidth * frame.chromaHeight)

        val lumaWritten = YuvPlaneWriter.write(ByteBuffer.allocateDirect(15), 3, 1, frame.y, 3, 5)
        val chromaWritten = YuvPlaneWriter.write(chromaBuffer, frame.chromaWidth, 1, frame.u, frame.chromaWidth, frame.chromaHeight)

        assertEquals(15, lumaWritten)
        assertEquals(6, chromaWritten)
        assertTrue(chromaBuffer.get(5).toInt() != 0 || chromaBuffer.get(0).toInt() != 0)
    }
}