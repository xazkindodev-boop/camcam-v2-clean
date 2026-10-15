package com.zkincamcam.xcam.core.capture

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hardcore JPEG Forensic Reconstruction Engine.
 * Eradicates digital traces by performing bit-level injection of 
 * genuine hardware Quantization Tables (DQT) cloned from real physical sensors.
 * Now featuring persistent signature storage.
 */
object ExifForensicEngine {

    private const val SIG_FILE = "hardware_sig.bin"
    /** JPEG headers/EXIF/DQT always live in the first megabytes; cap the scan buffer. */
    private const val MAX_SCAN_BYTES = 4L * 1024 * 1024
    private var genuineLuminanceTable: ByteArray? = null
    private var genuineChrominanceTable: ByteArray? = null

    fun cloneHardwareTables(sourceFile: File, context: Context? = null) {
        if (!sourceFile.exists()) return
        try {
            val raf = RandomAccessFile(sourceFile, "r")
            val data = ByteArray(sourceFile.length().coerceAtMost(MAX_SCAN_BYTES).toInt())
            raf.readFully(data); raf.close()

            var pos = 2
            while (pos < data.size - 64) {
                if (data[pos].toInt() == -1 && (data[pos + 1].toInt() and 0xFF) == 0xDB) {
                    val len = jpegSegmentLength(data, pos)
                    if (len != null) {
                        var tPos = pos + 4
                        while (tPos < pos + 2 + len && tPos + 65 <= data.size) {
                            val id = (data[tPos].toInt() and 0x0F)
                            val table = ByteArray(64); System.arraycopy(data, tPos + 1, table, 0, 64)
                            if (id == 0) genuineLuminanceTable = table
                            else if (id == 1) genuineChrominanceTable = table
                            tPos += 65
                        }
                        pos += 2 + len
                    } else pos++
                } else pos++
            }
            context?.let { saveSignature(it) }
        } catch (_: Exception) {}
    }

    /** Segment length must cover the 2 length bytes itself; reject degenerate/corrupt values. */
    private fun jpegSegmentLength(data: ByteArray, pos: Int): Int? {
        if (pos + 4 > data.size) return null
        val len = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
        return if (len < 2 || pos + 2 + len > data.size) null else len
    }

    fun saveSignature(context: Context) {
        val lum = genuineLuminanceTable ?: return
        val chr = genuineChrominanceTable ?: return
        try {
            val file = File(context.filesDir, SIG_FILE)
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(lum); raf.write(chr)
            }
        } catch (_: Exception) {}
    }

    fun loadSignature(context: Context) {
        try {
            val file = File(context.filesDir, SIG_FILE)
            if (!file.exists()) return
            RandomAccessFile(file, "r").use { raf ->
                val lum = ByteArray(64); val chr = ByteArray(64)
                raf.readFully(lum); raf.readFully(chr)
                genuineLuminanceTable = lum; genuineChrominanceTable = chr
            }
        } catch (_: Exception) {}
    }

    fun reconstructMetadata(targetFile: File) {
        if (!targetFile.exists()) return
        applyExifAttributes(targetFile)
        injectGenuineTables(targetFile)
    }

    private fun applyExifAttributes(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Android " + Build.VERSION.RELEASE + " (HAL3 v2.1)")
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "26/1")
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, "1.8")
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "100")
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.02")
            val time = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, time)
            exif.saveAttributes()
        } catch (_: Exception) {}
    }

    private fun injectGenuineTables(file: File) {
        val lum = genuineLuminanceTable ?: ByteArray(64) { 1.toByte() }
        val chr = genuineChrominanceTable ?: ByteArray(64) { 1.toByte() }
        try {
            val fileSize = file.length().coerceAtMost(MAX_SCAN_BYTES)
            val raf = RandomAccessFile(file, "rw")
            val buffer = ByteArray(fileSize.toInt())
            raf.readFully(buffer)
            var pos = 2
            while (pos < buffer.size - 64) {
                if (buffer[pos].toInt() == -1) {
                    val marker = buffer[pos + 1].toInt() and 0xFF
                    val len = jpegSegmentLength(buffer, pos)
                    if (len == null) { pos++; continue }
                    if (marker == 0xDB) {
                        var tPos = pos + 4
                        while (tPos < pos + 2 + len && tPos + 65 <= buffer.size) {
                            val id = buffer[tPos].toInt() and 0x0F
                            System.arraycopy(if (id == 0) lum else chr, 0, buffer, tPos + 1, 64)
                            tPos += 65
                        }
                    }
                    if (marker == 0xDA) break
                    pos += 2 + len
                } else pos++
            }
            raf.seek(0); raf.write(buffer); raf.setLength(fileSize); raf.close()
        } catch (_: Exception) {}
    }
}
