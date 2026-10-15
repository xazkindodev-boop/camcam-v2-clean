package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivenessMeshMapperTest {

    @Test
    fun mapsNormalisedLandmarksToClipSpace() {
        val mesh = floatArrayOf(
            0f, 0f, 0f,   // top-left
            1f, 1f, 0f,   // bottom-right
            0.5f, 0.5f, 0f,
        )

        assertArrayEquals(
            floatArrayOf(-1f, -1f, 0f, 1f, 1f, 0f, 0f, 0f, 0f),
            LivenessMeshMapper.toClipSpace(mesh),
            0.0001f,
        )
    }

    @Test
    fun flipsTextureCoordinateYOnly() {
        val mesh = floatArrayOf(0.25f, 0.2f, 0f, 0.75f, 0.8f, 0f)

        assertArrayEquals(
            floatArrayOf(0.25f, 0.8f, 0.75f, 0.2f),
            LivenessMeshMapper.toTexCoords(mesh),
            0.0001f,
        )
    }

    @Test
    fun eyeUniformSharesTheTextureCoordinateSpace() {
        val mesh = floatArrayOf(0.43f, 0.52f, 0f)
        val uvs = LivenessMeshMapper.toTexCoords(mesh)

        assertEquals(uvs[1], LivenessMeshMapper.toUniform(0.52f), 0.0001f)
    }

    @Test
    fun rejectsPartialOrMalformedMeshes() {
        assertFalse(LivenessMeshMapper.isComplete(null, intArrayOf(0, 1, 2)))
        assertFalse(LivenessMeshMapper.isComplete(FloatArray(468 * 3), null))
        // Only 10 landmarks -> incomplete, must not be used as a full face mesh.
        assertFalse(LivenessMeshMapper.isComplete(FloatArray(10 * 3), intArrayOf(0, 1, 2)))
        assertFalse(LivenessMeshMapper.isComplete(FloatArray(468 * 3), intArrayOf(0, 1)))
        assertTrue(LivenessMeshMapper.isComplete(FloatArray(468 * 3), intArrayOf(0, 1, 2)))
    }
}
