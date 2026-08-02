package com.karin.streamtv.player

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class EnhancedGlSurfaceView(context: android.content.Context) : GLSurfaceView(context) {

    private var renderer: EnhancedRenderer? = null

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun getEnhancedRenderer(): EnhancedRenderer {
        if (renderer == null) {
            renderer = EnhancedRenderer()
            setRenderer(renderer!!)
        }
        return renderer!!
    }

    class EnhancedRenderer : Renderer {

        private var program = 0
        private var texId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var outputSurface: Surface? = null
        private var frameAvailable = false
        private val frameLock = Object()

        private var texMatrixLoc = -1
        private var saturationLoc = -1
        private var contrastLoc = -1
        private var brightnessLoc = -1
        private var sharpnessLoc = -1
        private var colorBoostLoc = -1
        private var texelSizeLoc = -1
        private var enabledLoc = -1

        private var viewWidth = 0
        private var viewHeight = 0
        private var videoWidth = 1920
        private var videoHeight = 1080

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
            .put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
            .also { it.position(0) }

        private val quadTexCoords: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(floatArrayOf(0f,1f, 1f,1f, 0f,0f, 1f,0f))
            .also { it.position(0) }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            texId = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            surfaceTexture = SurfaceTexture(texId)
            surfaceTexture!!.setOnFrameAvailableListener({
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            })

            program = createProgram(vertexShaderCode, fragmentShaderCode)
            texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            saturationLoc = GLES20.glGetUniformLocation(program, "uSaturation")
            contrastLoc = GLES20.glGetUniformLocation(program, "uContrast")
            brightnessLoc = GLES20.glGetUniformLocation(program, "uBrightness")
            sharpnessLoc = GLES20.glGetUniformLocation(program, "uSharpness")
            colorBoostLoc = GLES20.glGetUniformLocation(program, "uColorBoost")
            texelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
            enabledLoc = GLES20.glGetUniformLocation(program, "uEnabled")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width
            viewHeight = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            synchronized(frameLock) {
                if (frameAvailable) {
                    surfaceTexture?.updateTexImage()
                    frameAvailable = false
                }
            }

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
            GLES20.glUniform2f(texelSizeLoc, 1f / videoWidth, 1f / videoHeight)
            GLES20.glUniform1f(enabledLoc, if (cfg.isEnabled()) 1f else 0f)

            val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(posHandle)
            GLES20.glDisableVertexAttribArray(texHandle)
        }

        fun getInputSurface(): Surface? {
            return surfaceTexture?.let { Surface(it) }
        }

        fun setVideoSize(w: Int, h: Int) {
            videoWidth = w
            videoHeight = h
        }

        fun release() {
            surfaceTexture?.release()
            surfaceTexture = null
            outputSurface?.release()
            outputSurface = null
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e("EnhancedGL", "Link error: ${GLES20.glGetProgramInfoLog(prog)}")
                GLES20.glDeleteProgram(prog)
                return 0
            }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return prog
        }

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("EnhancedGL", "Compile error: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }
}
