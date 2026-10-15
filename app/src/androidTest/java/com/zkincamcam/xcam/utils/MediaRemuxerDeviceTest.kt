package com.zkincamcam.xcam.utils

import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MediaRemuxerDeviceTest {

    @Test
    fun remuxKeepsAudioAndVideoAlignedAcrossLoopBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "remux_device_source_${System.nanoTime()}.mp4")
        val output = File(context.cacheDir, "remux_device_output_${System.nanoTime()}.mp4")
        try {
            val session = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-f", "lavfi",
                    "-i", "testsrc=size=320x240:rate=30",
                    "-f", "lavfi",
                    "-i", "sine=frequency=440:sample_rate=44100",
                    "-t", "2",
                    "-c:v", "libx264",
                    "-preset", "ultrafast",
                    "-g", "30",
                    "-pix_fmt", "yuv420p",
                    "-c:a", "aac",
                    "-b:a", "64k",
                    "-shortest",
                    source.absolutePath,
                ),
            )
            assertTrue(
                "Fixture generation failed: ${session.getReturnCode()}",
                ReturnCode.isSuccess(session.getReturnCode()),
            )

            val remuxLogs = mutableListOf<String>()
            val remuxed = MediaRemuxer.trimVideo(
                    context = context,
                    sourcePath = source.absolutePath,
                    targetFile = output,
                    startMs = 1_500,
                    durationMs = 1_000,
                    logAction = remuxLogs::add,
                )
            assertTrue("MediaRemuxer returned false: ${remuxLogs.joinToString()}", remuxed)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                var videoTrack = -1
                var audioTrack = -1
                for (index in 0 until extractor.trackCount) {
                    when (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.substringBefore('/')) {
                        "video" -> videoTrack = index
                        "audio" -> audioTrack = index
                    }
                }
                assertTrue("Output is missing video", videoTrack >= 0)
                assertTrue("Output is missing audio", audioTrack >= 0)

                val firstVideoTimeUs = firstSampleTimeUs(extractor, videoTrack)
                val firstAudioTimeUs = firstSampleTimeUs(extractor, audioTrack)
                assertTrue("Output video starts before time zero: $firstVideoTimeUs", firstVideoTimeUs >= 0)
                assertTrue("Output audio starts before time zero: $firstAudioTimeUs", firstAudioTimeUs >= 0)
                assertTrue(
                    "Audio/video start offset exceeds 150 ms: video=$firstVideoTimeUs audio=$firstAudioTimeUs",
                    abs(firstVideoTimeUs - firstAudioTimeUs) <= 150_000,
                )
            } finally {
                extractor.release()
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                assertNotNull("Output duration is unavailable", durationMs)
                assertTrue("Unexpected output duration: $durationMs ms", abs(durationMs!! - 1_000) <= 200)
            } finally {
                retriever.release()
            }
            assertEquals(true, output.isFile && output.length() > 0)
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun firstSampleTimeUs(extractor: MediaExtractor, trackIndex: Int): Long {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (extractor.sampleTime < 0 && extractor.advance()) {
            // Advance until a selected-track sample is available.
        }
        return extractor.sampleTime.also { extractor.unselectTrack(trackIndex) }
    }
}
