package com.zkincamcam.xcam.core.capture

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rasterizes the 468-point face mesh into a low-resolution skin alpha mask
 * (one byte per pixel, 0..255) that keeps the beauty pass on the face and off
 * the eyes and background — the anti-plastic pass.
 *
 * Pure Kotlin, no Android APIs: the geometry rules are JVM-tested, and the
 * byte array is uploaded to GL as a luminance texture by whichever pipeline
 * needs it (preview renderer and export shader share the same mask, so the
 * beauty effect is identical on both sides).
 *
 * Pipeline: scanline-rasterize the mesh triangles -> dilate outward so the
 * mask slightly over-covers the face -> feather the edge -> subtract exclusion
 * circles (eyes; lips could be added the same way).
 */
object SkinMaskGenerator {

    /** One byte per pixel, row-major, size = width*height. */
    fun generate(
        meshPoints: FloatArray,
        meshIndices: IntArray,
        width: Int,
        height: Int,
        featherPx: Float = 1.5f,
        dilatePx: Float = 1f,
        exclusionPoints: List<Pair<Float, Float>> = emptyList(),
        exclusionRadius: Float = 0f,
    ): ByteArray {
        if (!LivenessMeshMapper.isComplete(meshPoints, meshIndices)) return ByteArray(width * height)
        val coverage = BooleanArray(width * height)

        // --- 1. Rasterize triangles by barycentric bounding-box scan. ---
        for (t in meshIndices.indices step 3) {
            val i0 = meshIndices[t] * 3
            val i1 = meshIndices[t + 1] * 3
            val i2 = meshIndices[t + 2] * 3
            val ax = meshPoints[i0] * width; val ay = meshPoints[i0 + 1] * height
            val bx = meshPoints[i1] * width; val by = meshPoints[i1 + 1] * height
            val cx = meshPoints[i2] * width; val cy = meshPoints[i2 + 1] * height
            val minX = max(0, (min(ax, min(bx, cx)) - dilatePx - 1).roundToInt())
            val maxX = min(width - 1, (max(ax, max(bx, cx)) + dilatePx + 1).roundToInt())
            val minY = max(0, (min(ay, min(by, cy)) - dilatePx - 1).roundToInt())
            val maxY = min(height - 1, (max(ay, max(by, cy)) + dilatePx + 1).roundToInt())
            val det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (det == 0f) continue
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val px = x + 0.5f; val py = y + 0.5f
                    val w0 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / det
                    val w1 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / det
                    val w2 = 1f - w0 - w1
                    if (w0 >= -0.001f && w1 >= -0.001f && w2 >= -0.001f) coverage[y * width + x] = true
                }
            }
        }

        // --- 2. Feather: box-blur the boolean coverage into soft alpha. ---
        val r = max(1, featherPx.roundToInt())
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0; var n = 0
                for (dy in -r..r) {
                    val yy = y + dy
                    if (yy < 0 || yy >= height) continue
                    for (dx in -r..r) {
                        val xx = x + dx
                        if (xx < 0 || xx >= width) continue
                        sum += if (coverage[yy * width + xx]) 255 else 0
                        n++
                    }
                }
                out[y * width + x] = (sum / n).toByte()
            }
        }

        // --- 3. Exclusions (eyes, etc.) — radial falloff. ---
        if (exclusionPoints.isNotEmpty() && exclusionRadius > 0f) {
            val rpx = exclusionRadius * width
            for ((ex, ey) in exclusionPoints) {
                val cx = ex * width; val cy = ey * height
                val minX = max(0, (cx - rpx).roundToInt()); val maxX = min(width - 1, (cx + rpx).roundToInt())
                val minY = max(0, (cy - rpx * height / width).roundToInt()); val maxY = min(height - 1, (cy + rpx * height / width).roundToInt())
                for (y in minY..maxY) {
                    for (x in minX..maxX) {
                        val d = kotlin.math.hypot((x - cx).toDouble(), ((y - cy) * width / height).toDouble())
                        if (d < rpx) {
                            val kill = (1f - (d / rpx).toFloat()).coerceIn(0f, 1f)
                            val i = y * width + x
                            val v = (out[i].toInt() and 0xFF) * (1f - kill)
                            out[i] = v.roundToInt().toByte()
                        }
                    }
                }
            }
        }
        return out
    }

    /** Convenience: eye exclusion points from analyzed landmarks (normalised top-down). */
    fun eyeExclusions(landmarks: FaceLandmarkAnalyzer.EyeLandmarks): List<Pair<Float, Float>> =
        if (landmarks.detected) listOf(landmarks.leftEyeX to landmarks.leftEyeY, landmarks.rightEyeX to landmarks.rightEyeY) else emptyList()
}
