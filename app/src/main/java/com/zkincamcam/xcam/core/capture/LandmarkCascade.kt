package com.zkincamcam.xcam.core.capture

/**
 * Decision logic for the two-tier landmark cascade.
 *
 * Tier 1 — ML Kit FaceMesh: 468 points, precise, but picky — blurry photos,
 * hard shadows, or eyes without catchlights can make it fail.
 * Tier 2 — ML Kit FaceDetection: coarser (just eyes + bounding box), but far
 * more tolerant of blur and bad lighting.
 *
 * [pick] resolves which tier's result the pipeline uses; when both fail it
 * returns a deliberate degraded state — face centre placed where portraits
 * usually put a head (slightly above frame centre) so the transform and
 * beauty-mask fallbacks still land somewhere sensible instead of dead-centre.
 *
 * Pure logic, JVM-tested; FaceLandmarkAnalyzer does the actual ML calls.
 */
object LandmarkCascade {

    /** Deliberate degraded default: eyes assumed around 42% height, face width span. */
    fun degraded(): FaceLandmarkAnalyzer.EyeLandmarks =
        FaceLandmarkAnalyzer.EyeLandmarks(
            leftEyeX = 0.44f, leftEyeY = 0.40f,
            rightEyeX = 0.56f, rightEyeY = 0.40f,
            detected = false,
            meshPoints = null,
            meshIndices = null,
            faceCenterX = 0.5f,
            faceCenterY = 0.42f,
            faceRollDegrees = 0f,
            eyeLandmarksDetected = false
        )

    fun faceBoxOnly(
        faceCenterX: Float,
        faceCenterY: Float,
    ): FaceLandmarkAnalyzer.EyeLandmarks =
        FaceLandmarkAnalyzer.EyeLandmarks(
            leftEyeX = 0.44f,
            leftEyeY = 0.40f,
            rightEyeX = 0.56f,
            rightEyeY = 0.40f,
            detected = true,
            faceCenterX = faceCenterX,
            faceCenterY = faceCenterY,
            eyeLandmarksDetected = false,
        )

    /** Tier 1 (mesh) wins when detected; else tier 2; else [degraded]. */
    fun pick(mesh: FaceLandmarkAnalyzer.EyeLandmarks, faceDetection: FaceLandmarkAnalyzer.EyeLandmarks): FaceLandmarkAnalyzer.EyeLandmarks =
        when {
            mesh.detected && mesh.meshPoints != null && mesh.meshIndices != null -> mesh
            faceDetection.detected -> faceDetection
            else -> degraded()
        }

    fun preferMoreCompleteDetection(
        original: FaceLandmarkAnalyzer.EyeLandmarks,
        retry: FaceLandmarkAnalyzer.EyeLandmarks,
    ): FaceLandmarkAnalyzer.EyeLandmarks {
        val originalHasMesh = original.meshPoints != null && original.meshIndices != null
        val retryHasMesh = retry.meshPoints != null && retry.meshIndices != null
        return if (retry.detected && (!original.detected || (!originalHasMesh && retryHasMesh))) {
            retry
        } else {
            original
        }
    }
}
