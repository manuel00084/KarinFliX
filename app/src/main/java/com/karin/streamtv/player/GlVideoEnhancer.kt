package com.karin.streamtv.player

import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class GlVideoEnhancer {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var outputSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null

    private var texMatrixLoc = -1
    private var saturationLoc = -1
    private var contrastLoc = -1
    private var brightnessLoc = -1
    private var sharpnessLoc = -1
    private var colorBoostLoc = -1
    private var texelSizeLoc = -1
    private var enabledLoc = -1
    private var resolutionLoc = -1

    private var frameWidth = 1920
    private var frameHeight = 1080

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        uniform mat4 uTexMatrix;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #ifdef GL_ES
        precision mediump float;
        #endif
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform float uSaturation;
        uniform float uContrast;
        uniform float uBrightness;
        uniform float uSharpness;
        uniform float uColorBoost;
        uniform vec2 uTexelSize;
        uniform float uEnabled;
        uniform vec2 uResolution;

        vec3 adjustSaturation(vec3 color, float sat) {
            float grey = dot(color, vec3(0.2126, 0.7152, 0.0722));
            return mix(vec3(grey), color, sat);
        }

        vec3 adjustContrast(vec3 color, float contrast) {
            return (color - 0.5) * contrast + 0.5;
        }

        void main() {
            vec4 texColor = texture2D(uTexture, vTexCoord);
            vec3 color = texColor.rgb;
            if (uEnabled > 0.5) {
                color = adjustContrast(color, uContrast);
                color = adjustSaturation(color, uSaturation);
                color *= uColorBoost;
                color += uBrightness;
                vec2 texel = uTexelSize;
                vec3 center = texture2D(uTexture, vTexCoord).rgb;
                vec3 top    = texture2D(uTexture, vTexCoord + vec2(0.0, texel.y)).rgb;
                vec3 bottom = texture2D(uTexture, vTexCoord - vec2(0.0, texel.y)).rgb;
                vec3 left   = texture2D(uTexture, vTexCoord - vec2(texel.x, 0.0)).rgb;
                vec3 right  = texture2D(uTexture, vTexCoord + vec2(texel.x, 0.0)).rgb;
                vec3 sharpened = center + uSharpness * (4.0 * center - top - bottom - left - right);
                color = mix(color, sharpened, uSharpness);
                color = clamp(color, 0.0, 1.0);
            }
            gl_FragColor = vec4(color, texColor.a);
        }
    """.trimIndent()

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )).also { it.position(0) }

    private val quadTexCoords: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )).also { it.position(0) }

    private val identityMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    fun init(width: Int, height: Int, outputSurface: Surface): Boolean {
        this.outputSurface = outputSurface
        this.frameWidth = width
        this.frameHeight = height

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed")
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed")
            return false
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        if (numConfigs[0] == 0) {
            Log.e(TAG, "eglChooseConfig failed")
            return false
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed")
            return false
        }

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0]!!, outputSurface, surfaceAttribs, 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed")
            return false
        }

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        program = createProgram(vertexShaderCode, fragmentShaderCode)
        if (program == 0) {
            Log.e(TAG, "createProgram failed")
            return false
        }

        GLES20.glUseProgram(program)
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        saturationLoc = GLES20.glGetUniformLocation(program, "uSaturation")
        contrastLoc = GLES20.glGetUniformLocation(program, "uContrast")
        brightnessLoc = GLES20.glGetUniformLocation(program, "uBrightness")
        sharpnessLoc = GLES20.glGetUniformLocation(program, "uSharpness")
        colorBoostLoc = GLES20.glGetUniformLocation(program, "uColorBoost")
        texelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
        enabledLoc = GLES20.glGetUniformLocation(program, "uEnabled")
        resolutionLoc = GLES20.glGetUniformLocation(program, "uResolution")

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        Log.i(TAG, "GlVideoEnhancer initialized: ${width}x${height}")
        return true
    }

    fun createInputSurface(): SurfaceTexture {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(texIds[0])
        return surfaceTexture!!
    }

    fun drawFrame() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val texMatrix = FloatArray(16)
        surfaceTexture?.getTransformMatrix(texMatrix)

        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)

        val cfg = VideoEnhanceConfig
        GLES20.glUniform1f(saturationLoc, cfg.getSaturation())
        GLES20.glUniform1f(contrastLoc, cfg.getContrast())
        GLES20.glUniform1f(brightnessLoc, cfg.getBrightness())
        GLES20.glUniform1f(sharpnessLoc, cfg.getSharpness())
        GLES20.glUniform1f(colorBoostLoc, cfg.getColorBoost())
        GLES20.glUniform2f(texelSizeLoc, 1f / frameWidth, 1f / frameHeight)
        GLES20.glUniform1f(enabledLoc, if (cfg.isEnabled()) 1f else 0f)
        GLES20.glUniform2f(resolutionLoc, frameWidth.toFloat(), frameHeight.toFloat())

        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        Log.i(TAG, "GlVideoEnhancer released")
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return prog
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "GlVideoEnhancer"
    }
}
