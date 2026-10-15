package com.zkincamcam.xcam.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLoopTimelineTest {
    @Test
    fun recordingWithinSourceProducesOneSegment() {
        assertEquals(
            listOf(VideoLoopSegment(2_000_000, 5_000_000, 0)),
            VideoLoopTimeline.create(startUs = 2_000_000, durationUs = 3_000_000, sourceDurationUs = 8_000_000),
        )
    }

    @Test
    fun recordingCrossingSourceEndContinuesFromBeginning() {
        assertEquals(
            listOf(
                VideoLoopSegment(7_000_000, 8_000_000, 0),
                VideoLoopSegment(0, 4_000_000, 1_000_000),
            ),
            VideoLoopTimeline.create(startUs = 7_000_000, durationUs = 5_000_000, sourceDurationUs = 8_000_000),
        )
    }

    @Test
    fun recordingLongerThanSeveralLoopsKeepsContinuousOutputTime() {
        val segments = VideoLoopTimeline.create(startUs = 3, durationUs = 11, sourceDurationUs = 5)

        assertEquals(
            listOf(
                VideoLoopSegment(3, 5, 0),
                VideoLoopSegment(0, 5, 2),
                VideoLoopSegment(0, 4, 7),
            ),
            segments,
        )
        assertEquals(11, segments.sumOf { it.durationUs })
    }

    @Test
    fun videoAndAudioPresentationTimesShareTheSameOutputTimeline() {
        val segments = VideoLoopTimeline.create(startUs = 7_000_000, durationUs = 5_000_000, sourceDurationUs = 8_000_000)

        assertEquals(250_000, VideoLoopTimeline.mapPresentationTimeUs(segments[0], 7_250_000))
        assertEquals(1_250_000, VideoLoopTimeline.mapPresentationTimeUs(segments[1], 250_000))
    }

    @Test
    fun invalidOrOutOfRangeWindowsProduceNoSegments() {
        assertTrue(VideoLoopTimeline.create(5, 1, 5).isEmpty())
        assertTrue(VideoLoopTimeline.create(0, 0, 5).isEmpty())
        assertTrue(VideoLoopTimeline.create(-1, 1, 5).isEmpty())
    }

    @Test
    fun outputValidationUsesFrameSizedTolerance() {
        assertTrue(VideoOutputValidation.coversRequestedDuration(9_966_666, 10_000_000, 30))
        assertTrue(!VideoOutputValidation.coversRequestedDuration(9_900_000, 10_000_000, 30))
        assertTrue(!VideoOutputValidation.coversRequestedDuration(-1, 10_000_000, 30))
    }
}
