package com.zkincamcam.xcam.ui

/**
 * Immutable snapshot of every user-tunable editor value at one point in time.
 * Pure data — no Android classes — so history logic is JVM-testable.
 */
data class EditorSnapshot(
    val zoom: Float = 1f,
    val fineRotation: Float = 0f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 1f,
    val sharpness: Float = 0f,
    val beauty: Float = 0f,
    val noise: Float = 0.5f,
    val pulse: Float = 0.5f,
    val catchlight: Float = 1f,
    val hd: Float = 0f,
    val eyeMovement: Boolean = false,
    val mirrored: Boolean = false,
    val rotationAngle: Int = 0,
    val catchlightTouched: Boolean = false,
    val beautyPresetId: String = "studio",
    val lutUri: String? = null,
    val cropFraction: CropFraction? = null,
    val trimStartFraction: Float? = null,
    val trimEndFraction: Float? = null,
)

/**
 * Bounded undo/redo history of [EditorSnapshot]s.
 *
 * The stack stores snapshots in chronological order; `past.last()` is the
 * CURRENT state. undo() pops the current state onto the redo stack and
 * returns the previous one; redo() is the inverse. Coalescing ([beginCoalesce]/
 * [endCoalesce]) replaces the tip instead of appending, so a whole gesture
 * collapses into one entry. Pushing after an undo truncates the redo branch.
 * Capacity-bounded (oldest evicted).
 */
class EditorHistory(private val maxEntries: Int = 50) {
    private val past = ArrayDeque<EditorSnapshot>()
    private val future = ArrayDeque<EditorSnapshot>() // ordered oldest-first of undone states
    private var coalescing = false

    fun canUndo(): Boolean = past.size >= 2
    fun canRedo(): Boolean = future.isNotEmpty()

    /** Records [snapshot] as the newest state; drops any redo branch. */
    fun push(snapshot: EditorSnapshot) {
        if (coalescing && past.isNotEmpty()) {
            // A gesture merges into its tip: keep the pre-gesture state, replace
            // the gesture's tip with the latest frame. The FIRST push of a gesture
            // must still create the tip (compare identity of the current tip).
            if (gestureTipPushed) {
                past.removeLast()
            } else {
                gestureTipPushed = true
            }
        }
        past.addLast(snapshot)
        future.clear()
        while (past.size > maxEntries) past.removeFirst()
    }

    private var gestureTipPushed = false

    fun beginCoalesce() {
        coalescing = true
        gestureTipPushed = false
    }

    fun endCoalesce() {
        coalescing = false
        gestureTipPushed = false
    }

    /** Returns the state to restore one step back, or null if none. */
    fun undo(): EditorSnapshot? {
        if (past.size < 2) return null
        val current = past.removeLast()
        future.addLast(current)
        return past.last()
    }

    /** Returns the state to restore one step forward, or null if none. */
    fun redo(): EditorSnapshot? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(next)
        return next
    }

    fun clear() {
        past.clear(); future.clear(); coalescing = false; gestureTipPushed = false
    }
}
