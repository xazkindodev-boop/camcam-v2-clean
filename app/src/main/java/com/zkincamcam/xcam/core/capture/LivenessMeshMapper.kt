package com.zkincamcam.xcam.core.capture

/**
 * Pure coordinate mapping for the exported liveness mesh.
 *
 * ML Kit returns 468 landmarks normalised top-down with (0,0) at the image's top-left corner.
 * The preview renderer ([com.zkincamcam.xcam.ui.LiveEditorMesh]) and this export pipeline must agree
 * on one convention, otherwise the blink / catchlight deformations land on the mirrored face
 * feature while the colour work still looks fine — which is exactly what used to happen.
 *
 * Convention (identical to the preview):
 *  * GL clip position: x = nx * 2 - 1, y = ny * 2 - 1  (normalised y is top-down, so y = -1 is top)
 *  * texture coordinate: (nx, 1 - ny)  — Android bitmap textures have v = 0 at the bottom, so a
 *    top-down normalised coordinate must be flipped before sampling.
 *  * eye uniforms are passed as (x, 1 - y) for the same reason.
 *
 * Kept free of Android APIs so the mapping is covered by JVM unit tests.
 */
internal object LivenessMeshMapper {

    /** Every landmark is 3 floats (x, y, z), all normalised by the source bitmap size. */
    const val LANDMARK_STRIDE = 3
    const val LANDMARK_COUNT = 468

    fun isComplete(meshPoints: FloatArray?, meshIndices: IntArray?): Boolean =
        meshPoints != null && meshIndices != null &&
            meshPoints.size >= LANDMARK_COUNT * LANDMARK_STRIDE &&
            meshIndices.isNotEmpty() && meshIndices.size % 3 == 0

    /** Normalised top-down landmark positions -> GL clip space. */
    fun toClipSpace(meshPoints: FloatArray): FloatArray {
        val vertexCount = meshPoints.size / LANDMARK_STRIDE
        val clip = FloatArray(vertexCount * LANDMARK_STRIDE)
        for (i in 0 until vertexCount) {
            val base = i * LANDMARK_STRIDE
            clip[base] = meshPoints[base] * 2f - 1f
            clip[base + 1] = meshPoints[base + 1] * 2f - 1f
            clip[base + 2] = meshPoints[base + 2]
        }
        return clip
    }

    /** Normalised top-down landmark positions -> texture coordinates with v flipped. */
    fun toTexCoords(meshPoints: FloatArray): FloatArray {
        val vertexCount = meshPoints.size / LANDMARK_STRIDE
        val uvs = FloatArray(vertexCount * 2)
        for (i in 0 until vertexCount) {
            uvs[i * 2] = meshPoints[i * LANDMARK_STRIDE]
            uvs[i * 2 + 1] = 1f - meshPoints[i * LANDMARK_STRIDE + 1]
        }
        return uvs
    }

    /** Eye uniform takes the same flipped space as the texture coordinates. */
    fun toUniform(normalisedY: Float): Float = 1f - normalisedY
}
