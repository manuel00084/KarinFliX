package com.karin.streamtv.player

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.karin.streamtv.player.dsp.DspRenderersFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private data class FrameMeta(val ptsUs: Long, val releaseNs: Long)

@UnstableApi
class Media3SixtyFpsProcessor(
    private val context: Context,
    private val glSurface: GLSurfaceView,
    private val referer: String = ""
) {
    private var player: ExoPlayer? = null
    var renderer: InterpolationRenderer? = null
        private set
    private var inputSurface: Surface? = null
    var onGlFailure: (() -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun createPlayer(trackSelector: DefaultTrackSelector? = null, dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null): ExoPlayer {
        val renderersFactory = DspRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(MediaCodecSelector.DEFAULT)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000,
                50000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector ?: DefaultTrackSelector(context))
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(dataSourceFactory ?: VideoDataSource.factory(referer))
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(android.os.PowerManager.PARTIAL_WAKE_LOCK)
            .build()

        player = exoPlayer
        renderer?.let { exoPlayer.setVideoFrameMetadataListener(it) }
        return exoPlayer
    }

    fun setupGlPipeline() {
        renderer = InterpolationRenderer(
            onSurfaceReady = { surface ->
                inputSurface = surface
                mainHandler.post {
                    player?.setVideoSurface(surface)
                    Log.i(TAG, "Player connected to GL 60fps pipeline")
                }
            },
            onGlFailure = { onGlFailure?.invoke() }
        )
        glSurface.setRenderer(renderer)
        glSurface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun connectPlayer(player: ExoPlayer) {
        this.player = player
        renderer?.let { player.setVideoFrameMetadataListener(it) }
        val surface = inputSurface
        if (surface != null) {
            player.setVideoSurface(surface)
            Log.i(TAG, "Player connected to GL 60fps pipeline")
        }
    }

    fun play(url: String) {
        player?.let {
            it.setMediaItem(MediaItem.fromUri(url))
            it.prepare()
            it.playWhenReady = true
            Log.i(TAG, "Playing: ${url.takeLast(60)}")
        }
    }

    fun isPipelineReady(): Boolean = renderer?.pipelineReady == true

    fun resyncSurface() {
        mainHandler.post {
            val p = player
            val s = inputSurface
            if (p != null && s != null) {
                try {
                    p.setVideoSurface(null)
                    p.setVideoSurface(s)
                    Log.i(TAG, "video surface resync triggered")
                } catch (t: Throwable) {
                    Log.w(TAG, "surface resync failed: ${t.message}")
                }
            }
        }
    }

    fun release() {
        renderer?.requestStop()
        val r = renderer
        val p = player
        val surface = inputSurface
        inputSurface = null
        if (r != null && glSurface.isAttachedToWindow) {
            glSurface.queueEvent {
                r.cleanupGl()
                mainHandler.post {
                    surface?.release()
                    p?.release()
                    player = null
                    renderer = null
                }
            }
        } else {
            surface?.release()
            p?.release()
            player = null
            renderer = null
        }
    }

    inner class InterpolationRenderer(
        private val onSurfaceReady: (Surface) -> Unit = {},
        private val onGlFailure: () -> Unit = {}
    ) : GLSurfaceView.Renderer, VideoFrameMetadataListener {

        private var program = 0
        private var motionProgram = 0
        private var staticProgram = 0

        private var inputTexId = 0
        private var inputSurfaceTexture: android.graphics.SurfaceTexture? = null
        private var cachedOutputSurface: Surface? = null

        private var prevTexId = 0
        private var prevFbo = 0
        private var prevReady = false

        private var motionTexId = 0
        private var motionAccumId = 0
        private var motionBwdId = 0
        private var motionBwdAccumId = 0
        private var motionFbo = 0
        private var motionW = 0
        private var motionH = 0
        private var motionAlpha = 0.6f

        private var staticTexId = 0
        private var staticFbo = 0
        private val staticPixelBuf = ByteBuffer.allocateDirect(16 * 16 * 4)

        private var globalProgram = 0
        private var globalTexId = 0
        private var globalFbo = 0
        private val globalBuf = ByteBuffer.allocateDirect(16 * 16 * 4)
        private val globalVec = FloatArray(2)
        private val globalXs = FloatArray(256)
        private val globalYs = FloatArray(256)
        private val globalWs = FloatArray(256)
        private val globalIdx = IntArray(256)
        private var globalVecReady = false
        private var globalCounter = 0

        private var coarseProgram = 0
        private var coarseTexId = 0
        private var coarseW = 0
        private var coarseH = 0

        private var motionBwdProgram = 0

        private var downTexId = 0
        private var downFbo = 0

        private var renderScale = 1f
        private var drsFbo = 0
        private var drsTexId = 0
        private var drsW = 0
        private var drsH = 0
        private var blitProgram = 0
        private var blitPosLoc = -1
        private var blitTexLoc = -1
        private var blitSamplerLoc = -1
        private var blitTexMatrixLoc = -1
        private var blitVFlipLoc = -1
        private var fsrUpProgram = 0
        private var fsrUpPosLoc = -1
        private var fsrUpTexLoc = -1
        private var fsrUpSamplerLoc = -1
        private var fsrUpTexMatrixLoc = -1
        private var fsrUpVFlipLoc = -1
        private var fsrUpTexelLoc = -1
        private var fsrUpSharpLoc = -1
        private var fsrRenderScale = 0f
        private var lowFpsStreak = 0
        private var highFpsStreak = 0
        private val identityMat = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        private val blitFragmentShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTexCoord);
            }
        """.trimIndent()

        private val fsrUpFragmentShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uSharpness;

            vec3 fsrUp(vec2 uv) {
                vec2 texel = uTexel;
                vec3 c = texture2D(uTex, uv).rgb;
                vec3 t  = texture2D(uTex, uv + vec2(0.0, -texel.y)).rgb;
                vec3 b  = texture2D(uTex, uv + vec2(0.0,  texel.y)).rgb;
                vec3 l  = texture2D(uTex, uv + vec2(-texel.x, 0.0)).rgb;
                vec3 r  = texture2D(uTex, uv + vec2( texel.x, 0.0)).rgb;
                float lumaC = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float lumaT = dot(t, vec3(0.2126, 0.7152, 0.0722));
                float lumaB = dot(b, vec3(0.2126, 0.7152, 0.0722));
                float lumaL = dot(l, vec3(0.2126, 0.7152, 0.0722));
                float lumaR = dot(r, vec3(0.2126, 0.7152, 0.0722));
                float minLuma = min(min(min(lumaT, lumaB), min(lumaL, lumaR)), lumaC);
                float maxLuma = max(max(max(lumaT, lumaB), max(lumaR, lumaL)), lumaC);
                vec2 dirH = vec2(lumaR - lumaL, 0.0);
                vec2 dirV = vec2(0.0, lumaT - lumaB);
                float lenH = abs(dirH.x) + 0.001;
                float lenV = abs(dirV.y) + 0.001;
                vec2 dir = normalize(dirH * lenH + dirV * lenV + vec2(0.001));
                float peak = -1.0 / mix(8.0, 5.0, uSharpness);
                vec2 dir2 = dir * texel;
                vec3 sp1 = texture2D(uTex, uv - dir2 * 1.0).rgb;
                vec3 sp2 = texture2D(uTex, uv + dir2 * 1.0).rgb;
                vec3 sp3 = texture2D(uTex, uv - dir2 * 2.0).rgb;
                vec3 sp4 = texture2D(uTex, uv + dir2 * 2.0).rgb;
                float lumaSp1 = dot(sp1, vec3(0.2126, 0.7152, 0.0722));
                float lumaSp2 = dot(sp2, vec3(0.2126, 0.7152, 0.0722));
                float lumaSp3 = dot(sp3, vec3(0.2126, 0.7152, 0.0722));
                float lumaSp4 = dot(sp4, vec3(0.2126, 0.7152, 0.0722));
                float clampRange1 = max(0.0, lumaC - minLuma);
                float clampRange2 = max(0.0, maxLuma - lumaC);
                float peakVal1 = clamp((lumaC - lumaSp1) * peak, 0.0, clampRange1);
                float peakVal2 = clamp((lumaC - lumaSp2) * peak, 0.0, clampRange2);
                float peakVal3 = clamp((lumaC - lumaSp3) * peak * 0.5, 0.0, clampRange1);
                float peakVal4 = clamp((lumaC - lumaSp4) * peak * 0.5, 0.0, clampRange2);
                vec3 result = c;
                result -= (sp1 - c) * peakVal1;
                result -= (sp2 - c) * peakVal2;
                result -= (sp3 - c) * peakVal3;
                result -= (sp4 - c) * peakVal4;
                result = clamp(c + (result - c) * 1.3, vec3(0.0), vec3(1.0));
                return result;
            }

            void main() {
                gl_FragColor = vec4(fsrUp(vTexCoord), 1.0);
            }
        """.trimIndent()

        @Volatile var pipelineReady = false
        @Volatile var interpolationActive = false
            private set
        @Volatile var sourceFps = 24f
        @Volatile var outputFps = 0f
        @Volatile var frameMs = 0f
        @Volatile var motionLevel = 0f
        @Volatile var droppedFrames = 0L
        @Volatile var qualityLabel = "60p"
        @Volatile var debugMode = 0
        @Volatile var lastRenderedFrameNs = 0L

        fun markResync() { lastRenderedFrameNs = System.nanoTime() }

        private var frameAvailable = false
        private val frameLock = Object()
        private val metaLock = Object()
        private val metaQueue = ArrayDeque<FrameMeta>()
        private val metaScratch = ArrayList<FrameMeta>(8)
        @Volatile private var metadataCount = 0L
        private var lastDrainedMeta: FrameMeta? = null
        private var prevReleaseNs = -1L
        private var currReleaseNs = -1L
        private var prevPtsUs = -1L
        private var currPtsUs = -1L
        private var segmentStartNs = 0L
        private var lastFrameTimeNs = 0L

        private var staticScene = false
        private var staticCheckCounter = 0
        private var staticReadCounter = 0
        private var staticFrames = 0
        @Volatile private var staticRenderMode = false
        private var prevDirty = false
        private var firstLatch = true
        private var matrixLogged = false
        private var passthroughLatch = false

        private var stopped = false

        private var viewWidth = 0
        private var viewHeight = 0
        private var videoWidth = 1920
        private var videoHeight = 1080
        private var hasNewFrame = false
        private var frameCount = 0L
        private val texMatrix = FloatArray(16)
        private val matrixOld = FloatArray(16)
        private var lastFpsTimeNs = 0L
        private var fpsFrames = 0
        private var fpsTotalNs = 0L
        private var fpsRenderNs = 0L

        private val intervalNs: Long
            get() {
                if (currPtsUs > prevPtsUs && prevPtsUs > 0) {
                    return ((currPtsUs - prevPtsUs) * 1000L).coerceIn(5_000_000L, 200_000_000L)
                }
                return ((1000f / sourceFps.coerceAtLeast(1f)) * 1_000_000f).toLong().coerceIn(5_000_000L, 200_000_000L)
            }

        private var curTexLoc = -1
        private var prevTexLoc = -1
        private var motionTexLoc = -1
        private var bwdTexLoc = -1
        private var downTexLoc = -1
        private var downTexelLoc = -1
        private var globalVecLoc = -1
        private var texMatrixLoc = -1
        private var vFlipLoc = -1
        private var interpFactorLoc = -1
        private var modeLoc = -1
        private var motionScaleLoc = -1
        private var motionTexelLoc = -1
        private var saturationLoc = -1
        private var contrastLoc = -1
        private var brightnessLoc = -1
        private var sharpnessLoc = -1
        private var colorBoostLoc = -1
        private var denoiseLoc = -1
        private var debandLoc = -1
        private var dbgLoc = -1
        private var upscalerLoc = -1
        private var fsrScaleLoc = -1
        private var fsrSharpnessLoc = -1
        private var videoResLoc = -1
        private var texelSizeLoc = -1
        private var enabledLoc = -1
        private var interpEnabledLoc = -1
        private var staticFlagLoc = -1
        private var posLoc = -1
        private var texLoc = -1

        private var mCurTexLoc = -1
        private var mPrevTexLoc = -1
        private var mOldMotionTexLoc = -1
        private var mCoarseTexLoc = -1
        private var mAlphaLoc = -1
        private var mDirLoc = -1
        private var mMotionTexelLoc = -1
        private var mTexMatrixLoc = -1
        private var mVFlipLoc = -1
        private var mPosLoc = -1
        private var mTexLoc = -1
        private var mvProbeFrames = 0
        private var mvProbeBuf: java.nio.ByteBuffer? = null
        private var coarseProbeBuf: java.nio.ByteBuffer? = null
        private var bwdProbeBuf: java.nio.ByteBuffer? = null

        private var bmCurTexLoc = -1
        private var bmPrevTexLoc = -1
        private var bmOldMotionTexLoc = -1
        private var bmCoarseTexLoc = -1
        private var bmAlphaLoc = -1
        private var bmDirLoc = -1
        private var bmMotionTexelLoc = -1
        private var bmTexMatrixLoc = -1
        private var bmVFlipLoc = -1
        private var bmPosLoc = -1
        private var bmTexLoc = -1

        private var cCurTexLoc = -1
        private var cPrevTexLoc = -1
        private var cCoarseTexelLoc = -1
        private var cTexMatrixLoc = -1
        private var cVFlipLoc = -1
        private var cPosLoc = -1
        private var cTexLoc = -1

        private var sMotionTexLoc = -1
        private var sVFlipLoc = -1
        private var sPosLoc = -1
        private var sTexLoc = -1

        private var gMotionTexLoc = -1
        private var gPosLoc = -1
        private var gTexLoc = -1

        private fun trackOutputFps(frameStartNs: Long, now: Long) {
            fpsFrames++
            fpsTotalNs += now - frameStartNs
            if (lastFpsTimeNs == 0L) lastFpsTimeNs = now
            val elapsed = now - lastFpsTimeNs
            if (elapsed >= 1_000_000_000L) {
                outputFps = fpsFrames * 1_000_000_000f / elapsed
                if (fpsFrames > 0) frameMs = (fpsRenderNs / 1_000_000f) / fpsFrames
                if (!staticScene && fsrRenderScale <= 0f) {
                    if (!interpolationActive) {
                        if (renderScale < 1f) {
                            renderScale = 1f
                            Log.i(TAG, "DRS full-res scale=${renderScale}")
                        }
                        lowFpsStreak = 0
                        highFpsStreak = 0
                    } else if (VideoEnhanceConfig.isDynamicResolutionEnabled() && outputFps < 28f && renderScale > 0.7f) {
                        lowFpsStreak++
                        if (lowFpsStreak >= 2) {
                            lowFpsStreak = 0
                            highFpsStreak = 0
                            renderScale = (renderScale - 0.1f).coerceAtLeast(0.7f)
                            Log.i(TAG, "DRS down scale=${renderScale}")
                        }
                    } else if (outputFps > 52f && renderScale < 1f) {
                        highFpsStreak++
                        if (highFpsStreak >= 4) {
                            highFpsStreak = 0
                            lowFpsStreak = 0
                            renderScale = (renderScale + 0.1f).coerceAtMost(1f)
                            Log.i(TAG, "DRS up scale=${renderScale}")
                        }
                    } else {
                        lowFpsStreak = 0
                        highFpsStreak = 0
                    }
                }
                Log.i(TAG, "metrics out=${outputFps.toInt()}fps ms=${"%.1f".format(frameMs)} src=${sourceFps.toInt()}fps interp=$interpolationActive static=$staticScene mov=${(motionLevel * 100).toInt()} drop=$droppedFrames ${qualityLabel} gx=${"%.2f".format(globalVec[0])} gy=${"%.2f".format(globalVec[1])} prev=$prevReady srcF=$sourceFps dscale=${renderScale}")
                fpsFrames = 0
                fpsTotalNs = 0
                fpsRenderNs = 0
                lastFpsTimeNs = now
            }
        }

        private fun glFailed(reason: String, t: Throwable? = null) {
            Log.e(TAG, "GL pipeline failure: $reason", t)
            pipelineReady = false
            onGlFailure.invoke()
        }

        fun cleanupGl() {
            if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
            if (motionProgram != 0) { GLES20.glDeleteProgram(motionProgram); motionProgram = 0 }
            if (staticProgram != 0) { GLES20.glDeleteProgram(staticProgram); staticProgram = 0 }
            if (globalProgram != 0) { GLES20.glDeleteProgram(globalProgram); globalProgram = 0 }
            if (coarseProgram != 0) { GLES20.glDeleteProgram(coarseProgram); coarseProgram = 0 }
            if (blitProgram != 0) { GLES20.glDeleteProgram(blitProgram); blitProgram = 0 }
            if (fsrUpProgram != 0) { GLES20.glDeleteProgram(fsrUpProgram); fsrUpProgram = 0 }
            if (prevFbo != 0 || motionFbo != 0 || staticFbo != 0 || downFbo != 0 || globalFbo != 0 || drsFbo != 0) {
                GLES20.glDeleteFramebuffers(6, intArrayOf(prevFbo, motionFbo, staticFbo, downFbo, globalFbo, drsFbo), 0)
                prevFbo = 0; motionFbo = 0; staticFbo = 0; downFbo = 0; globalFbo = 0; drsFbo = 0
            }
            if (inputTexId != 0 || prevTexId != 0 || motionTexId != 0 || staticTexId != 0 || motionAccumId != 0 || downTexId != 0 || globalTexId != 0 || coarseTexId != 0 || motionBwdId != 0 || motionBwdAccumId != 0 || drsTexId != 0) {
                GLES20.glDeleteTextures(11, intArrayOf(inputTexId, prevTexId, motionTexId, staticTexId, motionAccumId, downTexId, globalTexId, coarseTexId, motionBwdId, motionBwdAccumId, drsTexId), 0)
                inputTexId = 0; prevTexId = 0; motionTexId = 0; staticTexId = 0; motionAccumId = 0; downTexId = 0; globalTexId = 0; coarseTexId = 0; motionBwdId = 0; motionBwdAccumId = 0; drsTexId = 0
            }
            inputSurfaceTexture?.release()
            inputSurfaceTexture = null
            cachedOutputSurface?.release()
            cachedOutputSurface = null
            prevReady = false
            staticScene = false
            staticCheckCounter = 0
            staticReadCounter = 0
            staticFrames = 0
            staticRenderMode = false
            globalVecReady = false
            glSurface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            firstLatch = true
            matrixLogged = false
            renderScale = 1f
            lowFpsStreak = 0
            highFpsStreak = 0
        }

        private val vertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uTexMatrix;
            uniform float uVFlip;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                vTexCoord.y = mix(vTexCoord.y, 1.0 - vTexCoord.y, uVFlip);
            }
        """.trimIndent()

        private val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform samplerExternalOES uCurrTex;
            uniform sampler2D uPrevTex;
            uniform sampler2D uMotionTex;
            uniform sampler2D uBwdTex;
            uniform sampler2D uDownTex;
            uniform float uFactor;
            uniform vec2 uMotionScale;
            uniform vec2 uMotionTexel;
            uniform vec2 uTexelSize;
            uniform vec2 uDownTexel;
            uniform vec2 uGlobalVec;
            uniform float uMode;
            uniform float uInterpEnabled;
            uniform float uEnabled;
            uniform float uStatic;
            uniform float uSaturation;
            uniform float uContrast;
            uniform float uBrightness;
            uniform float uSharpness;
            uniform float uColorBoost;
            uniform float uDenoise;
            uniform float uDeband;
            uniform float uDbgMode;
            uniform float uUpscalerMode;
            uniform float uFsrScale;
            uniform float uFsrSharpness;
            uniform vec2 uVideoRes;

            vec3 adjustSaturation(vec3 c, float s) {
                float g = dot(c, vec3(0.2126, 0.7152, 0.0722));
                return mix(vec3(g), c, s);
            }

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            }

            vec3 anime4kEdge(vec3 color, vec2 uv, vec2 inputTexel) {
                vec3 t = texture2D(uCurrTex, uv + vec2(0.0, inputTexel.y)).rgb;
                vec3 b = texture2D(uCurrTex, uv - vec2(0.0, inputTexel.y)).rgb;
                vec3 l = texture2D(uCurrTex, uv - vec2(inputTexel.x, 0.0)).rgb;
                vec3 r = texture2D(uCurrTex, uv + vec2(inputTexel.x, 0.0)).rgb;
                vec3 tl = texture2D(uCurrTex, uv + vec2(-inputTexel.x, inputTexel.y)).rgb;
                vec3 tr = texture2D(uCurrTex, uv + vec2(inputTexel.x, inputTexel.y)).rgb;
                vec3 bl = texture2D(uCurrTex, uv + vec2(-inputTexel.x, -inputTexel.y)).rgb;
                vec3 br = texture2D(uCurrTex, uv + vec2(inputTexel.x, -inputTexel.y)).rgb;
                float lumaC = dot(color, vec3(0.299, 0.587, 0.114));
                float lumaT = dot(t, vec3(0.299, 0.587, 0.114));
                float lumaB = dot(b, vec3(0.299, 0.587, 0.114));
                float lumaL = dot(l, vec3(0.299, 0.587, 0.114));
                float lumaR = dot(r, vec3(0.299, 0.587, 0.114));
                float gx = lumaR - lumaL;
                float gy = lumaT - lumaB;
                float edgeStrength = sqrt(gx * gx + gy * gy);
                vec3 result = color;
                if (edgeStrength > 0.03) {
                    float strength = clamp(edgeStrength * 4.0, 0.0, 1.0);
                    vec3 avg4 = (t + b + l + r) * 0.25;
                    vec3 avgDiag = (tl + tr + bl + br) * 0.25;
                    vec3 sharpened = 2.5 * color - 0.75 * avg4 - 0.25 * avgDiag;
                    result = mix(color, sharpened, strength);
                }
                result = color + (result - color) * 0.85;
                return result;
            }

            vec3 fsrEasu(vec2 uv, vec2 inputTexel) {
                // Adjust input texel for FSR scale factor (lower render resolution = larger texels)
                vec2 fsrTexel = inputTexel * uFsrScale;
                vec3 c = texture2D(uCurrTex, uv).rgb;
                vec3 t  = texture2D(uCurrTex, uv + vec2(0.0, -fsrTexel.y)).rgb;
                vec3 b  = texture2D(uCurrTex, uv + vec2(0.0,  fsrTexel.y)).rgb;
                vec3 l  = texture2D(uCurrTex, uv + vec2(-fsrTexel.x, 0.0)).rgb;
                vec3 r  = texture2D(uCurrTex, uv + vec2( fsrTexel.x, 0.0)).rgb;
                float lumaC = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float lumaT = dot(t, vec3(0.2126, 0.7152, 0.0722));
                float lumaB = dot(b, vec3(0.2126, 0.7152, 0.0722));
                float lumaL = dot(l, vec3(0.2126, 0.7152, 0.0722));
                float lumaR = dot(r, vec3(0.2126, 0.7152, 0.0722));
                float minLuma = min(min(min(lumaT, lumaB), min(lumaL, lumaR)), lumaC);
                float maxLuma = max(max(max(lumaT, lumaB), max(lumaR, lumaL)), lumaC);
                vec2 dirH = vec2(lumaR - lumaL, 0.0);
                vec2 dirV = vec2(0.0, lumaT - lumaB);
                float lenH = abs(dirH.x) + 0.001;
                float lenV = abs(dirV.y) + 0.001;
                vec2 dir = normalize(dirH * lenH + dirV * lenV + vec2(0.001));
                // Use uniform sharpness (AMD FSR quality preset values)
                float sharpness = uFsrSharpness;
                float peak = -1.0 / mix(8.0, 5.0, sharpness);
                vec2 dir2 = dir * fsrTexel;
                vec3 sp1 = texture2D(uCurrTex, uv - dir2 * 1.0).rgb;
                vec3 sp2 = texture2D(uCurrTex, uv + dir2 * 1.0).rgb;
                vec3 sp3 = texture2D(uCurrTex, uv - dir2 * 2.0).rgb;
                vec3 sp4 = texture2D(uCurrTex, uv + dir2 * 2.0).rgb;
                float lumaSp1 = dot(sp1, vec3(0.2126, 0.7152, 0.0722));
                float lumaSp2 = dot(sp2, vec3(0.2126, 0.7152, 0.0722));
                float lumaSp3 = dot(sp3, vec3(0.2126, 0.7152, 0.0722));
                float lumaSp4 = dot(sp4, vec3(0.2126, 0.7152, 0.0722));
                float clampRange1 = max(0.0, lumaC - minLuma);
                float clampRange2 = max(0.0, maxLuma - lumaC);
                float peakVal1 = clamp((lumaC - lumaSp1) * peak, 0.0, clampRange1);
                float peakVal2 = clamp((lumaC - lumaSp2) * peak, 0.0, clampRange2);
                float peakVal3 = clamp((lumaC - lumaSp3) * peak * 0.5, 0.0, clampRange1);
                float peakVal4 = clamp((lumaC - lumaSp4) * peak * 0.5, 0.0, clampRange2);
                vec3 result = c;
                result -= (sp1 - c) * peakVal1;
                result -= (sp2 - c) * peakVal2;
                result -= (sp3 - c) * peakVal3;
                result -= (sp4 - c) * peakVal4;
                result = clamp(c + (result - c) * 1.3, vec3(0.0), vec3(1.0));
                return result;
            }

            void main() {
                vec4 curr = texture2D(uCurrTex, vTexCoord);
                vec3 color = curr.rgb;

                if (uDbgMode > 0.5) {
                if (uDbgMode > 6.5) {
                    vec2 px = vTexCoord / uTexelSize;
                    float sqx = mix(100.0, 200.0, uFactor);
                    float inX = step(abs(px.x - sqx), 15.0);
                    float inY = step(abs(px.y - 200.0), 15.0);
                    float sq = inX * inY;
                    gl_FragColor = vec4(vec3(sq), 1.0);
                    return;
                }
                if (uDbgMode > 5.5) {
                    vec4 m0 = texture2D(uMotionTex, vTexCoord);
                    vec2 mvdbg = (m0.xy * 2.0 - 1.0) * 16.0;
                    vec2 msD = mvdbg * uMotionScale;
                    vec2 fuD = clamp(vTexCoord - msD, vec2(0.0), vec2(1.0));
                    float resD = length(texture2D(uPrevTex, fuD).rgb - curr.rgb);
                    float trustD = 1.0 - smoothstep(0.04, 0.3, resD);
                    float selD = clamp(max(m0.b * m0.a, trustD), 0.0, 1.0);
                    float maskD = mix(0.3, 1.0, selD);
                    gl_FragColor = vec4(vec3(maskD), 1.0);
                    return;
                }
                if (uDbgMode > 4.5) {
                    float m = texture2D(uMotionTex, vTexCoord).a;
                    gl_FragColor = vec4(vec3(m), 1.0);
                    return;
                }
                if (uDbgMode > 3.5) {
                    gl_FragColor = vec4(vec3(uFactor), 1.0);
                    return;
                }
                if (uDbgMode > 2.5) {
                    gl_FragColor = vec4(vTexCoord, 0.0, 1.0);
                    return;
                }
                    if (uDbgMode > 1.5) {
                        gl_FragColor = vec4(curr.rgb, 1.0);
                        return;
                    }
                    vec4 p = texture2D(uPrevTex, vTexCoord);
                    gl_FragColor = vec4(p.rgb, 1.0);
                    return;
                }

                if (uInterpEnabled > 0.5) {
                    vec3 interp;
                    float mask;
                    if (uMode > 0.5) {
                        vec2 mt = uMotionTexel * 3.0;
                        vec4 m0 = texture2D(uMotionTex, vTexCoord);
                        vec4 m1 = texture2D(uMotionTex, vTexCoord + vec2(mt.x, 0.0));
                        vec4 m2 = texture2D(uMotionTex, vTexCoord - vec2(mt.x, 0.0));
                        vec4 m3 = texture2D(uMotionTex, vTexCoord + vec2(0.0, mt.y));
                        vec4 m4 = texture2D(uMotionTex, vTexCoord - vec2(0.0, mt.y));
                        float w0 = m0.b * m0.a;
                        float w1 = m1.b * m1.a;
                        float w2 = m2.b * m2.a;
                        float w3 = m3.b * m3.a;
                        float w4 = m4.b * m4.a;
                        float wm = max(max(max(w0, w1), max(w2, w3)), w4);
                        vec2 mv;
                        if (wm <= 0.001) { mv = m0.xy * 2.0 - 1.0; }
                        else if (wm == w0) { mv = m0.xy * 2.0 - 1.0; }
                        else if (wm == w1) { mv = m1.xy * 2.0 - 1.0; }
                        else if (wm == w2) { mv = m2.xy * 2.0 - 1.0; }
                        else if (wm == w3) { mv = m3.xy * 2.0 - 1.0; }
                        else { mv = m4.xy * 2.0 - 1.0; }
                        mv = mix(uGlobalVec, mv, clamp(wm, 0.0, 1.0));
                        mv *= 16.0;
                        vec2 msF = mv * uMotionScale;

                        vec4 b0 = texture2D(uBwdTex, vTexCoord);
                        vec4 b1 = texture2D(uBwdTex, vTexCoord + vec2(mt.x, 0.0));
                        vec4 b2 = texture2D(uBwdTex, vTexCoord - vec2(mt.x, 0.0));
                        vec4 b3 = texture2D(uBwdTex, vTexCoord + vec2(0.0, mt.y));
                        vec4 b4 = texture2D(uBwdTex, vTexCoord - vec2(0.0, mt.y));
                        float q0 = b0.b * b0.a;
                        float q1 = b1.b * b1.a;
                        float q2 = b2.b * b2.a;
                        float q3 = b3.b * b3.a;
                        float q4 = b4.b * b4.a;
                        float qm = max(max(max(q0, q1), max(q2, q3)), q4);
                        vec2 mvB;
                        if (qm <= 0.001) { mvB = b0.xy * 2.0 - 1.0; }
                        else if (qm == q0) { mvB = b0.xy * 2.0 - 1.0; }
                        else if (qm == q1) { mvB = b1.xy * 2.0 - 1.0; }
                        else if (qm == q2) { mvB = b2.xy * 2.0 - 1.0; }
                        else if (qm == q3) { mvB = b3.xy * 2.0 - 1.0; }
                        else { mvB = b4.xy * 2.0 - 1.0; }
                        mvB = mix(uGlobalVec, mvB, clamp(qm, 0.0, 1.0));
                        mvB *= 16.0;
                        vec2 msB = mvB * uMotionScale;

                        float consistency = 1.0 - smoothstep(0.004, 0.016, length(msF + msB));
                        vec2 fwdUV = clamp(vTexCoord - msF * uFactor, vec2(0.0), vec2(1.0));
                        vec2 bwdUV = clamp(vTexCoord + msF * (1.0 - uFactor), vec2(0.0), vec2(1.0));
                        vec3 pF = texture2D(uPrevTex, fwdUV).rgb;
                        vec3 cF = texture2D(uCurrTex, bwdUV).rgb;
                        vec3 interpF = clamp(mix(pF, cF, uFactor), min(pF, cF), max(pF, cF));
                        vec2 fwdUVB = clamp(vTexCoord + msB * uFactor, vec2(0.0), vec2(1.0));
                        vec2 bwdUVB = clamp(vTexCoord - msB * (1.0 - uFactor), vec2(0.0), vec2(1.0));
                        vec3 pB = texture2D(uPrevTex, fwdUVB).rgb;
                        vec3 cB = texture2D(uCurrTex, bwdUVB).rgb;
                        vec3 interpB = clamp(mix(pB, cB, uFactor), min(pB, cB), max(pB, cB));
                        float confF = clamp(wm, 0.0, 1.0);
                        float confB = clamp(qm, 0.0, 1.0);
                        float bWeight = mix(confB / (confF + confB + 0.001), 0.5, consistency);
                        interp = mix(interpF, interpB, clamp(bWeight, 0.0, 1.0));

                        vec2 fullUV = clamp(vTexCoord - msF, vec2(0.0), vec2(1.0));
                        float residual = length(texture2D(uPrevTex, fullUV).rgb - curr.rgb);
                        float trust = 1.0 - smoothstep(0.1, 0.8, residual);
                        float maskConf = m0.b * m0.a;
                        float panBoost = smoothstep(0.06, 0.19, length(uGlobalVec));
                        float warpBase = mix(0.1 + 0.9 * clamp(maskConf, 0.0, 1.0), 0.92, panBoost);
                        float warpSel = clamp(trust * consistency * warpBase, 0.0, 1.0);
                        vec3 cf = mix(texture2D(uPrevTex, vTexCoord).rgb, curr.rgb, uFactor);
                        interp = mix(cf, interp, warpSel);
                        interp = mix(interp, clamp(interp, min(texture2D(uPrevTex, vTexCoord).rgb, curr.rgb), max(texture2D(uPrevTex, vTexCoord).rgb, curr.rgb)), 0.8);
                        mask = mix(0.3, 1.0, warpSel);
                    } else {
                        interp = mix(texture2D(uPrevTex, vTexCoord).rgb, curr.rgb, uFactor);
                        mask = 1.0;
                    }
                    color = mix(curr.rgb, interp, mask);
                }

                if (uUpscalerMode > 1.5) {
                    color = fsrEasu(vTexCoord, 1.0 / uVideoRes);
                } else if (uUpscalerMode > 0.5) {
                    color = anime4kEdge(color, vTexCoord, 1.0 / uVideoRes);
                }

                if (uEnabled > 0.5) {
                    color = (color - 0.5) * uContrast + 0.5;
                    color = adjustSaturation(color, uSaturation);
                    color *= uColorBoost;
                    color += uBrightness;

                    if (uStatic < 0.5 && uSharpness > 0.001) {
                    vec2 txl = uDownTexel;
                    vec3 top    = texture2D(uDownTex, vTexCoord + vec2(0.0, txl.y)).rgb;
                    vec3 bottom = texture2D(uDownTex, vTexCoord - vec2(0.0, txl.y)).rgb;
                    vec3 left   = texture2D(uDownTex, vTexCoord - vec2(txl.x, 0.0)).rgb;
                    vec3 right  = texture2D(uDownTex, vTexCoord + vec2(txl.x, 0.0)).rgb;
                    vec3 sharpened = color + uSharpness * (4.0 * color - top - bottom - left - right);
                    color = mix(color, sharpened, uSharpness * 0.5);
                    }

                    if (uDenoise > 0.001) {
                        vec2 txn = uTexelSize;
                        vec3 n1 = texture2D(uCurrTex, vTexCoord + vec2(txn.x, 0.0)).rgb;
                        vec3 n2 = texture2D(uCurrTex, vTexCoord - vec2(txn.x, 0.0)).rgb;
                        vec3 n3 = texture2D(uCurrTex, vTexCoord + vec2(0.0, txn.y)).rgb;
                        vec3 n4 = texture2D(uCurrTex, vTexCoord - vec2(0.0, txn.y)).rgb;
                        vec3 box = (color + n1 + n2 + n3 + n4) * 0.2;
                        if (uInterpEnabled > 0.5) {
                            vec4 mN = texture2D(uMotionTex, vTexCoord);
                            float mConf = clamp(mN.b * mN.a, 0.0, 1.0);
                            vec2 mvN = mix(uGlobalVec, mN.xy * 2.0 - 1.0, mConf) * 16.0 * uMotionScale;
                            vec2 nUV = clamp(vTexCoord - mvN * 0.5, vec2(0.0), vec2(1.0));
                            vec3 nPrev = texture2D(uPrevTex, nUV).rgb;
                            float diff = length(nPrev - box);
                            float trust = 1.0 - smoothstep(0.03, 0.22, diff);
                            vec3 den = mix(box, nPrev, 0.65);
                            color = mix(color, den, trust * uDenoise);
                        } else {
                            color = mix(color, box, uDenoise * 0.6);
                        }
                    }

                    if (uDeband > 0.001) {
                        color += (hash(vTexCoord) - 0.5) * uDeband;
                    }
                }

                color = clamp(color, 0.0, 1.0);
                gl_FragColor = vec4(color, 1.0);
            }
        """.trimIndent()

        private val motionShader = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform samplerExternalOES uCurrTex;
            uniform sampler2D uPrevTex;
            uniform sampler2D uOldMotionTex;
            uniform sampler2D uCoarseTex;
            uniform vec2 uMotionTexel;
            uniform float uTemporalAlpha;

            float luma(vec4 c) { return dot(c.rgb, vec3(0.299, 0.587, 0.114)); }

            void main() {
                vec2 t = uMotionTexel;

                vec4 coarse = texture2D(uCoarseTex, vTexCoord);
                vec2 f0 = (coarse.xy * 2.0 - 1.0) * 16.0;

                float l0 = luma(texture2D(uCurrTex, vTexCoord));
                float lpRaw = luma(texture2D(uPrevTex, vTexCoord));
                float lp = luma(texture2D(uPrevTex, vTexCoord - f0 * t));
                float cR = luma(texture2D(uCurrTex, vTexCoord + vec2(t.x, 0.0)));
                float cL = luma(texture2D(uCurrTex, vTexCoord - vec2(t.x, 0.0)));
                float cT = luma(texture2D(uCurrTex, vTexCoord + vec2(0.0, t.y)));
                float cB = luma(texture2D(uCurrTex, vTexCoord - vec2(0.0, t.y)));
                float gx = (cR - cL) * 0.25;
                float gy = (cT - cB) * 0.25;
                float denom = gx * gx + gy * gy + 1e-4;
                float d = l0 - lp;
                vec2 corr = vec2(clamp(-d * gx / denom, -8.0, 8.0),
                                 clamp(-d * gy / denom, -8.0, 8.0));
                vec2 f = clamp(f0 + corr, -8.0, 8.0);

                float l2 = luma(texture2D(uPrevTex, vTexCoord - f * t));
                float d2 = l0 - l2;
                vec2 corr2 = vec2(clamp(-d2 * gx / denom, -8.0, 8.0),
                                  clamp(-d2 * gy / denom, -8.0, 8.0));
                vec2 f2 = clamp(f + corr2, -8.0, 8.0);

                vec2 p = vTexCoord - f2 * t;
                float pl0 = luma(texture2D(uPrevTex, p));
                float pc0 = luma(texture2D(uCurrTex, p));
                float pR = luma(texture2D(uPrevTex, p + vec2(t.x, 0.0)));
                float pL = luma(texture2D(uPrevTex, p - vec2(t.x, 0.0)));
                float pT = luma(texture2D(uPrevTex, p + vec2(0.0, t.y)));
                float pB = luma(texture2D(uPrevTex, p - vec2(0.0, t.y)));
                float pgx = (pR - pL) * 0.25;
                float pgy = (pT - pB) * 0.25;
                float pdenom = pgx * pgx + pgy * pgy + 1e-4;
                float db = pl0 - pc0;
                vec2 b = vec2(clamp(-db * pgx / pdenom, -8.0, 8.0),
                              clamp(-db * pgy / pdenom, -8.0, 8.0));

                float conf = (0.15 + 0.85 * coarse.b) * (0.35 + 0.65 * (1.0 - smoothstep(0.0, 0.6, length(b))));

                vec4 old = texture2D(uOldMotionTex, vTexCoord);
                vec2 oldF = (old.xy * 2.0 - 1.0) * 16.0;
                float a = uTemporalAlpha;
                vec2 sm = clamp(mix(oldF, f2, a), -8.0, 8.0);
                float mag = mix(old.a, smoothstep(0.0, 0.035, abs(l0 - lpRaw)), a);
                float smConf = mix(old.b, conf, a);

                vec2 enc = clamp(sm * 0.0625 + 0.5, 0.0, 1.0);
                gl_FragColor = vec4(enc.x, enc.y, smConf, mag);
            }
        """.trimIndent()

        private val motionBwdShader = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uCurrTex;
            uniform samplerExternalOES uPrevTex;
            uniform sampler2D uOldMotionTex;
            uniform sampler2D uCoarseTex;
            uniform vec2 uMotionTexel;
            uniform float uTemporalAlpha;
            uniform float uDir;

            float luma(vec4 c) { return dot(c.rgb, vec3(0.299, 0.587, 0.114)); }

            void main() {
                vec2 t = uMotionTexel;

                vec4 coarse = texture2D(uCoarseTex, vTexCoord);
                vec2 f0 = uDir * (coarse.xy * 2.0 - 1.0) * 16.0;

                float l0 = luma(texture2D(uCurrTex, vTexCoord));
                float lpRaw = luma(texture2D(uPrevTex, vTexCoord));
                float lp = luma(texture2D(uPrevTex, vTexCoord - f0 * t));
                float cR = luma(texture2D(uCurrTex, vTexCoord + vec2(t.x, 0.0)));
                float cL = luma(texture2D(uCurrTex, vTexCoord - vec2(t.x, 0.0)));
                float cT = luma(texture2D(uCurrTex, vTexCoord + vec2(0.0, t.y)));
                float cB = luma(texture2D(uCurrTex, vTexCoord - vec2(0.0, t.y)));
                float gx = (cR - cL) * 0.25;
                float gy = (cT - cB) * 0.25;
                float denom = gx * gx + gy * gy + 1e-4;
                float d = l0 - lp;
                vec2 corr = vec2(clamp(-d * gx / denom, -8.0, 8.0),
                                 clamp(-d * gy / denom, -8.0, 8.0));
                vec2 f = clamp(f0 + corr, -8.0, 8.0);

                float l2 = luma(texture2D(uPrevTex, vTexCoord - f * t));
                float d2 = l0 - l2;
                vec2 corr2 = vec2(clamp(-d2 * gx / denom, -8.0, 8.0),
                                  clamp(-d2 * gy / denom, -8.0, 8.0));
                vec2 f2 = clamp(f + corr2, -8.0, 8.0);

                vec2 p = vTexCoord - f2 * t;
                float pl0 = luma(texture2D(uPrevTex, p));
                float pc0 = luma(texture2D(uCurrTex, p));
                float pR = luma(texture2D(uPrevTex, p + vec2(t.x, 0.0)));
                float pL = luma(texture2D(uPrevTex, p - vec2(t.x, 0.0)));
                float pT = luma(texture2D(uPrevTex, p + vec2(0.0, t.y)));
                float pB = luma(texture2D(uPrevTex, p - vec2(0.0, t.y)));
                float pgx = (pR - pL) * 0.25;
                float pgy = (pT - pB) * 0.25;
                float pdenom = pgx * pgx + pgy * pgy + 1e-4;
                float db = pl0 - pc0;
                vec2 b = vec2(clamp(-db * pgx / pdenom, -8.0, 8.0),
                              clamp(-db * pgy / pdenom, -8.0, 8.0));

                float conf = (0.15 + 0.85 * coarse.b) * (0.35 + 0.65 * (1.0 - smoothstep(0.0, 0.6, length(b))));

                vec4 old = texture2D(uOldMotionTex, vTexCoord);
                vec2 oldF = (old.xy * 2.0 - 1.0) * 16.0;
                float a = uTemporalAlpha;
                vec2 sm = clamp(mix(oldF, f2, a), -8.0, 8.0);
                float mag = mix(old.a, smoothstep(0.0, 0.035, abs(l0 - lpRaw)), a);
                float smConf = mix(old.b, conf, a);

                vec2 enc = clamp(sm * 0.0625 + 0.5, 0.0, 1.0);
                gl_FragColor = vec4(enc.x, enc.y, smConf, mag);
            }
        """.trimIndent()

        private val coarseShader = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform samplerExternalOES uCurrTex;
            uniform sampler2D uPrevTex;
            uniform vec2 uCoarseTexel;
            uniform mat4 uTexMatrix;
            uniform float uVFlip;

            float luma(vec4 c) { return dot(c.rgb, vec3(0.299, 0.587, 0.114)); }

            float boxCurr(vec2 uv, vec2 hs) {
                return (luma(texture2D(uCurrTex, uv + hs)) + luma(texture2D(uCurrTex, uv + vec2(-hs.x, hs.y)))
                      + luma(texture2D(uCurrTex, uv + vec2(hs.x, -hs.y))) + luma(texture2D(uCurrTex, uv - hs))) * 0.25;
            }
            float boxPrev(vec2 uv, vec2 hs) {
                return (luma(texture2D(uPrevTex, uv + hs)) + luma(texture2D(uPrevTex, uv + vec2(-hs.x, hs.y)))
                      + luma(texture2D(uPrevTex, uv + vec2(hs.x, -hs.y))) + luma(texture2D(uPrevTex, uv - hs))) * 0.25;
            }

            void main() {
                vec2 cell = uCoarseTexel;
                vec2 hs = uCoarseTexel * 0.5;

                float l0 = boxCurr(vTexCoord, hs);
                float bestS = 1e9;
                vec2 bestD = vec2(0.0);
                float s0 = 0.0;
                float sumS = 0.0;
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dx = -3; dx <= 3; dx++) {
                        vec2 off = vec2(float(dx), float(dy)) * cell;
                        float lp = boxPrev(vTexCoord - off, hs);
                        float s = abs(lp - l0);
                        sumS += s;
                        if (dx == 0 && dy == 0) s0 = s;
                        if (s < bestS) {
                            bestS = s;
                            bestD = vec2(float(dx), float(dy));
                        }
                    }
                }
                vec2 f = (bestS < s0 * 0.8) ? bestD : vec2(0.0);
                vec2 enc = clamp(f * 0.0625 + 0.5, 0.0, 1.0);
                float valley = bestS / (sumS * 0.0204 + 1e-4);
                float q = 1.0 - smoothstep(0.3, 0.7, valley);
                gl_FragColor = vec4(enc, q, 0.0);
            }
        """.trimIndent()

        private val staticVertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val staticShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uMotionTex;

            void main() {
                vec2 cell = vec2(1.0 / 16.0);
                float m = 0.0;
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.375, -0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.125, -0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.125, -0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.375, -0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.375, -0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.125, -0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.125, -0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.375, -0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.375, 0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.125, 0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.125, 0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.375, 0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.375, 0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.125, 0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.125, 0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.375, 0.375) * cell).a);
                gl_FragColor = vec4(m);
            }
        """.trimIndent()

        private val globalShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uMotionTex;
            void main() {
                vec4 m = texture2D(uMotionTex, vTexCoord);
                gl_FragColor = vec4(m.xy, m.b, m.a);
            }
        """.trimIndent()

        private val quadVerts = buf(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
        private val quadTexCoords = buf(floatArrayOf(0f,1f, 1f,1f, 0f,0f, 1f,0f))

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                onSurfaceCreatedSafe()
            } catch (t: Throwable) {
                glFailed("onSurfaceCreated crashed", t)
            }
        }

        private fun onSurfaceCreatedSafe() {
            pipelineReady = false
            cleanupGl()

            GLES20.glClearColor(0f, 0f, 0f, 1f)

            val texIds = IntArray(10)
            GLES20.glGenTextures(10, texIds, 0)
            inputTexId = texIds[0]
            prevTexId = texIds[1]
            motionTexId = texIds[2]
            staticTexId = texIds[3]
            motionAccumId = texIds[4]
            downTexId = texIds[5]
            globalTexId = texIds[6]
            coarseTexId = texIds[7]
            motionBwdId = texIds[8]
            motionBwdAccumId = texIds[9]

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionAccumId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionBwdId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionBwdAccumId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, staticTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 16, 16, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, globalTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 16, 16, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, coarseTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val fbos = IntArray(5)
            GLES20.glGenFramebuffers(5, fbos, 0)
            prevFbo = fbos[0]
            motionFbo = fbos[1]
            staticFbo = fbos[2]
            downFbo = fbos[3]
            globalFbo = fbos[4]

            inputSurfaceTexture = android.graphics.SurfaceTexture(inputTexId)
            inputSurfaceTexture!!.setOnFrameAvailableListener({
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
                if (staticScene) glSurface.requestRender()
            })
            onSurfaceReady(Surface(inputSurfaceTexture!!))

            program = buildProgram(vertexShader, fragmentShader)
            motionProgram = buildProgram(vertexShader, motionShader)
            motionBwdProgram = buildProgram(vertexShader, motionBwdShader)
            staticProgram = buildProgram(staticVertexShader, staticShader)
            globalProgram = buildProgram(staticVertexShader, globalShader)
            coarseProgram = buildProgram(vertexShader, coarseShader)
            pipelineReady = program != 0 && motionProgram != 0 && staticProgram != 0 && globalProgram != 0 && coarseProgram != 0 && motionBwdProgram != 0
            if (!pipelineReady) {
                Log.e(TAG, "GL program failed to compile/link - enhanced pipeline disabled")
            }

            curTexLoc = GLES20.glGetUniformLocation(program, "uCurrTex")
            prevTexLoc = GLES20.glGetUniformLocation(program, "uPrevTex")
            motionTexLoc = GLES20.glGetUniformLocation(program, "uMotionTex")
            bwdTexLoc = GLES20.glGetUniformLocation(program, "uBwdTex")
            downTexLoc = GLES20.glGetUniformLocation(program, "uDownTex")
            downTexelLoc = GLES20.glGetUniformLocation(program, "uDownTexel")
            texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            vFlipLoc = GLES20.glGetUniformLocation(program, "uVFlip")
            interpFactorLoc = GLES20.glGetUniformLocation(program, "uFactor")
            modeLoc = GLES20.glGetUniformLocation(program, "uMode")
            motionScaleLoc = GLES20.glGetUniformLocation(program, "uMotionScale")
            motionTexelLoc = GLES20.glGetUniformLocation(program, "uMotionTexel")
            globalVecLoc = GLES20.glGetUniformLocation(program, "uGlobalVec")
            texelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
            enabledLoc = GLES20.glGetUniformLocation(program, "uEnabled")
            interpEnabledLoc = GLES20.glGetUniformLocation(program, "uInterpEnabled")
            staticFlagLoc = GLES20.glGetUniformLocation(program, "uStatic")
            saturationLoc = GLES20.glGetUniformLocation(program, "uSaturation")
            contrastLoc = GLES20.glGetUniformLocation(program, "uContrast")
            brightnessLoc = GLES20.glGetUniformLocation(program, "uBrightness")
            sharpnessLoc = GLES20.glGetUniformLocation(program, "uSharpness")
            colorBoostLoc = GLES20.glGetUniformLocation(program, "uColorBoost")
            denoiseLoc = GLES20.glGetUniformLocation(program, "uDenoise")
            debandLoc = GLES20.glGetUniformLocation(program, "uDeband")
            dbgLoc = GLES20.glGetUniformLocation(program, "uDbgMode")
            upscalerLoc = GLES20.glGetUniformLocation(program, "uUpscalerMode")
            fsrScaleLoc = GLES20.glGetUniformLocation(program, "uFsrScale")
            fsrSharpnessLoc = GLES20.glGetUniformLocation(program, "uFsrSharpness")
            videoResLoc = GLES20.glGetUniformLocation(program, "uVideoRes")
            posLoc = GLES20.glGetAttribLocation(program, "aPosition")
            texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")

            mCurTexLoc = GLES20.glGetUniformLocation(motionProgram, "uCurrTex")
            mPrevTexLoc = GLES20.glGetUniformLocation(motionProgram, "uPrevTex")
            mOldMotionTexLoc = GLES20.glGetUniformLocation(motionProgram, "uOldMotionTex")
            mCoarseTexLoc = GLES20.glGetUniformLocation(motionProgram, "uCoarseTex")
            mAlphaLoc = GLES20.glGetUniformLocation(motionProgram, "uTemporalAlpha")
            mMotionTexelLoc = GLES20.glGetUniformLocation(motionProgram, "uMotionTexel")
            mTexMatrixLoc = GLES20.glGetUniformLocation(motionProgram, "uTexMatrix")
            mVFlipLoc = GLES20.glGetUniformLocation(motionProgram, "uVFlip")
            mPosLoc = GLES20.glGetAttribLocation(motionProgram, "aPosition")
            mTexLoc = GLES20.glGetAttribLocation(motionProgram, "aTexCoord")

            bmCurTexLoc = GLES20.glGetUniformLocation(motionBwdProgram, "uCurrTex")
            bmPrevTexLoc = GLES20.glGetUniformLocation(motionBwdProgram, "uPrevTex")
            bmOldMotionTexLoc = GLES20.glGetUniformLocation(motionBwdProgram, "uOldMotionTex")
            bmCoarseTexLoc = GLES20.glGetUniformLocation(motionBwdProgram, "uCoarseTex")
            bmAlphaLoc = GLES20.glGetUniformLocation(motionBwdProgram, "uTemporalAlpha")
            bmDirLoc = GLES20.glGetUniformLocation(motionBwdProgram, "uDir")
            bmMotionTexelLoc = GLES20.glGetUniformLocation(motionBwdProgram, "uMotionTexel")
            bmTexMatrixLoc = GLES20.glGetUniformLocation(motionBwdProgram, "uTexMatrix")
            bmVFlipLoc = GLES20.glGetUniformLocation(motionBwdProgram, "uVFlip")
            bmPosLoc = GLES20.glGetAttribLocation(motionBwdProgram, "aPosition")
            bmTexLoc = GLES20.glGetAttribLocation(motionBwdProgram, "aTexCoord")

            cCurTexLoc = GLES20.glGetUniformLocation(coarseProgram, "uCurrTex")
            cPrevTexLoc = GLES20.glGetUniformLocation(coarseProgram, "uPrevTex")
            cCoarseTexelLoc = GLES20.glGetUniformLocation(coarseProgram, "uCoarseTexel")
            cTexMatrixLoc = GLES20.glGetUniformLocation(coarseProgram, "uTexMatrix")
            cVFlipLoc = GLES20.glGetUniformLocation(coarseProgram, "uVFlip")
            cPosLoc = GLES20.glGetAttribLocation(coarseProgram, "aPosition")
            cTexLoc = GLES20.glGetAttribLocation(coarseProgram, "aTexCoord")

            sMotionTexLoc = GLES20.glGetUniformLocation(staticProgram, "uMotionTex")
            sVFlipLoc = GLES20.glGetUniformLocation(staticProgram, "uVFlip")
            sPosLoc = GLES20.glGetAttribLocation(staticProgram, "aPosition")
            sTexLoc = GLES20.glGetAttribLocation(staticProgram, "aTexCoord")

            gMotionTexLoc = GLES20.glGetUniformLocation(globalProgram, "uMotionTex")
            gPosLoc = GLES20.glGetAttribLocation(globalProgram, "aPosition")
            gTexLoc = GLES20.glGetAttribLocation(globalProgram, "aTexCoord")

            Log.i(TAG, "GL surface created, interpolator ready")
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            viewWidth = w
            viewHeight = h
            try {
                GLES20.glViewport(0, 0, w, h)

                val pw = w.coerceAtLeast(2)
                val ph = h.coerceAtLeast(2)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, pw, ph, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, (pw / 2).coerceAtLeast(2), (ph / 2).coerceAtLeast(2), 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)

                motionW = (w / 4).coerceIn(32, 480)
                motionH = (h / 4).coerceIn(32, 270)
                coarseW = (motionW / 2).coerceAtLeast(8)
                coarseH = (motionH / 2).coerceAtLeast(8)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, motionW, motionH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionAccumId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, motionW, motionH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionBwdId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, motionW, motionH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionBwdAccumId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, motionW, motionH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, coarseTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, coarseW, coarseH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, coarseTexId, 0)
                GLES20.glViewport(0, 0, coarseW, coarseH)
                GLES20.glClearColor(0.5f, 0.5f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, motionTexId, 0)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, motionAccumId, 0)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, motionBwdId, 0)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, motionBwdAccumId, 0)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, viewWidth, viewHeight)
            } catch (t: Throwable) {
                glFailed("onSurfaceChanged crashed", t)
            }
        }

        override fun onVideoFrameAboutToBeRendered(releaseTimeNs: Long, presentationTimeUs: Long, format: Format, mediaFormat: android.media.MediaFormat?) {
            synchronized(metaLock) {
                if (metaQueue.size >= 32) metaQueue.removeFirst()
                metaQueue.addLast(FrameMeta(presentationTimeUs, releaseTimeNs))
                metadataCount++
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            if (stopped) return
            try {
                onDrawFrameSafe()
            } catch (t: Throwable) {
                glFailed("onDrawFrame crashed", t)
            }
        }

        private fun onDrawFrameSafe() {
            val t0 = System.nanoTime()
            val cfg = VideoEnhanceConfig
            qualityLabel = cfg.qualityLabel()
            val mode = if (cfg.isInterpolationEnabled()) 1 else 0
            val interpWanted = cfg.isInterpolationEnabled() && mode > 0 && sourceFps < 50f
            val debugNeedsPrev = debugMode == 1 || debugMode == 5

            val st = inputSurfaceTexture
            var latched = false
            if (st != null) {
                synchronized(frameLock) {
                    if (frameAvailable) {
                        updateSourceFpsFallback(t0)
                        val shouldCopy = interpWanted || debugNeedsPrev
                        if ((shouldCopy || prevDirty) && !firstLatch) {
                            st.getTransformMatrix(matrixOld)
                            normalizeMatrix(matrixOld)
                            copyOldToPrev()
                            prevDirty = false
                        }
                        st.updateTexImage()
                        st.getTransformMatrix(texMatrix)
                        normalizeMatrix(texMatrix)
                        val newTs = st.timestamp
                        drainMetadata(newTs)
                        if (interpWanted || debugNeedsPrev) {
                            if (!staticScene || debugNeedsPrev) {
                                buildMotionMap()
                                globalCounter++
                                if (globalCounter % 5 == 0) computeGlobalMotion()
                                staticReadCounter++
                                if (staticReadCounter >= STATIC_READ_INTERVAL) {
                                    staticReadCounter = 0
                                    val lvl = readStaticLevel()
                                    motionLevel = lvl
                                    if (lvl < STATIC_THRESHOLD) {
                                        staticFrames++
                                        if (staticFrames >= 2) {
                                            staticScene = true
                                            staticFrames = 0
                                        }
                                    } else {
                                        staticFrames = 0
                                    }
                                }
                            } else {
                                staticCheckCounter++
                                if (staticCheckCounter >= 20) {
                                    staticCheckCounter = 0
                                    buildMotionMap()
                                    val lvl = readStaticLevel()
                                    motionLevel = lvl
                                    if (lvl >= STATIC_THRESHOLD * 3f) {
                                        staticScene = false
                                        prevDirty = true
                                        passthroughLatch = true
                                        globalVecReady = false
                                        globalVec[0] = 0f
                                        globalVec[1] = 0f
                                    }
                                }
                            }
                        }
                        if (cfg.isEnabled() && !staticScene && cfg.getSharpness() > 0.001f) downscaleCurr()
                        frameAvailable = false
                        hasNewFrame = true
                        frameCount++
                        lastRenderedFrameNs = System.nanoTime()
                        latched = true
                        firstLatch = false
                    }
                }
            }

            val now = System.nanoTime()
            if (latched) {
                val cr = currReleaseNs
                segmentStartNs = if (cr > 0) cr else now
                droppedFrames = (metadataCount - frameCount).coerceAtLeast(0L)
            }

            val stallNs = now - lastRenderedFrameNs
            if (lastRenderedFrameNs > 0 && stallNs > STALL_RESET_NS && (prevReady || staticScene)) {
                Log.i(TAG, "frame stall ${stallNs / 1_000_000}ms, resetting interpolation state")
                prevReady = false
                firstLatch = true
                prevPtsUs = -1L
                currPtsUs = -1L
                currReleaseNs = -1L
                prevReleaseNs = -1L
                segmentStartNs = 0L
                staticScene = false
                passthroughLatch = false
                synchronized(metaLock) { metaQueue.clear() }
            }

            val texMatrix = this.texMatrix
            st?.getTransformMatrix(texMatrix)
            normalizeMatrix(texMatrix)
            if (!matrixLogged) {
                matrixLogged = true
                Log.i(TAG, "texMatrix=" + texMatrix.joinToString(",") { String.format("%.3f", it) })
            }

            val interpolating = interpWanted && !staticScene && prevReady
            interpolationActive = interpolating
            val factor = if (passthroughLatch) {
                passthroughLatch = false
                1f
            } else if (interpolating || debugMode == 7) {
                computeFactor(now)
            } else {
                1f
            }

            val renderStartNs = System.nanoTime()
            renderFrame(texMatrix, factor, cfg, interpolating, mode)
            fpsRenderNs += System.nanoTime() - renderStartNs
            val wantDirty = staticScene && !debugNeedsPrev
            if (wantDirty != staticRenderMode) {
                staticRenderMode = wantDirty
                glSurface.renderMode = if (wantDirty) GLSurfaceView.RENDERMODE_WHEN_DIRTY else GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            trackOutputFps(t0, now)
        }

        private fun updateSourceFpsFallback(now: Long) {
            if (lastFrameTimeNs > 0) {
                val delta = (now - lastFrameTimeNs) / 1_000_000f
                if (delta > 0 && currPtsUs <= 0) {
                    val instantFps = 1000f / delta
                    sourceFps = sourceFps * 0.9f + instantFps * 0.1f
                }
            }
            lastFrameTimeNs = now
        }

        private fun drainMetadata(currTimestampNs: Long) {
            synchronized(metaLock) {
                metaScratch.clear()
                val consumed = metaScratch
                while (metaQueue.isNotEmpty() && metaQueue.first().ptsUs * 1000L <= currTimestampNs) {
                    consumed.add(metaQueue.removeFirst())
                }
                if (consumed.isNotEmpty()) {
                    var curr = consumed.last()
                    for (i in consumed.indices.reversed()) {
                        if (consumed[i].ptsUs * 1000L == currTimestampNs) { curr = consumed[i]; break }
                    }
                    val idx = consumed.indexOf(curr)
                    val p = if (idx > 0) consumed[idx - 1] else lastDrainedMeta
                    prevReleaseNs = p?.releaseNs ?: -1L
                    prevPtsUs = p?.ptsUs ?: -1L
                    currReleaseNs = curr.releaseNs
                    currPtsUs = curr.ptsUs
                    lastDrainedMeta = curr
                    if (prevPtsUs > 0 && currPtsUs > prevPtsUs) {
                        val ivUs = currPtsUs - prevPtsUs
                        if (ivUs in 8_000L..200_000L) {
                            val instFps = 1_000_000f / ivUs
                            sourceFps = sourceFps * 0.85f + instFps * 0.15f
                        }
                    }
                }
            }
        }

        private fun computeFactor(now: Long): Float {
            val iv = intervalNs
            if (segmentStartNs > 0 && iv > 0) {
                return ((now - segmentStartNs).toFloat() / iv.toFloat()).coerceIn(0f, 1f)
            }
            return 1f
        }

        private fun normalizeMatrix(m: FloatArray) {
            if (m[5] < 0f) {
                m[5] = -m[5]
                m[13] = 1f - m[13]
            }
        }

        private fun renderFrame(texMatrix: FloatArray, factor: Float, cfg: VideoEnhanceConfig, interpolating: Boolean, mode: Int) {
            if (program == 0) return
            val upscalerMode = cfg.getUpscalerMode()
            val fsrActive = upscalerMode.value >= 2f
            fsrRenderScale = if (fsrActive) (1f / upscalerMode.scaleFactor) else 0f
            val effScale = if (fsrActive) fsrRenderScale else renderScale
            val offscreen = fsrActive || effScale < 1f
            val rw = if (offscreen) ((viewWidth * effScale).toInt()).coerceAtLeast(2) else viewWidth
            val rh = if (offscreen) ((viewHeight * effScale).toInt()).coerceAtLeast(2) else viewHeight
            if (offscreen) {
                ensureDrsTarget(rw, rh)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, drsFbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, drsTexId, 0)
                GLES20.glViewport(0, 0, rw, rh)
            } else {
                GLES20.glViewport(0, 0, viewWidth, viewHeight)
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId)
            GLES20.glUniform1i(curTexLoc, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTexId)
            GLES20.glUniform1i(prevTexLoc, 1)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexId)
            GLES20.glUniform1i(motionTexLoc, 2)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionBwdId)
            GLES20.glUniform1i(bwdTexLoc, 4)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTexId)
            GLES20.glUniform1i(downTexLoc, 3)
            GLES20.glUniform2f(downTexelLoc, 1f / (rw / 2).coerceAtLeast(1), 1f / (rh / 2).coerceAtLeast(1))

            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform1f(vFlipLoc, 0f)
            GLES20.glUniform1f(interpFactorLoc, factor)
            GLES20.glUniform1f(modeLoc, mode.toFloat())
            GLES20.glUniform2f(motionScaleLoc, 1f / motionW.coerceAtLeast(1), 1f / motionH.coerceAtLeast(1))
            GLES20.glUniform2f(motionTexelLoc, 1f / motionW.coerceAtLeast(1), 1f / motionH.coerceAtLeast(1))
            GLES20.glUniform2f(globalVecLoc, if (globalVecReady) globalVec[0] else 0f, if (globalVecReady) globalVec[1] else 0f)
            GLES20.glUniform2f(texelSizeLoc, 1f / rw.coerceAtLeast(1), 1f / rh.coerceAtLeast(1))
            GLES20.glUniform1f(enabledLoc, if (cfg.isEnabled()) 1f else 0f)
            GLES20.glUniform1f(interpEnabledLoc, if (interpolating) 1f else 0f)
            GLES20.glUniform1f(staticFlagLoc, if (staticScene) 1f else 0f)
            GLES20.glUniform1f(saturationLoc, cfg.getSaturation())
            GLES20.glUniform1f(contrastLoc, cfg.getContrast())
            GLES20.glUniform1f(brightnessLoc, cfg.getBrightness())
            GLES20.glUniform1f(sharpnessLoc, cfg.getSharpness())
            GLES20.glUniform1f(colorBoostLoc, cfg.getColorBoost())
            GLES20.glUniform1f(denoiseLoc, cfg.getDenoise())
            GLES20.glUniform1f(debandLoc, cfg.getDeband())
            GLES20.glUniform1f(dbgLoc, debugMode.toFloat())
            GLES20.glUniform1f(upscalerLoc, if (fsrActive) 0f else upscalerMode.value)
            GLES20.glUniform1f(fsrScaleLoc, upscalerMode.scaleFactor)
            GLES20.glUniform1f(fsrSharpnessLoc, upscalerMode.sharpness)
            GLES20.glUniform2f(videoResLoc, videoWidth.toFloat(), videoHeight.toFloat())

            drawQuad(posLoc, texLoc)

            if (offscreen) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, viewWidth, viewHeight)
                if (fsrActive) {
                    ensureFsrUpProgram()
                    if (fsrUpProgram != 0) {
                        GLES20.glUseProgram(fsrUpProgram)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, drsTexId)
                        GLES20.glUniform1i(fsrUpSamplerLoc, 0)
                        GLES20.glUniformMatrix4fv(fsrUpTexMatrixLoc, 1, false, identityMat, 0)
                        GLES20.glUniform1f(fsrUpVFlipLoc, 1f)
                        GLES20.glUniform2f(fsrUpTexelLoc, 1f / rw.coerceAtLeast(1), 1f / rh.coerceAtLeast(1))
                        GLES20.glUniform1f(fsrUpSharpLoc, upscalerMode.sharpness)
                        drawQuad(fsrUpPosLoc, fsrUpTexLoc)
                    } else {
                        ensureBlitProgram()
                        if (blitProgram != 0) {
                            GLES20.glUseProgram(blitProgram)
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, drsTexId)
                            GLES20.glUniform1i(blitSamplerLoc, 0)
                            GLES20.glUniformMatrix4fv(blitTexMatrixLoc, 1, false, identityMat, 0)
                            GLES20.glUniform1f(blitVFlipLoc, 1f)
                            drawQuad(blitPosLoc, blitTexLoc)
                        }
                    }
                } else {
                    ensureBlitProgram()
                    if (blitProgram != 0) {
                        GLES20.glUseProgram(blitProgram)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, drsTexId)
                        GLES20.glUniform1i(blitSamplerLoc, 0)
                        GLES20.glUniformMatrix4fv(blitTexMatrixLoc, 1, false, identityMat, 0)
                        GLES20.glUniform1f(blitVFlipLoc, 1f)
                        drawQuad(blitPosLoc, blitTexLoc)
                    }
                }
            }
        }

        private fun copyOldToPrev() {
            if (program == 0) return
            val w = viewWidth.coerceAtLeast(2)
            val h = viewHeight.coerceAtLeast(2)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, prevTexId, 0)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId)
            GLES20.glUniform1i(curTexLoc, 0)
            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, matrixOld, 0)
            GLES20.glUniform1f(vFlipLoc, 1f)
            GLES20.glUniform1f(dbgLoc, 0f)
            GLES20.glUniform1f(interpFactorLoc, 1f)
            GLES20.glUniform1f(modeLoc, 0f)
            GLES20.glUniform1f(enabledLoc, 0f)
            GLES20.glUniform1f(interpEnabledLoc, 0f)
            GLES20.glUniform1f(staticFlagLoc, 0f)
            GLES20.glUniform1f(upscalerLoc, 0f)
            GLES20.glUniform1f(fsrScaleLoc, 1f)
            GLES20.glUniform1f(fsrSharpnessLoc, 0.8f)
            drawQuad(posLoc, texLoc)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            prevReady = true
        }

        private fun downscaleCurr() {
            if (program == 0 || downTexId == 0) return
            val w = (viewWidth / 2).coerceAtLeast(2)
            val h = (viewHeight / 2).coerceAtLeast(2)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, downFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, downTexId, 0)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId)
            GLES20.glUniform1i(curTexLoc, 0)
            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform1f(vFlipLoc, 1f)
            GLES20.glUniform1f(dbgLoc, 0f)
            GLES20.glUniform1f(interpFactorLoc, 1f)
            GLES20.glUniform1f(modeLoc, 0f)
            GLES20.glUniform1f(enabledLoc, 0f)
            GLES20.glUniform1f(interpEnabledLoc, 0f)
            GLES20.glUniform1f(staticFlagLoc, 0f)
            GLES20.glUniform1f(upscalerLoc, 0f)
            GLES20.glUniform1f(fsrScaleLoc, 1f)
            GLES20.glUniform1f(fsrSharpnessLoc, 0.8f)
            drawQuad(posLoc, texLoc)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
        }

        private fun buildMotionMap() {
            if (motionProgram == 0 || motionW == 0 || motionH == 0) return
            if (coarseProgram != 0 && coarseTexId != 0 && coarseW > 0 && coarseH > 0) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, coarseTexId, 0)
                GLES20.glViewport(0, 0, coarseW, coarseH)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(coarseProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId)
                GLES20.glUniform1i(cCurTexLoc, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTexId)
                GLES20.glUniform1i(cPrevTexLoc, 1)
                GLES20.glUniform2f(cCoarseTexelLoc, 1f / coarseW, 1f / coarseH)
                GLES20.glUniformMatrix4fv(cTexMatrixLoc, 1, false, texMatrix, 0)
                GLES20.glUniform1f(cVFlipLoc, 1f)
                drawQuad(cPosLoc, cTexLoc)

                if (mvProbeFrames % 15 == 0 && debugMode > 0) {
                    try {
                        val mw = coarseW.coerceAtLeast(2)
                        val mh = coarseH.coerceAtLeast(2)
                        val buf = coarseProbeBuf
                            ?: java.nio.ByteBuffer.allocateDirect(mw * mh * 4).also { coarseProbeBuf = it }
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
                        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, coarseTexId, 0)
                        GLES20.glViewport(0, 0, mw, mh)
                        buf.rewind()
                        GLES20.glReadPixels(0, 0, mw, mh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                        buf.rewind()
                        var sx = 0f
                        var sy = 0f
                        var nz = 0
                        var cnt = 0
                        var large = 0
                        var qSum = 0f
                        var modeCount = 0
                        var modeX = 0
                        var modeY = 0
                        val hist = IntArray(49)
                        var off = 0
                        while (off < mw * mh * 4) {
                            val rx = (buf.get(off).toInt() and 0xFF) / 255f
                            val ry = (buf.get(off + 1).toInt() and 0xFF) / 255f
                            val fx = (rx - 0.5f) * 256f
                            val fy = (ry - 0.5f) * 256f
                            sx += fx
                            sy += fy
                            qSum += (buf.get(off + 2).toInt() and 0xFF) / 255f
                            if (Math.abs(fx) > 0.5f || Math.abs(fy) > 0.5f) nz++
                            if (Math.sqrt((fx * fx + fy * fy).toDouble()) >= 16.0) large++
                            val hi = ((Math.round(fx / 16f) + 3).coerceIn(0, 6)) * 7 + (Math.round(fy / 16f) + 3).coerceIn(0, 6)
                            val hc = ++hist[hi]
                            if (hc > modeCount) {
                                modeCount = hc
                                modeX = Math.round(fx / 16f)
                                modeY = Math.round(fy / 16f)
                            }
                            cnt++
                            off += 4
                        }
                        if (cnt > 0) Log.i(TAG, "coarseProbe mean=(${"%.1f".format(sx / cnt)},${"%.1f".format(sy / cnt)})px mag=${"%.1f".format(Math.sqrt((sx * sx + sy * sy).toDouble()) / cnt)}px mode=(${modeX},${modeY})x16 largePct=${(large * 100 / cnt)} q=${"%.2f".format(qSum / cnt)}")
                    } catch (t: Throwable) {
                        Log.w(TAG, "coarseProbe failed: ${t.message}")
                    }
                }
            }
            var target = motionAccumId
            if (target == 0) return
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target, 0)
            GLES20.glViewport(0, 0, motionW, motionH)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(motionProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId)
            GLES20.glUniform1i(mCurTexLoc, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTexId)
            GLES20.glUniform1i(mPrevTexLoc, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexId)
            GLES20.glUniform1i(mOldMotionTexLoc, 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, coarseTexId)
            GLES20.glUniform1i(mCoarseTexLoc, 3)
            GLES20.glUniform2f(mMotionTexelLoc, 2f / motionW, 2f / motionH)
            GLES20.glUniformMatrix4fv(mTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform1f(mVFlipLoc, 1f)
            GLES20.glUniform1f(mAlphaLoc, if (firstLatch) 1f else motionAlpha)
            drawQuad(mPosLoc, mTexLoc)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            val tmp = motionTexId
            motionTexId = motionAccumId
            motionAccumId = tmp

            if (mvProbeFrames++ % 15 == 0 && debugMode > 0) {
                try {
                    val mw = motionW.coerceAtLeast(2)
                    val mh = motionH.coerceAtLeast(2)
                    val buf = mvProbeBuf
                        ?: java.nio.ByteBuffer.allocateDirect(mw * mh * 4).also { mvProbeBuf = it }
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, motionTexId, 0)
                    GLES20.glViewport(0, 0, mw, mh)
                    buf.rewind()
                    GLES20.glReadPixels(0, 0, mw, mh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                    buf.rewind()
                        var sx = 0f
                        var sy = 0f
                        var cSum = 0f
                        var cCount = 0
                        var aSum = 0f
                        var nz = 0
                        var cnt = 0
                        var off = 0
                        while (off < mw * mh * 4) {
                            val rx = (buf.get(off).toInt() and 0xFF) / 255f
                            val ry = (buf.get(off + 1).toInt() and 0xFF) / 255f
                            val conf = (buf.get(off + 2).toInt() and 0xFF) / 255f
                            val magV = (buf.get(off + 3).toInt() and 0xFF) / 255f
                            val fx = (rx - 0.5f) * 16f * 8f
                            val fy = (ry - 0.5f) * 16f * 8f
                            sx += fx
                            sy += fy
                            cSum += conf
                            aSum += magV
                            if (Math.abs(fx) > 0.5f || Math.abs(fy) > 0.5f) nz++
                            cCount++
                            cnt++
                            off += 4
                        }
                        if (cnt > 0) Log.i(TAG, "mvProbe mean=(${"%.1f".format(sx / cnt)},${"%.1f".format(sy / cnt)})px mag=${"%.1f".format(Math.sqrt((sx * sx + sy * sy).toDouble()) / cnt)}px conf=${"%.2f".format(cSum / cCount)} magc=${"%.2f".format(aSum / cCount)} nz=${(nz * 100 / cnt)}")
                } catch (t: Throwable) {
                    Log.w(TAG, "mvProbe failed: ${t.message}")
                }
            }

            target = motionBwdAccumId
            if (motionBwdProgram == 0 || target == 0) return
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target, 0)
            GLES20.glViewport(0, 0, motionW, motionH)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(motionBwdProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTexId)
            GLES20.glUniform1i(bmCurTexLoc, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId)
            GLES20.glUniform1i(bmPrevTexLoc, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionBwdId)
            GLES20.glUniform1i(bmOldMotionTexLoc, 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, coarseTexId)
            GLES20.glUniform1i(bmCoarseTexLoc, 3)
            GLES20.glUniform2f(bmMotionTexelLoc, 2f / motionW, 2f / motionH)
            GLES20.glUniformMatrix4fv(bmTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform1f(bmVFlipLoc, 1f)
            GLES20.glUniform1f(bmAlphaLoc, if (firstLatch) 1f else motionAlpha)
            GLES20.glUniform1f(bmDirLoc, -1f)
            drawQuad(bmPosLoc, bmTexLoc)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            val tmpB = motionBwdId
            motionBwdId = motionBwdAccumId
            motionBwdAccumId = tmpB

            if (mvProbeFrames % 15 == 0 && debugMode > 0) {
                try {
                    val mw = motionW.coerceAtLeast(2)
                    val mh = motionH.coerceAtLeast(2)
                    val buf = bwdProbeBuf
                        ?: java.nio.ByteBuffer.allocateDirect(mw * mh * 4).also { bwdProbeBuf = it }
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, motionBwdId, 0)
                    GLES20.glViewport(0, 0, mw, mh)
                    buf.rewind()
                    GLES20.glReadPixels(0, 0, mw, mh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                    buf.rewind()
                    var sx = 0f
                    var sy = 0f
                    var cSum = 0f
                    var cCount = 0
                    var off = 0
                    while (off < mw * mh * 4) {
                        val rx = (buf.get(off).toInt() and 0xFF) / 255f
                        val ry = (buf.get(off + 1).toInt() and 0xFF) / 255f
                        val conf = (buf.get(off + 2).toInt() and 0xFF) / 255f
                        sx += (rx - 0.5f) * 128f
                        sy += (ry - 0.5f) * 128f
                        cSum += conf
                        cCount++
                        off += 4
                    }
                    if (cCount > 0) Log.i(TAG, "bwdProbe mean=(${"%.1f".format(sx / cCount)},${"%.1f".format(sy / cCount)})px mag=${"%.1f".format(Math.sqrt((sx * sx + sy * sy).toDouble()) / cCount)}px conf=${"%.2f".format(cSum / cCount)}")
                } catch (t: Throwable) {
                    Log.w(TAG, "bwdProbe failed: ${t.message}")
                }
            }
        }

        private fun computeGlobalMotion() {
            if (globalProgram == 0 || globalTexId == 0 || motionW == 0 || motionH == 0) return
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, globalFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, globalTexId, 0)
            GLES20.glViewport(0, 0, 16, 16)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(globalProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexId)
            GLES20.glUniform1i(gMotionTexLoc, 0)
            drawQuad(gPosLoc, gTexLoc)
            globalBuf.rewind()
            GLES20.glReadPixels(0, 0, 16, 16, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, globalBuf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            globalBuf.rewind()
            var n = 0
            var totalW = 0f
            for (i in 0 until 256) {
                val off = i * 4
                val wx = (globalBuf.get(off).toInt() and 0xFF) / 255f
                val wy = (globalBuf.get(off + 1).toInt() and 0xFF) / 255f
                val conf = (globalBuf.get(off + 2).toInt() and 0xFF) / 255f
                val mag = (globalBuf.get(off + 3).toInt() and 0xFF) / 255f
                val w = conf * mag
                if (w > 0.02f) {
                    globalXs[n] = wx * 2f - 1f
                    globalYs[n] = wy * 2f - 1f
                    globalWs[n] = w
                    totalW += w
                    n++
                }
            }
            if (totalW < 1.5f) {
                globalVecReady = false
                motionAlpha = 0.5f
                return
            }
            val hx = weightedMedian(globalXs, n, totalW / 2f)
            val hy = weightedMedian(globalYs, n, totalW / 2f)
            globalVec[0] = globalVec[0] * 0.4f + hx * 0.6f
            globalVec[1] = globalVec[1] * 0.4f + hy * 0.6f
            val speedPx = Math.hypot(globalVec[0].toDouble(), globalVec[1].toDouble()).toFloat() * 128f
            motionAlpha = 0.5f + 0.35f * (speedPx / 40f).coerceIn(0f, 1f)
            globalVecReady = true
            Log.i(TAG, "globalVec=(${"%.1f".format(globalVec[0] * 128f)},${"%.1f".format(globalVec[1] * 128f)})px n=${n}")
        }

        private fun weightedMedian(vals: FloatArray, n: Int, half: Float): Float {
            for (i in 0 until n) globalIdx[i] = i
            var i = 1
            while (i < n) {
                val key = globalIdx[i]
                var j = i - 1
                while (j >= 0 && vals[globalIdx[j]] > vals[key]) {
                    globalIdx[j + 1] = globalIdx[j]
                    j--
                }
                globalIdx[j + 1] = key
                i++
            }
            var acc = 0f
            for (i in 0 until n) {
                acc += globalWs[globalIdx[i]]
                if (acc >= half) return vals[globalIdx[i]]
            }
            return vals[globalIdx[n - 1]]
        }

        private fun readStaticLevel(): Float {
            if (staticProgram == 0 || staticTexId == 0) return 0f
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, staticFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, staticTexId, 0)
            GLES20.glViewport(0, 0, 16, 16)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(staticProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexId)
            GLES20.glUniform1i(sMotionTexLoc, 0)
            GLES20.glUniform1f(sVFlipLoc, 0f)
            drawQuad(sPosLoc, sTexLoc)
            staticPixelBuf.rewind()
            GLES20.glReadPixels(0, 0, 16, 16, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, staticPixelBuf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            staticPixelBuf.rewind()
            var maxLevel = 0
            for (i in 0 until 256) {
                val v = staticPixelBuf.get(i * 4).toInt() and 0xFF
                if (v > maxLevel) maxLevel = v
            }
            return maxLevel / 255f
        }

        private fun drawQuad(pos: Int, tex: Int) {
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quadVerts)
            GLES20.glEnableVertexAttribArray(tex)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(tex)
        }

        private fun ensureDrsTarget(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            if (drsTexId == 0 || drsW != w || drsH != h) {
                if (drsTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(drsTexId), 0)
                if (drsFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(drsFbo), 0)
                val texs = IntArray(1)
                GLES20.glGenTextures(1, texs, 0)
                drsTexId = texs[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, drsTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val fbos = IntArray(1)
                GLES20.glGenFramebuffers(1, fbos, 0)
                drsFbo = fbos[0]
                drsW = w
                drsH = h
            }
        }

        private fun ensureBlitProgram() {
            if (blitProgram != 0) return
            blitProgram = buildProgram(vertexShader, blitFragmentShader)
            if (blitProgram != 0) {
                blitPosLoc = GLES20.glGetAttribLocation(blitProgram, "aPosition")
                blitTexLoc = GLES20.glGetAttribLocation(blitProgram, "aTexCoord")
                blitSamplerLoc = GLES20.glGetUniformLocation(blitProgram, "uTex")
                blitTexMatrixLoc = GLES20.glGetUniformLocation(blitProgram, "uTexMatrix")
                blitVFlipLoc = GLES20.glGetUniformLocation(blitProgram, "uVFlip")
            }
        }

        private fun ensureFsrUpProgram() {
            if (fsrUpProgram != 0) return
            fsrUpProgram = buildProgram(vertexShader, fsrUpFragmentShader)
            if (fsrUpProgram != 0) {
                fsrUpPosLoc = GLES20.glGetAttribLocation(fsrUpProgram, "aPosition")
                fsrUpTexLoc = GLES20.glGetAttribLocation(fsrUpProgram, "aTexCoord")
                fsrUpSamplerLoc = GLES20.glGetUniformLocation(fsrUpProgram, "uTex")
                fsrUpTexMatrixLoc = GLES20.glGetUniformLocation(fsrUpProgram, "uTexMatrix")
                fsrUpVFlipLoc = GLES20.glGetUniformLocation(fsrUpProgram, "uVFlip")
                fsrUpTexelLoc = GLES20.glGetUniformLocation(fsrUpProgram, "uTexel")
                fsrUpSharpLoc = GLES20.glGetUniformLocation(fsrUpProgram, "uSharpness")
            }
        }

        fun getInputSurface(): Surface? {
            if (cachedOutputSurface == null) {
                cachedOutputSurface = inputSurfaceTexture?.let { Surface(it) }
            }
            return cachedOutputSurface
        }
        fun setVideoSize(w: Int, h: Int) { videoWidth = w; videoHeight = h }
        fun requestStop() { stopped = true }

        fun setDebugModeValue(mode: Int) {
            debugMode = mode.coerceIn(0, 7)
        }

        private fun buildProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val s = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, s, 0)
            if (s[0] == 0) { Log.e(TAG, "Link: ${GLES20.glGetProgramInfoLog(p)}"); return 0 }
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { Log.e(TAG, "Compile: ${GLES20.glGetShaderInfoLog(s)}"); return 0 }
            return s
        }

        private fun buf(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(data).also { it.position(0) }
    }

    companion object {
        private const val TAG = "Media3-60fps"
        private const val STATIC_THRESHOLD = 0.04f
        private const val STATIC_READ_INTERVAL = 6
        private const val STALL_RESET_NS = 1_500_000_000L
    }
}
