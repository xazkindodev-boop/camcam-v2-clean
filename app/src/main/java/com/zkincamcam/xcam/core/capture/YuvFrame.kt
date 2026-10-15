package com.zkincamcam.xcam.core.capture

/**
 * Planar YUV 4:2:0 buffer triplet sized for a single stream resolution.
 * Treat the arrays as immutable once published so concurrent readers never observe a
 * half-written frame.
 */
internal class YuvFrame(val width: Int, val height: Int) {
    val chromaWidth: Int = (width + 1) / 2
    val chromaHeight: Int = (height + 1) / 2
    val y = ByteArray(width * height)
    val u = ByteArray(chromaWidth * chromaHeight)
    val v = ByteArray(chromaWidth * chromaHeight)

    val size: Int get() = y.size + u.size + v.size

    fun matches(otherWidth: Int, otherHeight: Int) = width == otherWidth && height == otherHeight

    fun describe() = "${width}x$height (Y ${y.size}, U ${u.size}, V ${v.size})"
}