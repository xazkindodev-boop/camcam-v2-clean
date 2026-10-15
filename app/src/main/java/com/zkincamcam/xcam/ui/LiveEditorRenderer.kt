package com.zkincamcam.xcam.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.TextureView
import com.zkincamcam.xcam.core.capture.FaceLandmarkAnalyzer
import com.zkincamcam.xcam.core.capture.LivenessShaders
import com.zkincamcam.xcam.core.capture.LivenessUniforms
import com.zkincamcam.xcam.core.capture.MediaPreprocessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class LiveEditorRenderer : TextureView.SurfaceTextureListener {

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var programId = 0
    private var textureId = 0
    private var lutTextureId = 0
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    @Volatile private var currentBitmap: Bitmap? = null
    @Volatile private var currentLut: Bitmap? = null
    private var uploadedLutBitmap: Bitmap? = null
    private var adj = MediaPreprocessor.ColorAdjustments()
    private var landmarks = FaceLandmarkAnalyzer.EyeLandmarks(0.5f, 0.5f, 0.5f, 0.5f, false)

    @Volatile private var isLiveness = false
    @Volatile private var beautyIntensity = 0f
    @Volatile private var beautyPreset: com.zkincamcam.xcam.core.capture.BeautyEnginePreset =
        com.zkincamcam.xcam.core.capture.BeautyEnginePreset.DEFAULT
    @Volatile private var catchlightOverride: Float? = null
    @Volatile private var hdLevel = 0f
    private var maskTextureId = 0
    private var maskBitmapSource: android.graphics.Bitmap? = null  // weak cache guard
    private var maskLandmarksRef: Any? = null
    @Volatile private var noiseIntensity = 0.5f
    @Volatile private var pulseIntensity = 0.5f
    @Volatile private var sharpness = 0f
    @Volatile private var startTime = System.currentTimeMillis()
    @Volatile private var transformMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    fun updateSettings(b: Bitmap?, l: Bitmap?, a: MediaPreprocessor.ColorAdjustments, lm: FaceLandmarkAnalyzer.EyeLandmarks, liv: Boolean, noise: Float, pulse: Float, sharpness: Float, beauty: Float = 0f, preset: com.zkincamcam.xcam.core.capture.BeautyEnginePreset? = null, catchlight: Float? = null, hd: Float = 0f) {
        if (liv && !isLiveness) startTime = System.currentTimeMillis()
        currentBitmap = b; currentLut = l; adj = a; landmarks = lm; isLiveness = liv; noiseIntensity = noise; pulseIntensity = pulse; this.sharpness = sharpness
        beautyIntensity = beauty
        if (preset != null) beautyPreset = preset
        catchlightOverride = catchlight
        hdLevel = hd
        requestRender()
    }

    fun updateTransform(m: FloatArray) { System.arraycopy(m, 0, transformMatrix, 0, 16); requestRender() }
    private fun requestRender() { renderHandler?.post { draw() } }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        renderThread = HandlerThread("EditorRenderer").apply { start() }
        renderHandler = Handler(renderThread!!.looper)
        renderHandler?.post { initEGL(st); initGL(); startAnimationLoop() }
    }

    private fun startAnimationLoop() {
        renderHandler?.post(object : Runnable {
            override fun run() { if (isLiveness) { draw() }; renderHandler?.postDelayed(this, 16) }
        })
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        renderHandler?.post { releaseGL(); releaseEGL(); renderThread?.quitSafely() }
        return true
    }

    private fun initEGL(st: SurfaceTexture) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(eglDisplay, v, 0, v, 1)
        val conf = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1); EGL14.eglChooseConfig(eglDisplay, conf, 0, configs, 0, 1, n, 0)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun initGL() {
        // Parity: both pipelines compile the SAME shader sources (see LivenessShaders).
        val vs = LivenessShaders.VERTEX
        val fs = LivenessShaders.FRAGMENT

        programId = createProgram(vs, fs)
        val mesh = LiveEditorMesh.buildTriangleStrips(GRID_RESOLUTION)
        vertexBuffer = ByteBuffer.allocateDirect(mesh.positions.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(mesh.positions).apply { position(0) }
        texCoordBuffer = ByteBuffer.allocateDirect(mesh.texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(mesh.texCoords).apply { position(0) }
    }

    private fun draw() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val bitmap = currentBitmap
        if (bitmap == null) {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            return
        }
        GLES20.glUseProgram(programId)
        if (textureId == 0) {
            val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0); textureId = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val lutBitmap = currentLut
        if (lutBitmap != null) {
            if (lutTextureId == 0) {
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                lutTextureId = tex[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            }
            if (uploadedLutBitmap !== lutBitmap) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, lutBitmap, 0)
                uploadedLutBitmap = lutBitmap
            }
        } else if (lutTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            lutTextureId = 0
            uploadedLutBitmap = null
        }

        // Skin mask for the beauty pass — rebuilt only when landmarks change.
        val lm = landmarks
        if (com.zkincamcam.xcam.core.capture.LivenessMeshMapper.isComplete(lm.meshPoints, lm.meshIndices) && lm !== maskLandmarksRef) {
            val mw = 128; val mh = 128
            val bytes = com.zkincamcam.xcam.core.capture.SkinMaskGenerator.generate(
                lm.meshPoints!!, lm.meshIndices!!, mw, mh,
                exclusionPoints = com.zkincamcam.xcam.core.capture.SkinMaskGenerator.eyeExclusions(lm),
                exclusionRadius = 0.045f
            )
            val colors = IntArray(mw * mh) { i ->
                val v = bytes[i].toInt() and 0xFF
                (v shl 16) or (v shl 8) or v or (0xFF shl 24)
            }
            val maskBmp = android.graphics.Bitmap.createBitmap(colors, mw, mh, android.graphics.Bitmap.Config.ARGB_8888)
            if (maskTextureId == 0) { val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0); maskTextureId = tex[0] }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, maskBmp, 0)
            maskBmp.recycle()
            maskLandmarksRef = lm
        }

        // Bind mask texture to unit 2 (unit 0 = source, unit 1 = LUT).
        val useMask = if (maskTextureId != 0) 1f else 0f
        if (maskTextureId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, LivenessUniforms.MASK), 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.USE_MASK), useMask)

        val posH = GLES20.glGetAttribLocation(programId, "a_position").also { GLES20.glEnableVertexAttribArray(it); GLES20.glVertexAttribPointer(it, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer) }
        val texH = GLES20.glGetAttribLocation(programId, "a_texCoord").also { GLES20.glEnableVertexAttribArray(it); GLES20.glVertexAttribPointer(it, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer) }
        val t = (System.currentTimeMillis() - startTime) * 0.001
        val animatedMatrix = com.zkincamcam.xcam.core.capture.LivenessAnimation.applyMotion(
            transformMatrix,
            t,
            isLiveness,
        )
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programId, LivenessUniforms.MATRIX), 1, false, animatedMatrix, 0)
        val blink = com.zkincamcam.xcam.core.capture.LivenessAnimation.blinkAmount(t, isLiveness)
        
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.TIME), t.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.BLINK), blink)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, LivenessUniforms.LEFT_EYE), landmarks.leftEyeX, 1f - landmarks.leftEyeY)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, LivenessUniforms.RIGHT_EYE), landmarks.rightEyeX, 1f - landmarks.rightEyeY)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.EYES_DETECTED), if (landmarks.eyeLandmarksDetected) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.EXPOSURE), adj.exposure); GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.CONTRAST), adj.contrast)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.HIGHLIGHTS), adj.highlights)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.SHADOWS), adj.shadows)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.TEMPERATURE), adj.temperature)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.TINT), adj.tint)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.SATURATION), adj.saturation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.USE_LUT), if (lutTextureId != 0) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.BEAUTY), beautyIntensity)
        val bp = beautyPreset
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.BEAUTY_THETA), bp.theta)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.SMOOTH_CEILING), bp.smoothCeiling)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.RESTORE_GAIN), bp.restoreGain)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.WHITEN), bp.whitening)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.CATCHLIGHT_BOOST), com.zkincamcam.xcam.core.capture.CatchlightControl(catchlightOverride).resolve(bp.catchlightBoost))
        val hd = com.zkincamcam.xcam.core.capture.HdEnhance.params(hdLevel)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.HD_LEVEL), hdLevel)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, LivenessUniforms.HD_RADIUS), hd.radius, hd.radius * 3f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.NOISE), noiseIntensity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.PULSE), pulseIntensity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.SHARPNESS), sharpness.coerceIn(0f, 5f) * 0.15f)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(programId, LivenessUniforms.TEXEL_SIZE),
            1f / bitmap.width,
            1f / bitmap.height
        )

        if (lutTextureId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, LivenessUniforms.LUT), 1)
        }

        for (row in 0 until GRID_RESOLUTION - 1) {
            GLES20.glDrawArrays(
                GLES20.GL_TRIANGLE_STRIP,
                row * GRID_RESOLUTION * 2,
                GRID_RESOLUTION * 2
            )
        }
        GLES20.glDisableVertexAttribArray(posH); GLES20.glDisableVertexAttribArray(texH); EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun createProgram(vs: String, fs: String): Int {
        val v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { GLES20.glShaderSource(it, vs); GLES20.glCompileShader(it) }
        val f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { GLES20.glShaderSource(it, fs); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { GLES20.glAttachShader(it, v); GLES20.glAttachShader(it, f); GLES20.glLinkProgram(it) }
    }

    private fun releaseGL() { if (programId != 0) GLES20.glDeleteProgram(programId); if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0); if (lutTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0); uploadedLutBitmap = null }
    private fun releaseEGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface); EGL14.eglDestroyContext(eglDisplay, eglContext); EGL14.eglTerminate(eglDisplay)
        }
    }

    private companion object {
        const val GRID_RESOLUTION = 32
    }
}
