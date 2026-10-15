package com.zkincamcam.xcam.core.capture

import kotlin.math.abs

internal object LandmarkImageQuality {
    const val SOFT_IMAGE_THRESHOLD = 12f

    fun meanLaplacianMagnitude(grayscale: IntArray, width: Int, height: Int): Float {
        require(width >= 3 && height >= 3 && grayscale.size == width * height)
        var total = 0L
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val laplacian = 4 * grayscale[index] -
                    grayscale[index - width] -
                    grayscale[index + width] -
                    grayscale[index - 1] -
                    grayscale[index + 1]
                total += abs(laplacian)
                count++
            }
        }
        return total.toFloat() / count
    }

    fun needsSharpening(meanLaplacianMagnitude: Float): Boolean =
        meanLaplacianMagnitude.isFinite() && meanLaplacianMagnitude < SOFT_IMAGE_THRESHOLD
}
