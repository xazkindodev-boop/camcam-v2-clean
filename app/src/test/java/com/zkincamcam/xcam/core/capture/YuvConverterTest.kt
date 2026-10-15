package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvConverterTest {

    private fun convert(
        argb: Int,
        matrix: YuvConverter.ColorMatrix = YuvConverter.ColorMatrix.BT601,
        range: YuvConverter.ColorRange = YuvConverter.ColorRange.LIMITED,
    ): Triple<Int, Int, Int> {
        val frame = YuvConverter.createFrame(2, 2)
        YuvConverter.argbToYuv420(
            IntArray(4) { argb }, 2, 2, frame, matrix, range,
        )
        return Triple(frame.y[0].toInt() and 0xFF, frame.u[0].toInt() and 0xFF, frame.v[0].toInt() and 0xFF)
    }

    @Test
    fun limitedRangeMapsBlackTo16AndWhiteTo235() {
        assertEquals(16, convert(0xFF000000.toInt()).first)
        assertEquals(235, convert(0xFFFFFFFF.toInt()).first)
    }

    @Test
    fun fullRangeMapsBlackTo0AndWhiteTo255() {
        val black = convert(0xFF000000.toInt(), range = YuvConverter.ColorRange.FULL).first
        val white = convert(0xFFFFFFFF.toInt(), range = YuvConverter.ColorRange.FULL).first

        assertEquals(0, black)
        assertEquals(255, white)
    }

    @Test
    fun bt601LimitedMatchesStandardRedPrimary() {
        val (luma, u, v) = convert(0xFFFF0000.toInt())

        assertEquals(82, luma)
        assertEquals(90, u)
        assertEquals(240, v)
    }

    @Test
    fun bt709LimitedMatchesStandardRedPrimary() {
        val (luma, u, v) = convert(0xFFFF0000.toInt(), matrix = YuvConverter.ColorMatrix.BT709)

        assertEquals(63, luma)
        assertEquals(102, u)
        assertEquals(240, v)
    }

    @Test
    fun greyProducesNeutralChromaInEveryMode() {
        val modes = listOf(
            YuvConverter.ColorMatrix.BT601 to YuvConverter.ColorRange.LIMITED,
            YuvConverter.ColorMatrix.BT601 to YuvConverter.ColorRange.FULL,
            YuvConverter.ColorMatrix.BT709 to YuvConverter.ColorRange.LIMITED,
            YuvConverter.ColorMatrix.BT709 to YuvConverter.ColorRange.FULL,
        )

        for ((matrix, range) in modes) {
            val (_, u, v) = convert(0xFF808080.toInt(), matrix, range)
            assertTrue("$matrix/$range U=$u", kotlin.math.abs(u - 128) <= 1)
            assertTrue("$matrix/$range V=$v", kotlin.math.abs(v - 128) <= 1)
        }
    }

    @Test
    fun chromaAveragesTheWhole2x2BlockInsteadOfPointSampling() {
        val pixels = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
        )
        val frame = YuvConverter.createFrame(2, 2)

        YuvConverter.argbToYuv420(pixels, 2, 2, frame, YuvConverter.ColorMatrix.BT601, YuvConverter.ColorRange.LIMITED)

        assertEquals(16, frame.y[0].toInt() and 0xFF)
        assertEquals(235, frame.y[1].toInt() and 0xFF)
        assertEquals(128, frame.u[0].toInt() and 0xFF)
        assertEquals(128, frame.v[0].toInt() and 0xFF)
    }

    @Test
    fun oddDimensionsRoundChromaPlaneSizeUp() {
        val frame = YuvConverter.createFrame(3, 5)
        val pixels = IntArray(15) { 0xFF404040.toInt() }

        YuvConverter.argbToYuv420(pixels, 3, 5, frame, YuvConverter.ColorMatrix.BT601, YuvConverter.ColorRange.LIMITED)

        assertEquals(2, frame.chromaWidth)
        assertEquals(3, frame.chromaHeight)
        assertEquals(15, frame.y.size)
        assertEquals(6, frame.u.size)
        assertEquals(6, frame.v.size)
        assertEquals(71, frame.y[0].toInt() and 0xFF)
        assertTrue(frame.u.all { (it.toInt() and 0xFF) == 128 })
        assertTrue(frame.v.all { (it.toInt() and 0xFF) == 128 })
    }

    @Test
    fun fitCropTrimsWideSourceToSquareTarget() {
        val crop = YuvConverter.fitCrop(1920, 1080, 720, 720)

        assertEquals(420, crop.x)
        assertEquals(0, crop.y)
        assertEquals(1080, crop.width)
        assertEquals(1080, crop.height)
    }

    @Test
    fun fitCropTrimsTallSourceToWideTarget() {
        val crop = YuvConverter.fitCrop(720, 1280, 1920, 1080)

        assertEquals(0, crop.x)
        assertEquals(437, crop.y)
        assertEquals(720, crop.width)
        assertEquals(405, crop.height)
    }

    @Test
    fun fitCropKeepsMatchingAspectUntouched() {
        val crop = YuvConverter.fitCrop(4, 4, 3, 3)

        assertEquals(YuvConverter.CropRect(0, 0, 4, 4), crop)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameRejectsNonPositiveSize() {
        YuvConverter.createFrame(0, 720)
    }

    @Test(expected = IllegalArgumentException::class)
    fun conversionRejectsFrameSizeMismatch() {
        YuvConverter.argbToYuv420(
            IntArray(4), 2, 2, YuvConverter.createFrame(4, 4),
            YuvConverter.ColorMatrix.BT601, YuvConverter.ColorRange.LIMITED,
        )
    }
}