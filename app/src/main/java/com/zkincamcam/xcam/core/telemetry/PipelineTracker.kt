package com.zkincamcam.xcam.core.telemetry

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import com.zkincamcam.xcam.data.Constants.AUTHORITY
import com.zkincamcam.xcam.data.Constants.METHOD_RECORD_NODE
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Tracks the hook pipeline nodes executed by the target application process
 * and reports telemetry to xCam's SettingsProvider.
 */
object PipelineTracker {
    private val activeNodes = Collections.synchronizedSet(LinkedHashSet<String>())
    private val executor = Executors.newSingleThreadExecutor()

    fun reset() {
        activeNodes.clear()
    }

    fun recordNode(context: Context?, node: String) {
        if (activeNodes.add(node)) {
            val ctx = context ?: return
            executor.execute {
                try {
                    val extras = Bundle().apply {
                        putString("node", node)
                        putString("route", getRouteSummary())
                    }
                    ctx.contentResolver.call(
                        "content://$AUTHORITY".toUri(),
                        METHOD_RECORD_NODE,
                        ctx.packageName,
                        extras
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    fun getRouteSummary(): String {
        synchronized(activeNodes) {
            return if (activeNodes.isEmpty()) "Default" else activeNodes.joinToString(" ➔ ")
        }
    }
}
