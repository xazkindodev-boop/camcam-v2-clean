package com.zkincamcam.xcam.core.capture

import com.zkincamcam.xcam.data.Constants.MAX_STREAM_FRAME_MS
import com.zkincamcam.xcam.data.Constants.MIN_STREAM_FRAME_MS
import com.zkincamcam.xcam.data.Constants.STREAM_CADENCE_HEADROOM

/**
 * Decision state machine for the virtual-video stream: decides WHEN a new frame extraction
 * may start. It owns no bitmap and no thread — CaptureManager remains the owner of both.
 *
 * Rules carried over verbatim from the original CaptureManager logic:
 *  - a different stream key (path|size|rotation|mirror) restarts pacing and is reported
 *    to the caller so it can retire the stale frame;
 *  - at most one extraction runs at a time;
 *  - the interval between extractions adapts to the measured decode cost (see
 *    [nextStreamDelayMs]), bounded by MIN/MAX_STREAM_FRAME_MS.
 */
internal class StreamPacer {

    /** Result of a frame request: whether to start an extraction, and why the state changed. */
    data class Decision(val start: Boolean, val keyChanged: Boolean)

    private var key: String? = null
    private var lastRequestedAtMs = 0L
    private var intervalMs = MIN_STREAM_FRAME_MS
    private var extractionRunning = false

    fun onFrameRequested(newKey: String, nowMs: Long): Decision {
        val keyChanged = key != newKey
        if (keyChanged) {
            key = newKey
            lastRequestedAtMs = 0L
            extractionRunning = false
        }

        val due = !extractionRunning && nowMs - lastRequestedAtMs >= intervalMs
        if (due) {
            extractionRunning = true
            lastRequestedAtMs = nowMs
        }
        return Decision(start = due, keyChanged = keyChanged)
    }

    /** Call when the running extraction completes; [measuredDurationMs] re-paces the cadence. */
    fun onExtractionFinished(measuredDurationMs: Long) {
        intervalMs = nextStreamDelayMs(measuredDurationMs)
        extractionRunning = false
    }

    /** Source changed on the outside (e.g. new virtual.mp4) — forget everything, keep cadence bounds. */
    fun reset() {
        key = null
        lastRequestedAtMs = 0L
        extractionRunning = false
        intervalMs = MIN_STREAM_FRAME_MS
    }
}

/**
 * Paces frame production from the cost of the previous extraction, so a heavy decoder settles at
 * the highest cadence it can sustain instead of a fixed guess.
 */
internal fun nextStreamDelayMs(measuredDurationMs: Long): Long =
    (measuredDurationMs * STREAM_CADENCE_HEADROOM).coerceIn(MIN_STREAM_FRAME_MS, MAX_STREAM_FRAME_MS)
