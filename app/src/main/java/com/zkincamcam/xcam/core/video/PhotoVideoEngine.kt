package com.zkincamcam.xcam.core.video

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream

object PhotoVideoEngine {

    fun createLoopingVideoFromImage(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        durationSeconds: Double = 5.0,
        fps: Int = 30,
        width: Int = 1280,
        height: Int = 1280,
        onComplete: (Boolean, FFmpegSession?) -> Unit,
    ) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val sourceCopy = File(context.cacheDir, "ffmpeg_input_${System.currentTimeMillis()}.jpg")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(sourceCopy).use { output -> input.copyTo(output) }
            } ?: run {
                sourceCopy.delete()
                onComplete(false, null)
                return
            }
        } catch (e: Exception) {
            sourceCopy.delete()
            onComplete(false, null)
            return
        }

        val arguments = arrayOf(
            "-loop", "1",
            "-framerate", fps.toString(),
            "-i", sourceCopy.absolutePath,
            "-t", durationSeconds.toString(),
            "-vf", "scale=$width:$height:force_original_aspect_ratio=decrease,pad=$width:$height:(ow-iw)/2:(oh-ih)/2,format=yuv420p",
            "-an",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "22",
            "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            outputFile.absolutePath,
        )

        try {
            FFmpegKit.executeWithArgumentsAsync(arguments) { session ->
                sourceCopy.delete()
                val succeeded = ReturnCode.isSuccess(session.getReturnCode()) &&
                    outputFile.exists() && outputFile.length() > 0L
                if (!succeeded) outputFile.delete()
                onComplete(succeeded, session)
            }
        } catch (e: Exception) {
            sourceCopy.delete()
            outputFile.delete()
            onComplete(false, null)
        }
    }
}
