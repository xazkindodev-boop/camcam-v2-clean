package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the skin mask generator: rasterizes the 468-point face mesh
 * into a low-res alpha mask marking skin pixels. The mask is what keeps the
 * beauty pass ON the face and OFF the eyes/background (the anti-plastic pass).
 */
class SkinMaskGeneratorTest {

    private fun fullMesh(): Pair<FloatArray, IntArray> {
        // Two triangles covering a quad in normalised top-down space (0.25..0.75).
        val pts = FloatArray(468 * 3)
        val quad = listOf(
            0.25f to 0.25f, 0.75f to 0.25f,
            0.25f to 0.75f, 0.75f to 0.75f,
        )
        for (i in 0 until 468) {
            val (x, y) = quad[i % 4]
            pts[i * 3] = x; pts[i * 3 + 1] = y
        }
        val idx = intArrayOf(0, 1, 2, 1, 3, 2)
        return pts to idx
    }

    @Test
    fun insideMeshIsMasked() {
        val (pts, idx) = fullMesh()
        val mask = SkinMaskGenerator.generate(pts, idx, 64, 64)
        val at = { x: Int, y: Int -> mask[y * 64 + x].toInt() and 0xFF }
        assertTrue(at(32, 32) > 200) // centre
    }

    @Test
    fun outsideMeshIsClear() {
        val (pts, idx) = fullMesh()
        val mask = SkinMaskGenerator.generate(pts, idx, 64, 64)
        val at = { x: Int, y: Int -> mask[y * 64 + x].toInt() and 0xFF }
        assertEquals(0, at(2, 2))
        assertEquals(0, at(61, 61))
    }

    @Test
    fun featherBlendsEdge() {
        val (pts, idx) = fullMesh()
        val mask = SkinMaskGenerator.generate(pts, idx, 64, 64, featherPx = 2f)
        val at = { x: Int, y: Int -> mask[y * 64 + x].toInt() and 0xFF }
        val edge = at(20, 20) // just inside the left edge (0.25*64=16)
        val centre = at(32, 32)
        assertTrue(edge in 1..centre) // partial, not full, not zero
    }

    @Test
    fun incompleteMeshYieldsEmptyMask() {
        val mask = SkinMaskGenerator.generate(FloatArray(100), intArrayOf(0, 1, 2), 64, 64)
        assertTrue(mask.all { (it.toInt() and 0xFF) == 0 })
    }

    @Test
    fun eyesAreExcluded() {
        // Mesh quad + two eye exclusion circles at (0.4,0.5) and (0.6,0.5).
        val (pts, idx) = fullMesh()
        val eyes = listOf(0.4f to 0.5f, 0.6f to 0.5f)
        val mask = SkinMaskGenerator.generate(pts, idx, 64, 64, exclusionPoints = eyes, exclusionRadius = 0.05f)
        val at = { x: Int, y: Int -> mask[y * 64 + x].toInt() and 0xFF }
        val ex = (0.4f * 64).toInt(); val ey = (0.5f * 64).toInt()
        val inside = at(32, 32)          // cheek — still masked
        val eyeCenter = at(ex, ey)       // eye — excluded
        assertTrue(inside > 200)
        assertTrue(eyeCenter < inside / 4)
    }

    @Test
    fun outputIsExactlyWidthTimesHeight() {
        val (pts, idx) = fullMesh()
        val mask = SkinMaskGenerator.generate(pts, idx, 48, 32)
        assertEquals(48 * 32, mask.size)
    }
}
