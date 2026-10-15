package com.zkincamcam.xcam.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.zkincamcam.xcam.core.capture.LivenessMotionEffect
import com.zkincamcam.xcam.core.capture.LivenessConfig
import com.zkincamcam.xcam.core.capture.DeepLivenessShader
import com.zkincamcam.xcam.core.capture.FaceLandmarkAnalyzer
import com.zkincamcam.xcam.core.capture.MediaPreprocessor
import com.zkincamcam.xcam.core.capture.ExifForensicEngine
import com.zkincamcam.xcam.core.video.PhotoVideoEngine
import com.zkincamcam.xcam.ui.CropFraction
import java.io.File
import java.io.FileOutputStream

@OptIn(UnstableApi::class)
object MediaConverter {

    fun convertImageToMp4(
        context: Context,
        imageUri: Uri,
        outputFile: File,
        config: LivenessConfig,
        sharpnessValue: Float,
        trim: VideoTrim? = null,
        cropFraction: CropFraction? = null,
        onComplete: (Boolean) -> Unit
    ) {
        if (outputFile.exists()) outputFile.delete()
        var processedFile: File? = null
        var landmarks = FaceLandmarkAnalyzer.EyeLandmarks(0.5f, 0.5f, 0.5f, 0.5f, false)

        try {
            context.contentResolver.openInputStream(imageUri).use { inputStream ->
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                if (originalBitmap != null) {
                    val cropped = if (cropFraction != null && !cropFraction.isFullFrame) {
                        val x = (cropFraction.left * originalBitmap.width).toInt().coerceIn(0, originalBitmap.width - 1)
                        val y = (cropFraction.top * originalBitmap.height).toInt().coerceIn(0, originalBitmap.height - 1)
                        val w = ((cropFraction.right - cropFraction.left) * originalBitmap.width).toInt().coerceIn(1, originalBitmap.width - x)
                        val h = ((cropFraction.bottom - cropFraction.top) * originalBitmap.height).toInt().coerceIn(1, originalBitmap.height - y)
                        Bitmap.createBitmap(originalBitmap, x, y, w, h)
                    } else originalBitmap
                    landmarks = FaceLandmarkAnalyzer.analyze(cropped)
                    val enhanced = MediaPreprocessor.applySharpen(cropped, sharpnessValue)
                    val tempFile = File(context.cacheDir, "studio_input.jpg")
                    FileOutputStream(tempFile).use { out -> enhanced.compress(Bitmap.CompressFormat.JPEG, 100, out) }
                    enhanced.recycle(); if (cropped != enhanced) cropped.recycle(); if (originalBitmap != cropped && originalBitmap != enhanced) originalBitmap.recycle()
                    ExifForensicEngine.reconstructMetadata(tempFile)
                    processedFile = tempFile
                }
            }
        } catch (_: Exception) { onComplete(false); return }

        val inputUri = if (processedFile != null) Uri.fromFile(processedFile) else imageUri

        fun exportWithTransformer(uri: Uri, durationMs: Long, temporaryVideo: File? = null) {
            fun cleanup() {
                processedFile?.delete()
                temporaryVideo?.delete()
            }

            val mediaItemBuilder = MediaItem.Builder().setUri(uri).setImageDurationMs(durationMs)
            if (trim != null && !trim.isNoOp()) {
                mediaItemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(trim.startUs / 1000L)
                        .setEndPositionMs(trim.endUs / 1000L)
                        .build()
                )
            }
            val mediaItem = mediaItemBuilder.build()
            val videoEffects = mutableListOf<Effect>()
            if (config.eyeMovementEnabled) videoEffects.add(LivenessMotionEffect())
            videoEffects.add(
                DeepLivenessShader(
                    landmarks,
                    config.adjustments,
                    config.lutBitmap,
                    config.beautyLevel,
                    config.transformMatrix,
                    config.noiseLevel,
                    config.pulseLevel,
                    config.sharpnessLevel,
                    config.beautyPreset,
                    config.catchlightOverride,
                    config.hdLevel,
                )
            )

            val transformerEffects = Effects(listOf(), videoEffects)
            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setFrameRate(30)
                .setEffects(transformerEffects)
                .build()
            val transformer = Transformer.Builder(context).setVideoMimeType("video/avc").build()

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    cleanup()
                    val succeeded = outputFile.isFile && outputFile.length() > 0
                    if (succeeded) {
                        Log.i("MediaConverter", "MP4 export completed")
                    } else {
                        Log.e("MediaConverter", "MP4 export completed without a usable output file")
                        outputFile.delete()
                    }
                    onComplete(succeeded)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, ex: ExportException) {
                    Log.e("MediaConverter", "MP4 export failed", ex)
                    cleanup()
                    outputFile.delete()
                    onComplete(false)
                }
            })

            try {
                transformer.start(editedMediaItem, outputFile.absolutePath)
            } catch (e: Exception) {
                Log.e("MediaConverter", "Unable to start MP4 export", e)
                cleanup()
                outputFile.delete()
                onComplete(false)
            }
        }

        if (processedFile == null) {
            exportWithTransformer(inputUri, 6400L)
            return
        }

        val ffmpegIntermediate = File(context.cacheDir, "ffmpeg_video_${System.currentTimeMillis()}.mp4")
        PhotoVideoEngine.createLoopingVideoFromImage(
            context = context,
            sourceUri = inputUri,
            outputFile = ffmpegIntermediate,
            durationSeconds = 6.4,
            fps = 30,
        ) { succeeded, _ ->
            Handler(Looper.getMainLooper()).post {
                if (succeeded) {
                    exportWithTransformer(Uri.fromFile(ffmpegIntermediate), 6400L, ffmpegIntermediate)
                } else {
                    ffmpegIntermediate.delete()
                    exportWithTransformer(inputUri, 6400L)
                }
            }
        }
    }

    /**
     * Re-encodes a video import applying the current trim selection (and nothing
     * else — video imports get no liveness/effects pipeline). A no-op/absent trim
     * copies the file through unchanged.
     */
    fun convertVideoWithTrim(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        trim: VideoTrim?,
        onComplete: (Boolean) -> Unit
    ) {
        if (outputFile.exists()) outputFile.delete()
        if (trim == null || trim.isNoOp()) {
            // Straight copy — no re-encode, no quality loss.
            try {
                val inputStream = context.contentResolver.openInputStream(inputUri)
                    ?: throw java.io.IOException("Could not open input URI: $inputUri")
                inputStream.use { input ->
                    java.io.FileOutputStream(outputFile).use { output -> input.copyTo(output) }
                }
                val succeeded = outputFile.isFile && outputFile.length() > 0
                if (!succeeded) {
                    outputFile.delete()
                    throw java.io.IOException("Video copy produced no output")
                }
                onComplete(succeeded)
            } catch (e: Exception) {
                outputFile.delete()
                Log.e("MediaConverter", "Video copy failed", e)
                onComplete(false)
            }
            return
        }
        val mediaItem = MediaItem.Builder().setUri(inputUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trim.startUs / 1000L)
                    .setEndPositionMs(trim.endUs / 1000L)
                    .build()
            )
            .build()
        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
        val transformer = Transformer.Builder(context).setVideoMimeType("video/avc").build()
        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                val succeeded = outputFile.isFile && outputFile.length() > 0
                if (succeeded) {
                    Log.i("MediaConverter", "Trimmed video export completed")
                } else {
                    Log.e("MediaConverter", "Trimmed export completed without a usable output file")
                    outputFile.delete()
                }
                onComplete(succeeded)
            }
            override fun onError(composition: Composition, exportResult: ExportResult, ex: ExportException) {
                Log.e("MediaConverter", "Trimmed video export failed", ex)
                outputFile.delete()
                onComplete(false)
            }
        })
        try {
            transformer.start(editedMediaItem, outputFile.absolutePath)
        } catch (e: Exception) {
            Log.e("MediaConverter", "Unable to start trimmed video export", e)
            outputFile.delete()
            onComplete(false)
        }
    }
}
