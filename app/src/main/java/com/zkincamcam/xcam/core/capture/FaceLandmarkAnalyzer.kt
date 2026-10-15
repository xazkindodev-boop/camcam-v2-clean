package com.zkincamcam.xcam.core.capture

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Premium-Grade AI On-Device Computer Vision Landmark Extraction Engine.
 *
 * Tier 0 — MediaPipe Tasks FaceLandmarker: 468-pt 3D mesh via the bundled canonical
 *          tessellation ([FaceMeshTopology]); most accurate, GPU delegate.
 * Tier 1 — ML Kit FaceMesh: 468-pt mesh with ML Kit's own triangulation.
 * Tier 2 — ML Kit FaceDetection: coarse eyes + box fallback.
 * Tier 3 — degraded default via [LandmarkCascade].
 *
 * All tiers emit the same [EyeLandmarks] contract, so the pipelines
 * (LiveEditorRenderer, MediaConverter, SkinMaskGenerator) are engine-agnostic.
 */
object FaceLandmarkAnalyzer {

    private const val TAG = "FaceLandmarkAnalyzer"

    class EyeLandmarks(
        val leftEyeX: Float, val leftEyeY: Float,
        val rightEyeX: Float, val rightEyeY: Float,
        val detected: Boolean,
        val meshPoints: FloatArray? = null,
        val meshIndices: IntArray? = null,
        val faceCenterX: Float = 0.5f,
        val faceCenterY: Float = 0.5f,
        val faceRollDegrees: Float = 0f
    )

    /**
     * Tier 0: MediaPipe Tasks FaceLandmarker. 478 landmarks (468 canonical face mesh
     * + 4 iris points per eye) with the same normalised 0..1 convention ML Kit uses,
     * so [LivenessMeshMapper] applies unchanged. Triangulation comes from the bundled
     * canonical tessellation ([FaceMeshTopology]) since the task API does not expose
     * its own index list. Returns null (never throws) so the caller falls through to
     * the ML Kit tiers when the engine is unavailable or no face is found.
     */
    private fun analyzeWithMediaPipe(bitmap: Bitmap): EyeLandmarks? {
        val t0 = System.currentTimeMillis()
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()
            FaceLandmarker.createFromOptions(
                com.zkincamcam.xcam.XCamApplication.appContext,
                options
            ).use { landmarker ->
                val detect: (Bitmap) -> EyeLandmarks? = { bmp ->
                    detectWithLandmarker(landmarker, bmp, t0)
                }
                // Pass 1: as-is. Pass 2 (only when dark): brightness-boosted copy.
                val plain = detect(bitmap)
                val hist = lumaHistogram(bitmap)
                if (plain != null || hist == null || !LowLightGate.needsBoost(hist)) {
                    plain
                } else {
                    val gain = LowLightGate.gainFor(hist)
                    val boosted = brightenForDetection(bitmap, gain)
                    val retry = detect(boosted)
                    Log.d(TAG, "tier0 lowlight retry (gain=$gain): ${if (retry != null) "OK" else "no face"} in ${System.currentTimeMillis() - t0}ms")
                    LandmarkConfidence.pick(plain, retry, bPreprocessed = true)
                        .takeIf { it?.detected == true }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "tier0 mediapipe error: ${e.message} -> falling to tier1")
            null
        }
    }

