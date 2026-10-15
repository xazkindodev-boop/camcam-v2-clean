package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the bundled canonical face-mesh topology used by the
 * MediaPipe tier so its results satisfy the same mesh contract as ML Kit.
 */
class FaceMeshTopologyTest {

    @Test
    fun countsMatchCanonicalModel() {
        assertEquals(468, FaceMeshTopology.LANDMARK_COUNT)
        assertEquals(898, FaceMeshTopology.TRIANGLE_COUNT)
        assertEquals(898 * 3, FaceMeshTopology.TRIANGLES.size)
    }

    @Test
    fun allIndicesInLandmarkRange() {
        FaceMeshTopology.TRIANGLES.forEach { i ->
            assertTrue("index $i out of range", i in 0 until FaceMeshTopology.LANDMARK_COUNT)
        }
    }

    @Test
    fun flatListIsTriangleGroups() {
        assertEquals(0, FaceMeshTopology.TRIANGLES.size % 3)
        // every triangle's indices are distinct (degenerate triangles break the rasteriser)
        for (t in FaceMeshTopology.TRIANGLES.indices step 3) {
            val (a, b, c) = FaceMeshTopology.TRIANGLES.slice(t until t + 3)
            assertTrue("degenerate triangle at $t: $a,$b,$c", a != b && b != c && a != c)
        }
    }

    @Test
    fun everyLandmarkReferencedAtLeastOnce() {
        val used = FaceMeshTopology.TRIANGLES.toHashSet()
        assertEquals(FaceMeshTopology.LANDMARK_COUNT, used.size)
    }
}
