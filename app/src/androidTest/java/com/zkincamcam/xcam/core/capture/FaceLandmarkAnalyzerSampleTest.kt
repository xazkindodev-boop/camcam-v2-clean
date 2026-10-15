package com.zkincamcam.xcam.core.capture

import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FaceLandmarkAnalyzerSampleTest {

    @Test
    fun detectsFaceInDeviceSampleImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val externalFiles = context.getExternalFilesDir(null)
        assumeTrue("No optional sample image was staged for this device", externalFiles != null)
        val sample = File(externalFiles!!, SAMPLE_FILE_NAME)
        assumeTrue("No optional sample image was staged for this device", sample.isFile)
        val bitmap = BitmapFactory.decodeFile(sample.absolutePath)
        assertNotNull("Could not decode sample image", bitmap)

        bitmap!!.let {
            try {
                val probe = it.scaleToQualityProbe()
                val sharpness = LandmarkImageQuality.meanLaplacianMagnitude(
                    probe.second,
                    probe.first.first,
                    probe.first.second,
                )
                Log.i(TAG, "sample sharpness score=$sharpness, retry=${LandmarkImageQuality.needsSharpening(sharpness)}")
                val result = FaceLandmarkAnalyzer.analyze(it)
                val hasMesh = result.meshPoints != null && result.meshIndices != null
                Log.i(
                    TAG,
                    "sample=${it.width}x${it.height}, detected=${result.detected}, mesh=$hasMesh, " +
                        "center=${result.faceCenterX},${result.faceCenterY}, " +
                        "eyes=${result.leftEyeX},${result.leftEyeY}/${result.rightEyeX},${result.rightEyeY}",
                )
                assertTrue("Face was not detected in the device's sample image", result.detected)
            } finally {
                it.recycle()
            }
        }
    }

    @Test
    fun detectsFaceInBlurredVersionOfDeviceSample() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val externalFiles = context.getExternalFilesDir(null)
        assumeTrue("No optional sample image was staged for this device", externalFiles != null)
        val sample = File(externalFiles!!, SAMPLE_FILE_NAME)
        assumeTrue("No optional sample image was staged for this device", sample.isFile)
        val bitmap = BitmapFactory.decodeFile(sample.absolutePath)
        assertNotNull("Could not decode sample image", bitmap)

        bitmap!!.let { source ->
            val blurred = blur(source, passes = 8)
            try {
                val probe = blurred.scaleToQualityProbe()
                val sharpness = LandmarkImageQuality.meanLaplacianMagnitude(
                    probe.second,
                    probe.first.first,
                    probe.first.second,
                )
                assertTrue(
                    "Synthetic blur was not classified as soft (score=$sharpness)",
                    LandmarkImageQuality.needsSharpening(sharpness),
                )
                val result = FaceLandmarkAnalyzer.analyze(blurred)
                val hasMesh = result.meshPoints != null && result.meshIndices != null
                Log.i(TAG, "blurred sample detected=${result.detected}, mesh=$hasMesh, sharpness=$sharpness")
                assertTrue("Face was not detected in the blurred sample", result.detected)
            } finally {
                blurred.recycle()
                source.recycle()
            }
        }
    }

    private fun blur(source: android.graphics.Bitmap, passes: Int): android.graphics.Bitmap {
        val width = source.width
        val height = source.height
        val sourcePixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        var pixels = sourcePixels
        val weights = intArrayOf(1, 4, 6, 4, 1)
        repeat(passes) {
            val horizontal = IntArray(pixels.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    horizontal[y * width + x] = weightedPixel(pixels, width, height, x, y, weights, horizontal = true)
                }
            }
            val vertical = IntArray(pixels.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    vertical[y * width + x] = weightedPixel(horizontal, width, height, x, y, weights, horizontal = false)
                }
            }
            pixels = vertical
        }
        return createBitmap(width, height).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun weightedPixel(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        weights: IntArray,
        horizontal: Boolean,
    ): Int {
        var red = 0
        var green = 0
        var blue = 0
        var totalWeight = 0
        for (offset in -2..2) {
            val px = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
            val py = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
            val pixel = pixels[py * width + px]
            val weight = weights[offset + 2]
            red += ((pixel shr 16) and 0xFF) * weight
            green += ((pixel shr 8) and 0xFF) * weight
            blue += (pixel and 0xFF) * weight
            totalWeight += weight
        }
        return (0xFF shl 24) or
            ((red / totalWeight) shl 16) or
            ((green / totalWeight) shl 8) or
            (blue / totalWeight)
    }

    private fun android.graphics.Bitmap.scaleToQualityProbe(): Pair<Pair<Int, Int>, IntArray> {
        val maxDimension = 128
        val ratio = maxDimension.toFloat() / maxOf(width, height)
        val probeWidth = maxOf(3, (width * ratio).toInt())
        val probeHeight = maxOf(3, (height * ratio).toInt())
        val probeBitmap = android.graphics.Bitmap.createScaledBitmap(this, probeWidth, probeHeight, true)
        return try {
            val pixels = IntArray(probeWidth * probeHeight)
            probeBitmap.getPixels(pixels, 0, probeWidth, 0, 0, probeWidth, probeHeight)
            val grayscale = IntArray(pixels.size) { index ->
                val pixel = pixels[index]
                (0.299f * ((pixel shr 16) and 0xFF) +
                    0.587f * ((pixel shr 8) and 0xFF) +
                    0.114f * (pixel and 0xFF)).toInt()
            }
            Pair(Pair(probeWidth, probeHeight), grayscale)
        } finally {
            if (probeBitmap !== this) probeBitmap.recycle()
        }
    }

    private companion object {
        const val TAG = "FaceLandmarkSampleTest"
        const val SAMPLE_FILE_NAME = "landmark-sample.jpg"
    }
}