    /** One detection pass over an existing landmarker instance; null when no usable face. */
    private fun detectWithLandmarker(
        landmarker: FaceLandmarker,
        bitmap: Bitmap,
        t0: Long,
    ): EyeLandmarks? {
        val result = landmarker.detect(BitmapImageBuilder(bitmap).build())
        val landmarks = result.faceLandmarks().firstOrNull()
        if (landmarks == null || landmarks.size < FaceMeshTopology.LANDMARK_COUNT) {
            Log.d(TAG, "tier0 mediapipe: no face (${landmarks?.size ?: 0} pts) in ${System.currentTimeMillis() - t0}ms")
            return null
        }
        val meshBuffer = FloatArray(FaceMeshTopology.LANDMARK_COUNT * 3)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until FaceMeshTopology.LANDMARK_COUNT) {
            val p = landmarks[i]
            meshBuffer[i * 3] = p.x()
            meshBuffer[i * 3 + 1] = p.y()
            meshBuffer[i * 3 + 2] = p.z()
            if (p.x() < minX) minX = p.x(); if (p.x() > maxX) maxX = p.x()
            if (p.y() < minY) minY = p.y(); if (p.y() > maxY) maxY = p.y()
        }
        // Left eye = subject's right in image space; ML Kit used indices 159/386 of
        // the canonical topology — identical landmark ordering here.
        val pLeft = landmarks[159]
        val pRight = landmarks[386]
        val faceRollDegrees = Math.toDegrees(
            atan2(
                (pRight.y() - pLeft.y()).toDouble(),
                (pRight.x() - pLeft.x()).toDouble()
            )
        ).toFloat().coerceIn(-30f, 30f)
        Log.d(TAG, "tier0 mediapipe: OK (${landmarks.size} pts) in ${System.currentTimeMillis() - t0}ms")
        return EyeLandmarks(
            pLeft.x(), pLeft.y(),
            pRight.x(), pRight.y(),
            detected = true,
            meshBuffer,
            FaceMeshTopology.TRIANGLES.copyOf(),
            (minX + maxX) / 2f,
            (minY + maxY) / 2f,
            faceRollDegrees
        )
    }

    /**
     * Cheap 256-bin luma histogram of a downscaled copy (64px) — used by
     * [LowLightGate] to decide if a brightness-boosted retry is worthwhile.
     * Returns null on any failure (gate then skips the retry).
     */
    private fun lumaHistogram(bitmap: Bitmap): IntArray? = try {
        val w = 64
        val h = Math.max(1, (bitmap.height * 64L / bitmap.width).toInt())
        val small = bitmap.scale(w, h)
        val pixels = try {
            IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        } finally {
            if (small !== bitmap) small.recycle()
        }
        val hist = IntArray(256)
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            // Rec. 601 luma
            hist[(0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)]++
        }
        hist
    } catch (e: Throwable) {
        Log.w(TAG, "luma probe failed: ${e.message}")
        null
    }

    /**
     * Detection-only brightness boost (multiplies RGB by [gain], clamped) on a
     * downscaled copy — the user-visible bitmap is never touched.
     */
    private fun brightenForDetection(bitmap: Bitmap, gain: Float): Bitmap = try {
        val boosted = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(boosted)
        canvas.drawColor(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.DST_OVER)
        val cm = android.graphics.ColorMatrix(
            floatArrayOf(
                gain, 0f, 0f, 0f, 0f,
                0f, gain, 0f, 0f, 0f,
                0f, 0f, gain, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(boosted, 0f, 0f, paint)
        boosted
    } catch (e: Throwable) {
        Log.w(TAG, "brighten failed: ${e.message} -> using original")
        bitmap
    }

    fun analyze(bitmap: Bitmap): EyeLandmarks {
        val originalResult = analyzeOnBitmap(bitmap)
        val originalHasMesh = originalResult.meshPoints != null && originalResult.meshIndices != null
        if (originalHasMesh || !isSoftForLandmarks(bitmap)) return originalResult

        val retryBitmap = createSharpenedDetectionBitmap(bitmap) ?: return originalResult
        return try {
            val retryResult = analyzeOnBitmap(retryBitmap)
            val selected = LandmarkCascade.preferMoreCompleteDetection(originalResult, retryResult)
            if (selected !== originalResult) {
                Log.d(TAG, "soft-image retry improved landmark detection")
            }
            selected
        } finally {
            if (retryBitmap !== bitmap) retryBitmap.recycle()
        }
    }

    private fun analyzeOnBitmap(bitmap: Bitmap): EyeLandmarks {
        analyzeWithMediaPipe(bitmap)?.let { return it }
        val options = FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
        val detector = FaceMeshDetection.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)

        return try {
            val t0 = System.currentTimeMillis()
            val meshTask = detector.process(image)
            val meshes = Tasks.await(meshTask)
            Log.d(TAG, "tier1 mesh: ${if (meshes != null && meshes.isNotEmpty()) "OK" else "no face"} in ${System.currentTimeMillis() - t0}ms")

            if (meshes != null && meshes.isNotEmpty()) {
                val mesh = meshes[0]

                val allPoints = mesh.allPoints
                val meshBuffer = FloatArray(468 * 3)
                val pointCount = 468.coerceAtMost(allPoints.size)
                for (i in 0 until pointCount) {
                    val p = allPoints[i]
                    meshBuffer[i * 3] = p.position.x / bitmap.width
                    meshBuffer[i * 3 + 1] = p.position.y / bitmap.height
                    meshBuffer[i * 3 + 2] = p.position.z / bitmap.width
                }
                // A partial mesh (fewer than 468 points) would leave zeroed vertices that collapse
                // the mesh towards the top-left corner in the export shader — fall back cleanly.
                if (pointCount < 468 || mesh.allTriangles.isEmpty()) {
                    Log.w(TAG, "tier1 mesh incomplete ($pointCount pts) -> falling to tier2")
                    return analyzeWithFaceDetection(bitmap)
                }
                val faceCenterX = (allPoints.minOf { it.position.x } + allPoints.maxOf { it.position.x }) / (2f * bitmap.width)
                val faceCenterY = (allPoints.minOf { it.position.y } + allPoints.maxOf { it.position.y }) / (2f * bitmap.height)

                val allTriangles = mesh.allTriangles
                val indicesBuffer = IntArray(allTriangles.size * 3)
                for (i in allTriangles.indices) {
                    val tri = allTriangles[i]
                    val points = tri.allPoints
                    indicesBuffer[i * 3] = points[0].index
                    indicesBuffer[i * 3 + 1] = points[1].index
                    indicesBuffer[i * 3 + 2] = points[2].index
                }

                val pLeft = allPoints[159].position
                val pRight = allPoints[386].position
                val faceRollDegrees = Math.toDegrees(
                    atan2(
                        (pRight.y - pLeft.y).toDouble(),
                        (pRight.x - pLeft.x).toDouble()
                    )
                ).toFloat().coerceIn(-30f, 30f)

                return EyeLandmarks(
                    pLeft.x / bitmap.width, pLeft.y / bitmap.height,
                    pRight.x / bitmap.width, pRight.y / bitmap.height,
                    true,
                    meshBuffer,
                    indicesBuffer,
                    faceCenterX,
                    faceCenterY,
                    faceRollDegrees
                )
            }
            analyzeWithFaceDetection(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "tier1 mesh error: ${e.message} -> falling to tier2")
            analyzeWithFaceDetection(bitmap)
        } finally {
            detector.close()
        }
    }

    /**
     * Tier 2: ML Kit FaceDetection — coarser than the mesh (contour eyes only,
     * no 468-point geometry) but much more tolerant of blur, hard shadows, and
     * eyes without catchlights. Supplies centre/roll/eyes so the transform,
     * auto-level, and beauty fallbacks still track the face.
     */
    private fun analyzeWithFaceDetection(bitmap: Bitmap): EyeLandmarks {
        val t0 = System.currentTimeMillis()
        return try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
            val faceDetector = FaceDetection.getClient(options)
            val faces = Tasks.await(faceDetector.process(InputImage.fromBitmap(bitmap, 0)))
            faceDetector.close()
            val face = faces?.firstOrNull()
                ?: run {
                    Log.w(TAG, "tier2 face-detect: no face in ${System.currentTimeMillis() - t0}ms -> tier3 degraded")
                    return LandmarkCascade.degraded()
                }
            Log.d(TAG, "tier2 face-detect: OK in ${System.currentTimeMillis() - t0}ms")
            val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val bounds = face.boundingBox
            val centerX = (bounds.left + bounds.right) / (2f * bitmap.width)
            val centerY = (bounds.top + bounds.bottom) / (2f * bitmap.height)
            if (leftEye != null && rightEye != null) {
                val roll = Math.toDegrees(
                    atan2(
                        (rightEye.y - leftEye.y).toDouble(),
                        (rightEye.x - leftEye.x).toDouble()
                    )
                ).toFloat().coerceIn(-30f, 30f)
                EyeLandmarks(
                    leftEye.x / bitmap.width, leftEye.y / bitmap.height,
                    rightEye.x / bitmap.width, rightEye.y / bitmap.height,
                    detected = true,
                    meshPoints = null,   // tier 2: no mesh — beauty falls back to radial gate
                    meshIndices = null,
                    faceCenterX = centerX,
                    faceCenterY = centerY,
                    faceRollDegrees = roll
                )
            } else {
                // Box only — centre is still better than the degraded guess.
                EyeLandmarks(
                    0.44f, 0.40f, 0.56f, 0.40f,
                    detected = true,
                    faceCenterX = centerX,
                    faceCenterY = centerY
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "tier2 face-detect error: ${e.message} -> tier3 degraded")
            LandmarkCascade.degraded()
        }
    }

    private fun isSoftForLandmarks(bitmap: Bitmap): Boolean = try {
        val maxDimension = 128
        val scale = maxDimension.toFloat() / max(bitmap.width, bitmap.height)
        val width = max(3, (bitmap.width * scale).roundToInt())
        val height = max(3, (bitmap.height * scale).roundToInt())
        val sample = bitmap.scale(width, height)
        try {
            val pixels = IntArray(width * height)
            sample.getPixels(pixels, 0, width, 0, 0, width, height)
            val grayscale = IntArray(pixels.size) { index ->
                val pixel = pixels[index]
                ((0.299f * ((pixel shr 16) and 0xFF)) +
                    (0.587f * ((pixel shr 8) and 0xFF)) +
                    (0.114f * (pixel and 0xFF))).roundToInt()
            }
            LandmarkImageQuality.needsSharpening(
                LandmarkImageQuality.meanLaplacianMagnitude(grayscale, width, height),
            )
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    } catch (e: Exception) {
        Log.w(TAG, "image sharpness probe failed: ${e.message}")
        false
    }

    private fun createSharpenedDetectionBitmap(bitmap: Bitmap): Bitmap? = try {
        val maxDimension = 1280
        val scale = minOf(1f, maxDimension.toFloat() / max(bitmap.width, bitmap.height))
        val width = max(3, (bitmap.width * scale).roundToInt())
        val height = max(3, (bitmap.height * scale).roundToInt())
        val resized = bitmap.scale(width, height)
        try {
            val pixels = IntArray(width * height)
            resized.getPixels(pixels, 0, width, 0, 0, width, height)
            val sharpenedPixels = MediaPreprocessor.sharpenArgbPixels(
                pixels,
                width,
                height,
                weight = 2.5f,
            )
            createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(sharpenedPixels, 0, width, 0, 0, width, height)
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    } catch (e: Exception) {
        Log.w(TAG, "soft-image preprocessing failed: ${e.message}")
        null
    }
}
