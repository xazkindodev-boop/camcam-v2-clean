package com.zkincamcam.xcam.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.zkincamcam.xcam.data.Constants.AUTHORITY
import com.zkincamcam.xcam.data.Constants.KEY_IS_ENABLED
import com.zkincamcam.xcam.data.Constants.KEY_IS_MIRRORED
import com.zkincamcam.xcam.data.Constants.KEY_ROTATION_ANGLE
import com.zkincamcam.xcam.data.Constants.KEY_MEDIA_PATH
import com.zkincamcam.xcam.data.Constants.PREFS_NAME
import com.zkincamcam.xcam.data.Constants.KEY_LATEST_PIPELINE_APP
import com.zkincamcam.xcam.data.Constants.KEY_LATEST_PIPELINE_ROUTE
import com.zkincamcam.xcam.data.Constants.KEY_LATEST_PIPELINE_TIME
import com.zkincamcam.xcam.data.Constants.METHOD_RECORD_NODE
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.content.edit
import java.io.File
import java.io.FileNotFoundException

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (method == METHOD_RECORD_NODE) {
            prefs.edit {
                putString(KEY_LATEST_PIPELINE_APP, arg ?: "Unknown")
                putString(KEY_LATEST_PIPELINE_ROUTE, extras?.getString("route") ?: "")
                putLong(KEY_LATEST_PIPELINE_TIME, System.currentTimeMillis())
            }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs?.getString(KEY_MEDIA_PATH, "") ?: ""
        val fileName = if (path.isNotEmpty()) File(path).name else "media"
        
        val cursor = MatrixCursor(arrayOf(KEY_MEDIA_PATH, KEY_IS_ENABLED, KEY_IS_MIRRORED, KEY_ROTATION_ANGLE))
        val videoUri = "content://$AUTHORITY/$fileName"
        
        cursor.addRow(arrayOf(
            videoUri,
            "1",
            if (prefs?.getBoolean(KEY_IS_MIRRORED, false) == true) "1" else "0",
            (prefs?.getInt(KEY_ROTATION_ANGLE, 0) ?: 0).toString()
        ))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Virtual media provider is read-only")
        }
        val filesDirectory = context?.filesDir
            ?: throw FileNotFoundException("Application storage is unavailable")
        val fileName = uri.lastPathSegment
            ?: throw FileNotFoundException("Virtual media name is missing")
        val file = try {
            VirtualMediaFileResolver.resolve(filesDirectory, fileName)
        } catch (e: Exception) {
            throw FileNotFoundException("Invalid virtual media path: ${e.message}")
        } ?: throw FileNotFoundException("Virtual media does not exist: $fileName")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val extension = uri.path?.substringAfterLast('.', "")?.lowercase() ?: ""
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        
        return when {
            mimeType?.startsWith("image/") == true -> mimeType
            mimeType?.startsWith("video/") == true -> mimeType
            else -> "video/mp4"
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
