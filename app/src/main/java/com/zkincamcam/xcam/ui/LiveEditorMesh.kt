package com.zkincamcam.xcam.ui

internal object LiveEditorMesh {
    data class TriangleStrips(
        val positions: FloatArray,
        val texCoords: FloatArray
    )

    fun buildTriangleStrips(resolution: Int): TriangleStrips {
        require(resolution >= 2)

        val vertexCount = (resolution - 1) * resolution * 2
        val positions = FloatArray(vertexCount * 3)
        val texCoords = FloatArray(vertexCount * 2)
        var positionIndex = 0
        var texCoordIndex = 0

        for (row in 0 until resolution - 1) {
            for (column in 0 until resolution) {
                for (y in row..row + 1) {
                    val xFraction = column.toFloat() / (resolution - 1)
                    val yFraction = y.toFloat() / (resolution - 1)
                    positions[positionIndex++] = xFraction * 2f - 1f
                    positions[positionIndex++] = yFraction * 2f - 1f
                    positions[positionIndex++] = 0f
                    texCoords[texCoordIndex++] = xFraction
                    texCoords[texCoordIndex++] = 1f - yFraction
                }
            }
        }

        return TriangleStrips(positions, texCoords)
    }
}
