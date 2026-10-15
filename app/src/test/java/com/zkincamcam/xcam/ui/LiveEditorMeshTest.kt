package com.zkincamcam.xcam.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LiveEditorMeshTest {
    @Test
    fun buildInterleavesAdjacentRowsForEachTriangleStrip() {
        val mesh = LiveEditorMesh.buildTriangleStrips(3)

        assertArrayEquals(
            floatArrayOf(
                -1f, -1f, 0f, -1f, 0f, 0f,
                0f, -1f, 0f, 0f, 0f, 0f,
                1f, -1f, 0f, 1f, 0f, 0f,
                -1f, 0f, 0f, -1f, 1f, 0f,
                0f, 0f, 0f, 0f, 1f, 0f,
                1f, 0f, 0f, 1f, 1f, 0f
            ),
            mesh.positions,
            0f
        )
        assertArrayEquals(
            floatArrayOf(
                0f, 1f, 0f, 0.5f,
                0.5f, 1f, 0.5f, 0.5f,
                1f, 1f, 1f, 0.5f,
                0f, 0.5f, 0f, 0f,
                0.5f, 0.5f, 0.5f, 0f,
                1f, 0.5f, 1f, 0f
            ),
            mesh.texCoords,
            0f
        )
    }

    @Test
    fun textureCoordinatesKeepBitmapTopAtScreenTop() {
        val mesh = LiveEditorMesh.buildTriangleStrips(2)

        assertArrayEquals(
            floatArrayOf(
                0f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
            ),
            mesh.texCoords,
            0f
        )
    }
}
