package com.zkincamcam.xcam.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the crop rectangle state: pure geometry (no Android) —
 * clamping to bounds, minimum size, aspect lock, and hit-testing handles.
 * Coordinates are in VIEW space (pixels of the overlay view).
 */
class CropRectStateTest {

    /** 1000x1000 view, 100px min, no aspect lock by default. */
    private fun state(bounds: FloatArray = floatArrayOf(0f, 0f, 1000f, 1000f), min: Float = 100f, aspect: Float? = null) =
        CropRectState(bounds, min, aspect)

    @Test
    fun initialRectIsFullBounds() {
        val s = state()
        assertEquals(0f, s.left); assertEquals(0f, s.top)
        assertEquals(1000f, s.right); assertEquals(1000f, s.bottom)
    }

    @Test
    fun dragCornerMovesThatCorner() {
        val s = state()
        s.beginDrag(CropRectState.Handle.BOTTOM_RIGHT, 900f, 900f)
        s.dragTo(850f, 870f)
        assertEquals(850f, s.right)
        assertEquals(870f, s.bottom)
        assertEquals(0f, s.left) // other corners untouched
    }

    @Test
    fun dragClampsToBounds() {
        val s = state()
        s.beginDrag(CropRectState.Handle.BOTTOM_RIGHT, 900f, 900f)
        s.dragTo(1200f, 950f)
        assertEquals(1000f, s.right) // clamped at bound
        // min-size wins when dragged far past the anchor
        s.beginDrag(CropRectState.Handle.TOP_RIGHT, 900f, 100f)
        s.dragTo(1200f, 5000f)
        assertEquals(1000f, s.right)
        assertEquals(100f, s.bottom - s.top) // min height preserved
    }

    @Test
    fun dragEnforcesMinimumSize() {
        val s = state()
        s.beginDrag(CropRectState.Handle.BOTTOM_RIGHT, 900f, 900f)
        s.dragTo(10f, 10f) // would make 10px wide — clamped to min
        assertEquals(100f, s.right)
    }

    @Test
    fun aspectLockKeepsRatio() {
        // 1:1 on a 1000x800 view: initial rect centers to 800x800
        val s = state(bounds = floatArrayOf(0f, 0f, 1000f, 800f), aspect = 1f)
        assertEquals(800f, s.right - s.left, 0.01f)
        assertEquals(800f, s.bottom - s.top, 0.01f)
        // corner drag keeps the square
        s.beginDrag(CropRectState.Handle.BOTTOM_RIGHT, s.right, s.bottom)
        s.dragTo(500f, 500f)
        assertEquals(500f, s.right - s.left, 1f)
        assertEquals(500f, s.bottom - s.top, 1f)
    }

    @Test
    fun aspectLockClampsWithinBounds() {
        // 9:16 portrait on a wide view — height drives, rect must fit
        val s = state(bounds = floatArrayOf(0f, 0f, 1000f, 800f), aspect = 9f / 16f)
        val w = s.right - s.left; val h = s.bottom - s.top
        assertEquals(9f / 16f, w / h, 0.01f)
        assertTrue(s.left >= 0f && s.right <= 1000f && s.top >= 0f && s.bottom <= 800f)
    }

    @Test
    fun minSizeRespectsAspect() {
        // min 100 with 9:16 -> min height = 100/(9/16) = 177.8
        val s = state(min = 100f, aspect = 9f / 16f)
        s.beginDrag(CropRectState.Handle.BOTTOM_RIGHT, s.right, s.bottom)
        s.dragTo(5f, 5f)
        val w = s.right - s.left
        val h = s.bottom - s.top
        assertEquals(9f / 16f, w / h, 0.02f)
        assertTrue(w >= 99f) // ~min width preserved
    }

    @Test
    fun hitTestDetectsCornerHandles() {
        val s = state()
        assertEquals(CropRectState.Handle.TOP_LEFT, s.handleAt(10f, 10f))
        assertEquals(CropRectState.Handle.BOTTOM_RIGHT, s.handleAt(990f, 990f))
        assertEquals(CropRectState.Handle.TOP_RIGHT, s.handleAt(990f, 10f))
        assertEquals(CropRectState.Handle.BOTTOM_LEFT, s.handleAt(10f, 990f))
    }

    @Test
    fun hitTestOutsideHandlesReturnsNull() {
        val s = state()
        assertNull(s.handleAt(500f, 500f))
        assertNull(s.handleAt(-10f, -10f))
    }

    @Test
    fun dragEdgeMovesOnlyThatEdge() {
        val s = state()
        s.beginDrag(CropRectState.Edge.LEFT, s.left, 500f)
        s.dragTo(200f, 500f)
        assertEquals(200f, s.left)
        assertEquals(1000f, s.right)
    }

    @Test
    fun dragInsideRectMovesWholeRect() {
        val s = state()
        // shrink first so there is room to move
        s.beginDrag(CropRectState.Handle.BOTTOM_RIGHT, 1000f, 1000f)
        s.dragTo(700f, 700f)
        s.beginDrag(CropRectState.MoveHandle, 600f, 600f)
        s.dragTo(650f, 620f)
        assertEquals(700f, s.right - s.left, 0.01f)
        assertEquals(700f, s.bottom - s.top, 0.01f)
        assertEquals(50f, s.left, 0.01f)
        assertEquals(20f, s.top, 0.01f)
    }

    @Test
    fun moveStaysWithinBounds() {
        val s = state()
        s.beginDrag(CropRectState.Handle.BOTTOM_RIGHT, 1000f, 1000f)
        s.dragTo(700f, 700f) // rect now 0..700
        s.beginDrag(CropRectState.MoveHandle, 600f, 600f)
        s.dragTo(900f, 900f) // would push right edge to 1200
        assertEquals(1000f, s.right, 0.01f) // clamped at bound
        assertEquals(1000f, s.bottom, 0.01f)
        assertEquals(300f, s.left, 0.01f)  // moved as a whole
        assertEquals(300f, s.top, 0.01f)
    }

    @Test
    fun moveKeepsSize() {
        val s = state()
        val w0 = s.right - s.left; val h0 = s.bottom - s.top
        s.beginDrag(CropRectState.MoveHandle, 500f, 500f)
        s.dragTo(550f, 520f)
        assertEquals(w0, s.right - s.left, 0.01f)
        assertEquals(h0, s.bottom - s.top, 0.01f)
    }

    @Test
    fun fractionalCropReadsCorrectly() {
        val s = state()
        s.beginDrag(CropRectState.Edge.LEFT, s.left, 500f)
        s.dragTo(250f, 500f)
        val frac = s.fractional()
        assertEquals(0.25f, frac.left, 0.001f)
        assertEquals(1f, frac.right, 0.001f)
        assertFalse(frac.isFullFrame)
    }

    @Test
    fun fullFrameIsNoOp() {
        val s = state()
        assertTrue(s.fractional().isFullFrame)
    }
}
