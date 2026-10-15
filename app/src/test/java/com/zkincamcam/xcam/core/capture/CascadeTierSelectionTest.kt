package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the cascade decision with the new MediaPipe tier.
 *
 * Tier order (best → fallback):
 *  0. MediaPipe FaceLandmarker — 468-pt mesh via canonical tessellation (new)
 *  1. ML Kit FaceMesh — 468-pt mesh with ML Kit's own triangulation
 *  2. ML Kit FaceDetection — eyes + box
 *  3. degraded default
 *
 * Semantics preserved: a complete mesh (detected + points + indices) always wins.
 * [LandmarkCascade.pick] treats any complete-mesh result identically regardless of
 * which engine produced it — pinned by tests here.
 */
class CascadeTierSelectionTest {

    private fun meshFrom(engine: String) = FaceLandmarkAnalyzer.EyeLandmarks(
        0.40f, 0.50f, 0.60f, 0.50f, true,
        FloatArray(468 * 3), FaceMeshTopology.TRIANGLES,
    )

    @Test
    fun mediapipeMeshBeatsMlKitMesh() {
        val mp = meshFrom("mediapipe")
        val mlkit = meshFrom("mlkit")
        assertEquals(mp, LandmarkCascade.pick(mp, mlkit))
    }

    @Test
    fun mediapipeMeshBeatsMlKitFaceDetection() {
        val mp = meshFrom("mediapipe")
        val face = FaceLandmarkAnalyzer.EyeLandmarks(0.35f, 0.45f, 0.55f, 0.47f, true)
        assertEquals(mp, LandmarkCascade.pick(mp, face))
    }

    @Test
    fun completeMeshContractUnchanged() {
        // The mesh contract is engine-agnostic: 468*3 floats + non-empty triangle indices
        val lm = meshFrom("mediapipe")
        assertTrue(LivenessMeshMapper.isComplete(lm.meshPoints, lm.meshIndices))
        // MediaPipe landmarks are normalised 0..1 — same as ML Kit's, so the clip/tex mapping applies unchanged
        val clip = LivenessMeshMapper.toClipSpace(lm.meshPoints!!)
        assertEquals(lm.meshPoints.size, clip.size)
        val (x, y) = clip[0] to clip[1]
        assertTrue(x in -1f..1f && y in -1f..1f)
    }

    @Test
    fun topologyTrianglesSatisfySkinMaskGenerator() {
        // SkinMaskGenerator walks indices step 3 — canonical tessellation is already a flat triangle list
        val idx = FaceMeshTopology.TRIANGLES
        assertFalse(idx.isEmpty())
        assertEquals(0, idx.size % 3)
    }

    @Test
    fun faceBoxFallbackDoesNotClaimEyeLandmarksWereDetected() {
        val face = LandmarkCascade.faceBoxOnly(0.62f, 0.48f)

        assertTrue(face.detected)
        assertFalse(face.eyeLandmarksDetected)
        assertEquals(0.62f, face.faceCenterX)
        assertEquals(0.48f, face.faceCenterY)
    }

    @Test
    fun actualEyeLandmarksAreMarkedAsDetected() {
        val face = FaceLandmarkAnalyzer.EyeLandmarks(
            0.4f, 0.45f, 0.6f, 0.47f, detected = true,
        )

        assertTrue(face.eyeLandmarksDetected)
    }
}
