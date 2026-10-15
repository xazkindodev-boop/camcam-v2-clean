package com.zkincamcam.xcam.core.capture

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.pow

object MediaPreprocessor {

    data class ColorAdjustments(
        val exposure: Float = 0f,
        val contrast: Float = 1f,
        val highlights: Float = 0f,
        val shadows: Float = 0f,
        val temperature: Float = 0f,
        val tint: Float = 0f,
        val saturation: Float = 1f
    )

    fun suggestExposure(src: Bitmap): Float {
        val sample = src.scale(64, 64, true)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        if (sample !== src) sample.recycle()
        return suggestExposure(pixels)
    }

    internal fun suggestExposure(pixels: IntArray): Float {
        require(pixels.isNotEmpty())
        val luminance = pixels.map { pixel ->
            (0.299f * ((pixel shr 16) and 0xFF) +
                    0.587f * ((pixel shr 8) and 0xFF) +
                    0.114f * (pixel and 0xFF)) / 255f
        }.sorted()
        val percentile = luminance[(luminance.size * 0.65f).toInt().coerceAtMost(luminance.lastIndex)]
            .coerceAtLeast(0.02f)
        return (kotlin.math.ln(0.52f / percentile) / kotlin.math.ln(2f)).coerceIn(-1.5f, 1.5f)
    }

    fun applyZoom(src: Bitmap, zoomValue: Float): Bitmap {
        if (zoomValue <= 1.0f) return src
        val width = src.width; val height = src.height
        val newWidth = (width / zoomValue).toInt().coerceIn(10, width)
        val newHeight = (height / zoomValue).toInt().coerceIn(10, height)
        val x = ((width - newWidth) / 2).coerceIn(0, width - newWidth)
        val y = ((height - newHeight) / 2).coerceIn(0, height - newHeight)
        val matrix = android.graphics.Matrix(); matrix.postScale(zoomValue, zoomValue)
        return Bitmap.createBitmap(src, x, y, newWidth, newHeight, matrix, true)
    }

    fun applyColorAdjustments(src: Bitmap, adj: ColorAdjustments): Bitmap {
        val width = src.width; val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val outputPixels = IntArray(width * height)

        val exposureFactor = 2.0f.pow(adj.exposure)
        val contrastFactor = adj.contrast

        val rMod = 1.0f + adj.temperature * 0.12f - adj.tint * 0.05f
        val gMod = 1.0f + adj.tint * 0.10f
        val bMod = 1.0f - adj.temperature * 0.12f - adj.tint * 0.05f

        for (i in pixels.indices) {
            val p = pixels[i]
            var r = ((p shr 16) and 0xFF) / 255f
            var g = ((p shr 8) and 0xFF) / 255f
            var b = (p and 0xFF) / 255f

            r *= exposureFactor * rMod
            g *= exposureFactor * gMod
            b *= exposureFactor * bMod

            r = 0.5f + (r - 0.5f) * contrastFactor
            g = 0.5f + (g - 0.5f) * contrastFactor
            b = 0.5f + (b - 0.5f) * contrastFactor

            val luma = 0.299f * r + 0.587f * g + 0.114f * b

            if (adj.highlights != 0f && luma > 0.6f) {
                val hWeight = (luma - 0.6f) * 2.5f * adj.highlights
                r -= r * hWeight; g -= g * hWeight; b -= b * hWeight
            }

            if (adj.shadows != 0f && luma < 0.4f) {
                val sWeight = (0.4f - luma) * 2.5f * adj.shadows
                r += (1f - r) * sWeight; g += (1f - g) * sWeight; b += (1f - b) * sWeight
            }

            val finalLuma = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
            r = finalLuma + (r - finalLuma) * adj.saturation
            g = finalLuma + (g - finalLuma) * adj.saturation
            b = finalLuma + (b - finalLuma) * adj.saturation

            val ir = (r * 255f).toInt().coerceIn(0, 255)
            val ig = (g * 255f).toInt().coerceIn(0, 255)
            val ib = (b * 255f).toInt().coerceIn(0, 255)

            outputPixels[i] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
        }

        val dest = createBitmap(width, height, src.config ?: Bitmap.Config.ARGB_8888)
        dest.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return dest
    }

    fun applySharpen(src: Bitmap, weight: Float): Bitmap {
        if (weight <= 0f) return src
        val width = src.width; val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val outputPixels = sharpenArgbPixels(pixels, width, height, weight)

        val dest = createBitmap(width, height, src.config ?: Bitmap.Config.ARGB_8888)
        dest.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return dest
    }

    internal fun sharpenArgbPixels(pixels: IntArray, width: Int, height: Int, weight: Float): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val output = pixels.copyOf()
        val amount = (weight.coerceAtLeast(0f) * 0.15f).coerceAtMost(0.75f)
        if (amount == 0f || width < 3 || height < 3) return output

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val center = pixels[index]
                val top = pixels[index - width]
                val bottom = pixels[index + width]
                val left = pixels[index - 1]
                val right = pixels[index + 1]
                val red = sharpenChannel(
                    (center shr 16) and 0xFF,
                    (top shr 16) and 0xFF,
                    (bottom shr 16) and 0xFF,
                    (left shr 16) and 0xFF,
                    (right shr 16) and 0xFF,
                    amount
                )
                val green = sharpenChannel(
                    (center shr 8) and 0xFF,
                    (top shr 8) and 0xFF,
                    (bottom shr 8) and 0xFF,
                    (left shr 8) and 0xFF,
                    (right shr 8) and 0xFF,
                    amount
                )
                val blue = sharpenChannel(
                    center and 0xFF,
                    top and 0xFF,
                    bottom and 0xFF,
                    left and 0xFF,
                    right and 0xFF,
                    amount
                )
                output[index] = (center and -0x1000000) or (red shl 16) or (green shl 8) or blue
            }
        }
        return output
    }

    private fun sharpenChannel(
        center: Int,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        amount: Float
    ): Int {
        val neighborAverage = (top + bottom + left + right) / 4f
        return (center + (center - neighborAverage) * amount).toInt().coerceIn(0, 255)
    }
}
