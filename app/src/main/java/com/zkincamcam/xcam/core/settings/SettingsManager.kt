package com.zkincamcam.xcam.core.settings

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.zkincamcam.xcam.data.Constants.AUTHORITY

class SettingsManager {
    var mediaPath: String? = null
    var isMirrored = false
    var rotationAngle = 0

    fun refreshSettings(context: Context) {
        try {
            val uri = "content://$AUTHORITY".toUri()
            val cursor = context.contentResolver.query(uri, null, null, null, null)
                ?: throw IllegalStateException("Settings provider returned a null cursor")
            cursor.use {
                if (!it.moveToFirst()) {
                    throw IllegalStateException("Settings provider returned no settings row")
                }
                mediaPath = it.getString(0)
                isMirrored = it.getString(2) == "1"
                rotationAngle = it.getString(3).toIntOrNull()
                    ?: throw IllegalStateException("Settings provider returned an invalid rotation angle")
            }
        } catch (e: Exception) {
            Log.e("SettingsManager", "Could not refresh settings; retaining previous values", e)
        }
    }
}
