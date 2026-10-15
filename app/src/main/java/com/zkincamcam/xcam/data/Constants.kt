package com.zkincamcam.xcam.data
object Constants {
    const val PREFS_NAME = "xcam_prefs"
    const val KEY_MEDIA_PATH = "media_path"
    const val KEY_IS_ENABLED = "is_enabled"
    const val KEY_IS_MIRRORED = "is_mirrored"
    const val KEY_ROTATION_ANGLE = "rotation_angle"
    const val AUTHORITY = "com.zkincamcam.xcam.provider"

    // Engine Configurations
    const val DEFAULT_CAPTURE_WIDTH = 1280
    const val DEFAULT_CAPTURE_HEIGHT = 1280
    const val DUMMY_SURFACE_TEXTURE_ID = 999
    const val MIN_SESSION_DEBOUNCE_MS = 100L
    const val MIN_STREAM_FRAME_MS = 33L
    const val MAX_STREAM_FRAME_MS = 200L
    const val STREAM_CADENCE_HEADROOM = 2L
    const val VIDEO_SEEK_GUARD_MS = 120L

    // Telemetry (pipeline tracker)
    const val KEY_LATEST_PIPELINE_APP = "latest_pipeline_app"
    const val KEY_LATEST_PIPELINE_ROUTE = "latest_pipeline_route"
    const val KEY_LATEST_PIPELINE_TIME = "latest_pipeline_time"
    const val METHOD_RECORD_NODE = "record_node"
}
