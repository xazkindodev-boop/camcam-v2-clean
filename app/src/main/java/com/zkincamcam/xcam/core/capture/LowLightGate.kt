package com.zkincamcam.xcam.core.capture

/**
 * Pure decision logic for the low-light preprocessing gate in front of MediaPipe
 * tier 0 (blur + dark-eyes case).
 *
 * A cheap 256-bin luminance histogram probe decides whether the bitmap is dark
 * enough that landmark detection benefits from a brightness boost, and how much.
 * The boost is applied to a detection-only copy — never to what the user sees.
 *
 * Units: luma values are normalised 0..1, histogram bins 0..255.
 * JVM-tested; no Android APIs.
 */
object LowLightGate {

    /** Below this mean luma the image counts as underexposed for detection. */
    const val DARK_MEAN_THRESHOLD = 0.30f

    /** Gain bounds — over-brightening invents detector noise, under does nothing. */
    const val MIN_GAIN = 2.5f
    const val MAX_GAIN = 4.0f

    /** True when the histogram's mean luma is below [DARK_MEAN_THRESHOLD]. */
    fun needsBoost(lumaHistogram: IntArray): Boolean {
        if (lumaHistogram.size != 256) return false
        val mean = meanLuma(lumaHistogram) ?: return false
        return mean < DARK_MEAN_THRESHOLD
    }

    /**
     * Gain derived from how dark the image is: 0.05 mean → near [MAX_GAIN],
     * just under threshold → near [MIN_GAIN], clamped to [MIN_GAIN, MAX_GAIN].
     */
    fun gainFor(lumaHistogram: IntArray): Float {
        val mean = meanLuma(lumaHistogram) ?: return MIN_GAIN
        return clampGain(MIN_GAIN + (1f - mean / DARK_MEAN_THRESHOLD) * (MAX_GAIN - MIN_GAIN))
    }

    fun clampGain(gain: Float): Float = gain.coerceIn(MIN_GAIN, MAX_GAIN)

    /** Mean luma from a histogram, or null when the histogram carries no pixels. */
    private fun meanLuma(hist: IntArray): Float? {
        var total = 0L
        var weighted = 0L
        for (b in hist.indices) {
            total += hist[b]
            weighted += hist[b].toLong() * b
        }
        if (total == 0L) return null
        return (weighted.toFloat() / total.toFloat()) / 255f
    }
}

/**
 * Picks between the plain tier-0 pass (a) and the boosted retry (b) using eye-position
 * consistency: a gross displacement between passes means at least one pass produced
 * drifted landmarks, and the boosted (better-conditioned) pass wins. Sub-threshold
 * agreement keeps the plain pass — no reason to prefer the boosted one.
 *
 * When b has no mesh but a does, a wins regardless (mesh completeness outranks
 * conditioning — beauty pipeline degrades hard without mesh).
 */
object LandmarkConfidence {

    /** Above this eye-midpoint displacement (normalised units) the passes disagree. */
    const val DRIFT_THRESHOLD = 0.05f

    /** Mean Euclidean displacement between the two passes' eye midpoints. */
    fun eyeDisplacement(a: FaceLandmarkAnalyzer.EyeLandmarks, b: FaceLandmarkAnalyzer.EyeLandmarks): Float {
        val ax = (a.leftEyeX + a.rightEyeX) / 2f
        val ay = (a.leftEyeY + a.rightEyeY) / 2f
        val bx = (b.leftEyeX + b.rightEyeX) / 2f
        val by = (b.leftEyeY + b.rightEyeY) / 2f
        val dx = ax - bx
        val dy = ay - by
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    fun isConsistent(a: FaceLandmarkAnalyzer.EyeLandmarks, b: FaceLandmarkAnalyzer.EyeLandmarks): Boolean =
        eyeDisplacement(a, b) < DRIFT_THRESHOLD

    fun pick(
        a: FaceLandmarkAnalyzer.EyeLandmarks,
        b: FaceLandmarkAnalyzer.EyeLandmarks?,
        bPreprocessed: Boolean,
    ): FaceLandmarkAnalyzer.EyeLandmarks {
        if (b == null || !bPreprocessed) return a
        // mesh completeness outranks conditioning
        val aHasMesh = a.meshPoints != null && a.meshIndices != null
        val bHasMesh = b.meshPoints != null && b.meshIndices != null
        if (aHasMesh && !bHasMesh) return a
        // both suspect when they disagree → boosted pass is the better-conditioned one
        return if (isConsistent(a, b)) a else b
    }
}
