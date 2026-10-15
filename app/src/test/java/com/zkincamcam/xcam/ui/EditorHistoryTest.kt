package com.zkincamcam.xcam.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the editor undo/redo history.
 *
 * Model: the stack holds the timeline of snapshots; the LAST entry is the
 * current state. undo() moves back one and returns the state to restore;
 * redo() is its inverse. So with N snapshots you get N-1 undos. A gesture
 * bracketed by beginCoalesce/endCoalesce collapses to a single entry.
 */
class EditorHistoryTest {

    private fun snap(v: Float = 1f) = EditorSnapshot(zoom = v)

    @Test
    fun startsEmpty() {
        val h = EditorHistory()
        assertNull(h.undo())
        assertNull(h.redo())
        assertFalse(h.canUndo())
        assertFalse(h.canRedo())
    }

    @Test
    fun pushThenUndoWalksBackToStart() {
        val h = EditorHistory()
        h.push(snap(1f)); h.push(snap(2f))
        assertEquals(1f, h.undo()!!.zoom)
        assertNull(h.undo())
        assertFalse(h.canUndo())
    }

    @Test
    fun redoReplaysAfterUndo() {
        val h = EditorHistory()
        h.push(snap(1f)); h.push(snap(2f))
        h.undo()
        assertEquals(2f, h.redo()!!.zoom)
        assertNull(h.redo())
        assertFalse(h.canRedo())
    }

    @Test
    fun pushAfterUndoTruncatesRedoBranch() {
        val h = EditorHistory()
        h.push(snap(1f)); h.push(snap(2f)); h.push(snap(3f))
        h.undo() // back to 2f
        h.push(snap(9f))
        assertNull(h.redo())
        assertEquals(2f, h.undo()!!.zoom)
    }

    @Test
    fun coalescingDragMergesIntoOneEntry() {
        val h = EditorHistory()
        h.push(snap(1f))
        h.beginCoalesce()
        h.push(snap(1.5f))
        h.push(snap(2f))
        h.push(snap(2.5f))
        h.endCoalesce()
        // the whole drag is one step: undo lands on the pre-drag state
        assertEquals(1f, h.undo()!!.zoom)
        assertFalse(h.canUndo())
    }

    @Test
    fun boundedToMaxEntries() {
        val h = EditorHistory(maxEntries = 3)
        for (i in 1..10) h.push(snap(i.toFloat()))
        // oldest 7 evicted -> timeline holds 8,9,10
        assertEquals(9f, h.undo()!!.zoom)
        assertEquals(8f, h.undo()!!.zoom)
        assertFalse(h.canUndo())
    }

    @Test
    fun clearEmptiesEverything() {
        val h = EditorHistory()
        h.push(snap()); h.push(snap())
        h.clear()
        assertFalse(h.canUndo()); assertFalse(h.canRedo())
    }
}
