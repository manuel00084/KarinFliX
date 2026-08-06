package com.karin.streamtv.player

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
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
    @Volatile private var inputSurface: Surface? = null
    var onGlFailure: (() -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun createPlayer(trackSelector: DefaultTrackSelector? = null, dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null): ExoPlayer {
        val renderersFactory = CodecSelectorFactory.renderersFactory(context)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20000,
                80000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector ?: TrackSelectorFactory.create(context))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(dataSourceFactory ?: VideoDataSource.factory(context, referer))
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
                    renderer = null
                }
            }
        } else if (r != null) {
            try {
                glSurface.queueEvent { r.cleanupGl() }
            } catch (_: Exception) {}
            surface?.release()
            p?.release()
            renderer = null
        } else {
            surface?.release()
            p?.release()
        }
        player = null
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
        private var motionTexStorageAllocated = false
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
        private var bicubicProgram = 0; private var bicubicPosLoc = -1; private var bicubicTexLoc = -1; private var bicubicSamplerLoc = -1; private var bicubicTexMatrixLoc = -1; private var bicubicVFlipLoc = -1; private var bicubicTexelLoc = -1
        private var dogLumaProgram = 0; private var dogLumaPosLoc = -1; private var dogLumaTexLoc = -1; private var dogLumaSamplerLoc = -1; private var dogLumaTexMatrixLoc = -1; private var dogLumaVFlipLoc = -1
        private var dogGaussXProgram = 0; private var dogGaussXPosLoc = -1; private var dogGaussXTexLoc = -1; private var dogGaussXSamplerLoc = -1; private var dogGaussXTexMatrixLoc = -1; private var dogGaussXVFlipLoc = -1; private var dogGaussXTexelLoc = -1
        private var dogGaussYProgram = 0; private var dogGaussYPosLoc = -1; private var dogGaussYTexLoc = -1; private var dogGaussYSamplerLoc = -1; private var dogGaussYTexMatrixLoc = -1; private var dogGaussYVFlipLoc = -1; private var dogGaussYTexelLoc = -1
        private var dogApplyProgram = 0; private var dogApplyPosLoc = -1; private var dogApplyTexLoc = -1; private var dogApplyInputSamplerLoc = -1; private var dogApplyGaussSamplerLoc = -1; private var dogApplyTexMatrixLoc = -1; private var dogApplyVFlipLoc = -1; private var dogApplyStrengthLoc = -1
        private var fsrEasuProgram = 0; private var fsrEasuPosLoc = -1; private var fsrEasuTexLoc = -1; private var fsrEasuSamplerLoc = -1; private var fsrEasuTexMatrixLoc = -1; private var fsrEasuVFlipLoc = -1; private var fsrEasuInputSizeLoc = -1; private var fsrEasuOutputSizeLoc = -1
        private var fsrRcasProgram = 0; private var fsrRcasPosLoc = -1; private var fsrRcasTexLoc = -1; private var fsrRcasSamplerLoc = -1; private var fsrRcasTexMatrixLoc = -1; private var fsrRcasVFlipLoc = -1; private var fsrRcasTexelLoc = -1; private var fsrRcasSharpLoc = -1
        private var dogFBO1 = 0; private var dogTex1 = 0; private var dogFBO2 = 0; private var dogTex2 = 0
        private var fsrIntermediateFBO = 0; private var fsrIntermediateTex = 0
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

        private val bicubicFragmentShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            float cubic(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return -0.5*x3 + x2 - 0.5*x;
            }
            float cubic2(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return 1.5*x3 - 2.5*x2 + 1.0;
            }
            float cubic3(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return -1.5*x3 + 2.0*x2 + 0.5*x;
            }
            float cubic4(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return 0.5*x3 - 0.5*x2;
            }
            vec4 textureBicubic(sampler2D tex, vec2 texCoords, vec2 texelSize) {
                vec2 texel = texCoords / texelSize - 0.5;
                vec2 f = fract(texel);
                vec2 texelFloor = floor(texel);
                vec4 cx = vec4(cubic(f.x), cubic2(f.x), cubic3(f.x), cubic4(f.x));
                vec4 cy = vec4(cubic(f.y), cubic2(f.y), cubic3(f.y), cubic4(f.y));
                vec4 c = cx.x * (cy.x * texture2D(tex, (texelFloor + vec2(-1.0, -1.0)) * texelSize) +
                                 cy.y * texture2D(tex, (texelFloor + vec2(-1.0, 0.0)) * texelSize) +
                                 cy.z * texture2D(tex, (texelFloor + vec2(-1.0, 1.0)) * texelSize) +
                                 cy.w * texture2D(tex, (texelFloor + vec2(-1.0, 2.0)) * texelSize));
                c += cx.y * (cy.x * texture2D(tex, (texelFloor + vec2(0.0, -1.0)) * texelSize) +
                             cy.y * texture2D(tex, (texelFloor + vec2(0.0, 0.0)) * texelSize) +
                             cy.z * texture2D(tex, (texelFloor + vec2(0.0, 1.0)) * texelSize) +
                             cy.w * texture2D(tex, (texelFloor + vec2(0.0, 2.0)) * texelSize));
                c += cx.z * (cy.x * texture2D(tex, (texelFloor + vec2(1.0, -1.0)) * texelSize) +
                             cy.y * texture2D(tex, (texelFloor + vec2(1.0, 0.0)) * texelSize) +
                             cy.z * texture2D(tex, (texelFloor + vec2(1.0, 1.0)) * texelSize) +
                             cy.w * texture2D(tex, (texelFloor + vec2(1.0, 2.0)) * texelSize));
                c += cx.w * (cy.x * texture2D(tex, (texelFloor + vec2(2.0, -1.0)) * texelSize) +
                             cy.y * texture2D(tex, (texelFloor + vec2(2.0, 0.0)) * texelSize) +
                             cy.z * texture2D(tex, (texelFloor + vec2(2.0, 1.0)) * texelSize) +
                             cy.w * texture2D(tex, (texelFloor + vec2(2.0, 2.0)) * texelSize));
                return c;
            }
            void main() {
                gl_FragColor = textureBicubic(uTex, vTexCoord, uTexel);
            }
        """.trimIndent()

        private val dogLumaShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            void main() {
                vec4 c = texture2D(uTex, vTexCoord);
                float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                gl_FragColor = vec4(luma, 0.0, 0.0, 1.0);
            }
        """.trimIndent()

        private val dogGaussXShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            float max3v(float a, float b, float c) { return max(max(a, b), c); }
            float min3v(float a, float b, float c) { return min(min(a, b), c); }
            vec2 minmax3(vec2 pos, vec2 d) {
                float a = texture2D(uTex, pos - d).x;
                float b = texture2D(uTex, pos).x;
                float c = texture2D(uTex, pos + d).x;
                return vec2(min3v(a, b, c), max3v(a, b, c));
            }
            float lumGaussian7(vec2 pos, vec2 d) {
                float g = (texture2D(uTex, pos - (d + d)).x + texture2D(uTex, pos + (d + d)).x) * 0.06136;
                g += (texture2D(uTex, pos - d).x + texture2D(uTex, pos + d).x) * 0.24477;
                g += texture2D(uTex, pos).x * 0.38774;
                return g;
            }
            void main() {
                vec2 d = vec2(uTexel.x, 0.0);
                gl_FragColor = vec4(lumGaussian7(vTexCoord, d), minmax3(vTexCoord, d), 1.0);
            }
        """.trimIndent()

        private val dogGaussYShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            float max3v(float a, float b, float c) { return max(max(a, b), c); }
            float min3v(float a, float b, float c) { return min(min(a, b), c); }
            vec2 minmax3(vec2 pos, vec2 d) {
                float a0 = texture2D(uTex, pos - d).y;
                float b0 = texture2D(uTex, pos).y;
                float c0 = texture2D(uTex, pos + d).y;
                float a1 = texture2D(uTex, pos - d).z;
                float b1 = texture2D(uTex, pos).z;
                float c1 = texture2D(uTex, pos + d).z;
                return vec2(min3v(a0, b0, c0), max3v(a1, b1, c1));
            }
            float lumGaussian7(vec2 pos, vec2 d) {
                float g = (texture2D(uTex, pos - (d + d)).x + texture2D(uTex, pos + (d + d)).x) * 0.06136;
                g += (texture2D(uTex, pos - d).x + texture2D(uTex, pos + d).x) * 0.24477;
                g += texture2D(uTex, pos).x * 0.38774;
                return g;
            }
            void main() {
                vec2 d = vec2(0.0, uTexel.y);
                gl_FragColor = vec4(lumGaussian7(vTexCoord, d), minmax3(vTexCoord, d), 1.0);
            }
        """.trimIndent()

        private val dogApplyShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uInput;
            uniform sampler2D uGauss;
            uniform float uStrength;
            void main() {
                float lumaOrig = dot(texture2D(uInput, vTexCoord).rgb, vec3(0.299, 0.587, 0.114));
                vec4 gauss = texture2D(uGauss, vTexCoord);
                float diff = lumaOrig - gauss.x;
                float cc = clamp(diff * uStrength + lumaOrig, gauss.y, gauss.z) - lumaOrig;
                vec4 inCol = texture2D(uInput, vTexCoord);
                gl_FragColor = vec4(inCol.rgb + vec3(cc), 1.0);
            }
        """.trimIndent()

        private val fsrEasuShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uInputSize;
            uniform vec2 uOutputSize;
            vec3 FsrEasuCF(vec2 p) { return texture2D(uTex, p).rgb; }
            void main() {
                vec2 pp = vTexCoord;
                vec3 b = FsrEasuCF(pp + vec2(0.0, -1.0) / uInputSize);
                vec3 l = FsrEasuCF(pp + vec2(-1.0, 0.0) / uInputSize);
                vec3 g = FsrEasuCF(pp);
                vec3 r = FsrEasuCF(pp + vec2(1.0, 0.0) / uInputSize);
                vec3 t = FsrEasuCF(pp + vec2(0.0, 1.0) / uInputSize);
                float bL = dot(b, vec3(0.2126, 0.7152, 0.0722));
                float lL = dot(l, vec3(0.2126, 0.7152, 0.0722));
                float gL = dot(g, vec3(0.2126, 0.7152, 0.0722));
                float rL = dot(r, vec3(0.2126, 0.7152, 0.0722));
                float tL = dot(t, vec3(0.2126, 0.7152, 0.0722));
                vec2 dir = vec2((rL - lL), (tL - bL));
                float d2 = dir.x * dir.x + dir.y * dir.y;
                dir = dir * inversesqrt(max(d2, 0.00001));
                vec3 sp1 = FsrEasuCF(pp + dir / uInputSize);
                vec3 sp2 = FsrEasuCF(pp - dir / uInputSize);
                vec3 mnv = min(g, min(min(b, l), min(r, t)));
                vec3 mxv = max(g, max(max(b, l), max(r, t)));
                vec3 result = g + (dir.x * (r - l) + dir.y * (t - b)) * 0.5;
                result = clamp(result, mnv, mxv);
                result = mix(g, result, 0.5);
                gl_FragColor = vec4(result, 1.0);
            }
        """.trimIndent()

        private val fsrRcasShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uSharpness;
            void main() {
                vec2 sp = vTexCoord;
                vec3 b = texture2D(uTex, sp + vec2(0.0, -uTexel.y)).rgb;
                vec3 d = texture2D(uTex, sp + vec2(-uTexel.x, 0.0)).rgb;
                vec3 e = texture2D(uTex, sp).rgb;
                vec3 f = texture2D(uTex, sp + vec2(uTexel.x, 0.0)).rgb;
                vec3 h = texture2D(uTex, sp + vec2(0.0, uTexel.y)).rgb;
                float bL = b.b*0.5+(b.r*0.5+b.g);
                float dL = d.b*0.5+(d.r*0.5+d.g);
                float eL = e.b*0.5+(e.r*0.5+e.g);
                float fL = f.b*0.5+(f.r*0.5+f.g);
                float hL = h.b*0.5+(h.r*0.5+h.g);
                float nz = 0.25*bL+0.25*dL+0.25*fL+0.25*hL-eL;
                float maxL = max(max(bL, dL), max(fL, hL));
                float minL = min(min(bL, dL), min(fL, hL));
                nz = clamp(abs(nz)/max(maxL-minL, 0.0001), 0.0, 1.0);
                nz = -0.5*nz+1.0;
                float mn4R=min(min(b.r,d.r),min(f.r,h.r));
                float mn4G=min(min(b.g,d.g),min(f.g,h.g));
                float mn4B=min(min(b.b,d.b),min(f.b,h.b));
                float mx4R=max(max(b.r,d.r),max(f.r,h.r));
                float mx4G=max(max(b.g,d.g),max(f.g,h.g));
                float mx4B=max(max(b.b,d.b),max(f.b,h.b));
                float hitMinR=min(mn4R,e.r)/(4.0*mx4R+0.0001);
                float hitMinG=min(mn4G,e.g)/(4.0*mx4G+0.0001);
                float hitMinB=min(mn4B,e.b)/(4.0*mx4B+0.0001);
                float hitMaxR=(1.0-max(mx4R,e.r))/(4.0*mn4R-4.0+0.0001);
                float hitMaxG=(1.0-max(mx4G,e.g))/(4.0*mn4G-4.0+0.0001);
                float hitMaxB=(1.0-max(mx4B,e.b))/(4.0*mn4B-4.0+0.0001);
                float lobe=max(-0.25,min(max(max(max(-hitMinR,hitMaxR),max(-hitMinG,hitMaxG)),max(-hitMinB,hitMaxB)),0.0))*uSharpness;
                lobe*=nz;
                float rcpL=1.0/(4.0*lobe+1.0);
                gl_FragColor=vec4((lobe*b.r+lobe*d.r+lobe*h.r+lobe*f.r+e.r)*rcpL,(lobe*b.g+lobe*d.g+lobe*h.g+lobe*f.g+e.g)*rcpL,(lobe*b.b+lobe*d.b+lobe*h.b+lobe*f.b+e.b)*rcpL,1.0);
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

        @Volatile private var stopped = false

        private var viewWidth = 0
        private var viewHeight = 0
        @Volatile private var videoWidth = 1920
        @Volatile private var videoHeight = 1080
        private var frameCount = 0L
        private val texMatrix = FloatArray(16)
        private val matrixOld = FloatArray(16)
        private var lastFpsTimeNs = 0L
        private var fpsFrames = 0
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
        private var deblockLoc = -1
        private var desRingingLoc = -1
        private var localContrastLoc = -1
        private var grainLoc = -1
        private var grainSeedLoc = -1
        private var dehazeLoc = -1
        private var adaptiveSharpLoc = -1
        private var tintLoc = -1
        private var hdrLoc = -1
        private var detailBoostLoc = -1
        private var lightBoostLoc = -1
         private var lowBitrateBoostLoc = -1
        private var dbgLoc = -1
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
            if (lastFpsTimeNs == 0L) lastFpsTimeNs = now
            val elapsed = now - lastFpsTimeNs
            if (elapsed >= 1_000_000_000L) {
                outputFps = fpsFrames * 1_000_000_000f / elapsed
                if (fpsFrames > 0) frameMs = (fpsRenderNs / 1_000_000f) / fpsFrames
                if (!staticScene && VideoEnhanceConfig.getUpscalerMode() == VideoEnhanceConfig.UpscalerMode.OFF) {
                    if (!interpolationActive) {
                        if (renderScale < 1f) {
                            renderScale = 1f
                            Log.i(TAG, "DRS full-res scale=${renderScale}")
                        }
                        lowFpsStreak = 0
                        highFpsStreak = 0
                    } else if (outputFps < 28f && renderScale > 0.7f) {
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
            listOf(bicubicProgram, dogLumaProgram, dogGaussXProgram, dogGaussYProgram, dogApplyProgram, fsrEasuProgram, fsrRcasProgram).forEach { if (it != 0) GLES20.glDeleteProgram(it) }
            bicubicProgram = 0; dogLumaProgram = 0; dogGaussXProgram = 0; dogGaussYProgram = 0; dogApplyProgram = 0; fsrEasuProgram = 0; fsrRcasProgram = 0
            if (prevFbo != 0 || motionFbo != 0 || staticFbo != 0 || downFbo != 0 || globalFbo != 0 || drsFbo != 0) {
                GLES20.glDeleteFramebuffers(6, intArrayOf(prevFbo, motionFbo, staticFbo, downFbo, globalFbo, drsFbo), 0)
                prevFbo = 0; motionFbo = 0; staticFbo = 0; downFbo = 0; globalFbo = 0; drsFbo = 0
            }
            if (inputTexId != 0 || prevTexId != 0 || motionTexId != 0 || staticTexId != 0 || motionAccumId != 0 || downTexId != 0 || globalTexId != 0 || coarseTexId != 0 || motionBwdId != 0 || motionBwdAccumId != 0 || drsTexId != 0) {
                GLES20.glDeleteTextures(11, intArrayOf(inputTexId, prevTexId, motionTexId, staticTexId, motionAccumId, downTexId, globalTexId, coarseTexId, motionBwdId, motionBwdAccumId, drsTexId), 0)
                inputTexId = 0; prevTexId = 0; motionTexId = 0; staticTexId = 0; motionAccumId = 0; downTexId = 0; globalTexId = 0; coarseTexId = 0; motionBwdId = 0; motionBwdAccumId = 0; drsTexId = 0
            }
            if (dogFBO1 != 0 || dogFBO2 != 0) { GLES20.glDeleteFramebuffers(2, intArrayOf(dogFBO1, dogFBO2), 0); dogFBO1 = 0; dogFBO2 = 0 }
            if (dogTex1 != 0 || dogTex2 != 0) { GLES20.glDeleteTextures(2, intArrayOf(dogTex1, dogTex2), 0); dogTex1 = 0; dogTex2 = 0 }
            if (fsrIntermediateFBO != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(fsrIntermediateFBO), 0); fsrIntermediateFBO = 0 }
            if (fsrIntermediateTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(fsrIntermediateTex), 0); fsrIntermediateTex = 0 }
            inputSurfaceTexture?.release()
            inputSurfaceTexture = null
            cachedOutputSurface?.release()
            cachedOutputSurface = null
            inputSurface?.release()
            inputSurface = null
            prevReady = false
            staticScene = false
            motionTexStorageAllocated = false
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
            uniform float uAdaptiveSharp;
            uniform float uColorBoost;
            uniform float uDenoise;
            uniform float uDeband;
            uniform float uDeblock;
            uniform float uDesRinging;
            uniform float uLocalContrast;
            uniform float uGrain;
            uniform float uGrainSeed;
            uniform float uDehaze;
            uniform float uTint;
            uniform float uHdr;
            uniform float uDetailBoost;
            uniform float uLightBoost;
             uniform float uLowBitrateBoost;
            uniform float uDbgMode;
            uniform vec2 uVideoRes;

            vec3 adjustSaturation(vec3 c, float s) {
                float g = dot(c, vec3(0.2126, 0.7152, 0.0722));
                vec3 sat = mix(vec3(g), c, s);
                float skinMask = smoothstep(0.15, 0.08, abs(sat.r - sat.g)) * smoothstep(0.15, 0.05, sat.r - sat.b);
                skinMask *= step(0.3, sat.r) * step(sat.r, 0.75) * step(0.15, sat.g) * step(sat.g, 0.65);
                float skinProtect = 1.0 - skinMask * clamp(s - 1.0, 0.0, 1.0) * 0.4;
                return mix(sat, mix(vec3(g), c, 1.0 + (s - 1.0) * 0.6), skinProtect);
            }

            float lumaOf(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            // Gamut boost estilo Splash: expande la croma (gama de colores) con
            // curva "vibrance" adaptativa. Protege highlights, sombras y piel, y
            // evita recorte limitando el factor por pixel para que el color no se aplane.
            vec3 gamutBoost(vec3 c, float s) {
                if (abs(s - 1.0) < 0.001) return c;
                float luma = lumaOf(c);
                vec3 dev = c - vec3(luma);
                float chroma = length(dev);
                float sat = chroma / max(luma * 2.0, 0.0001);
                // vibrance: colores apagados se expanden mucho, vivos apenas se tocan
                float vibrance = 1.0 - smoothstep(0.25, 0.85, sat);
                float f = 1.0 + (s - 1.0) * (0.6 + 0.4 * vibrance);
                // proteccion highlights (evita quemar luces)
                float hl = smoothstep(0.65, 0.9, luma);
                f = mix(f, 1.0, hl * 0.6);
                // proteccion sombras profundas (evita ruido en oscuros)
                float sh = smoothstep(0.0, 0.1, luma);
                f = mix(f, 1.0, (1.0 - sh) * 0.8);
                // proteccion tonos de piel
                float skinMask = smoothstep(0.15, 0.08, abs(c.r - c.g)) * smoothstep(0.15, 0.05, c.r - c.b);
                skinMask *= step(0.3, c.r) * step(c.r, 0.75) * step(0.15, c.g) * step(c.g, 0.65);
                f = mix(f, 1.0 + (s - 1.0) * 0.35, skinMask);
                // limite de gama por pixel: no deja que ningun canal rebase [0,1]
                float maxDev = max(dev.r, max(dev.g, dev.b));
                float minDev = min(dev.r, min(dev.g, dev.b));
                float fUp = (1.0 - luma) / max(maxDev, 0.00001);
                float fDn = luma / max(-minDev, 0.00001);
                f = min(f, min(fUp, fDn));
                vec3 outC = vec3(luma) + dev * f;
                return clamp(outC, 0.0, 1.0);
            }

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            }

            // Quita el "ringing" (halo/ecos) alrededor de bordes: detecta oscilación de luma en la dirección del gradiente.
            vec3 desRinging(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 h = texel * 1.0;
                vec3 r = texture2D(uCurrTex, uv + vec2(h.x, 0.0)).rgb;
                vec3 l = texture2D(uCurrTex, uv - vec2(h.x, 0.0)).rgb;
                vec3 t = texture2D(uCurrTex, uv + vec2(0.0, h.y)).rgb;
                vec3 b = texture2D(uCurrTex, uv - vec2(0.0, h.y)).rgb;
                vec3 r2 = texture2D(uCurrTex, uv + vec2(h.x * 2.0, 0.0)).rgb;
                vec3 l2 = texture2D(uCurrTex, uv - vec2(h.x * 2.0, 0.0)).rgb;
                vec3 t2 = texture2D(uCurrTex, uv + vec2(0.0, h.y * 2.0)).rgb;
                vec3 b2 = texture2D(uCurrTex, uv - vec2(0.0, h.y * 2.0)).rgb;
                float lc = lumaOf(color);
                float lr = lumaOf(r); float ll = lumaOf(l);
                float lt = lumaOf(t); float lb = lumaOf(b);
                float lr2 = lumaOf(r2); float ll2 = lumaOf(l2);
                float lt2 = lumaOf(t2); float lb2 = lumaOf(b2);
                float oscH1 = (lr - lc) * (ll - lc);
                float oscH2 = (lr2 - lr) * (lc - lr);
                float ringH = smoothstep(0.0, 0.02, oscH1) * smoothstep(0.0, 0.02, oscH2);
                float oscV1 = (lt - lc) * (lb - lc);
                float oscV2 = (lt2 - lt) * (lc - lt);
                float ringV = smoothstep(0.0, 0.02, oscV1) * smoothstep(0.0, 0.02, oscV2);
                float ring = clamp(max(ringH, ringV), 0.0, 1.0);
                vec3 avg = (t + b + l + r) * 0.25;
                return mix(color, avg, ring * strength * 0.8);
            }

            // Ajusta la temperatura de color: positivo = cálido (rojo), negativo = frío (azul).
            vec3 applyTint(vec3 c, float t) {
                float luma = lumaOf(c);
                if (t > 0.0) {
                    c.r = mix(c.r, min(c.r + t * 0.6, 1.0), 0.8 + luma * 0.2);
                    c.g = mix(c.g, c.g + t * 0.15, 0.7);
                    c.b = mix(c.b, c.b * (1.0 - t * 0.5), 0.8);
                } else {
                    float tt = -t;
                    c.r = mix(c.r, c.r * (1.0 - tt * 0.5), 0.8);
                    c.g = mix(c.g, c.g + tt * 0.08, 0.7);
                    c.b = mix(c.b, min(c.b + tt * 0.6, 1.0), 0.8 + luma * 0.2);
                }
                return clamp(c, 0.0, 1.0);
            }

            // Deblock por CONTENIDO y periodicidad: detecta el nodo de bloque (borde de celda
            // de 8px) por salto de luma a 1px + planitud interna de cada celda + repetición del
            // salto a ±8px (firma de rejilla). Suaviza hacia la media de ambas celdas con clamp
            // al rango local: no dibuja malla sobre textura real ni crea halos.
            float gridScore(float stepC, float cellFlat, float periodic) {
                float flatFactor = 1.0 - smoothstep(0.002, 0.03, cellFlat);
                float perFactor = clamp((periodic - stepC * 0.3) / (stepC * 0.55 + 0.0001), 0.0, 1.0);
                return stepC * flatFactor * perFactor;
            }

            vec3 deblock(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 m = 1.0 / uVideoRes;
                vec3 n1 = texture2D(uCurrTex, uv + vec2(0.0, m.y)).rgb;
                vec3 n2 = texture2D(uCurrTex, uv - vec2(0.0, m.y)).rgb;
                vec3 n3 = texture2D(uCurrTex, uv + vec2(m.x, 0.0)).rgb;
                vec3 n4 = texture2D(uCurrTex, uv - vec2(m.x, 0.0)).rgb;
                vec3 f1 = texture2D(uCurrTex, uv + vec2(0.0, m.y * 4.0)).rgb;
                vec3 f2 = texture2D(uCurrTex, uv - vec2(0.0, m.y * 4.0)).rgb;
                vec3 f3 = texture2D(uCurrTex, uv + vec2(m.x * 4.0, 0.0)).rgb;
                vec3 f4 = texture2D(uCurrTex, uv - vec2(m.x * 4.0, 0.0)).rgb;
                vec3 g1 = texture2D(uCurrTex, uv + vec2(0.0, m.y * 8.0)).rgb;
                vec3 g2 = texture2D(uCurrTex, uv - vec2(0.0, m.y * 8.0)).rgb;
                vec3 g3 = texture2D(uCurrTex, uv + vec2(m.x * 8.0, 0.0)).rgb;
                vec3 g4 = texture2D(uCurrTex, uv - vec2(m.x * 8.0, 0.0)).rgb;

                float stepV = abs(lumaOf(n1) - lumaOf(n2));
                float stepH = abs(lumaOf(n3) - lumaOf(n4));
                // Planitud DENTRO de cada celda (1px vs 4px, misma celda de 8px).
                float cellFlatV = max(abs(lumaOf(n1) - lumaOf(f1)), abs(lumaOf(n2) - lumaOf(f2)));
                float cellFlatH = max(abs(lumaOf(n3) - lumaOf(f3)), abs(lumaOf(n4) - lumaOf(f4)));
                // Periodicidad: el mismo salto se repite a ±8px (siguientes nodos de rejilla).
                float periodicV = max(abs(lumaOf(f1) - lumaOf(g1)), abs(lumaOf(f2) - lumaOf(g2)));
                float periodicH = max(abs(lumaOf(f3) - lumaOf(g3)), abs(lumaOf(f4) - lumaOf(g4)));

                float blockV = gridScore(stepV, cellFlatV, periodicV);
                float blockH = gridScore(stepH, cellFlatH, periodicH);
                float blockiness = max(blockV, blockH);
                float amount = smoothstep(0.015, 0.09, blockiness) * strength;
                amount = min(amount, 0.85);
                // Media de las dos celdas (muestreo a 4px) con clamp al rango local para no crear halo.
                vec3 blendV = clamp((f1 + f2) * 0.5, min(min(n1, n2), min(f1, f2)), max(max(n1, n2), max(f1, f2)));
                vec3 blendH = clamp((f3 + f4) * 0.5, min(min(n3, n4), min(f3, f4)), max(max(n3, n4), max(f3, f4)));
                float wV = stepV / (stepV + stepH + 0.0001);
                vec3 result = color;
                result = mix(result, blendV, amount * wV);
                result = mix(result, blendH, amount * (1.0 - wV));
                return clamp(result, 0.0, 1.0);
            }

            // Contraste local estilo CLAHE-lite: compara con la luma media local y empuja hacia los extremos.
            vec3 localContrast(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 h1 = texel * 2.0;
                vec3 n1 = texture2D(uCurrTex, uv + vec2(0.0, h1.y)).rgb
                        + texture2D(uCurrTex, uv - vec2(0.0, h1.y)).rgb
                        + texture2D(uCurrTex, uv + vec2(h1.x, 0.0)).rgb
                        + texture2D(uCurrTex, uv - vec2(h1.x, 0.0)).rgb
                        + texture2D(uCurrTex, uv).rgb;
                vec3 mean1 = n1 * 0.2;
                vec2 h2 = texel * 6.0;
                vec3 n2 = texture2D(uCurrTex, uv + vec2(0.0, h2.y)).rgb
                        + texture2D(uCurrTex, uv - vec2(0.0, h2.y)).rgb
                        + texture2D(uCurrTex, uv + vec2(h2.x, 0.0)).rgb
                        + texture2D(uCurrTex, uv - vec2(h2.x, 0.0)).rgb
                        + texture2D(uCurrTex, uv).rgb;
                vec3 mean2 = n2 * 0.2;
                float lm1 = lumaOf(mean1);
                float lm2 = lumaOf(mean2);
                float lc = lumaOf(color);
                float shift1 = (lc - lm1) * strength * 1.2;
                float shift2 = (lc - lm2) * strength * 0.6;
                float combined = shift1 + shift2;
                vec3 result = color + combined;
                float edgeProtect = smoothstep(0.02, 0.08, abs(combined));
                result = mix(color, result, 0.5 + edgeProtect * 0.5);
                return clamp(result, 0.0, 1.0);
            }

            // Granado fílmico coherente y animado por semilla de frame.
            vec3 addGrain(vec3 color, vec2 uv, float amount) {
                float g1 = (hash(uv * 57.0 + uGrainSeed) - 0.5);
                float g2 = (hash(uv * 113.0 + uGrainSeed * 1.7) - 0.5);
                float g = (g1 * 0.7 + g2 * 0.3) * amount;
                float flicker = (hash(vec2(uGrainSeed * 0.1, 0.0)) - 0.5) * amount * 0.15;
                float luma = lumaOf(color);
                float grainLuma = mix(1.0, 0.4, luma);
                return clamp(color + g * grainLuma + flicker, 0.0, 1.0);
            }

            // Quita gris lavado: sube el contraste global sutil con clamp asimétrico según la luma media local.
            vec3 dehaze(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 h = texel * 4.0;
                vec3 s0 = texture2D(uCurrTex, uv).rgb;
                vec3 s1 = texture2D(uCurrTex, uv + vec2(0.0, h.y)).rgb;
                vec3 s2 = texture2D(uCurrTex, uv - vec2(0.0, h.y)).rgb;
                vec3 s3 = texture2D(uCurrTex, uv + vec2(h.x, 0.0)).rgb;
                vec3 s4 = texture2D(uCurrTex, uv - vec2(h.x, 0.0)).rgb;
                vec3 s5 = texture2D(uCurrTex, uv + vec2(h.x, h.y)).rgb;
                vec3 s6 = texture2D(uCurrTex, uv - vec2(h.x, h.y)).rgb;
                vec3 s7 = texture2D(uCurrTex, uv + vec2(-h.x, h.y)).rgb;
                vec3 s8 = texture2D(uCurrTex, uv + vec2(h.x, -h.y)).rgb;
                vec3 darkMin = min(min(min(s1, s2), min(s3, s4)), min(min(s5, s6), min(s7, s8)));
                darkMin = min(darkMin, s0);
                float darkChannel = lumaOf(darkMin);
                float atmosLight = lumaOf(color);
                float transmission = 1.0 - darkChannel * strength * 1.5;
                transmission = clamp(transmission, 0.2, 1.0);
                vec3 result = (color - darkChannel * strength * 0.15) / max(transmission, 0.3);
                float localContrast = smoothstep(0.1, 0.6, atmosLight) * strength * 0.25;
                result = mix(result, result * (1.0 + localContrast), localContrast);
                return clamp(result, 0.0, 1.0);
            }

            vec3 applyHdr(vec3 color, float strength) {
                float luma = lumaOf(color);
                float a = 2.51;
                float b = 0.03;
                float c = 2.43;
                float d = 0.59;
                float e = 0.14;
                vec3 toneMapped = clamp((color * (a * color + b)) / (color * (c * color + d) + e), 0.0, 1.0);
                float shadowMask = smoothstep(0.4, 0.0, luma) * strength;
                vec3 lifted = toneMapped + shadowMask * 0.25;
                float highlightMask = smoothstep(0.6, 1.0, luma) * strength;
                vec3 highlightTint = vec3(0.95, 1.0, 1.1);
                vec3 brightened = lifted + highlightMask * 0.15 * highlightTint;
                float localCont = (luma - 0.5) * strength * 0.8;
                vec3 contrastResult = brightened * (1.0 + localCont);
                float satBoost = 1.0 + strength * 0.4 * (1.0 - smoothstep(0.1, 0.5, luma));
                vec3 satResult = mix(vec3(lumaOf(contrastResult)), contrastResult, satBoost);
                vec3 result = mix(contrastResult, satResult, strength * 0.5);
                float gamma = 1.0 - strength * 0.1;
                result = pow(clamp(result, 0.0, 1.0), vec3(gamma));
                float bloom = smoothstep(0.7, 1.0, luma) * strength * 0.1;
                result += bloom * 0.05;
                return clamp(result, 0.0, 1.0);
            }

            // Detail Boost estilo Splash: smart sharpening espacial + realce de color y micro-contraste.
            vec3 detailBoost(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 txl = texel;
                vec3 n  = texture2D(uCurrTex, uv + vec2(0.0, -txl.y)).rgb;
                vec3 s  = texture2D(uCurrTex, uv + vec2(0.0,  txl.y)).rgb;
                vec3 w  = texture2D(uCurrTex, uv + vec2(-txl.x, 0.0)).rgb;
                vec3 e  = texture2D(uCurrTex, uv + vec2( txl.x, 0.0)).rgb;
                vec3 nw = texture2D(uCurrTex, uv + vec2(-txl.x, -txl.y)).rgb;
                vec3 ne = texture2D(uCurrTex, uv + vec2( txl.x, -txl.y)).rgb;
                vec3 sw = texture2D(uCurrTex, uv + vec2(-txl.x,  txl.y)).rgb;
                vec3 se = texture2D(uCurrTex, uv + vec2( txl.x,  txl.y)).rgb;

                vec3 mean = (n + s + w + e) * 0.25;
                vec3 localMin = min(min(min(n, s), min(w, e)), min(min(nw, ne), min(sw, se)));
                vec3 localMax = max(max(max(n, s), max(w, e)), max(max(nw, ne), max(sw, se)));

                float luma = lumaOf(color);
                vec3 hi = color - mean;
                float detail = length(hi);
                float range = lumaOf(localMax) - lumaOf(localMin);
                float edgeMask = smoothstep(0.01, 0.08, range);
                float detailMask = smoothstep(0.004, 0.03, detail);
                float mask = detailMask * edgeMask;

                vec3 sharpened = color + hi * (strength * 0.5) * mask;
                sharpened = clamp(sharpened, localMin, localMax);

                float rDev = sharpened.r - luma;
                float gDev = sharpened.g - luma;
                float bDev = sharpened.b - luma;
                float colorDetail = sqrt(rDev * rDev + gDev * gDev + bDev * bDev);
                float colorMask = smoothstep(0.005, 0.05, colorDetail);
                vec3 enhanced = sharpened + vec3(rDev, gDev, bDev) * colorMask * strength * 0.6;
                float micro = 1.0 + strength * 0.12 * colorMask;
                enhanced = mix(vec3(0.5), enhanced, micro);
                float hl = smoothstep(0.8, 1.0, luma);
                enhanced = mix(enhanced, color, hl * strength * 0.5);
                enhanced = enhanced * enhanced * (3.0 - 2.0 * enhanced);
                return clamp(enhanced, 0.0, 1.0);
            }

            // Light Boost estilo Splash: "iluminación inteligente y color vivo".
            // Es DINÁMICO: estima la luminancia media de la escena en cada frame
            // (muestreo en malla 3x3) y modula la fuerza. Si el video ya es muy
            // brillante reduce la elevación y la saturación, para jamás lavar
            // luces ni quemar highlights.
            vec3 lightBoost(vec3 color, vec2 uv, vec2 texel, float strength) {
                float luma = lumaOf(color);
                // Estimación del brillo global de la escena, en coords absolutas
                // (siempre dentro del frame, sin depender del texel).
                float s00 = lumaOf(texture2D(uCurrTex, vec2(0.18, 0.18)).rgb);
                float s01 = lumaOf(texture2D(uCurrTex, vec2(0.50, 0.18)).rgb);
                float s02 = lumaOf(texture2D(uCurrTex, vec2(0.82, 0.18)).rgb);
                float s10 = lumaOf(texture2D(uCurrTex, vec2(0.18, 0.50)).rgb);
                float s11 = lumaOf(texture2D(uCurrTex, vec2(0.50, 0.50)).rgb);
                float s12 = lumaOf(texture2D(uCurrTex, vec2(0.82, 0.50)).rgb);
                float s20 = lumaOf(texture2D(uCurrTex, vec2(0.18, 0.82)).rgb);
                float s21 = lumaOf(texture2D(uCurrTex, vec2(0.50, 0.82)).rgb);
                float s22 = lumaOf(texture2D(uCurrTex, vec2(0.82, 0.82)).rgb);
                float sceneLuma = (s00 + s01 + s02 + s10 + s11 + s12 + s20 + s21 + s22) / 9.0;
                // Factor dinámico: 1.0 en escenas normales/oscuras, se degrada
                // hacia ~0.4 cuando la escena ya es muy brillante (evita quemar).
                float dyn = 1.0 - smoothstep(0.5, 0.82, sceneLuma) * 0.6;
                float s = strength * dyn;
                // Elevación suave de sombras y medios, sin tocar brillos.
                float lift = (1.0 - smoothstep(0.12, 0.6, luma)) * s * 0.45;
                // Contraste real centrado en 0.5 (S-curve leve), no encendido bruto.
                float contrast = 1.0 + s * 0.18;
                vec3 result = color * (1.0 + lift) + lift * 0.05;
                result = (result - vec3(0.5)) * contrast + vec3(0.5);
                // Saturación "vibrance": mucha en tonos apagados, un toque en luces,
                // pero SIEMPRE presente para que las escenas claras no pierdan color.
                float chromaDev = length(result - vec3(lumaOf(result)));
                float vibrance = 1.0 - smoothstep(0.15, 0.7, chromaDev);
                float sat = 1.0 + s * (0.55 + 0.25 * vibrance);
                result = mix(vec3(lumaOf(result)), result, sat);
                // Protección de highlights: el brillo NO debe lavar el tono.
                float hl = smoothstep(0.75, 0.98, luma);
                result = mix(result, color, hl * strength * 0.55);
                // Proteje piel para no volverla anaranjada.
                float skinMask = smoothstep(0.15, 0.08, abs(color.r - color.g))
                               * smoothstep(0.15, 0.05, color.r - color.b);
                skinMask *= step(0.3, color.r) * step(color.r, 0.75)
                          * step(0.15, color.g) * step(color.g, 0.65);
                result = mix(result, color, skinMask * strength * 0.5);
                result = pow(clamp(result, 0.0, 1.0), vec3(1.0 - strength * 0.05));
                return clamp(result, 0.0, 1.0);
            }

            // Mejora baja calidad: reparación de artefactos de compresión de bajo bitrate.
            // Solo técnicas que ayudan a vídeos pobres: 1) anti-mosquito bilateral (quita el
            // "crawl" de ruido en bordes y planos sin desenfocar), 2) deblock dirigido (suaviza
            // a través de los nodos de rejilla de 8px detectados por periodicidad) y 3)
            // recuperación de detalle real en banda 2px sin amplificar ruido de 1px ni rejilla.
            vec3 lowBitrateBoost(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 txl = texel;
                vec2 t2 = txl * 2.0;
                vec3 c = texture2D(uCurrTex, uv).rgb;
                vec3 n1 = texture2D(uCurrTex, uv + vec2(0.0, -txl.y)).rgb;
                vec3 s1 = texture2D(uCurrTex, uv + vec2(0.0,  txl.y)).rgb;
                vec3 w1 = texture2D(uCurrTex, uv + vec2(-txl.x, 0.0)).rgb;
                vec3 e1 = texture2D(uCurrTex, uv + vec2( txl.x, 0.0)).rgb;
                vec3 n2 = texture2D(uCurrTex, uv + vec2(0.0, -t2.y)).rgb;
                vec3 s2 = texture2D(uCurrTex, uv + vec2(0.0,  t2.y)).rgb;
                vec3 w2 = texture2D(uCurrTex, uv + vec2(-t2.x, 0.0)).rgb;
                vec3 e2 = texture2D(uCurrTex, uv + vec2( t2.x, 0.0)).rgb;
                float lc = lumaOf(c);
                float ln = lumaOf(n1); float ls = lumaOf(s1);
                float lw = lumaOf(w1); float le = lumaOf(e1);

                // Bordes reales (para no dañarlos).
                float edgeMask = smoothstep(0.02, 0.14, max(abs(le - lw), abs(ln - ls)));

                // Nodos de rejilla de bloque (8px nativos) por periodicidad: el salto a 1px
                // debe repetirse a 8px para ser rejilla de compresión y no textura real.
                vec2 gm = 1.0 / uVideoRes;
                float gH1 = abs(lumaOf(texture2D(uCurrTex, uv + vec2(gm.x, 0.0)).rgb) -
                                lumaOf(texture2D(uCurrTex, uv - vec2(gm.x, 0.0)).rgb));
                float gV1 = abs(lumaOf(texture2D(uCurrTex, uv + vec2(0.0, gm.y)).rgb) -
                                lumaOf(texture2D(uCurrTex, uv - vec2(0.0, gm.y)).rgb));
                float gH8 = abs(lumaOf(texture2D(uCurrTex, uv + vec2(gm.x * 8.0, 0.0)).rgb) -
                                lumaOf(texture2D(uCurrTex, uv + vec2(gm.x * 7.0, 0.0)).rgb));
                float gV8 = abs(lumaOf(texture2D(uCurrTex, uv + vec2(0.0, gm.y * 8.0)).rgb) -
                                lumaOf(texture2D(uCurrTex, uv + vec2(0.0, gm.y * 7.0)).rgb));
                float gridH = gH1 * clamp((gH8 - gH1 * 0.4) / (gH1 * 0.5 + 0.0001), 0.0, 1.0);
                float gridV = gV1 * clamp((gV8 - gV1 * 0.4) / (gV1 * 0.5 + 0.0001), 0.0, 1.0);
                float gridMask = smoothstep(0.02, 0.08, max(gridH, gridV));

                // Anti-mosquito bilateral 1px: promedia solo vecinos parecidos (exp de la
                // diferencia de luma), así elimina el crawl de ruido sin desenfocar detalle.
                float wN = exp(-abs(ln - lc) * 12.0);
                float wS = exp(-abs(ls - lc) * 12.0);
                float wW = exp(-abs(lw - lc) * 12.0);
                float wE = exp(-abs(le - lc) * 12.0);
                vec3 smooth1 = (c + n1*wN + s1*wS + w1*wW + e1*wE) / (1.0 + wN + wS + wW + wE);
                float noiseAmt = smoothstep(0.008, 0.05, length(smooth1 - c)) * (1.0 - edgeMask * 0.6);
                vec3 deblocked = mix(c, smooth1, noiseAmt * strength * 0.8);

                // Deblock dirigido: en nodos de rejilla sobre contenido plano, suaviza a través
                // del borde (media de vecinos) en vez de dejar la línea dura de bloque.
                float localVar = (abs(lc - ln) + abs(lc - ls) + abs(lc - lw) + abs(lc - le)) * 0.25;
                float isFlatish = 1.0 - smoothstep(0.03, 0.12, localVar);
                vec3 blendLine = (n1 + s1 + w1 + e1) * 0.25;
                deblocked = mix(deblocked, blendLine, gridMask * isFlatish * strength * 0.6);

                // Recuperación de detalle en banda 2px (detalle real del contenido), sin
                // rejilla (8px) ni ruido (1px); clamp al rango local para no crear halos.
                vec3 hi = deblocked - (n2 + s2 + w2 + e2) * 0.25;
                float detailMask = smoothstep(0.004, 0.03, length(hi));
                float mask = detailMask * edgeMask * (1.0 - gridMask * 0.9);
                vec3 result = deblocked + hi * strength * 1.6 * mask;
                result = clamp(result, min(min(n1, s1), min(w1, e1)), max(max(n1, s1), max(w1, e1)));
                return clamp(result, 0.0, 1.0);
            }

            // Denoise espacial-temporal: bilateral 3x3 + muestreo del frame anterior
            // compensado por movimiento (solo cuando hay interpolación activa).
            vec3 denoisePass(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec3 n1 = texture2D(uCurrTex, uv + vec2(texel.x, 0.0)).rgb;
                vec3 n2 = texture2D(uCurrTex, uv - vec2(texel.x, 0.0)).rgb;
                vec3 n3 = texture2D(uCurrTex, uv + vec2(0.0, texel.y)).rgb;
                vec3 n4 = texture2D(uCurrTex, uv - vec2(0.0, texel.y)).rgb;
                vec3 n5 = texture2D(uCurrTex, uv + vec2(texel.x, texel.y)).rgb;
                vec3 n6 = texture2D(uCurrTex, uv - vec2(texel.x, texel.y)).rgb;
                vec3 n7 = texture2D(uCurrTex, uv + vec2(-texel.x, texel.y)).rgb;
                vec3 n8 = texture2D(uCurrTex, uv + vec2(texel.x, -texel.y)).rgb;
                float w1 = exp(-length(n1 - color) * 8.0);
                float w2 = exp(-length(n2 - color) * 8.0);
                float w3 = exp(-length(n3 - color) * 8.0);
                float w4 = exp(-length(n4 - color) * 8.0);
                float w5 = exp(-length(n5 - color) * 8.0) * 0.7;
                float w6 = exp(-length(n6 - color) * 8.0) * 0.7;
                float w7 = exp(-length(n7 - color) * 8.0) * 0.7;
                float w8 = exp(-length(n8 - color) * 8.0) * 0.7;
                float wSum = 1.0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8;
                vec3 bilateral = (color + n1*w1 + n2*w2 + n3*w3 + n4*w4 + n5*w5 + n6*w6 + n7*w7 + n8*w8) / wSum;
                vec3 result = color;
                if (uInterpEnabled > 0.5) {
                    vec4 mN = texture2D(uMotionTex, vTexCoord);
                    float mConf = clamp(mN.b * mN.a, 0.0, 1.0);
                    vec2 mvN = mix(uGlobalVec, mN.xy * 2.0 - 1.0, mConf) * 16.0 * uMotionScale;
                    vec2 nUV = clamp(vTexCoord - mvN * 0.5, vec2(0.0), vec2(1.0));
                    vec3 nPrev = texture2D(uPrevTex, nUV).rgb;
                    float diff = length(nPrev - bilateral);
                    float trust = 1.0 - smoothstep(0.03, 0.22, diff);
                    vec3 den = mix(bilateral, nPrev, 0.65);
                    result = mix(result, den, trust * strength);
                } else {
                    result = mix(result, bilateral, strength * 0.7);
                }
                return clamp(result, 0.0, 1.0);
            }

            // Deband: difumina gradientes planos y añade dither azul sin tocar bordes reales.
            vec3 debandPass(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 h2 = texel * 3.0;
                vec3 avg = (texture2D(uCurrTex, uv + vec2(h2.x, 0.0)).rgb
                         + texture2D(uCurrTex, uv - vec2(h2.x, 0.0)).rgb
                         + texture2D(uCurrTex, uv + vec2(0.0, h2.y)).rgb
                         + texture2D(uCurrTex, uv - vec2(0.0, h2.y)).rgb
                         + texture2D(uCurrTex, uv + vec2(h2.x, h2.y)).rgb
                         + texture2D(uCurrTex, uv - vec2(h2.x, h2.y)).rgb
                         + texture2D(uCurrTex, uv + vec2(h2.x, -h2.y)).rgb
                         + texture2D(uCurrTex, uv - vec2(h2.x, -h2.y)).rgb) * 0.125;
                float lDiff = abs(lumaOf(color) - lumaOf(avg));
                // Difuminar en zonas planas/gradientes (banding); NO tocar bordes reales.
                float bandMask = 1.0 - smoothstep(0.004, 0.03, lDiff);
                float dither = (hash(vTexCoord * 437.58 + vec2(uGrainSeed, uGrainSeed * 0.7)) - 0.5);
                vec3 result = color;
                result += dither * strength * bandMask * 0.05;
                result = mix(result, avg, bandMask * strength * 0.3);
                return clamp(result, 0.0, 1.0);
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
                    if (uMode > 3.5) {
                        // Híbrido recomendado: frame-doubling con micro-blend adaptativo al movimiento.
                        // Estático -> 0% blend (nítido); movimiento rápido -> hasta 16% (oculta judder).
                        vec3 pv = texture2D(uPrevTex, vTexCoord).rgb;
                        float adapt = smoothstep(0.03, 0.20, length(uGlobalVec));
                        interp = mix(curr.rgb, pv, 0.16 * adapt);
                        mask = 1.0;
                    } else if (uMode > 1.5) {
                        // Blend por movimiento con clamp min/max: evita el lavado/overshoot del fundido puro.
                        vec3 pv = texture2D(uPrevTex, vTexCoord).rgb;
                        interp = clamp(mix(pv, curr.rgb, uFactor), min(pv, curr.rgb), max(pv, curr.rgb));
                        mask = 1.0;
                    } else {
                        interp = curr.rgb;
                        mask = 1.0;
                    }
                    color = mix(curr.rgb, interp, mask);
                }

                if (uEnabled > 0.5) {
                    // Limpieza primero (denoise → deband → deblock): la reparación de
                    // lowBitrateBoost debe operar sobre señal limpia, no amplificar artefactos.
                    if (uDenoise > 0.001) {
                        color = denoisePass(color, vTexCoord, uTexelSize, uDenoise);
                    }

                    if (uDeband > 0.001) {
                        color = debandPass(color, vTexCoord, uTexelSize, uDeband);
                    }

                    if (uDeblock > 0.001) {
                        color = deblock(color, vTexCoord, uTexelSize, uDeblock);
                    }

                    if (uLowBitrateBoost > 0.001) {
                        color = lowBitrateBoost(color, vTexCoord, uTexelSize, uLowBitrateBoost);
                    }

                    // Nitidez del perfil de color (básica, sobre luma)
                    if (uStatic < 0.5 && uSharpness > 0.001) {
                    vec2 txl = uDownTexel;
                    vec3 top    = texture2D(uDownTex, vTexCoord + vec2(0.0, txl.y)).rgb;
                    vec3 bottom = texture2D(uDownTex, vTexCoord - vec2(0.0, txl.y)).rgb;
                    vec3 left   = texture2D(uDownTex, vTexCoord - vec2(txl.x, 0.0)).rgb;
                    vec3 right  = texture2D(uDownTex, vTexCoord + vec2(txl.x, 0.0)).rgb;
                    vec3 sharpened = color + uSharpness * (4.0 * color - top - bottom - left - right);
                    color = mix(color, sharpened, uSharpness * 0.5);
                    }

                    // Nitidez adaptativa (técnica de video): afila solo bordes reales, no ruido.
                    if (uStatic < 0.5 && uAdaptiveSharp > 0.001) {
                    vec2 txl = uDownTexel;
                    vec3 top    = texture2D(uDownTex, vTexCoord + vec2(0.0, txl.y)).rgb;
                    vec3 bottom = texture2D(uDownTex, vTexCoord - vec2(0.0, txl.y)).rgb;
                    vec3 left   = texture2D(uDownTex, vTexCoord - vec2(txl.x, 0.0)).rgb;
                    vec3 right  = texture2D(uDownTex, vTexCoord + vec2(txl.x, 0.0)).rgb;
                    vec3 sharpened = color + uAdaptiveSharp * (2.0 * color - top - bottom);
                    float gx = lumaOf(right) - lumaOf(left);
                    float gy = lumaOf(top) - lumaOf(bottom);
                    float edge = clamp(sqrt(gx * gx + gy * gy) * 6.0, 0.0, 1.0);
                    color = mix(color, sharpened, uAdaptiveSharp * 0.5 * edge);
                    }

                    if (uLocalContrast > 0.001) {
                        color = localContrast(color, vTexCoord, uTexelSize, uLocalContrast);
                    }

                    // Limpieza de halos tras TODO el afilado (lowBitrateBoost, sharpness, adaptive, local).
                    if (uDesRinging > 0.001) {
                        color = desRinging(color, vTexCoord, uTexelSize, uDesRinging);
                    }

                    color = applyTint(color, uTint);
                    float ct = (uContrast - 1.0) * 0.5;
                    color = clamp(color, 0.0, 1.0);
                    color = color * color * (3.0 - 2.0 * color);
                    color = mix(color, color * color * (3.0 - 2.0 * color), ct);
                    color = mix(vec3(0.5), color, uContrast);
                    color = adjustSaturation(color, uSaturation);
                    color = gamutBoost(color, uColorBoost);
                    color += uBrightness * 0.3;

                    if (uDehaze > 0.001) {
                        color = dehaze(color, vTexCoord, uTexelSize, uDehaze);
                    }

                    if (uHdr > 0.001) {
                        color = applyHdr(color, uHdr);
                    }

                    if (uLightBoost > 0.001) {
                        color = lightBoost(color, vTexCoord, uTexelSize, uLightBoost);
                    }

                    if (uDetailBoost > 0.001) {
                        color = detailBoost(color, vTexCoord, uTexelSize, uDetailBoost);
                    }

                    // Grano fílmico SIEMPRE al final: ningún lift/tone-mapping posterior lo
                    // amplifica y el afilado no lo endurece.
                    if (uGrain > 0.001) {
                        color = addGrain(color, vTexCoord, uGrain);
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
            deblockLoc = GLES20.glGetUniformLocation(program, "uDeblock")
            desRingingLoc = GLES20.glGetUniformLocation(program, "uDesRinging")
            localContrastLoc = GLES20.glGetUniformLocation(program, "uLocalContrast")
            grainLoc = GLES20.glGetUniformLocation(program, "uGrain")
            grainSeedLoc = GLES20.glGetUniformLocation(program, "uGrainSeed")
            dehazeLoc = GLES20.glGetUniformLocation(program, "uDehaze")
            adaptiveSharpLoc = GLES20.glGetUniformLocation(program, "uAdaptiveSharp")
            tintLoc = GLES20.glGetUniformLocation(program, "uTint")
            hdrLoc = GLES20.glGetUniformLocation(program, "uHdr")
            detailBoostLoc = GLES20.glGetUniformLocation(program, "uDetailBoost")
            lightBoostLoc = GLES20.glGetUniformLocation(program, "uLightBoost")
             lowBitrateBoostLoc = GLES20.glGetUniformLocation(program, "uLowBitrateBoost")
            dbgLoc = GLES20.glGetUniformLocation(program, "uDbgMode")
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

                // Las texturas de motion/coarse se asignan de forma perezosa (solo
                // cuando hay interpolación), para ahorrar VRAM y setup si no se usan.
                motionW = (w / 4).coerceIn(32, 480)
                motionH = (h / 4).coerceIn(32, 270)
                coarseW = (motionW / 2).coerceAtLeast(8)
                coarseH = (motionH / 2).coerceAtLeast(8)
                motionTexStorageAllocated = false
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
            val mode = if (cfg.isInterpolationEnabled()) cfg.interpolationMode().intValue else 0
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
                                if (globalCounter % 12 == 0) computeGlobalMotion()
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

        /** Rectángulo [ox, oy, ow, oh] que encaja el video manteniendo su aspecto dentro del surface. */
        private fun aspectRect(): IntArray {
            val vw = viewWidth
            val vh = viewHeight
            if (vw <= 0 || vh <= 0) return intArrayOf(0, 0, vw, vh)
            var vv = videoWidth
            var vhh = videoHeight
            if (vv <= 0 || vhh <= 0) { vv = 16; vhh = 9 }
            val ar = vv.toFloat() / vhh
            val panelAr = vw.toFloat() / vh
            return if (ar > panelAr) {
                val ow = vw
                val oh = (vw / ar).toInt().coerceAtLeast(1)
                intArrayOf(0, (vh - oh) / 2, ow, oh)
            } else {
                val ow = (vh * ar).toInt().coerceAtLeast(1)
                intArrayOf((vw - ow) / 2, 0, ow, vh)
            }
        }

        private fun renderFrame(texMatrix: FloatArray, factor: Float, cfg: VideoEnhanceConfig, interpolating: Boolean, mode: Int) {
            if (program == 0) return
            val upscalerMode = cfg.getUpscalerMode()
            val needsUpscale = videoWidth > 0 && videoHeight > 0 &&
                (viewWidth.toFloat() / videoWidth > 1.25f || viewHeight.toFloat() / videoHeight > 1.25f)
            val effUpscaler = if (needsUpscale) upscalerMode else VideoEnhanceConfig.UpscalerMode.OFF
            val upscalerActive = effUpscaler != VideoEnhanceConfig.UpscalerMode.OFF
            val offscreen = upscalerActive || renderScale < 1f
            val effScale = if (upscalerActive) 0.5f else renderScale
            val rect = aspectRect()
            val rw = if (offscreen) ((rect[2] * effScale).toInt()).coerceAtLeast(2) else rect[2]
            val rh = if (offscreen) ((rect[3] * effScale).toInt()).coerceAtLeast(2) else rect[3]
            if (offscreen) {
                ensureDrsTarget(rw, rh)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, drsFbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, drsTexId, 0)
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    glFailed("drsFBO incomplete")
                    return
                }
                GLES20.glViewport(0, 0, rw, rh)
            } else {
                GLES20.glViewport(rect[0], rect[1], rect[2], rect[3])
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
            GLES20.glUniform1f(deblockLoc, if (cfg.deblockEnabled()) cfg.getDeblock() else 0f)
            GLES20.glUniform1f(desRingingLoc, if (cfg.desringingEnabled()) cfg.getDesringing() else 0f)
            GLES20.glUniform1f(localContrastLoc, if (cfg.localContrastEnabled()) cfg.getLocalContrast() else 0f)
            GLES20.glUniform1f(grainLoc, if (cfg.grainEnabled()) cfg.getGrain() else 0f)
            GLES20.glUniform1f(grainSeedLoc, (frameCount % 1024).toFloat())
            GLES20.glUniform1f(dehazeLoc, if (cfg.dehazeEnabled()) cfg.getDehaze() else 0f)
            GLES20.glUniform1f(adaptiveSharpLoc, if (cfg.adaptiveSharpEnabled()) cfg.getAdaptiveSharp() else 0f)
            GLES20.glUniform1f(tintLoc, cfg.getTint())
            GLES20.glUniform1f(hdrLoc, if (cfg.hdrEnabled()) cfg.getHdr() else 0f)
            GLES20.glUniform1f(detailBoostLoc, if (cfg.detailBoostEnabled()) cfg.getDetailBoost() else 0f)
            GLES20.glUniform1f(lightBoostLoc, if (cfg.lightBoostEnabled()) cfg.getLightBoost() else 0f)
             GLES20.glUniform1f(lowBitrateBoostLoc, if (cfg.superResEnabled()) cfg.getSuperRes() else 0f)
            GLES20.glUniform1f(dbgLoc, debugMode.toFloat())
            GLES20.glUniform2f(videoResLoc, videoWidth.coerceAtLeast(1).toFloat(), videoHeight.coerceAtLeast(1).toFloat())

            drawQuad(posLoc, texLoc)

            if (offscreen) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(rect[0], rect[1], rect[2], rect[3])
                when (effUpscaler) {
                    VideoEnhanceConfig.UpscalerMode.BILINEAR -> renderBlit()
                    VideoEnhanceConfig.UpscalerMode.BICUBIC -> renderBicubic(rw, rh)
                    VideoEnhanceConfig.UpscalerMode.DOG -> renderDoG(rw, rh, cfg)
                    VideoEnhanceConfig.UpscalerMode.FSR -> renderFSR(rw, rh, cfg)
                    else -> renderBlit()
                }
            }
        }

        private fun copyOldToPrev() {
            if (program == 0) return
            val rect = aspectRect()
            val w = viewWidth.coerceAtLeast(2)
            val h = viewHeight.coerceAtLeast(2)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, prevTexId, 0)
            GLES20.glViewport(rect[0], rect[1], rect[2], rect[3])
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
            drawQuad(posLoc, texLoc)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
        }

        private fun buildMotionMap() {
            if (motionProgram == 0 || motionW == 0 || motionH == 0) return
            if (!motionTexStorageAllocated) ensureMotionStorage()
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
                        if (coarseProbeBuf == null || coarseProbeBuf!!.capacity() < mw * mh * 4) {
                            coarseProbeBuf = java.nio.ByteBuffer.allocateDirect(mw * mh * 4)
                        }
                        val buf = coarseProbeBuf!!
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
                    if (mvProbeBuf == null || mvProbeBuf!!.capacity() < mw * mh * 4) {
                        mvProbeBuf = java.nio.ByteBuffer.allocateDirect(mw * mh * 4)
                    }
                    val buf = mvProbeBuf!!
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
                    if (bwdProbeBuf == null || bwdProbeBuf!!.capacity() < mw * mh * 4) {
                        bwdProbeBuf = java.nio.ByteBuffer.allocateDirect(mw * mh * 4)
                    }
                    val buf = bwdProbeBuf!!
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

        // Asigna de forma perezosa el storage de las texturas de motion/coarse.
        // Solo se llama cuando hay interpolación activa; si no, se ahorra VRAM y setup.
        private fun ensureMotionStorage() {
            if (motionTexStorageAllocated) return
            motionTexStorageAllocated = true
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
            if (n <= 0) return 0f
            if (n == 1) return vals[0]
            for (i in 0 until n) globalIdx[i] = i
            // Ordena el array de índices (n <= 256) con quicksort: O(n log n)
            // en vez del insertion sort O(n²) previo. Solo ordena el índice.
            quicksortIdx(vals, 0, n - 1)
            var acc = 0f
            for (i in 0 until n) {
                acc += globalWs[globalIdx[i]]
                if (acc >= half) return vals[globalIdx[i]]
            }
            return vals[globalIdx[n - 1]]
        }

        // Quicksort clásico sobre globalIdx[] comparando vals[] (no copia datos).
        private fun quicksortIdx(vals: FloatArray, lo: Int, hi: Int) {
            if (lo >= hi) return
            var i = lo
            var j = hi
            val pivot = vals[globalIdx[lo + ((hi - lo) / 2)]]
            while (i <= j) {
                while (vals[globalIdx[i]] < pivot) i++
                while (vals[globalIdx[j]] > pivot) j--
                if (i <= j) {
                    val tmp = globalIdx[i]; globalIdx[i] = globalIdx[j]; globalIdx[j] = tmp
                    i++; j--
                }
            }
            quicksortIdx(vals, lo, j)
            quicksortIdx(vals, i, hi)
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
            if (pos < 0 || tex < 0) return
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

        private fun ensureBicubicProgram() {
            if (bicubicProgram != 0) return
            bicubicProgram = buildProgram(vertexShader, bicubicFragmentShader)
            if (bicubicProgram != 0) {
                bicubicPosLoc = GLES20.glGetAttribLocation(bicubicProgram, "aPosition")
                bicubicTexLoc = GLES20.glGetAttribLocation(bicubicProgram, "aTexCoord")
                bicubicSamplerLoc = GLES20.glGetUniformLocation(bicubicProgram, "uTex")
                bicubicTexMatrixLoc = GLES20.glGetUniformLocation(bicubicProgram, "uTexMatrix")
                bicubicVFlipLoc = GLES20.glGetUniformLocation(bicubicProgram, "uVFlip")
                bicubicTexelLoc = GLES20.glGetUniformLocation(bicubicProgram, "uTexel")
            }
        }

        private fun ensureDogPrograms() {
            if (dogLumaProgram == 0) {
                dogLumaProgram = buildProgram(vertexShader, dogLumaShader)
                if (dogLumaProgram != 0) {
                    dogLumaPosLoc = GLES20.glGetAttribLocation(dogLumaProgram, "aPosition")
                    dogLumaTexLoc = GLES20.glGetAttribLocation(dogLumaProgram, "aTexCoord")
                    dogLumaSamplerLoc = GLES20.glGetUniformLocation(dogLumaProgram, "uTex")
                    dogLumaTexMatrixLoc = GLES20.glGetUniformLocation(dogLumaProgram, "uTexMatrix")
                    dogLumaVFlipLoc = GLES20.glGetUniformLocation(dogLumaProgram, "uVFlip")
                }
            }
            if (dogGaussXProgram == 0) {
                dogGaussXProgram = buildProgram(vertexShader, dogGaussXShader)
                if (dogGaussXProgram != 0) {
                    dogGaussXPosLoc = GLES20.glGetAttribLocation(dogGaussXProgram, "aPosition")
                    dogGaussXTexLoc = GLES20.glGetAttribLocation(dogGaussXProgram, "aTexCoord")
                    dogGaussXSamplerLoc = GLES20.glGetUniformLocation(dogGaussXProgram, "uTex")
                    dogGaussXTexMatrixLoc = GLES20.glGetUniformLocation(dogGaussXProgram, "uTexMatrix")
                    dogGaussXVFlipLoc = GLES20.glGetUniformLocation(dogGaussXProgram, "uVFlip")
                    dogGaussXTexelLoc = GLES20.glGetUniformLocation(dogGaussXProgram, "uTexel")
                }
            }
            if (dogGaussYProgram == 0) {
                dogGaussYProgram = buildProgram(vertexShader, dogGaussYShader)
                if (dogGaussYProgram != 0) {
                    dogGaussYPosLoc = GLES20.glGetAttribLocation(dogGaussYProgram, "aPosition")
                    dogGaussYTexLoc = GLES20.glGetAttribLocation(dogGaussYProgram, "aTexCoord")
                    dogGaussYSamplerLoc = GLES20.glGetUniformLocation(dogGaussYProgram, "uTex")
                    dogGaussYTexMatrixLoc = GLES20.glGetUniformLocation(dogGaussYProgram, "uTexMatrix")
                    dogGaussYVFlipLoc = GLES20.glGetUniformLocation(dogGaussYProgram, "uVFlip")
                    dogGaussYTexelLoc = GLES20.glGetUniformLocation(dogGaussYProgram, "uTexel")
                }
            }
            if (dogApplyProgram == 0) {
                dogApplyProgram = buildProgram(vertexShader, dogApplyShader)
                if (dogApplyProgram != 0) {
                    dogApplyPosLoc = GLES20.glGetAttribLocation(dogApplyProgram, "aPosition")
                    dogApplyTexLoc = GLES20.glGetAttribLocation(dogApplyProgram, "aTexCoord")
                    dogApplyInputSamplerLoc = GLES20.glGetUniformLocation(dogApplyProgram, "uInput")
                    dogApplyGaussSamplerLoc = GLES20.glGetUniformLocation(dogApplyProgram, "uGauss")
                    dogApplyTexMatrixLoc = GLES20.glGetUniformLocation(dogApplyProgram, "uTexMatrix")
                    dogApplyVFlipLoc = GLES20.glGetUniformLocation(dogApplyProgram, "uVFlip")
                    dogApplyStrengthLoc = GLES20.glGetUniformLocation(dogApplyProgram, "uStrength")
                }
            }
        }

        private fun ensureFsrPrograms() {
            if (fsrEasuProgram == 0) {
                fsrEasuProgram = buildProgram(vertexShader, fsrEasuShader)
                if (fsrEasuProgram != 0) {
                    fsrEasuPosLoc = GLES20.glGetAttribLocation(fsrEasuProgram, "aPosition")
                    fsrEasuTexLoc = GLES20.glGetAttribLocation(fsrEasuProgram, "aTexCoord")
                    fsrEasuSamplerLoc = GLES20.glGetUniformLocation(fsrEasuProgram, "uTex")
                    fsrEasuTexMatrixLoc = GLES20.glGetUniformLocation(fsrEasuProgram, "uTexMatrix")
                    fsrEasuVFlipLoc = GLES20.glGetUniformLocation(fsrEasuProgram, "uVFlip")
                    fsrEasuInputSizeLoc = GLES20.glGetUniformLocation(fsrEasuProgram, "uInputSize")
                    fsrEasuOutputSizeLoc = GLES20.glGetUniformLocation(fsrEasuProgram, "uOutputSize")
                }
            }
            if (fsrRcasProgram == 0) {
                fsrRcasProgram = buildProgram(vertexShader, fsrRcasShader)
                if (fsrRcasProgram != 0) {
                    fsrRcasPosLoc = GLES20.glGetAttribLocation(fsrRcasProgram, "aPosition")
                    fsrRcasTexLoc = GLES20.glGetAttribLocation(fsrRcasProgram, "aTexCoord")
                    fsrRcasSamplerLoc = GLES20.glGetUniformLocation(fsrRcasProgram, "uTex")
                    fsrRcasTexMatrixLoc = GLES20.glGetUniformLocation(fsrRcasProgram, "uTexMatrix")
                    fsrRcasVFlipLoc = GLES20.glGetUniformLocation(fsrRcasProgram, "uVFlip")
                    fsrRcasTexelLoc = GLES20.glGetUniformLocation(fsrRcasProgram, "uTexel")
                    fsrRcasSharpLoc = GLES20.glGetUniformLocation(fsrRcasProgram, "uSharpness")
                }
            }
        }

        private fun ensureDogTargets(w: Int, h: Int) {
            val w1 = w; val h1 = h
            val w2 = w; val h2 = h
            if (dogTex1 == 0 || dogFBO1 == 0) {
                val texs = IntArray(1); GLES20.glGenTextures(1, texs, 0); dogTex1 = texs[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dogTex1)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w1, h1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val fbos = IntArray(1); GLES20.glGenFramebuffers(1, fbos, 0); dogFBO1 = fbos[0]
            }
            if (dogTex2 == 0 || dogFBO2 == 0) {
                val texs = IntArray(1); GLES20.glGenTextures(1, texs, 0); dogTex2 = texs[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dogTex2)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w2, h2, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val fbos = IntArray(1); GLES20.glGenFramebuffers(1, fbos, 0); dogFBO2 = fbos[0]
            }
        }

        private fun ensureFsrIntermediate(w: Int, h: Int) {
            if (fsrIntermediateTex != 0) return
            val texs = IntArray(1); GLES20.glGenTextures(1, texs, 0); fsrIntermediateTex = texs[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fsrIntermediateTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val fbos = IntArray(1); GLES20.glGenFramebuffers(1, fbos, 0); fsrIntermediateFBO = fbos[0]
        }

        private fun renderBlit() {
            ensureBlitProgram()
            if (blitProgram == 0) return
            GLES20.glUseProgram(blitProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, drsTexId)
            GLES20.glUniform1i(blitSamplerLoc, 0)
            GLES20.glUniformMatrix4fv(blitTexMatrixLoc, 1, false, identityMat, 0)
            GLES20.glUniform1f(blitVFlipLoc, 1f)
            drawQuad(blitPosLoc, blitTexLoc)
        }

        private fun renderBicubic(srcW: Int, srcH: Int) {
            ensureBicubicProgram()
            if (bicubicProgram == 0) return renderBlit()
            GLES20.glUseProgram(bicubicProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, drsTexId)
            GLES20.glUniform1i(bicubicSamplerLoc, 0)
            GLES20.glUniformMatrix4fv(bicubicTexMatrixLoc, 1, false, identityMat, 0)
            GLES20.glUniform1f(bicubicVFlipLoc, 1f)
            GLES20.glUniform2f(bicubicTexelLoc, 1f / srcW.coerceAtLeast(1), 1f / srcH.coerceAtLeast(1))
            drawQuad(bicubicPosLoc, bicubicTexLoc)
        }

        private fun renderDoG(srcW: Int, srcH: Int, cfg: VideoEnhanceConfig) {
            ensureDogPrograms()
            if (dogLumaProgram == 0 || dogGaussXProgram == 0 || dogGaussYProgram == 0 || dogApplyProgram == 0) {
                renderBlit()
                return
            }
            ensureDogTargets(srcW, srcH)
            val texW = srcW.toFloat(); val texH = srcH.toFloat()
            val texelX = 1f / texW; val texelY = 1f / texH
            val strength = (0.6f + cfg.getSharpness() * 3.0f).coerceIn(0.3f, 3.0f)
            // Pass 1: Luma extract -> dogTex1
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dogFBO1)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, dogTex1, 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                glFailed("dogFBO1 incomplete")
                renderBlit()
                return
            }
            GLES20.glViewport(0, 0, srcW, srcH)
            GLES20.glUseProgram(dogLumaProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, drsTexId)
            GLES20.glUniform1i(dogLumaSamplerLoc, 0)
            GLES20.glUniformMatrix4fv(dogLumaTexMatrixLoc, 1, false, identityMat, 0)
            GLES20.glUniform1f(dogLumaVFlipLoc, 1f)
            drawQuad(dogLumaPosLoc, dogLumaTexLoc)
            // Pass 2: Gaussian X -> dogTex2
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dogFBO2)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, dogTex2, 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                glFailed("dogFBO2 incomplete")
                renderBlit()
                return
            }
            GLES20.glUseProgram(dogGaussXProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dogTex1)
            GLES20.glUniform1i(dogGaussXSamplerLoc, 0)
            GLES20.glUniformMatrix4fv(dogGaussXTexMatrixLoc, 1, false, identityMat, 0)
            GLES20.glUniform1f(dogGaussXVFlipLoc, 1f)
            GLES20.glUniform2f(dogGaussXTexelLoc, texelX, texelY)
            drawQuad(dogGaussXPosLoc, dogGaussXTexLoc)
            // Pass 3: Gaussian Y -> dogTex1
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dogFBO1)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, dogTex1, 0)
            GLES20.glUseProgram(dogGaussYProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dogTex2)
            GLES20.glUniform1i(dogGaussYSamplerLoc, 0)
            GLES20.glUniformMatrix4fv(dogGaussYTexMatrixLoc, 1, false, identityMat, 0)
            GLES20.glUniform1f(dogGaussYVFlipLoc, 1f)
            GLES20.glUniform2f(dogGaussYTexelLoc, texelX, texelY)
            drawQuad(dogGaussYPosLoc, dogGaussYTexLoc)
            // Pass 4: Apply -> screen
            val rect = aspectRect()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(rect[0], rect[1], rect[2], rect[3])
            GLES20.glUseProgram(dogApplyProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, drsTexId)
            GLES20.glUniform1i(dogApplyInputSamplerLoc, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dogTex1)
            GLES20.glUniform1i(dogApplyGaussSamplerLoc, 1)
            GLES20.glUniformMatrix4fv(dogApplyTexMatrixLoc, 1, false, identityMat, 0)
            GLES20.glUniform1f(dogApplyVFlipLoc, 1f)
            GLES20.glUniform1f(dogApplyStrengthLoc, strength)
            drawQuad(dogApplyPosLoc, dogApplyTexLoc)
        }

        private fun renderFSR(srcW: Int, srcH: Int, cfg: VideoEnhanceConfig) {
            ensureFsrPrograms()
            if (fsrEasuProgram == 0 || fsrRcasProgram == 0) {
                renderBlit()
                return
            }
            val outW = srcW * 2; val outH = srcH * 2
            ensureFsrIntermediate(outW, outH)
            // Pass 1: EASU -> intermediate (half -> full)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fsrIntermediateFBO)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fsrIntermediateTex, 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                glFailed("fsrIntermediateFBO incomplete")
                renderBlit()
                return
            }
            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glUseProgram(fsrEasuProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, drsTexId)
            GLES20.glUniform1i(fsrEasuSamplerLoc, 0)
            GLES20.glUniformMatrix4fv(fsrEasuTexMatrixLoc, 1, false, identityMat, 0)
            GLES20.glUniform1f(fsrEasuVFlipLoc, 1f)
            GLES20.glUniform2f(fsrEasuInputSizeLoc, srcW.toFloat(), srcH.toFloat())
            GLES20.glUniform2f(fsrEasuOutputSizeLoc, outW.toFloat(), outH.toFloat())
            drawQuad(fsrEasuPosLoc, fsrEasuTexLoc)
            // Pass 2: RCAS -> screen (full)
            val rect = aspectRect()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(rect[0], rect[1], rect[2], rect[3])
            GLES20.glUseProgram(fsrRcasProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fsrIntermediateTex)
            GLES20.glUniform1i(fsrRcasSamplerLoc, 0)
            GLES20.glUniformMatrix4fv(fsrRcasTexMatrixLoc, 1, false, identityMat, 0)
            GLES20.glUniform1f(fsrRcasVFlipLoc, 1f)
            GLES20.glUniform2f(fsrRcasTexelLoc, 1f / outW.coerceAtLeast(1), 1f / outH.coerceAtLeast(1))
            GLES20.glUniform1f(fsrRcasSharpLoc, (cfg.getSharpness() * 0.5f).coerceIn(0f, 2f))
            drawQuad(fsrRcasPosLoc, fsrRcasTexLoc)
        }

        fun getInputSurface(): Surface? {
            if (cachedOutputSurface == null) {
                cachedOutputSurface = inputSurfaceTexture?.let { Surface(it) }
            }
            return cachedOutputSurface
        }
        fun setVideoSize(w: Int, h: Int) { videoWidth = w; videoHeight = h }
        fun viewWidth(): Int = viewWidth
        fun viewHeight(): Int = viewHeight
        fun videoInputWidth(): Int = videoWidth
        fun videoInputHeight(): Int = videoHeight

        // Estimación aproximada de VRAM usada por el pipeline (texturas RGBA en bytes).
        fun approxVramMb(): Float {
            var bytes = 0L
            fun add(w: Int, h: Int, n: Int = 1) {
                if (w > 0 && h > 0) bytes += w.toLong() * h * 4 * n
            }
            add(viewWidth, viewHeight)               // prevTex
            add(viewWidth / 2, viewHeight / 2)       // downTex
            if (motionTexStorageAllocated) {
                add(motionW, motionH, 4)             // 4 motion/acumuladores
                add(coarseW, coarseH)                // coarse
            }
            if (drsTexId != 0) add(drsW, drsH)       // target upscaler/DRS
            return bytes / (1024f * 1024f)
        }
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
        private const val STATIC_READ_INTERVAL = 10
        private const val STALL_RESET_NS = 1_500_000_000L
    }
}
