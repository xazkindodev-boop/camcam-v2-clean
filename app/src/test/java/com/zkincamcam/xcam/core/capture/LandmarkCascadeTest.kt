package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the landmark cascade decision logic: which tier's data wins,
 * and what a graceful degraded result looks like when nothing detects.
 */
class LandmarkCascadeTest {

    @Test
    fun meshWinsWhenComplete() {
        val mesh = FaceLandmarkAnalyzer.EyeLandmarks(0.4f, 0.5f, 0.6f, 0.5f, true, FloatArray(468 * 3), intArrayOf(0, 1, 2))
        val face = FaceLandmarkAnalyzer.EyeLandmarks(0.45f, 0.5f, 0.55f, 0.5f, true)
        val picked = LandmarkCascade.pick(mesh, face)
        assertEquals(mesh, picked)
    }

    @Test
    fun faceDetectionFillsWhenMeshIncomplete() {
        val mesh = FaceLandmarkAnalyzer.EyeLandmarks(0.43f, 0.52f, 0.57f, 0.51f, false)
        val face = FaceLandmarkAnalyzer.EyeLandmarks(0.35f, 0.45f, 0.55f, 0.47f, true)
        val picked = LandmarkCascade.pick(mesh, face)
        assertEquals(face, picked)
    }

    @Test
    fun neverPicksUndetectedTier() {
        val mesh = FaceLandmarkAnalyzer.EyeLandmarks(0.43f, 0.52f, 0.57f, 0.51f, false)
        val face = FaceLandmarkAnalyzer.EyeLandmarks(0.43f, 0.52f, 0.57f, 0.51f, false)
        val picked = LandmarkCascade.pick(mesh, face)
        assertFalse(picked.detected)
        // degraded centre: slightly above frame middle (heads sit there in portraits)
        assertEquals(0.5f, picked.faceCenterX, 0.01f)
        assertEquals(0.42f, picked.faceCenterY, 0.05f)
    }

    @Test
    fun degradedResultMarksMeshAbsent() {
        val picked = LandmarkCascade.pick(
            FaceLandmarkAnalyzer.EyeLandmarks(0.43f, 0.52f, 0.57f, 0.51f, false),
            FaceLandmarkAnalyzer.EyeLandmarks(0.43f, 0.52f, 0.57f, 0.51f, false),
        )
        assertNull(picked.meshPoints)
        assertNull(picked.meshIndices)
    }

    @Test
    fun softImageRetryCanRecoverMissedFace() {
        val original = LandmarkCascade.degraded()
        val retry = FaceLandmarkAnalyzer.EyeLandmarks(0.4f, 0.5f, 0.6f, 0.5f, true)

        assertEquals(retry, LandmarkCascade.preferMoreCompleteDetection(original, retry))
    }

    @Test
    fun softImageRetryPrefersMeshOverCoarseFaceDetection() {
        val original = FaceLandmarkAnalyzer.EyeLandmarks(0.4f, 0.5f, 0.6f, 0.5f, true)
        val retry = FaceLandmarkAnalyzer.EyeLandmarks(
            0.4f,
            0.5f,
            0.6f,
            0.5f,
            true,
            FloatArray(468 * 3),
            intArrayOf(0, 1, 2),
        )

        assertEquals(retry, LandmarkCascade.preferMoreCompleteDetection(original, retry))
    }

    @Test
    fun softImageRetryKeepsExistingDetectionWhenRetryIsNotBetter() {
        val original = FaceLandmarkAnalyzer.EyeLandmarks(0.4f, 0.5f, 0.6f, 0.5f, true)
        val retry = FaceLandmarkAnalyzer.EyeLandmarks(0.42f, 0.5f, 0.58f, 0.5f, true)

        assertEquals(original, LandmarkCascade.preferMoreCompleteDetection(original, retry))
    }

    private fun assertNull(x: FloatArray?) { org.junit.Assert.assertNull(x) }
    private fun assertNull(x: IntArray?) { org.junit.Assert.assertNull(x) }
}
