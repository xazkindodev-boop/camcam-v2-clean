package com.zkincamcam.xcam.ui

/** Fractions of the source frame selected by the crop rect (0..1). */
data class CropFraction(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val isFullFrame: Boolean
        get() = left <= 1e-4f && top <= 1e-4f && right >= 0.9999f && bottom >= 0.9999f
}

/**
 * Pure geometry for the crop overlay: a rectangle in VIEW space that the user
 * can drag by its four corners, four edges, or as a whole. No Android imports
 * so the clamp / minimum-size / aspect-lock rules are JVM-tested.
 *
 * The overlay view owns one instance and feeds touch events into [beginDrag] /
 * [dragTo]; the editor reads [fractional] to turn the rect into source-frame
 * crop fractions for the render pipeline.
 */
class CropRectState(
    bounds: FloatArray,
    private val minSize: Float,
    private val aspect: Float? = null,
    initialRect: FloatArray? = null,
) {
    sealed interface HandleKind
    enum class Handle : HandleKind { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    enum class Edge : HandleKind { LEFT, RIGHT, TOP, BOTTOM }
    object MoveHandle : HandleKind

    private val bx0 = bounds[0]
    private val by0 = bounds[1]
    private val bx1 = bounds[2]
    private val by1 = bounds[3]

    var left = bx0; private set
    var top = by0; private set
    var right = bx1; private set
    var bottom = by1; private set

    private var dragKind: HandleKind? = null
    private var dragStartX = 0f
    private var dragStartY = 0f

    init {
        if (initialRect != null) {
            left = initialRect[0]; top = initialRect[1]
            right = initialRect[2]; bottom = initialRect[3]
        } else if (aspect != null) {
            centerAspectRect()
        }
    }

    /** Largest rect of [aspect] centred in bounds (used on open for locked presets). */
    private fun centerAspectRect() {
        val a = aspect ?: return
        val bw = bx1 - bx0
        val bh = by1 - by0
        var w = bw
        var h = w / a
        if (h > bh) { h = bh; w = h * a }
        val cx = (bx0 + bx1) / 2f
        val cy = (by0 + by1) / 2f
        left = cx - w / 2f; right = cx + w / 2f
        top = cy - h / 2f; bottom = cy + h / 2f
    }

    fun beginDrag(kind: HandleKind, x: Float, y: Float) {
        dragKind = kind; dragStartX = x; dragStartY = y
    }

    fun dragTo(x: Float, y: Float) {
        when (val k = dragKind ?: return) {
            is Handle -> dragCorner(k, x, y)
            is Edge -> dragEdge(k, x, y)
            MoveHandle -> dragMove(x, y)
        }
    }

    private fun dragCorner(k: Handle, x: Float, y: Float) {
        val anchorX: Float; val anchorY: Float; val sx: Float; val sy: Float
        when (k) {
            Handle.TOP_LEFT -> { anchorX = right; anchorY = bottom; sx = -1f; sy = -1f }
            Handle.TOP_RIGHT -> { anchorX = left; anchorY = bottom; sx = 1f; sy = -1f }
            Handle.BOTTOM_LEFT -> { anchorX = right; anchorY = top; sx = -1f; sy = 1f }
            Handle.BOTTOM_RIGHT -> { anchorX = left; anchorY = top; sx = 1f; sy = 1f }
        }
        var w = (x - anchorX) * sx
        var h = (y - anchorY) * sy
        val maxW = if (sx > 0) bx1 - anchorX else anchorX - bx0
        val maxH = if (sy > 0) by1 - anchorY else anchorY - by0
        if (aspect != null) {
            w = maxOf(w, h * aspect)   // dominant axis wins, keeps ratio
            val minW = maxOf(minSize, minSize * aspect)
            val maxWa = minOf(maxW, maxH * aspect)
            w = w.coerceIn(minW, maxWa)
            h = w / aspect
        } else {
            w = w.coerceIn(minSize, maxW)
            h = h.coerceIn(minSize, maxH)
        }
        if (sx > 0) right = anchorX + w else left = anchorX - w
        if (sy > 0) bottom = anchorY + h else top = anchorY - h
    }

    private fun dragEdge(e: Edge, x: Float, y: Float) {
        when (e) {
            Edge.LEFT -> left = x.coerceIn(bx0, right - minSize)
            Edge.RIGHT -> right = x.coerceIn(left + minSize, bx1)
            Edge.TOP -> top = y.coerceIn(by0, bottom - minSize)
            Edge.BOTTOM -> bottom = y.coerceIn(top + minSize, by1)
        }
    }

    private fun dragMove(x: Float, y: Float) {
        val w = right - left
        val h = bottom - top
        val nl = (left + (x - dragStartX)).coerceIn(bx0, bx1 - w)
        val nt = (top + (y - dragStartY)).coerceIn(by0, by1 - h)
        left = nl; top = nt; right = nl + w; bottom = nt + h
    }

    /** Returns the corner handle under (x,y), or null. Outside bounds → null. */
    fun handleAt(x: Float, y: Float): Handle? {
        if (x < bx0 || x > bx1 || y < by0 || y > by1) return null
        val r2 = 60f * 60f
        val corners = listOf(
            Triple(Handle.TOP_LEFT, left, top),
            Triple(Handle.TOP_RIGHT, right, top),
            Triple(Handle.BOTTOM_LEFT, left, bottom),
            Triple(Handle.BOTTOM_RIGHT, right, bottom),
        )
        var best: Handle? = null
        var bestD = r2
        for ((handle, px, py) in corners) {
            val dx = x - px
            val dy = y - py
            val d = dx * dx + dy * dy
            if (d <= bestD) { best = handle; bestD = d }
        }
        return best
    }

    /** Crop selection as fractions of the full frame, for the export pipeline. */
    fun fractional(): CropFraction {
        val bw = bx1 - bx0
        val bh = by1 - by0
        return CropFraction(
            left = (left - bx0) / bw,
            top = (top - by0) / bh,
            right = (right - bx0) / bw,
            bottom = (bottom - by0) / bh,
        )
    }
}
