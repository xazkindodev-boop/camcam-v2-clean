package com.zkincamcam.xcam.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.util.UnstableApi
import com.zkincamcam.xcam.core.capture.CaptureManager
import com.zkincamcam.xcam.core.capture.HookGuard
import com.zkincamcam.xcam.core.capture.YuvFrameProcessor
import com.zkincamcam.xcam.core.engine.MediaEngine
import com.zkincamcam.xcam.core.engine.XCamEngine
import com.zkincamcam.xcam.core.settings.SettingsManager
import com.zkincamcam.xcam.core.telemetry.PipelineTracker
import com.zkincamcam.xcam.core.surface.SurfaceManager
import com.zkincamcam.xcam.core.surface.SurfaceProvider
import com.zkincamcam.xcam.utils.Logger
import com.zkincamcam.xcam.utils.SystemUtils
import com.zkincamcam.xcam.utils.UIUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

@UnstableApi
class XCamModule : XposedModule() {
    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false
    private val ignoreHooks = ThreadLocal.withInitial { false }
    
    private val injectors = XCamInjectors(this)
    private val settings = SettingsManager()
    private val surfaceManager = SurfaceManager { printLog(it) }
    private val surfaceProvider = SurfaceProvider { printLog(it) }
    private val mediaEngine = MediaEngine { printLog(it) }
    private val yuvProcessor = YuvFrameProcessor(logAction = { printLog(it) })
    private var yuvMediaPath: String? = null
    
    private val captureManager = CaptureManager(
        contextProvider = { mContext },
        refreshSettingsAction = { settings.refreshSettings(it) },
        onBitmapRetired = { yuvProcessor.releaseWhenIdle(it) },
    ) { printLog(it) }
    
    private val engine = XCamEngine(
        contextProvider = { mContext },
        settingsProvider = { settings },
        surfaceManager = surfaceManager,
        mediaEngine = mediaEngine,
        surfaceProvider = surfaceProvider,
    ) { printLog(it) }

    fun isIgnoringHooks(): Boolean = HookGuard.isIgnoring()

    /** Java interop: run [block] with hooks ignored, restoring the guard even on throw. */
    fun <T> runIgnoringHooks(block: () -> T): T = HookGuard.withGuard(block)

    fun setIgnoringHooks(ignore: Boolean) {
        // Compatibility shim: callers outside CaptureManager still set a bare flag.
        // HookGuard owns restoration now; direct setters only exist for legacy call sites.
        if (ignore) HookGuard.enter() else HookGuard.exit()
    }

    val mediaPath: String? get() = settings.mediaPath
    fun getApplicationContextForHooks(): Context? = mContext?.applicationContext ?: mContext
    fun getPlaybackPositionMs(): Long = engine.getCurrentPosition()
    var previewSwapped: Boolean
        get() = surfaceManager.previewSwapped
        set(value) { surfaceManager.previewSwapped = value }

    fun printLog(msg: String, tr: Throwable? = null) {
        if (tr != null || (msg.contains("Error") || msg.contains("failed") || msg.contains("FATAL"))) {
            Logger.e(this, msg, tr)
        } else {
            Logger.i(this, msg)
        }
    }

    fun logInit(msg: String) = Logger.i(this, "[INIT] $msg")
    fun logHook(msg: String) = Logger.d(this, "[HOOK] $msg")

    fun showToast(message: String) = UIUtils.showToast(mContext, message) { printLog(it) }

    fun isCapturingState() = captureManager.isCapturing

    fun triggerCaptureState() {
        captureManager.triggerCaptureState { engine.getCurrentPosition() }
    }

    // Surface Management
    fun registerPreviewSurface(s: Surface) = surfaceManager.registerPreviewSurface(s)
    fun registerImageReaderSurface(s: Surface, f: Int, w: Int, h: Int) = surfaceManager.registerImageReaderSurface(s, f, w, h)
    fun unregisterImageReaderSurface(s: Surface) = surfaceManager.unregisterImageReaderSurface(s)
    fun isPreviewSurface(s: Surface?) = surfaceManager.isPreviewSurface(s)
    fun logSessionOutput(s: Surface) = surfaceManager.logSessionOutput(s)
    fun incrementSessionGeneration() = surfaceManager.incrementSessionGeneration()
    fun clearPreviewSurfaces() = surfaceManager.clearPreviewSurfaces(engine.isPlaying())

    // Frame Processing
    fun injectYuvFrame(image: android.media.Image, width: Int, height: Int): Boolean {
        if (isIgnoringHooks()) return false

        val path = mediaPath
        if (path == null) return false
        if (path != yuvMediaPath) {
            yuvMediaPath = path
            yuvProcessor.flush()
            captureManager.resetStream()
        }

        val frame = handleStreamFrame(width, height) ?: return false
        return yuvProcessor.injectFromBitmap(image, frame)
    }

    // Engine Delegation
    fun stopEngine() = engine.stop()
    fun handleCamera1Preview(st: SurfaceTexture) = engine.handleCamera1Preview(st)
    fun handleModernPreview(s: Surface) = engine.handleModernPreview(s)
    fun handleSurfaceViewPreview(h: SurfaceHolder) = engine.handleSurfaceViewPreview(h)
    fun getDummySurface() = engine.getDummySurface()

    // Capture Delegation
    fun recordPipelineNode(node: String) = PipelineTracker.recordNode(mContext, node)

    fun handleCapture(w: Int, h: Int): ByteArray? {
        recordPipelineNode("Capture(BitmapFactory)")
        return captureManager.handleCapture(
            settings.mediaPath, w, h, settings.rotationAngle, settings.isMirrored,
        )
    }

    fun handleStreamFrame(w: Int, h: Int): Bitmap? {
        recordPipelineNode("StreamFrame")
        return captureManager.handleStreamFrame(
            settings.mediaPath, w, h, settings.rotationAngle, settings.isMirrored,
            { engine.getCurrentPosition() },
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val processName = SystemUtils.getProcessNameStrict()
        if (param.packageName == "com.zkincamcam.xcam") {
            hookManagerApp(param)
            return
        }
        
        if (!processName.contains(param.packageName)) return
        if (hooksInstalled) return
        hooksInstalled = true

        incrementSessionGeneration()
        clearPreviewSurfaces()
        PipelineTracker.reset()
        logInit(">>> ACTIVE IN: $processName (API ${Build.VERSION.SDK_INT}) <<<")
        hookContextInit()
        injectors.install(param)
    }

    private fun hookManagerApp(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("com.zkincamcam.xcam.ui.MainActivity")
            hook(clazz.getDeclaredMethod("checkSelfActive")).intercept { true }
        } catch (_: Throwable) {}
    }

    private fun hookContextInit() {
        try {
            val attachMethod = Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context::class.java)

            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                if (!isInitialized) {
                    mContext = chain.thisObject as? Context
                    logInit("Context Initialized: ${mContext?.packageName}")
                    mContext?.let { settings.refreshSettings(it) }
                    isInitialized = true
                }
                result
            }
        } catch (e: Exception) {
            logInit("Context hook failure: ${e.message}")
        }
    }
}
