package com.zkincamcam.xcam.core.capture

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Ultimate God-Tier Completion Shader.
 * Combines 3D Vertex Mesh, rPPG Bio-Pulse, Poisson-Gaussian Physical Noise,
 * Subsurface Scattering (SSS) Emulation, AI Portrait Beauty Pass,
 * and 20MP Eye Liveliness Catchlight Enhancement into one engine.
 */
@OptIn(UnstableApi::class)
class DeepLivenessShader(
    private val landmarks: FaceLandmarkAnalyzer.EyeLandmarks,
    private val adj: MediaPreprocessor.ColorAdjustments = MediaPreprocessor.ColorAdjustments(),
    private val lutBitmap: Bitmap? = null,
    private val beautyLevel: Float = 0f,
    private val externalMatrix: FloatArray? = null,
    private val noiseLevel: Float = 0.5f,
    private val pulseLevel: Float = 0.5f,
    private val sharpnessLevel: Float = 0f,
    val beautyPreset: BeautyEnginePreset = BeautyEnginePreset.DEFAULT,
    private val catchlightOverride: Float? = null,
    private val hdLevel: Float = 0f,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHighPrecisionColor: Boolean): GlShaderProgram {
        return LivenessShaderProgram(landmarks, adj, lutBitmap, beautyLevel, externalMatrix, noiseLevel, pulseLevel, sharpnessLevel, beautyPreset, catchlightOverride, hdLevel)
    }

    private class LivenessShaderProgram(
        private val landmarks: FaceLandmarkAnalyzer.EyeLandmarks,
        private val adj: MediaPreprocessor.ColorAdjustments,
        private val lut: Bitmap?,
        private val beauty: Float,
        private val extMatrix: FloatArray?,
        private val noiseIntensity: Float,
        private val pulseIntensity: Float,
        private val sharpness: Float,
        val beautyPreset: BeautyEnginePreset = BeautyEnginePreset.DEFAULT,
        private val catchlightOverride: Float? = null,
    private val hdLevel: Float = 0f,
    ) : GlShaderProgram {
        private var errorListener: GlShaderProgram.ErrorListener? = null
        private var errorListenerExecutor: Executor? = null
        private var inputListener: GlShaderProgram.InputListener? = null
        private var outputListener: GlShaderProgram.OutputListener? = null

        private var programId = 0
        private var lutTextureId = 0

        private val vertexBuffer: FloatBuffer
        private val texCoordBuffer: FloatBuffer
        private val indexBuffer: IntBuffer?

        private data class OutputBuffers(
            val textureId: Int,
            val framebufferId: Int,
            val width: Int,
            val height: Int
        )

        private val MAX_BUFFER_COUNT = 3
        private var allocatedBufferCount = 0
        private val availableOutputBuffers = ArrayDeque<OutputBuffers>()
        private val inUseOutputBuffers = mutableMapOf<Int, OutputBuffers>()

        private val vertexShaderCode = LivenessShaders.VERTEX

        private val fragmentShaderCode = LivenessShaders.FRAGMENT

        init {
            val hasMesh = LivenessMeshMapper.isComplete(landmarks.meshPoints, landmarks.meshIndices)
            if (hasMesh) {
                val vbo = landmarks.meshPoints!!; val ibo = landmarks.meshIndices!!
                // Use the exact same coordinate convention as the preview (see LivenessMeshMapper);
                // mismatched spaces used to send blink/catchlight to the mirrored face feature.
                vertexBuffer = ByteBuffer.allocateDirect(vbo.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(LivenessMeshMapper.toClipSpace(vbo)).apply { position(0) }
                texCoordBuffer = ByteBuffer.allocateDirect((vbo.size / 3 * 2) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(LivenessMeshMapper.toTexCoords(vbo)).apply { position(0) }
                indexBuffer = ByteBuffer.allocateDirect(ibo.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(ibo).apply { position(0) }
            } else {
                // Fallback quad: reuse the exact same mesh builder as the preview renderer so both
                // pipelines share one coordinate convention.
                val mesh = com.zkincamcam.xcam.ui.LiveEditorMesh.buildTriangleStrips(32)
                vertexBuffer = ByteBuffer.allocateDirect(mesh.positions.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(mesh.positions).apply { position(0) }
                texCoordBuffer = ByteBuffer.allocateDirect(mesh.texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(mesh.texCoords).apply { position(0) }
                indexBuffer = null
            }
        }

        private var maskTextureId = 0

        /** Builds and uploads the skin mask once from the analyzed landmarks. */
        private fun ensureMaskTexture() {
            if (maskTextureId != 0) return
            val lm = landmarks
            if (!LivenessMeshMapper.isComplete(lm.meshPoints, lm.meshIndices)) return
            val mw = 128; val mh = 128
            val bytes = SkinMaskGenerator.generate(
                lm.meshPoints!!, lm.meshIndices!!, mw, mh,
                exclusionPoints = SkinMaskGenerator.eyeExclusions(lm),
                exclusionRadius = 0.045f
            )
            val colors = IntArray(mw * mh) { i ->
                val v = bytes[i].toInt() and 0xFF
                (v shl 16) or (v shl 8) or v or (0xFF shl 24)
            }
            val bmp = android.graphics.Bitmap.createBitmap(colors, mw, mh, android.graphics.Bitmap.Config.ARGB_8888)
            val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0); maskTextureId = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
        }

        private fun initGl() {
            if (programId != 0) return
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            val candidateProgram = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
            }
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(candidateProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(candidateProgram)
                GLES20.glDeleteProgram(candidateProgram)
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
                throw IllegalStateException("Unable to link liveness shader: $log")
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            programId = candidateProgram
            if (lut != null && lutTextureId == 0) {
                val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0); lutTextureId = tex[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, lut, 0)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            }
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("Unable to compile liveness shader: $log")
            }
            return shader
        }

        override fun queueInputFrame(gop: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            var outputBuffers: OutputBuffers? = null
            try {
                initGl()
                outputBuffers = acquireOutputBuffers(inputTexture.width, inputTexture.height)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputBuffers.framebufferId)
                GLES20.glViewport(0, 0, outputBuffers.width, outputBuffers.height)
                GLES20.glUseProgram(programId)
                val t = presentationTimeUs * 0.000001f; val f0 = 0.9817477f
                val blink = if (Math.sin(t.toDouble() * f0 * 3.0).toFloat() > 0.9f) (Math.sin(t.toDouble() * f0 * 3.0).toFloat() - 0.9f) * 10.0f else 0.0f
                // TIME convention: LivenessShaders.FRAGMENT expects SECONDS.
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.TIME), t)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.BLINK), blink)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, LivenessUniforms.LEFT_EYE), landmarks.leftEyeX, 1f - landmarks.leftEyeY)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, LivenessUniforms.RIGHT_EYE), landmarks.rightEyeX, 1f - landmarks.rightEyeY)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.EXPOSURE), adj.exposure); GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.CONTRAST), adj.contrast)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.HIGHLIGHTS), adj.highlights)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.SHADOWS), adj.shadows)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.TEMPERATURE), adj.temperature)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.TINT), adj.tint)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.SATURATION), adj.saturation)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.BEAUTY), beauty)
                val bp = beautyPreset
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.BEAUTY_THETA), bp.theta)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.SMOOTH_CEILING), bp.smoothCeiling)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.RESTORE_GAIN), bp.restoreGain)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.WHITEN), bp.whitening)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.CATCHLIGHT_BOOST), CatchlightControl(catchlightOverride).resolve(bp.catchlightBoost))
        val hd = HdEnhance.params(hdLevel)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.HD_LEVEL), hdLevel)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, LivenessUniforms.HD_RADIUS), hd.radius, hd.radius * 3f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.SHARPNESS), sharpness.coerceIn(0f, 5f) * 0.15f)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(programId, LivenessUniforms.TEXEL_SIZE), 1f / inputTexture.width, 1f / inputTexture.height)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.NOISE), noiseIntensity)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.PULSE), pulseIntensity)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.USE_LUT), if (lutTextureId != 0) 1.0f else 0.0f)
                if (lutTextureId != 0) { GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId); GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, LivenessUniforms.LUT), 1) }
                // Skin mask on unit 2 — same generator as the preview, so parity holds.
                ensureMaskTexture()
                GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, LivenessUniforms.USE_MASK), if (maskTextureId != 0) 1.0f else 0.0f)
                if (maskTextureId != 0) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, LivenessUniforms.MASK), 2)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                }
                val finalMatrix = FloatArray(16); if (extMatrix != null) System.arraycopy(extMatrix, 0, finalMatrix, 0, 16) else Matrix.setIdentityM(finalMatrix, 0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programId, LivenessUniforms.MATRIX), 1, false, finalMatrix, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture.texId)
                val posH = GLES20.glGetAttribLocation(programId, "a_position").also { GLES20.glEnableVertexAttribArray(it); GLES20.glVertexAttribPointer(it, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer) }
                val texH = GLES20.glGetAttribLocation(programId, "a_texCoord").also { GLES20.glEnableVertexAttribArray(it); GLES20.glVertexAttribPointer(it, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer) }
                if (indexBuffer != null) GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexBuffer.capacity(), GLES20.GL_UNSIGNED_INT, indexBuffer)
                else {
                    // LiveEditorMesh emits (resolution-1) triangle strips, each of `resolution*2`
                    // vertices, in row-major order — draw them exactly like the preview does.
                    val res = 32
                    for (i in 0 until res - 1) GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, i * res * 2, res * 2)
                }
                GLES20.glDisableVertexAttribArray(posH); GLES20.glDisableVertexAttribArray(texH); GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                val output = GlTextureInfo(
                    outputBuffers.textureId,
                    outputBuffers.framebufferId,
                    GLES20.GL_NONE,
                    outputBuffers.width,
                    outputBuffers.height
                )
                inUseOutputBuffers[output.texId] = outputBuffers
                inputListener?.onInputFrameProcessed(inputTexture)
                outputListener?.onOutputFrameAvailable(output, presentationTimeUs)
                inputListener?.onReadyToAcceptInputFrame()
            } catch (e: Exception) {
                outputBuffers?.let(availableOutputBuffers::addLast)
                errorListenerExecutor?.execute {
                    errorListener?.onError(VideoFrameProcessingException(e, presentationTimeUs))
                }
            }
        }

        private fun acquireOutputBuffers(width: Int, height: Int): OutputBuffers {
            val reusable = availableOutputBuffers.firstOrNull { it.width == width && it.height == height }
            if (reusable != null) {
                availableOutputBuffers.remove(reusable)
                return reusable
            }

            if (allocatedBufferCount >= MAX_BUFFER_COUNT && inUseOutputBuffers.isNotEmpty()) {
                // Reuse the oldest in-flight buffer only when nothing is actively reading it;
                // the pool size (3) normally guarantees a free buffer exists by this point.
                val oldestTexId = inUseOutputBuffers.keys.first()
                val reused = inUseOutputBuffers.remove(oldestTexId)
                if (reused != null && reused.width == width && reused.height == height) return reused
                GLES20.glDeleteTextures(1, intArrayOf(reused!!.textureId), 0)
                GLES20.glDeleteFramebuffers(1, intArrayOf(reused.framebufferId), 0)
                allocatedBufferCount--
            }

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val framebuffers = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffers, 0)
            val framebufferId = framebuffers[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                textureId,
                0
            )
            allocatedBufferCount++
            return OutputBuffers(textureId, framebufferId, width, height)
        }

        private fun releaseOutputBuffers() {
            val buffers = availableOutputBuffers + inUseOutputBuffers.values
            buffers.forEach {
                GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0)
                GLES20.glDeleteFramebuffers(1, intArrayOf(it.framebufferId), 0)
            }
            availableOutputBuffers.clear()
            inUseOutputBuffers.clear()
            allocatedBufferCount = 0
        }

        override fun setInputListener(il: GlShaderProgram.InputListener) {
            inputListener = il
            il.onReadyToAcceptInputFrame()
        }
        override fun setOutputListener(ol: GlShaderProgram.OutputListener) { outputListener = ol }
        override fun setErrorListener(ex: Executor, el: GlShaderProgram.ErrorListener) { errorListenerExecutor = ex; errorListener = el }
        override fun releaseOutputFrame(glTextureInfo: GlTextureInfo) {
            inUseOutputBuffers.remove(glTextureInfo.texId)?.let(availableOutputBuffers::addLast)
        }
        override fun signalEndOfCurrentInputStream() { outputListener?.onCurrentOutputStreamEnded() }
        override fun flush() {
            releaseOutputBuffers()
            inputListener?.onFlush()
            inputListener?.onReadyToAcceptInputFrame()
        }
        override fun release() { releaseOutputBuffers(); if (programId != 0) GLES20.glDeleteProgram(programId); if (lutTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0) }
    }
}
