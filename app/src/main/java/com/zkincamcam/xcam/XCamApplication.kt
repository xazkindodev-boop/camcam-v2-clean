package com.zkincamcam.xcam

import android.app.Application
import android.content.Context

/**
 * Application holder so pure singletons (e.g. [com.zkincamcam.xcam.core.capture.FaceLandmarkAnalyzer])
 * can obtain a Context for native engine initialisation without plumbing one through
 * every call site. Registered in AndroidManifest.xml.
 */
class XCamApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this
    }

    companion object {
        @JvmStatic
        lateinit var appContext: Context
            private set
    }
}
