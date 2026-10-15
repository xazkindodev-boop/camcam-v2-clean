package com.zkincamcam.xcam.utils

/**
 * Immutable trim selection for the video import pipeline (microseconds,
 * Media3's clock). [VideoTrim.of] normalizes messy UI input — reversed or
 * zero-length ranges degrade to a no-op so a bad drag can never produce a
 * broken export.
 */
data class VideoTrim(
    val startUs: Long,
    val endUs: Long,
) {
    /** True when the selection covers nothing meaningful — export everything. */
    fun isNoOp(): Boolean = endUs <= startUs

    fun durationMs(): Long = (endUs - startUs) / 1000L

    companion object {
        /**
         * Builds a trim from UI values. [durationUs] (the media's real length)
         * clamps both ends; omitted, the range is taken as-is.
         */
        fun of(startUs: Long, endUs: Long, durationUs: Long? = null): VideoTrim {
            val s = maxOf(0L, startUs)
            val e0 = if (durationUs != null) minOf(endUs, durationUs) else endUs
            val e = maxOf(e0, s)
            return VideoTrim(s, e)
        }
    }
}
