package com.zkincamcam.xcam.core.capture

import kotlin.math.min

/**
 * Pure colour-space maths for turning packed ARGB pixels into planar YUV 4:2:0.
 * Deliberately free of Android APIs so the conversion can be exercised by JVM unit tests.
 */
internal object YuvConverter {

    enum class ColorRange { LIMITED, FULL }

    enum class ColorMatrix { BT601, BT709 }

    data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

    private class Coefficients(
        val yR: Int, val yG: Int, val yB: Int, val yOffset: Int,
        val uR: Int, val uG: Int, val uB: Int,
        val vR: Int, val vG: Int, val vB: Int,
    ) {
        fun luma(r: Int, g: Int, b: Int): Int =
            (((yR * r + yG * g + yB * b + 128) shr 8) + yOffset).coerceIn(0, 255)

        fun chromaU(r: Int, g: Int, b: Int): Int =
            (((uR * r + uG * g + uB * b + 128) shr 8) + 128).coerceIn(0, 255)

        fun chromaV(r: Int, g: Int, b: Int): Int =
            (((vR * r + vG * g + vB * b + 128) shr 8) + 128).coerceIn(0, 255)
    }

    private val BT601_LIMITED = Coefficients(66, 129, 25, 16, -38, -74, 112, 112, -94, -18)
    private val BT601_FULL = Coefficients(77, 150, 29, 0, -43, -85, 128, 128, -107, -21)
    private val BT709_LIMITED = Coefficients(47, 157, 16, 16, -26, -87, 112, 112, -102, -10)
    private val BT709_FULL = Coefficients(54, 183, 19, 0, -29, -99, 128, 128, -116, -12)

    private fun coefficients(matrix: ColorMatrix, range: ColorRange): Coefficients = when (matrix) {
        ColorMatrix.BT601 -> if (range == ColorRange.LIMITED) BT601_LIMITED else BT601_FULL
        ColorMatrix.BT709 -> if (range == ColorRange.LIMITED) BT709_LIMITED else BT709_FULL
    }

    fun createFrame(width: Int, height: Int): YuvFrame {
        require(width > 0 && height > 0) { "Frame size must be positive: ${width}x$height" }
        return YuvFrame(width, height)
    }

    fun argbToYuv420(
        pixels: IntArray,
        width: Int,
        height: Int,
        frame: YuvFrame,
        matrix: ColorMatrix,
        range: ColorRange,
    ) {
        require(width > 0 && height > 0) { "Frame size must be positive: ${width}x$height" }
        require(pixels.size >= width * height) { "Pixel buffer too small: ${pixels.size} < ${width * height}" }
        require(frame.matches(width, height)) { "Frame size mismatch: ${frame.describe()} != ${width}x$height" }

        val coefficients = coefficients(matrix, range)
        val luma = frame.y
        val chromaU = frame.u
        val chromaV = frame.v
        val chromaWidth = frame.chromaWidth
        val chromaHeight = frame.chromaHeight

        for (row in 0 until height) {
            val rowBase = row * width
            for (col in 0 until width) {
                val argb = pixels[rowBase + col]
                luma[rowBase + col] = coefficients
                    .luma((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
                    .toByte()
            }
        }

        for (chromaRow in 0 until chromaHeight) {
            val topRow = chromaRow * 2
            val bottomRow = min(topRow + 1, height - 1)
            val topBase = topRow * width
            val bottomBase = bottomRow * width
            val outBase = chromaRow * chromaWidth

            for (chromaCol in 0 until chromaWidth) {
                val leftCol = chromaCol * 2
                val rightCol = min(leftCol + 1, width - 1)
                val topLeft = pixels[topBase + leftCol]
                val topRight = pixels[topBase + rightCol]
                val bottomLeft = pixels[bottomBase + leftCol]
                val bottomRight = pixels[bottomBase + rightCol]

                val r = average(
                    (topLeft shr 16) and 0xFF, (topRight shr 16) and 0xFF,
                    (bottomLeft shr 16) and 0xFF, (bottomRight shr 16) and 0xFF,
                )
                val g = average(
                    (topLeft shr 8) and 0xFF, (topRight shr 8) and 0xFF,
                    (bottomLeft shr 8) and 0xFF, (bottomRight shr 8) and 0xFF,
                )
                val b = average(
                    topLeft and 0xFF, topRight and 0xFF,
                    bottomLeft and 0xFF, bottomRight and 0xFF,
                )

                chromaU[outBase + chromaCol] = coefficients.chromaU(r, g, b).toByte()
                chromaV[outBase + chromaCol] = coefficients.chromaV(r, g, b).toByte()
            }
        }
    }

    /**
     * Largest centred crop of the source that matches the target aspect ratio, so a frame
     * captured at a different resolution is filled instead of stretched.
     */
    fun fitCrop(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): CropRect {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source size must be positive: ${sourceWidth}x$sourceHeight" }
        require(targetWidth > 0 && targetHeight > 0) { "Target size must be positive: ${targetWidth}x$targetHeight" }

        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()

        return if (sourceAspect > targetAspect) {
            val width = (sourceHeight * targetAspect).toInt().coerceIn(1, sourceWidth)
            CropRect((sourceWidth - width) / 2, 0, width, sourceHeight)
        } else {
            val height = (sourceWidth / targetAspect).toInt().coerceIn(1, sourceHeight)
            CropRect(0, (sourceHeight - height) / 2, sourceWidth, height)
        }
    }

    private fun average(a: Int, b: Int, c: Int, d: Int) = (a + b + c + d + 2) / 4
}