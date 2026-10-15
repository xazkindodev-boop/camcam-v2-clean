package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the low-light preprocessing gate that feeds MediaPipe tier 0.
 *
 * [LowLightGate] decides WHEN a bitmap needs luminance preprocessing before
 * landmark detection (blur + dark eyes case), using a cheap histogram probe.
 * Pure math on luminance histograms — no Android APIs, engine-agnostic.
 */
class LowLightGateTest {

    private fun histogramOf(meanLuma: Float, spread: Float = 0.1f): IntArray {
        // synthesise a 256-bin luma histogram centred on meanLuma (0..1)
        val hist = IntArray(256)
        val centre = (meanLuma * 255).toInt().coerceIn(0, 255)
        val width = (spread * 255).toInt().coerceIn(1, 128)
        for (b in 0 until 256) {
            val d = Math.abs(b - centre)
            if (d <= width) hist[b] = 100 - (d * 100 / width)
        }
        return hist
    }

    @Test
    fun darkImageNeedsBoost() {
        assertTrue(LowLightGate.needsBoost(histogramOf(meanLuma = 0.08f)))
    }

    @Test
    fun normalImageSkipsBoost() {
        assertFalse(LowLightGate.needsBoost(histogramOf(meanLuma = 0.5f)))
    }

    @Test
    fun brightImageSkipsBoost() {
        assertFalse(LowLightGate.needsBoost(histogramOf(meanLuma = 0.85f)))
    }

    @Test
    fun emptyHistogramIsSafeToSkip() {
        // no pixels / probe failure → never boost, fall through untouched
        assertFalse(LowLightGate.needsBoost(IntArray(256)))
    }

    @Test
    fun boostGainIsBounded() {
        // gain must be clamped — over-brightening invents landmarks noise
        assertEquals(2.5f, LowLightGate.clampGain(1.0f), 0.001f)   // min boost
        assertEquals(2.5f, LowLightGate.clampGain(2.5f), 0.001f)
        assertEquals(4.0f, LowLightGate.clampGain(6.0f), 0.001f)   // capped
    }

    @Test
    fun gainDerivedFromMeanLuma() {
        // very dark → stronger gain; moderately dark → gentler
        val veryDark = LowLightGate.gainFor(histogramOf(meanLuma = 0.05f))
        val mildlyDark = LowLightGate.gainFor(histogramOf(meanLuma = 0.25f))
        assertTrue(veryDark > mildlyDark)
        assertTrue(mildlyDark >= 1.0f)
    }

    @Test
    fun confidenceGatePrefersConsistentResult() {
        // two passes disagree wildly on eye position → both suspect, prefer boosted pass
        val a = FaceLandmarkAnalyzer.EyeLandmarks(0.40f, 0.50f, 0.60f, 0.50f, true, FloatArray(468 * 3), FaceMeshTopology.TRIANGLES.copyOf())
        val b = FaceLandmarkAnalyzer.EyeLandmarks(0.30f, 0.62f, 0.52f, 0.60f, true, FloatArray(468 * 3), FaceMeshTopology.TRIANGLES.copyOf())
        val picked = LandmarkConfidence.pick(a, b, bPreprocessed = true)
        assertEquals(b, picked)
    }

    @Test
    fun confidenceGateKeepsStablePass() {
        // two passes agree → first (unpreprocessed) wins; no reason to prefer boosted
        val a = FaceLandmarkAnalyzer.EyeLandmarks(0.40f, 0.50f, 0.60f, 0.50f, true, FloatArray(468 * 3), FaceMeshTopology.TRIANGLES.copyOf())
        val b = FaceLandmarkAnalyzer.EyeLandmarks(0.402f, 0.501f, 0.601f, 0.499f, true, FloatArray(468 * 3), FaceMeshTopology.TRIANGLES.copyOf())
        val picked = LandmarkConfidence.pick(a, b, bPreprocessed = true)
        assertEquals(a, picked)
    }

    @Test
    fun nullSecondPassFallsThroughToFirst() {
        val a = FaceLandmarkAnalyzer.EyeLandmarks(0.40f, 0.50f, 0.60f, 0.50f, true, FloatArray(468 * 3), FaceMeshTopology.TRIANGLES.copyOf())
        assertEquals(a, LandmarkConfidence.pick(a, null, bPreprocessed = true))
    }

    @Test
    fun disagreementMetricSane() {
        // eye midpoint displacement metric: same points → 0
        val a = FaceLandmarkAnalyzer.EyeLandmarks(0.40f, 0.50f, 0.60f, 0.50f, true)
        val b = FaceLandmarkAnalyzer.EyeLandmarks(0.40f, 0.50f, 0.60f, 0.50f, true)
        assertEquals(0f, LandmarkConfidence.eyeDisplacement(a, b), 0.0001f)
        // shifted one eye by 0.1 → displacement 0.05 (midpoint moves half)
        val c = FaceLandmarkAnalyzer.EyeLandmarks(0.50f, 0.50f, 0.60f, 0.50f, true)
        assertEquals(0.05f, LandmarkConfidence.eyeDisplacement(a, c), 0.001f)
    }

    @Test
    fun thresholdClassifiesDrift() {
        // sub-pixel drift passes, gross drift fails
        val tiny = FaceLandmarkAnalyzer.EyeLandmarks(0.402f, 0.5f, 0.598f, 0.5f, true)
        val gross = FaceLandmarkAnalyzer.EyeLandmarks(0.30f, 0.60f, 0.50f, 0.60f, true)
        val base = FaceLandmarkAnalyzer.EyeLandmarks(0.40f, 0.5f, 0.60f, 0.5f, true)
        assertTrue(LandmarkConfidence.isConsistent(base, tiny))
        assertFalse(LandmarkConfidence.isConsistent(base, gross))
    }

    @Test
    fun missingMeshNeverPreferred() {
        // boosted pass without mesh must not beat an unboosted pass WITH mesh
        val withMesh = FaceLandmarkAnalyzer.EyeLandmarks(0.40f, 0.50f, 0.60f, 0.50f, true, FloatArray(468 * 3), FaceMeshTopology.TRIANGLES.copyOf())
        val noMesh = FaceLandmarkAnalyzer.EyeLandmarks(0.40f, 0.50f, 0.60f, 0.50f, true)
        assertEquals(withMesh, LandmarkConfidence.pick(withMesh, noMesh, bPreprocessed = true))
    }
}
