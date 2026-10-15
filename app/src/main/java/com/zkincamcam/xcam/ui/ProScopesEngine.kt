package com.zkincamcam.xcam.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Cinema-Grade Real-time Signal Analysis Engine.
 * Provides scientific visualization of Luma and RGB distributions (Histogram/Waveform)
 * to achieve absolute color accuracy during the grading process.
 */
object ProScopesEngine {

    class ScopesData(
        val luma: IntArray,
        val red: IntArray,
        val green: IntArray,
        val blue: IntArray
    )

    fun analyze(bitmap: Bitmap): ScopesData {
        val luma = IntArray(256)
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (p in pixels) {
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            val py = (0.299f * pr + 0.587f * pg + 0.114f * pb).toInt().coerceIn(0, 255)
            
            luma[py]++
            r[pr]++
            g[pg]++
            b[pb]++
        }

        return ScopesData(luma, r, g, b)
    }

    fun renderHistogram(canvas: Canvas, data: ScopesData, width: Int, height: Int) {
        val paint = Paint().apply { isAntiAlias = true }
        
        var maxVal = 1
        for (v in data.luma) if (v > maxVal) maxVal = v
        
        canvas.drawColor(Color.argb(150, 0, 0, 0))
        
        val step = width.toFloat() / 256f
        for (i in 0..255) {
            // Luma (White)
            val hL = (data.luma[i].toFloat() / maxVal) * height
            paint.color = Color.WHITE
            paint.alpha = 180
            canvas.drawRect(i * step, height - hL, (i + 1) * step, height.toFloat(), paint)
            
            // RGB Overlays (Subtle lines)
            paint.strokeWidth = 1f
            paint.style = Paint.Style.STROKE
            
            paint.color = Color.RED
            canvas.drawLine(i * step, height - (data.red[i].toFloat() / maxVal) * height, i * step, height.toFloat(), paint)
            
            paint.color = Color.GREEN
            canvas.drawLine(i * step, height - (data.green[i].toFloat() / maxVal) * height, i * step, height.toFloat(), paint)
            
            paint.color = Color.BLUE
            canvas.drawLine(i * step, height - (data.blue[i].toFloat() / maxVal) * height, i * step, height.toFloat(), paint)
            
            paint.style = Paint.Style.FILL
        }
    }
}
