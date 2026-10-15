package com.zkincamcam.xcam.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import androidx.core.net.toUri
import java.io.File
import java.nio.ByteBuffer

/**
 * Hardware Surface-to-Surface transcode (decoder → encoder input surface) with PTS rebasing.
 * Repeats source segments when a recording crosses the end of the selected video.
 */
object MediaRemuxer {

    @JvmOverloads
    fun trimVideo(
        context: Context,
        sourcePath: String,
        targetFile: File,
        startMs: Long,
        durationMs: Long,
        logAction: ((String) -> Unit)? = null
    ): Boolean {
        if (durationMs <= 0) return false

        val parentDir = targetFile.parentFile ?: context.cacheDir
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            logAction?.invoke("[TRANSCODE] Cannot create output directory: $parentDir")
            return false
        }
        val tempOutputFile = File(parentDir, "xcam_trim_${System.nanoTime()}.mp4")

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()

        try {
            val uri = sourcePath.toUri()
            setDataSource(videoExtractor, context, uri, sourcePath)
            setDataSource(audioExtractor, context, uri, sourcePath)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var srcVideoFormat: MediaFormat? = null
            var srcAudioFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    srcVideoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    srcAudioFormat = format
                }
            }

            if (videoTrackIndex == -1 || srcVideoFormat == null) {
                logAction?.invoke("[TRANSCODE] No video track found")
                return false
            }

            val startUs = Math.multiplyExact(maxOf(0L, startMs), 1_000L)
            val requestedDurationUs = Math.multiplyExact(durationMs, 1_000L)
            var sourceDurationUs = if (srcVideoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                srcVideoFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            if (sourceDurationUs <= 0) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (uri.scheme == null || uri.scheme == "file") {
                        retriever.setDataSource(uri.path ?: sourcePath)
                    } else {
                        retriever.setDataSource(context, uri)
                    }
                    sourceDurationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.let { Math.multiplyExact(it, 1_000L) } ?: 0L
                } finally {
                    retriever.release()
                }
            }
            val segments = VideoLoopTimeline.create(startUs, requestedDurationUs, sourceDurationUs)
            if (segments.isEmpty()) {
                logAction?.invoke("[TRANSCODE] Invalid source duration or playback position")
                return false
            }

            val width = srcVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = srcVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = srcVideoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val frameRateRaw = srcVideoFormat.runCatching { getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull() ?: 30
            val frameRate = if (frameRateRaw > 0) frameRateRaw else 30
            val bitRateRaw = srcVideoFormat.runCatching { getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull() ?: 8_000_000
            val bitRate = if (bitRateRaw > 0) bitRateRaw else 8_000_000

            val encoderFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val enc = MediaCodec.createEncoderByType("video/avc")
            encoder = enc
            enc.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            inputSurface = surface
            enc.start()

            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(srcVideoFormat, surface, null, 0)
            dec.start()

            val outputMuxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = outputMuxer
            val rotation = try {
                if (srcVideoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                    srcVideoFormat.getInteger(MediaFormat.KEY_ROTATION)
                } else 0
            } catch (_: Throwable) { 0 }
            if (rotation != 0) {
                outputMuxer.setOrientationHint(rotation)
            }

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            videoExtractor.selectTrack(videoTrackIndex)
            videoExtractor.seekTo(segments.first().sourceStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            var decoderInputEos = false
            var encoderOutputEos = false
            var segmentIndex = 0
            var lastEncoderPtsUs = -1L
            var encodedFrameCount = 0L
            val timeoutUs = 5000L
            var lastProgressTimeMs = System.currentTimeMillis()

            while (!encoderOutputEos) {
                if (System.currentTimeMillis() - lastProgressTimeMs > 30_000) {
                    logAction?.invoke("[TRANSCODE] Codec made no progress for 30 seconds")
                    return false
                }

                if (!decoderInputEos) {
                    val inIndex = dec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = dec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            inBuf.clear()
                            val sampleSize = videoExtractor.readSampleData(inBuf, 0)
                            val segment = segments[segmentIndex]
                            val sampleTimeUs = videoExtractor.sampleTime
                            if (sampleSize < 0 || sampleTimeUs < 0 || sampleTimeUs >= segment.sourceEndUs) {
                                dec.queueInputBuffer(
                                    inIndex, 0, 0, segment.sourceEndUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                decoderInputEos = true
                            } else {
                                dec.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, 0)
                                videoExtractor.advance()
                                lastProgressTimeMs = System.currentTimeMillis()
                            }
                        } else {
                            dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderInputEos = true
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(decBufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val presentationUs = decBufferInfo.presentationTimeUs
                    if ((decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        dec.releaseOutputBuffer(outIndex, false)
                        if (segmentIndex + 1 < segments.size) {
                            segmentIndex++
                            dec.stop()
                            dec.configure(srcVideoFormat, surface, null, 0)
                            dec.start()
                            videoExtractor.seekTo(
                                segments[segmentIndex].sourceStartUs,
                                MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                            )
                            decoderInputEos = false
                        } else {
                            enc.signalEndOfInputStream()
                        }
                    } else {
                        val segment = segments[segmentIndex]
                        if (presentationUs >= segment.sourceStartUs && presentationUs < segment.sourceEndUs) {
                            val outputPresentationUs =
                                VideoLoopTimeline.mapPresentationTimeUs(segment, presentationUs)
                            dec.releaseOutputBuffer(outIndex, outputPresentationUs * 1_000L)
                        } else {
                            dec.releaseOutputBuffer(outIndex, false)
                        }
                        lastProgressTimeMs = System.currentTimeMillis()
                    }
                }

                while (true) {
                    val encOutIndex = enc.dequeueOutputBuffer(encBufferInfo, timeoutUs)
                    if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxerVideoTrack = outputMuxer.addTrack(enc.outputFormat)
                        if (srcAudioFormat != null && audioTrackIndex != -1) {
                            muxerAudioTrack = outputMuxer.addTrack(srcAudioFormat)
                        }
                        outputMuxer.start()
                        muxerStarted = true
                    } else if (encOutIndex >= 0) {
                        if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderOutputEos = true
                            enc.releaseOutputBuffer(encOutIndex, false)
                            break
                        }
                        val encBuf = enc.getOutputBuffer(encOutIndex)
                        if (encBuf != null && muxerStarted && encBufferInfo.size > 0) {
                            if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (encBufferInfo.presentationTimeUs < 0) {
                                    logAction?.invoke("[TRANSCODE] Encoder produced a negative video timestamp")
                                    return false
                                }
                                val outputPtsUs = encBufferInfo.presentationTimeUs
                                if (lastEncoderPtsUs >= 0 && outputPtsUs <= lastEncoderPtsUs) {
                                    logAction?.invoke(
                                        "[TRANSCODE] Encoder produced non-increasing video timestamps: " +
                                            "$outputPtsUs after $lastEncoderPtsUs",
                                    )
                                    return false
                                }
                                outputMuxer.writeSampleData(muxerVideoTrack, encBuf, encBufferInfo)
                                lastEncoderPtsUs = outputPtsUs
                                encodedFrameCount++
                            }
                        }
                        enc.releaseOutputBuffer(encOutIndex, false)
                        lastProgressTimeMs = System.currentTimeMillis()
                    } else {
                        break
                    }
                }
            }

            if (!encoderOutputEos || encodedFrameCount == 0L ||
                !VideoOutputValidation.coversRequestedDuration(lastEncoderPtsUs, requestedDurationUs, frameRate)
            ) {
                logAction?.invoke(
                    "[TRANSCODE] Incomplete video output: $encodedFrameCount frames, " +
                        "last PTS $lastEncoderPtsUs us, expected $requestedDurationUs us",
                )
                return false
            }

            if (muxerStarted && muxerAudioTrack != -1 && audioTrackIndex != -1) {
                audioExtractor.selectTrack(audioTrackIndex)
                val audioBuf = ByteBuffer.allocateDirect(4 * 1024 * 1024)
                val audioBufInfo = MediaCodec.BufferInfo()
                for (segment in segments) {
                    audioExtractor.seekTo(segment.sourceStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (audioExtractor.sampleTime >= 0 && audioExtractor.sampleTime < segment.sourceStartUs) {
                        if (!audioExtractor.advance()) break
                    }
                    while (audioExtractor.sampleTime >= 0 && audioExtractor.sampleTime < segment.sourceEndUs) {
                        audioBuf.clear()
                        audioBufInfo.offset = 0
                        audioBufInfo.size = audioExtractor.readSampleData(audioBuf, 0)
                        if (audioBufInfo.size < 0) break
                        audioBufInfo.presentationTimeUs =
                            segment.outputStartUs + audioExtractor.sampleTime - segment.sourceStartUs
                        @Suppress("WrongConstant")
                        audioBufInfo.flags = audioExtractor.sampleFlags
                        outputMuxer.writeSampleData(muxerAudioTrack, audioBuf, audioBufInfo)
                        if (!audioExtractor.advance()) break
                    }
                }
            }

            outputMuxer.stop()
            muxerStarted = false
            outputMuxer.release()
            muxer = null

            if (tempOutputFile.isFile && tempOutputFile.length() > 0 &&
                replaceTargetFile(tempOutputFile, targetFile, logAction)
            ) {
                logAction?.invoke("[TRANSCODE] Finished: ${targetFile.length()} bytes (Start: $startMs ms, Duration: $durationMs ms)")
                return true
            }
        } catch (t: Throwable) {
            logAction?.invoke("[TRANSCODE] Failed: ${t.message}")
        } finally {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { encoder?.stop() } catch (_: Throwable) {}
            try { encoder?.release() } catch (_: Throwable) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { inputSurface?.release() } catch (_: Throwable) {}
            try { videoExtractor.release() } catch (_: Throwable) {}
            try { audioExtractor.release() } catch (_: Throwable) {}
            if (tempOutputFile.exists()) {
                try { tempOutputFile.delete() } catch (_: Throwable) {}
            }
        }
        return false
    }

    private fun setDataSource(
        extractor: MediaExtractor,
        context: Context,
        uri: android.net.Uri,
        sourcePath: String,
    ) {
        if (uri.scheme == null || uri.scheme == "file") {
            extractor.setDataSource(uri.path ?: sourcePath)
        } else {
            extractor.setDataSource(context, uri, null)
        }
    }

    private fun replaceTargetFile(
        tempFile: File,
        targetFile: File,
        logAction: ((String) -> Unit)?,
    ): Boolean {
        val backupFile = File(targetFile.absoluteFile.parentFile, "${targetFile.name}.xcam_backup_${System.nanoTime()}")
        val hadOriginal = targetFile.exists()
        if (hadOriginal && !targetFile.renameTo(backupFile)) {
            logAction?.invoke("[TRANSCODE] Could not preserve original output before replacement")
            return false
        }

        return try {
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = false)
            }
            val replaced = targetFile.isFile && targetFile.length() > 0
            if (replaced) {
                if (hadOriginal) backupFile.delete()
                true
            } else {
                targetFile.delete()
                if (hadOriginal) backupFile.renameTo(targetFile)
                false
            }
        } catch (t: Throwable) {
            targetFile.delete()
            if (hadOriginal && backupFile.exists()) backupFile.renameTo(targetFile)
            logAction?.invoke("[TRANSCODE] Could not replace output file: ${t.message}")
            false
        }
    }
}
