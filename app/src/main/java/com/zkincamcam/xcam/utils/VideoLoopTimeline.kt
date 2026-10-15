package com.zkincamcam.xcam.utils

internal data class VideoLoopSegment(
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val outputStartUs: Long,
) {
    val durationUs: Long get() = sourceEndUs - sourceStartUs
}

internal object VideoLoopTimeline {
    fun create(startUs: Long, durationUs: Long, sourceDurationUs: Long): List<VideoLoopSegment> {
        if (startUs < 0 || durationUs <= 0 || sourceDurationUs <= 0 || startUs >= sourceDurationUs) {
            return emptyList()
        }

        val segments = mutableListOf<VideoLoopSegment>()
        var sourceStartUs = startUs
        var remainingUs = durationUs
        var outputStartUs = 0L

        while (remainingUs > 0) {
            val segmentDurationUs = minOf(remainingUs, sourceDurationUs - sourceStartUs)
            segments += VideoLoopSegment(
                sourceStartUs = sourceStartUs,
                sourceEndUs = sourceStartUs + segmentDurationUs,
                outputStartUs = outputStartUs,
            )
            remainingUs -= segmentDurationUs
            outputStartUs += segmentDurationUs
            sourceStartUs = 0
        }
        return segments
    }

    fun mapPresentationTimeUs(segment: VideoLoopSegment, sourcePresentationTimeUs: Long): Long =
        segment.outputStartUs + sourcePresentationTimeUs - segment.sourceStartUs
}

internal object VideoOutputValidation {
    fun coversRequestedDuration(lastPresentationTimeUs: Long, requestedDurationUs: Long, frameRate: Int): Boolean {
        if (lastPresentationTimeUs < 0 || requestedDurationUs <= 0 || frameRate <= 0) return false
        val frameDurationUs = 1_000_000L / frameRate
        val toleranceUs = frameDurationUs * 2
        return lastPresentationTimeUs + frameDurationUs >= requestedDurationUs - toleranceUs
    }
}
