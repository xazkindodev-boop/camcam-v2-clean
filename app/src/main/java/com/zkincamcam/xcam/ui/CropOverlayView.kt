package com.zkincamcam.xcam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of the editor preview: dims everything
 * outside the crop rect and draws the 8 handles (4 corners + 4 edges). Pure
 * drawing — touch handling lives in MainActivity, which routes into
 * [CropRectState] and calls [invalidate] on change.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val tmpRect = RectF()

    /** Corner handle arm length in px (drawn as L-shapes, Canva-style). */
    var handleArmPx = 48f

    /** Rect to draw, in this view's pixel coordinates (updated by the host). */
    fun setCropRect(left: Float, top: Float, right: Float, bottom: Float) {
        tmpRect.set(left, top, right, bottom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (tmpRect.isEmpty) return
        // Dim outside the crop rect (4 side strips).
        canvas.drawRect(0f, 0f, w, tmpRect.top, dimPaint)
        canvas.drawRect(0f, tmpRect.bottom, w, h, dimPaint)
        canvas.drawRect(0f, tmpRect.top, tmpRect.left, tmpRect.bottom, dimPaint)
        canvas.drawRect(tmpRect.right, tmpRect.top, w, tmpRect.bottom, dimPaint)
        // Frame.
        canvas.drawRect(tmpRect, framePaint)
        // Corner L-handles.
        drawCorner(canvas, tmpRect.left, tmpRect.top, 1f, 1f)
        drawCorner(canvas, tmpRect.right, tmpRect.top, -1f, 1f)
        drawCorner(canvas, tmpRect.left, tmpRect.bottom, 1f, -1f)
        drawCorner(canvas, tmpRect.right, tmpRect.bottom, -1f, -1f)
    }

    private fun drawCorner(canvas: Canvas, cx: Float, cy: Float, sx: Float, sy: Float) {
        canvas.drawLine(cx, cy, cx + handleArmPx * sx, cy, cornerPaint)
        canvas.drawLine(cx, cy, cx, cy + handleArmPx * sy, cornerPaint)
    }
}
