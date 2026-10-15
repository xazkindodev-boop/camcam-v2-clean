package com.zkincamcam.xcam.core.capture

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LivenessShaderCompileDeviceTest {

    @Test
    fun vertexAndFragmentShadersCompileOnDevice() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue("Could not get EGL display", display != EGL14.EGL_NO_DISPLAY)
        val versions = IntArray(2)
        assertTrue("Could not initialize EGL", EGL14.eglInitialize(display, versions, 0, versions, 1))

        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            assertTrue(
                "Could not choose an ES2 EGL config",
                EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, configs.size, configCount, 0),
            )
            assertTrue("No ES2 EGL config available", configCount[0] > 0)
            val config = configs[0] ?: error("EGL returned a null config")

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            assertTrue("Could not create an ES2 EGL context", context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            assertTrue("Could not create an EGL pbuffer surface", surface != EGL14.EGL_NO_SURFACE)
            assertTrue("Could not make EGL context current", EGL14.eglMakeCurrent(display, surface, surface, context))
            val shaders = listOf(
                compileShader(GLES20.GL_VERTEX_SHADER, LivenessShaders.VERTEX),
                compileShader(GLES20.GL_FRAGMENT_SHADER, LivenessShaders.FRAGMENT),
            )
            val program = GLES20.glCreateProgram()
            assertTrue("Could not allocate GL program", program != 0)
            try {
                shaders.forEach { GLES20.glAttachShader(program, it) }
                GLES20.glLinkProgram(program)
                val status = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                assertEquals("GL program linking failed: ${GLES20.glGetProgramInfoLog(program)}", 1, status[0])
            } finally {
                GLES20.glDeleteProgram(program)
                shaders.forEach(GLES20::glDeleteShader)
            }
        } finally {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        assertTrue("Could not allocate GL shader", shader != 0)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != 1) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw AssertionError("GL shader compilation failed: $log")
        }
        return shader
    }
}
