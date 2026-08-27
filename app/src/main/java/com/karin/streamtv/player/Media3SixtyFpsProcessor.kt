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

import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.karin.streamtv.player.VideoCache
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// GLES 2.0 no expone GL_RGBA16F; para FP16 usamos la extensión half-float.
// El formato interno/externo sigue siendo GL_RGBA, solo cambia el tipo de dato.
private const val GL_HALF_FLOAT_OES = 0x8D61

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

    fun createPlayer(trackSelector: DefaultTrackSelector? = null, dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null, isLocal: Boolean = false): ExoPlayer {
        val renderersFactory = CodecSelectorFactory.renderersFactory(context)

        val loadControl = RamAwareLoadControl.create(context, isLocal)

        val upstream = dataSourceFactory ?: VideoDataSource.factory(context, referer)
        val finalFactory = if (isLocal) upstream else VideoCache.wrap(context, upstream)

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
                    .setDataSourceFactory(finalFactory)
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
            it.playWhenReady = com.karin.streamtv.util.AppPreferences.isPlayNowEnabled()
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
        private var mainFullProgram = 0
        private val mainProgramCache = LinkedHashMap<Int, Int>(6, 0.75f, true)
        private var currentMainMask = -1
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
        private var motionFilterProgram = 0
        private var mfMotionTexLoc = -1
        private var mfMotionTexelLoc = -1
        private var mfBlurLoc = -1
        private var mfTexMatrixLoc = -1
        private var mfVFlipLoc = -1
        private var mfPosLoc = -1
        private var mfTexLoc = -1

        private var downTexId = 0
        private var downFbo = 0
        private var blueNoiseTexId = 0

        // Escala de dibujo inicial del lazo DRS: la gama del equipo la fija para
        // que los chips humildes arranquen más abajo y no den tirones al inicio.
        private var renderScale = com.karin.streamtv.util.DeviceProfile.get(context).recommendedRenderScale
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
        private var ravuProgram = 0; private var ravuPosLoc = -1; private var ravuTexLoc = -1; private var ravuSamplerLoc = -1; private var ravuTexMatrixLoc = -1; private var ravuVFlipLoc = -1; private var ravuInputSizeLoc = -1; private var ravuStrengthLoc = -1
        private var kxProgram = 0; private var kxPosLoc = -1; private var kxTexLoc = -1; private var kxSamplerLoc = -1; private var kxTexMatrixLoc = -1; private var kxVFlipLoc = -1; private var kxInputSizeLoc = -1; private var kxSharpLoc = -1; private var kxPass2Loc = -1
        private var dogFBO1 = 0; private var dogTex1 = 0; private var dogFBO2 = 0; private var dogTex2 = 0
        private var fsrIntermediateFBO = 0; private var fsrIntermediateTex = 0; private var fsrIntermediateW = 0; private var fsrIntermediateH = 0
        private var fsrTemporalProgram = 0
        private var fsrTempSamplerLoc = -1
        private var fsrTempDrsLoc = -1
        private var fsrTempPrevDrsLoc = -1
        private var fsrTempMotionLoc = -1
        private var fsrTempMotionScaleLoc = -1
        private var fsrTempFactorLoc = -1
        private var fsrTempGlobalVecLoc = -1
        private var fsrTempTexMatrixLoc = -1
        private var fsrTempVFlipLoc = -1
        private var fsrTempInputSizeLoc = -1
        private var fsrTempPosLoc = -1
        private var fsrTempTexLoc = -1
        private var prevDrsFbo = 0
        private var prevDrsTexId = 0
        private var prevDrsW = 0
        private var prevDrsH = 0
        private var temporalOutFbo = 0
        private var temporalOutTex = 0
        private var lowFpsStreak = 0
        private var highFpsStreak = 0
        private val identityMat = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

        // FP16 en FBOs de color: evita que el banding se acumule en las pasadas
        // intermedias (motion/upscalers/deband). Requiere GLES2 + extensiones
        // half-float; si el dispositivo no las soporta, caemos a 8-bit (actual).
        private var colorTexType = GLES20.GL_UNSIGNED_BYTE
        private var fp16Color = false

        private val blitFragmentShader = Shaders.blitFragmentShader.trimIndent()

        private val bicubicFragmentShader = Shaders.bicubicFragmentShader.trimIndent()

        private val dogLumaShader = Shaders.dogLumaShader.trimIndent()

        private val dogGaussXShader = Shaders.dogGaussXShader.trimIndent()

        private val dogGaussYShader = Shaders.dogGaussYShader.trimIndent()

        private val dogApplyShader = Shaders.dogApplyShader.trimIndent()

        private val fsrEasuShader = Shaders.fsrEasuShader.trimIndent()

        private val fsrRcasShader = Shaders.fsrRcasShader.trimIndent()

        private val ravuLiteShader = Shaders.ravuLiteShader.trimIndent()

        private val kxHybridShader = Shaders.kxHybridShader.trimIndent()

        private val motionFilterShader = Shaders.motionFilterShader.trimIndent()

        private val fsrTemporalShader = Shaders.fsrTemporalShader.trimIndent()

        @Volatile var pipelineReady = false
        @Volatile var interpolationActive = false
            private set
        @Volatile private var lastFactorFloat = 0.5f
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
        // Metadata de color del stream (para conversión HDR->SDR real en el shader).
        // uSrcTransfer: 0=SDR/sRGB, 1=PQ(ST2084), 2=HLG
        // uSrcPrimaries: 0=BT.709, 1=BT.2020
        // uSrcRange: 0=limited(16-235), 1=full
        @Volatile private var srcTransfer = 0
        @Volatile private var srcPrimaries = 0
        @Volatile private var srcRange = 0
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
        private var lightBoostHdrLoc = -1
        private var depthLoc = -1
        private var mode3DLoc = -1
        private var strength3DLoc = -1
        private var toneCurveLoc = -1
        private var crossfeed3DLoc = -1
         private var lowBitrateBoostLoc = -1
        private var dbgLoc = -1
        private var videoResLoc = -1
        private var texelSizeLoc = -1
        private var enabledLoc = -1
        private var interpEnabledLoc = -1
        private var staticFlagLoc = -1
        private var posLoc = -1
        private var texLoc = -1
        private var blueNoiseTexLoc = -1
        private var blueNoiseSizeLoc = -1
        private var ditherEnabledLoc = -1
        private var ditherStrengthLoc = -1
            private var contentTypeLoc = -1
            private var srcTransferLoc = -1
            private var srcPrimariesLoc = -1
            private var srcRangeLoc = -1

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
                    // DRS adaptativo para gama media/baja: si no llegamos a refresco fluido,
                    // bajamos la resolución interna de render (y el panel la reescala). Cuando
                    // vuelve a haber margen, restauramos 1.0. Así los chips débiles se mantienen
                    // fluidos sin perder calidad en dispositivos capaces (solo cuando cae fps).
                    val targetFloor = if (interpolationActive) 0.7f else 0.8f
                    if (outputFps < 24f && renderScale > targetFloor) {
                        lowFpsStreak++
                        if (lowFpsStreak >= 2) {
                            lowFpsStreak = 0
                            highFpsStreak = 0
                            renderScale = (renderScale - 0.1f).coerceAtLeast(targetFloor)
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
                Log.d(TAG, "metrics out=${outputFps.toInt()}fps ms=${"%.1f".format(frameMs)} src=${sourceFps.toInt()}fps interp=$interpolationActive mov=${(motionLevel * 100).toInt()} drop=$droppedFrames ${qualityLabel}")
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
            val mainPrograms = LinkedHashSet<Int>()
            if (program != 0) mainPrograms.add(program)
            if (mainFullProgram != 0) mainPrograms.add(mainFullProgram)
            mainProgramCache.values.forEach { if (it != 0) mainPrograms.add(it) }
            mainPrograms.forEach { GLES20.glDeleteProgram(it) }
            mainProgramCache.clear()
            program = 0
            mainFullProgram = 0
            currentMainMask = -1
            if (motionProgram != 0) { GLES20.glDeleteProgram(motionProgram); motionProgram = 0 }
            if (staticProgram != 0) { GLES20.glDeleteProgram(staticProgram); staticProgram = 0 }
            if (globalProgram != 0) { GLES20.glDeleteProgram(globalProgram); globalProgram = 0 }
            if (coarseProgram != 0) { GLES20.glDeleteProgram(coarseProgram); coarseProgram = 0 }
            if (motionFilterProgram != 0) { GLES20.glDeleteProgram(motionFilterProgram); motionFilterProgram = 0 }
            if (blitProgram != 0) { GLES20.glDeleteProgram(blitProgram); blitProgram = 0 }
            listOf(bicubicProgram, dogLumaProgram, dogGaussXProgram, dogGaussYProgram, dogApplyProgram, fsrEasuProgram, fsrRcasProgram, fsrTemporalProgram, ravuProgram, kxProgram).forEach { if (it != 0) GLES20.glDeleteProgram(it) }
            bicubicProgram = 0; dogLumaProgram = 0; dogGaussXProgram = 0; dogGaussYProgram = 0; dogApplyProgram = 0; fsrEasuProgram = 0; fsrRcasProgram = 0; fsrTemporalProgram = 0; ravuProgram = 0; kxProgram = 0
            if (prevFbo != 0 || motionFbo != 0 || staticFbo != 0 || downFbo != 0 || globalFbo != 0 || drsFbo != 0) {
                GLES20.glDeleteFramebuffers(6, intArrayOf(prevFbo, motionFbo, staticFbo, downFbo, globalFbo, drsFbo), 0)
                prevFbo = 0; motionFbo = 0; staticFbo = 0; downFbo = 0; globalFbo = 0; drsFbo = 0
            }
            if (inputTexId != 0 || prevTexId != 0 || motionTexId != 0 || staticTexId != 0 || motionAccumId != 0 || downTexId != 0 || globalTexId != 0 || coarseTexId != 0 || motionBwdId != 0 || motionBwdAccumId != 0 || drsTexId != 0) {
                GLES20.glDeleteTextures(11, intArrayOf(inputTexId, prevTexId, motionTexId, staticTexId, motionAccumId, downTexId, globalTexId, coarseTexId, motionBwdId, motionBwdAccumId, drsTexId), 0)
                inputTexId = 0; prevTexId = 0; motionTexId = 0; staticTexId = 0; motionAccumId = 0; downTexId = 0; globalTexId = 0; coarseTexId = 0; motionBwdId = 0; motionBwdAccumId = 0; drsTexId = 0; blueNoiseTexId = 0
            }
            if (dogFBO1 != 0 || dogFBO2 != 0) { GLES20.glDeleteFramebuffers(2, intArrayOf(dogFBO1, dogFBO2), 0); dogFBO1 = 0; dogFBO2 = 0 }
            if (dogTex1 != 0 || dogTex2 != 0) { GLES20.glDeleteTextures(2, intArrayOf(dogTex1, dogTex2), 0); dogTex1 = 0; dogTex2 = 0 }
            if (fsrIntermediateFBO != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(fsrIntermediateFBO), 0); fsrIntermediateFBO = 0 }
            if (fsrIntermediateTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(fsrIntermediateTex), 0); fsrIntermediateTex = 0 }
            fsrIntermediateW = 0; fsrIntermediateH = 0
            if (prevDrsFbo != 0 || temporalOutFbo != 0) {
                GLES20.glDeleteFramebuffers(2, intArrayOf(prevDrsFbo, temporalOutFbo), 0)
                prevDrsFbo = 0; temporalOutFbo = 0
            }
            if (prevDrsTexId != 0 || temporalOutTex != 0) {
                GLES20.glDeleteTextures(2, intArrayOf(prevDrsTexId, temporalOutTex), 0)
                prevDrsTexId = 0; temporalOutTex = 0
            }
            prevDrsW = 0; prevDrsH = 0
            if (blueNoiseTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(blueNoiseTexId), 0); blueNoiseTexId = 0 }
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
            renderScale = com.karin.streamtv.util.DeviceProfile.get(context).recommendedRenderScale
            lowFpsStreak = 0
            highFpsStreak = 0
        }

        private val vertexShader = Shaders.vertexShader.trimIndent()

        private val fragmentShaderTemplate = Shaders.fragmentShader.trimIndent()

        private val motionShader = Shaders.motionShader.trimIndent()

        private val motionBwdShader = Shaders.motionBwdShader.trimIndent()

        private val coarseShader = Shaders.coarseShader.trimIndent()

        private val staticVertexShader = Shaders.staticVertexShader.trimIndent()

        private val staticShader = Shaders.staticShader.trimIndent()

        private val globalShader = Shaders.globalShader.trimIndent()

        private val quadVerts = buf(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
        private val quadTexCoords = buf(floatArrayOf(0f,1f, 1f,1f, 0f,0f, 1f,0f))

        // --- Especialización del shader principal (Opción 1) -------------------
        // Compila variantes del fragment shader con solo los efectos activos
        // (#define por efecto) para que el compilador GLSL elimine el código
        // muerto y baje la presión de registros -> más fill-rate en GLES 2.0.
        private val M_DENOISE = 1
        private val M_DEBAND = 1 shl 1
        private val M_DEBLOCK = 1 shl 2
        private val M_SUPERRES = 1 shl 3
        private val M_SHARP = 1 shl 4
        private val M_ADAPTIVESHARP = 1 shl 5
        private val M_LOCALCONTRAST = 1 shl 6
        private val M_DESRINGING = 1 shl 7
        private val M_DEHAZE = 1 shl 8
        private val M_HDR = 1 shl 9
        private val M_LIGHTBOOST = 1 shl 10
        private val M_DETAILBOOST = 1 shl 11
        private val M_DEPTH = 1 shl 12
        private val M_GRAIN = 1 shl 13
        private val M_DITHER = 1 shl 14
        private val M_3D = 1 shl 15
        private val M_FULL = 0xFFFF

        private fun buildMainSource(mask: Int): String {
            val sb = StringBuilder()
            if (mask and M_DENOISE != 0) sb.append("#define USE_DENOISE\n")
            if (mask and M_DEBAND != 0) sb.append("#define USE_DEBAND\n")
            if (mask and M_DEBLOCK != 0) sb.append("#define USE_DEBLOCK\n")
            if (mask and M_SUPERRES != 0) sb.append("#define USE_SUPERRES\n")
            if (mask and M_SHARP != 0) sb.append("#define USE_SHARP\n")
            if (mask and M_ADAPTIVESHARP != 0) sb.append("#define USE_ADAPTIVESHARP\n")
            if (mask and M_LOCALCONTRAST != 0) sb.append("#define USE_LOCALCONTRAST\n")
            if (mask and M_DESRINGING != 0) sb.append("#define USE_DESRINGING\n")
            if (mask and M_DEHAZE != 0) sb.append("#define USE_DEHAZE\n")
            if (mask and M_HDR != 0) sb.append("#define USE_HDR\n")
            if (mask and M_LIGHTBOOST != 0) sb.append("#define USE_LIGHTBOOST\n")
            if (mask and M_DETAILBOOST != 0) sb.append("#define USE_DETAILBOOST\n")
            if (mask and M_DEPTH != 0) sb.append("#define USE_DEPTH\n")
            if (mask and M_GRAIN != 0) sb.append("#define USE_GRAIN\n")
            if (mask and M_DITHER != 0) sb.append("#define USE_DITHER\n")
            if (mask and M_3D != 0) sb.append("#define USE_3D\n")
            val defs = sb.toString()
            // El #extension debe ir PRIMERO. Si los #define se anteponen, compiladores
            // estrictos (SwiftShader/emulador) rechazan el shader y la pantalla queda negra.
            val extLine = "#extension GL_OES_EGL_image_external : require\n"
            return extLine + defs + fragmentShaderTemplate.removePrefix(extLine)
        }

        private fun computeMainMask(cfg: VideoEnhanceConfig, interpWanted: Boolean): Int {
            var m = 0
            if (cfg.getDenoise() > 0) m = m or M_DENOISE
            if (cfg.getDeband() > 0) m = m or M_DEBAND
            if (cfg.deblockEnabled() && cfg.getDeblock() > 0) m = m or M_DEBLOCK
            if (cfg.superResEnabled() && cfg.getSuperRes() > 0) m = m or M_SUPERRES
            if (cfg.getSharpness() > 0.001f) m = m or M_SHARP
            if (cfg.adaptiveSharpEnabled() && cfg.getAdaptiveSharp() > 0) m = m or M_ADAPTIVESHARP
            if (cfg.localContrastEnabled() && cfg.getLocalContrast() > 0) m = m or M_LOCALCONTRAST
            if (cfg.desringingEnabled() && cfg.getDesringing() > 0) m = m or M_DESRINGING
            if (cfg.dehazeEnabled() && cfg.getDehaze() > 0) m = m or M_DEHAZE
            if (cfg.hdrEnabled() && cfg.getHdr() > 0) m = m or M_HDR
            if (cfg.lightBoostEnabled() && cfg.getLightBoost() > 0) m = m or M_LIGHTBOOST
            if (cfg.detailBoostEnabled() && cfg.getDetailBoost() > 0) m = m or M_DETAILBOOST
            if (cfg.depthEnabled() && cfg.getDepth() > 0) m = m or M_DEPTH
            if (cfg.grainEnabled() && cfg.getGrain() > 0) m = m or M_GRAIN
            if (cfg.ditherEnabled() && cfg.getDither() > 0) m = m or M_DITHER
            if (cfg.get3DMode() > 0) m = m or M_3D
            return m
        }

        private fun selectMainProgram(mask: Int) {
            if (program != 0 && currentMainMask == mask) return
            var prog = if (mask == M_FULL) mainFullProgram else (mainProgramCache[mask] ?: 0)
            if (prog == 0) {
                prog = buildProgram(vertexShader, buildMainSource(mask))
                if (prog != 0) {
                    if (mask == M_FULL) mainFullProgram = prog else mainProgramCache[mask] = prog
                }
            }
            // Red de seguridad: nunca usar programa 0 (pantalla negra). Si este variant
            // falla al compilar, mantenemos el último programa válido en vez de quedar en negro.
            val useProg = if (prog != 0) prog else (if (mainFullProgram != 0) mainFullProgram else program)
            if (useProg != 0 && useProg != program) {
                GLES20.glUseProgram(useProg)
                program = useProg
                refetchMainUniforms(useProg)
            }
            currentMainMask = mask
        }

        private fun refetchMainUniforms(p: Int) {
            curTexLoc = GLES20.glGetUniformLocation(p, "uCurrTex")
            prevTexLoc = GLES20.glGetUniformLocation(p, "uPrevTex")
            motionTexLoc = GLES20.glGetUniformLocation(p, "uMotionTex")
            bwdTexLoc = GLES20.glGetUniformLocation(p, "uBwdTex")
            downTexLoc = GLES20.glGetUniformLocation(p, "uDownTex")
            downTexelLoc = GLES20.glGetUniformLocation(p, "uDownTexel")
            texMatrixLoc = GLES20.glGetUniformLocation(p, "uTexMatrix")
            vFlipLoc = GLES20.glGetUniformLocation(p, "uVFlip")
            interpFactorLoc = GLES20.glGetUniformLocation(p, "uFactor")
            modeLoc = GLES20.glGetUniformLocation(p, "uMode")
            motionScaleLoc = GLES20.glGetUniformLocation(p, "uMotionScale")
            motionTexelLoc = GLES20.glGetUniformLocation(p, "uMotionTexel")
            globalVecLoc = GLES20.glGetUniformLocation(p, "uGlobalVec")
            texelSizeLoc = GLES20.glGetUniformLocation(p, "uTexelSize")
            enabledLoc = GLES20.glGetUniformLocation(p, "uEnabled")
            interpEnabledLoc = GLES20.glGetUniformLocation(p, "uInterpEnabled")
            staticFlagLoc = GLES20.glGetUniformLocation(p, "uStatic")
            saturationLoc = GLES20.glGetUniformLocation(p, "uSaturation")
            contrastLoc = GLES20.glGetUniformLocation(p, "uContrast")
            brightnessLoc = GLES20.glGetUniformLocation(p, "uBrightness")
            sharpnessLoc = GLES20.glGetUniformLocation(p, "uSharpness")
            colorBoostLoc = GLES20.glGetUniformLocation(p, "uColorBoost")
            denoiseLoc = GLES20.glGetUniformLocation(p, "uDenoise")
            debandLoc = GLES20.glGetUniformLocation(p, "uDeband")
            deblockLoc = GLES20.glGetUniformLocation(p, "uDeblock")
            desRingingLoc = GLES20.glGetUniformLocation(p, "uDesRinging")
            localContrastLoc = GLES20.glGetUniformLocation(p, "uLocalContrast")
            grainLoc = GLES20.glGetUniformLocation(p, "uGrain")
            grainSeedLoc = GLES20.glGetUniformLocation(p, "uGrainSeed")
            dehazeLoc = GLES20.glGetUniformLocation(p, "uDehaze")
            adaptiveSharpLoc = GLES20.glGetUniformLocation(p, "uAdaptiveSharp")
            tintLoc = GLES20.glGetUniformLocation(p, "uTint")
            hdrLoc = GLES20.glGetUniformLocation(p, "uHdr")
            detailBoostLoc = GLES20.glGetUniformLocation(p, "uDetailBoost")
            lightBoostLoc = GLES20.glGetUniformLocation(p, "uLightBoost")
            lightBoostHdrLoc = GLES20.glGetUniformLocation(p, "uLightBoostHdr")
            depthLoc = GLES20.glGetUniformLocation(p, "uDepth")
            mode3DLoc = GLES20.glGetUniformLocation(p, "u3DMode")
            strength3DLoc = GLES20.glGetUniformLocation(p, "u3DStrength")
            toneCurveLoc = GLES20.glGetUniformLocation(p, "uToneCurve")
            crossfeed3DLoc = GLES20.glGetUniformLocation(p, "u3DCrossfeed")
            lowBitrateBoostLoc = GLES20.glGetUniformLocation(p, "uLowBitrateBoost")
            dbgLoc = GLES20.glGetUniformLocation(p, "uDbgMode")
            videoResLoc = GLES20.glGetUniformLocation(p, "uVideoRes")
            blueNoiseTexLoc = GLES20.glGetUniformLocation(p, "uBlueNoiseTex")
            blueNoiseSizeLoc = GLES20.glGetUniformLocation(p, "uBlueNoiseSize")
            ditherEnabledLoc = GLES20.glGetUniformLocation(p, "uDitherEnabled")
            ditherStrengthLoc = GLES20.glGetUniformLocation(p, "uDitherStrength")
            contentTypeLoc = GLES20.glGetUniformLocation(p, "uContentType")
            srcTransferLoc = GLES20.glGetUniformLocation(p, "uSrcTransfer")
            srcPrimariesLoc = GLES20.glGetUniformLocation(p, "uSrcPrimaries")
            srcRangeLoc = GLES20.glGetUniformLocation(p, "uSrcRange")
            posLoc = GLES20.glGetAttribLocation(p, "aPosition")
            texLoc = GLES20.glGetAttribLocation(p, "aTexCoord")
        }

        private fun bindTex(unit: Int, target: Int, texId: Int, loc: Int) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(target, texId)
            GLES20.glUniform1i(loc, unit)
        }
        private fun u1f(loc: Int, v: Float) { GLES20.glUniform1f(loc, v) }
        private fun u2f(loc: Int, x: Float, y: Float) { GLES20.glUniform2f(loc, x, y) }
        private fun u1i(loc: Int, v: Int) { GLES20.glUniform1i(loc, v) }

        private inline fun probeMotion(texId: Int, buf: java.nio.ByteBuffer?, crossinline analyze: (java.nio.ByteBuffer) -> Unit): java.nio.ByteBuffer {
            val b = if (buf == null || buf.capacity() < 64) java.nio.ByteBuffer.allocateDirect(64) else buf
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0)
            b.rewind()
            GLES20.glReadPixels(0, 0, 4, 4, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
            b.rewind()
            try {
                analyze(b)
            } catch (t: Throwable) {
                Log.w(TAG, "probeMotion failed: ${t.message}")
            }
            return b
        }

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
            detectFloatColorSupport()
            createTextures()
            createFramebuffers()
            buildPrograms()
            fetchUniforms()
            ensureBlitProgram()
            ensureBicubicProgram()
            ensureDogPrograms()
            ensureFsrPrograms()
            Log.i(TAG, "GL surface created, interpolator ready")
        }

        private fun detectFloatColorSupport() {
            fp16Color = false
            colorTexType = GLES20.GL_UNSIGNED_BYTE
            try {
                val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
                val hasHalfFloat = ext.contains("GL_OES_texture_half_float")
                val hasColorBuffer = ext.contains("GL_EXT_color_buffer_half_float")
                val hasLinear = ext.contains("GL_OES_texture_half_float_linear")
                fp16Color = hasHalfFloat && hasColorBuffer && hasLinear
                Log.i(TAG, "FP16 color FBOs: $fp16Color")
                colorTexType = if (fp16Color) GL_HALF_FLOAT_OES else GLES20.GL_UNSIGNED_BYTE
            } catch (t: Throwable) {
                Log.w(TAG, "float color detection failed: ${t.message}")
                fp16Color = false
                colorTexType = GLES20.GL_UNSIGNED_BYTE
            }
        }

        private fun createTextures() {
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

            // Blue noise texture 64x64 R8: generates a tiled blue noise pattern for dithering.
            // Uses Mitchell-Netravali filter kernels for each pixel to approximate blue noise.
            blueNoiseTexId = generateBlueNoiseTexture()

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
        }

        private fun createFramebuffers() {
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
        }

        private fun buildPrograms() {
            mainFullProgram = buildProgram(vertexShader, buildMainSource(M_FULL))
            program = mainFullProgram
            currentMainMask = M_FULL
            motionProgram = buildProgram(vertexShader, motionShader)
            motionBwdProgram = buildProgram(vertexShader, motionBwdShader)
            staticProgram = buildProgram(staticVertexShader, staticShader)
            globalProgram = buildProgram(staticVertexShader, globalShader)
            coarseProgram = buildProgram(vertexShader, coarseShader)
            motionFilterProgram = buildProgram(vertexShader, motionFilterShader)
            pipelineReady = program != 0 && motionProgram != 0 && staticProgram != 0 && globalProgram != 0 && coarseProgram != 0 && motionBwdProgram != 0
            if (!pipelineReady) {
                Log.e(TAG, "GL program failed to compile/link - enhanced pipeline disabled")
            }
        }

        private fun fetchUniforms() {
            refetchMainUniforms(program)

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

            mfMotionTexLoc = GLES20.glGetUniformLocation(motionFilterProgram, "uMotionTex")
            mfMotionTexelLoc = GLES20.glGetUniformLocation(motionFilterProgram, "uMotionTexel")
            mfBlurLoc = GLES20.glGetUniformLocation(motionFilterProgram, "uBlurStrength")
            mfTexMatrixLoc = GLES20.glGetUniformLocation(motionFilterProgram, "uTexMatrix")
            mfVFlipLoc = GLES20.glGetUniformLocation(motionFilterProgram, "uVFlip")
            mfPosLoc = GLES20.glGetAttribLocation(motionFilterProgram, "aPosition")
            mfTexLoc = GLES20.glGetAttribLocation(motionFilterProgram, "aTexCoord")

            sMotionTexLoc = GLES20.glGetUniformLocation(staticProgram, "uMotionTex")
            sVFlipLoc = GLES20.glGetUniformLocation(staticProgram, "uVFlip")
            sPosLoc = GLES20.glGetAttribLocation(staticProgram, "aPosition")
            sTexLoc = GLES20.glGetAttribLocation(staticProgram, "aTexCoord")

            gMotionTexLoc = GLES20.glGetUniformLocation(globalProgram, "uMotionTex")
            gPosLoc = GLES20.glGetAttribLocation(globalProgram, "aPosition")
            gTexLoc = GLES20.glGetAttribLocation(globalProgram, "aTexCoord")
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            viewWidth = w
            viewHeight = h
            try {
                GLES20.glViewport(0, 0, w, h)

                val pw = w.coerceAtLeast(2)
                val ph = h.coerceAtLeast(2)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, pw, ph, 0, GLES20.GL_RGBA, colorTexType, null)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, (pw / 2).coerceAtLeast(2), (ph / 2).coerceAtLeast(2), 0, GLES20.GL_RGBA, colorTexType, null)

                // Las texturas de motion/coarse se pre-asignan en SurfaceChanged para
                // evitar hitch al primer frame de interpolación.
                motionW = (w / 4).coerceIn(32, 480)
                motionH = (h / 4).coerceIn(32, 270)
                coarseW = (motionW / 2).coerceAtLeast(8)
                coarseH = (motionH / 2).coerceAtLeast(8)
                motionTexStorageAllocated = false
                ensureMotionStorage()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, viewWidth, viewHeight)
            } catch (t: Throwable) {
                glFailed("onSurfaceChanged crashed", t)
            }
        }

        override fun onVideoFrameAboutToBeRendered(releaseTimeNs: Long, presentationTimeUs: Long, format: Format, mediaFormat: android.media.MediaFormat?) {
            val ci = format.colorInfo
            if (ci != null) {
                srcTransfer = when (ci.colorTransfer) {
                    C.COLOR_TRANSFER_ST2084 -> 1
                    C.COLOR_TRANSFER_HLG -> 2
                    else -> 0
                }
                srcPrimaries = if (ci.colorSpace == C.COLOR_SPACE_BT2020) 1 else 0
                srcRange = if (ci.colorRange == C.COLOR_RANGE_FULL) 1 else 0
            } else {
                // Sin metadata: comportamiento actual (identidad, sin tocar el rango).
                srcTransfer = 0
                srcPrimaries = 0
                srcRange = 1
            }
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
                                if (staticCheckCounter >= 40) {
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
            val wantDirty = (staticScene && !debugNeedsPrev) ||
                (!interpWanted && !cfg.isEnabled() && !cfg.isGlQualityMode() && !debugNeedsPrev)
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
                var prev: FrameMeta? = null
                var curr: FrameMeta? = null
                while (metaQueue.isNotEmpty() && metaQueue.first().ptsUs * 1000L <= currTimestampNs) {
                    val item = metaQueue.removeFirst()
                    if (curr != null) prev = curr
                    curr = item
                }
                if (curr != null) {
                    prevReleaseNs = prev?.releaseNs ?: lastDrainedMeta?.releaseNs ?: -1L
                    prevPtsUs = prev?.ptsUs ?: lastDrainedMeta?.ptsUs ?: -1L
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
            lastFactorFloat = factor
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
            selectMainProgram(computeMainMask(cfg, interpolating))

            bindTex(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId, curTexLoc)
            bindTex(1, GLES20.GL_TEXTURE_2D, prevTexId, prevTexLoc)
            bindTex(2, GLES20.GL_TEXTURE_2D, motionTexId, motionTexLoc)
            bindTex(4, GLES20.GL_TEXTURE_2D, motionBwdId, bwdTexLoc)
            bindTex(3, GLES20.GL_TEXTURE_2D, downTexId, downTexLoc)
            GLES20.glUniform2f(downTexelLoc, 1f / (rw / 2).coerceAtLeast(1), 1f / (rh / 2).coerceAtLeast(1))

            setMainUniforms(texMatrix, factor, mode, interpolating, cfg, rw, rh)

            drawQuad(posLoc, texLoc)

            if (offscreen) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(rect[0], rect[1], rect[2], rect[3])
                when (effUpscaler) {
                    VideoEnhanceConfig.UpscalerMode.BILINEAR -> renderBlit()
                    VideoEnhanceConfig.UpscalerMode.BICUBIC -> renderBicubic(rw, rh)
                    VideoEnhanceConfig.UpscalerMode.DOG -> renderDoG(rw, rh, cfg)
                    VideoEnhanceConfig.UpscalerMode.FSR -> renderFSR(rw, rh, cfg)
                    VideoEnhanceConfig.UpscalerMode.RAVU -> renderRAVU(rw, rh, cfg)
                    VideoEnhanceConfig.UpscalerMode.KX -> renderKX(rw, rh, cfg)
                    else -> renderBlit()
                }
            }
        }

        private fun setMainUniforms(texMatrix: FloatArray, factor: Float, mode: Int, interpolating: Boolean, cfg: VideoEnhanceConfig, rw: Int, rh: Int) {
            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
            u1f(vFlipLoc, 0f)
            u1f(interpFactorLoc, factor)
            u1f(modeLoc, mode.toFloat())
            u2f(motionScaleLoc, 1f / motionW.coerceAtLeast(1), 1f / motionH.coerceAtLeast(1))
            u2f(motionTexelLoc, 1f / motionW.coerceAtLeast(1), 1f / motionH.coerceAtLeast(1))
            u2f(globalVecLoc, if (globalVecReady) globalVec[0] else 0f, if (globalVecReady) globalVec[1] else 0f)
            u2f(texelSizeLoc, 1f / rw.coerceAtLeast(1), 1f / rh.coerceAtLeast(1))
            u1f(enabledLoc, if (cfg.isEnabled()) 1f else 0f)
            u1f(interpEnabledLoc, if (interpolating) 1f else 0f)
            u1f(staticFlagLoc, if (staticScene) 1f else 0f)
            u1f(saturationLoc, cfg.getSaturation())
            u1f(contrastLoc, cfg.getContrast())
            u1f(brightnessLoc, cfg.getBrightness())
            u1f(sharpnessLoc, cfg.getSharpness())
            u1f(colorBoostLoc, if (cfg.colorBoostEnabled()) cfg.getColorBoost() else 1.0f)
            u1f(denoiseLoc, cfg.getDenoise())
            u1f(debandLoc, cfg.getDeband())
            u1f(deblockLoc, if (cfg.deblockEnabled()) cfg.getDeblock() else 0f)
            u1f(desRingingLoc, if (cfg.desringingEnabled()) cfg.getDesringing() else 0f)
            u1f(localContrastLoc, if (cfg.localContrastEnabled()) cfg.getLocalContrast() else 0f)
            u1f(grainLoc, if (cfg.grainEnabled()) cfg.getGrain() else 0f)
            u1f(grainSeedLoc, (frameCount % 1024).toFloat())
            u1f(dehazeLoc, if (cfg.dehazeEnabled()) cfg.getDehaze() else 0f)
            u1f(adaptiveSharpLoc, if (cfg.adaptiveSharpEnabled()) cfg.getAdaptiveSharp() else 0f)
            u1f(tintLoc, cfg.getTint())
            u1f(hdrLoc, if (cfg.hdrEnabled()) cfg.getHdr() else 0f)
            u1f(detailBoostLoc, if (cfg.detailBoostEnabled()) cfg.getDetailBoost() else 0f)
            u1f(lightBoostLoc, if (cfg.lightBoostEnabled()) cfg.getLightBoost() else 0f)
            u1f(lightBoostHdrLoc, if (cfg.lightBoostEnabled() && cfg.lightBoostHdrEnabled()) 1f else 0f)
            u1f(depthLoc, if (cfg.depthEnabled()) cfg.getDepth() else 0f)
            u1i(mode3DLoc, cfg.get3DMode())
            u1f(strength3DLoc, cfg.get3DStrength())
            u1i(toneCurveLoc, cfg.getToneCurve())
            u1f(crossfeed3DLoc, cfg.get3DCrossfeed())
            u1f(lowBitrateBoostLoc, if (cfg.superResEnabled()) cfg.getSuperRes() else 0f)
            u1f(dbgLoc, debugMode.toFloat())
            u2f(videoResLoc, videoWidth.coerceAtLeast(1).toFloat(), videoHeight.coerceAtLeast(1).toFloat())

            // Blue noise dithering
            bindTex(7, GLES20.GL_TEXTURE_2D, blueNoiseTexId, blueNoiseTexLoc)
            u2f(blueNoiseSizeLoc, 64f, 64f)
            u1f(ditherEnabledLoc, if (cfg.ditherEnabled()) 1f else 0f)
            u1f(ditherStrengthLoc, if (cfg.ditherEnabled()) cfg.getDither() else 0f)
            // Tipo de contenido: 1 para resoluciones bajas (anime/lineal comprimido),
            // 0 para contenido general/de alta resolución. Adapta el afilado.
            val contentType = if (videoHeight > 0 && videoHeight <= 720) 1f else 0f
            u1f(contentTypeLoc, contentType)
            u1i(srcTransferLoc, srcTransfer)
            u1i(srcPrimariesLoc, srcPrimaries)
            u1i(srcRangeLoc, srcRange)
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
            selectMainProgram(M_FULL)
            bindTex(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId, curTexLoc)
            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, matrixOld, 0)
            u1f(vFlipLoc, 1f)
            u1f(dbgLoc, 0f)
            u1f(interpFactorLoc, 1f)
            u1f(modeLoc, 0f)
            u1f(enabledLoc, 0f)
            u1f(interpEnabledLoc, 0f)
            u1f(staticFlagLoc, 0f)
            drawQuad(posLoc, texLoc)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            prevReady = true
        }

        /** Generate a 64x64 blue noise texture using Void-and-Cluster method.
         *  Blue noise has equal energy at all frequencies (no visible pattern), ideal for dithering. */
        private fun generateBlueNoiseTexture(): Int {
            val size = 64
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            val texId = texIds[0]

            // Generate blue noise using iterative Void-and-Cluster
            val data = ByteArray(size * size)
            // Start with white noise
            val rng = java.util.Random(42)
            for (i in data.indices) data[i] = (rng.nextFloat() * 255).toInt().toByte()

            // 4 iterations of Void-and-Cluster refinement
            for (iter in 0 until 4) {
                val binned = FloatArray(size * size)
                // Bin: threshold at median
                val sorted = data.map { it.toInt() and 0xFF }.sorted()
                val threshold = sorted[size * size / 2]
                for (i in binned.indices) binned[i] = if ((data[i].toInt() and 0xFF) > threshold) 1f else 0f

                // Low-pass filter 3x3 Gaussian
                val filtered = FloatArray(size * size)
                for (y in 0 until size) for (x in 0 until size) {
                    var sum = 0f; var wsum = 0f
                    for (dy in -1..1) for (dx in -1..1) {
                        val wx = 1f / (1f + (dx * dx + dy * dy))
                        val nx = (x + dx + size) % size
                        val ny = (y + dy + size) % size
                        sum += binned[ny * size + nx] * wx
                        wsum += wx
                    }
                    filtered[y * size + x] = sum / wsum
                }
                // Rank order: replace highest filtered values with 1, lowest with 0
                val indices = (0 until size * size).sortedBy { filtered[it] }
                for (i in indices.indices) {
                    data[indices[i]] = if (i < size * size / 2) 0 else 255.toByte()
                }
                // Convert back to grayscale: linearly map rank to 0-255
                for (i in indices.indices) {
                    data[indices[i]] = (i * 255f / (size * size - 1)).toInt().toByte()
                }
            }

            val buffer = ByteBuffer.allocateDirect(size * size).order(java.nio.ByteOrder.nativeOrder())
            buffer.put(data)
            buffer.position(0)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, size, size, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buffer)
            return texId
        }

        private fun downscaleCurr() {
            if (program == 0 || downTexId == 0) return
            val w = (viewWidth / 2).coerceAtLeast(2)
            val h = (viewHeight / 2).coerceAtLeast(2)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, downFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, downTexId, 0)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            selectMainProgram(M_FULL)
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
                    coarseProbeBuf = probeMotion(coarseTexId, coarseProbeBuf) { buf ->
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
                        while (off < 64) {
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

            // Regularización del flujo: filtro bilateral sobre los motion vectors
            // (inspirado en FSR 3.0) que suaviza el ruido en regiones de baja confianza
            // preservando los bordes de movimiento reales. Reduce ghosting en la interpolación.
            runMotionFilterPass()

            if (mvProbeFrames++ % 15 == 0 && debugMode > 0) {
                mvProbeBuf = probeMotion(motionTexId, mvProbeBuf) { buf ->
                    var sx = 0f
                    var sy = 0f
                    var cSum = 0f
                    var cCount = 0
                    var aSum = 0f
                    var nz = 0
                    var cnt = 0
                    var off = 0
                    while (off < 64) {
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
                bwdProbeBuf = probeMotion(motionBwdId, bwdProbeBuf) { buf ->
                    var sx = 0f
                    var sy = 0f
                    var cSum = 0f
                    var cCount = 0
                    var off = 0
                    while (off < 64) {
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
                }
            }
        }

        // Filtro bilateral sobre el campo de motion vectors forward. Lee motionTexId
        // (flujo recién computado) y escribe el resultado filtrado en motionAccumId
        // (buffer libre tras el ping-pong), luego vuelve a intercambiar para que
        // motionTexId quede con el flujo regularizado que consumirá el render.
        private fun runMotionFilterPass() {
            if (motionFilterProgram == 0 || motionTexId == 0 || motionAccumId == 0 || motionW == 0 || motionH == 0) return
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, motionAccumId, 0)
            GLES20.glViewport(0, 0, motionW, motionH)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(motionFilterProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexId)
            GLES20.glUniform1i(mfMotionTexLoc, 0)
            GLES20.glUniform2f(mfMotionTexelLoc, 1f / motionW, 1f / motionH)
            GLES20.glUniform1f(mfBlurLoc, 1.0f)
            GLES20.glUniformMatrix4fv(mfTexMatrixLoc, 1, false, identityMat, 0)
            GLES20.glUniform1f(mfVFlipLoc, 1f)
            drawQuad(mfPosLoc, mfTexLoc)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            val tmpM = motionTexId
            motionTexId = motionAccumId
            motionAccumId = tmpM
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
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, coarseW, coarseH, 0, GLES20.GL_RGBA, colorTexType, null)
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
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, colorTexType, null)
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
            if (fsrTemporalProgram == 0) {
                fsrTemporalProgram = buildProgram(vertexShader, fsrTemporalShader)
                if (fsrTemporalProgram != 0) {
                    fsrTempPosLoc = GLES20.glGetAttribLocation(fsrTemporalProgram, "aPosition")
                    fsrTempTexLoc = GLES20.glGetAttribLocation(fsrTemporalProgram, "aTexCoord")
                    fsrTempSamplerLoc = GLES20.glGetUniformLocation(fsrTemporalProgram, "uTex")
                    fsrTempDrsLoc = GLES20.glGetUniformLocation(fsrTemporalProgram, "uPrevDrs")
                    fsrTempMotionLoc = GLES20.glGetUniformLocation(fsrTemporalProgram, "uMotion")
                    fsrTempMotionScaleLoc = GLES20.glGetUniformLocation(fsrTemporalProgram, "uMotionScale")
                    fsrTempFactorLoc = GLES20.glGetUniformLocation(fsrTemporalProgram, "uFactor")
                    fsrTempGlobalVecLoc = GLES20.glGetUniformLocation(fsrTemporalProgram, "uGlobalVec")
                    fsrTempTexMatrixLoc = GLES20.glGetUniformLocation(fsrTemporalProgram, "uTexMatrix")
                    fsrTempInputSizeLoc = GLES20.glGetUniformLocation(fsrTemporalProgram, "uInputSize")
                    fsrTempVFlipLoc = GLES20.glGetUniformLocation(fsrTemporalProgram, "uVFlip")
                }
            }
        }

        private fun ensurePrevDrsTarget(w: Int, h: Int) {
            if (prevDrsTexId != 0 && prevDrsW == w && prevDrsH == h) return
            if (prevDrsTexId != 0) {
                GLES20.glDeleteTextures(2, intArrayOf(prevDrsTexId, temporalOutTex), 0)
                GLES20.glDeleteFramebuffers(2, intArrayOf(prevDrsFbo, temporalOutFbo), 0)
                prevDrsTexId = 0; prevDrsFbo = 0; temporalOutTex = 0; temporalOutFbo = 0
            }
            if (w < 2 || h < 2) return
            val texs = IntArray(1); GLES20.glGenTextures(1, texs, 0); prevDrsTexId = texs[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevDrsTexId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, colorTexType, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val fbos = IntArray(1); GLES20.glGenFramebuffers(1, fbos, 0); prevDrsFbo = fbos[0]
            // Scratch para la salida temporal (misma resolución DRS).
            val t2 = IntArray(1); GLES20.glGenTextures(1, t2, 0); temporalOutTex = t2[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, temporalOutTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, colorTexType, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val f2 = IntArray(1); GLES20.glGenFramebuffers(1, f2, 0); temporalOutFbo = f2[0]
            prevDrsW = w; prevDrsH = h
        }

        private fun ensureDogTargets(w: Int, h: Int) {
            val w1 = w; val h1 = h
            val w2 = w; val h2 = h
            if (dogTex1 == 0 || dogFBO1 == 0) {
                val texs = IntArray(1); GLES20.glGenTextures(1, texs, 0); dogTex1 = texs[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dogTex1)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w1, h1, 0, GLES20.GL_RGBA, colorTexType, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val fbos = IntArray(1); GLES20.glGenFramebuffers(1, fbos, 0); dogFBO1 = fbos[0]
            }
            if (dogTex2 == 0 || dogFBO2 == 0) {
                val texs = IntArray(1); GLES20.glGenTextures(1, texs, 0); dogTex2 = texs[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dogTex2)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w2, h2, 0, GLES20.GL_RGBA, colorTexType, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val fbos = IntArray(1); GLES20.glGenFramebuffers(1, fbos, 0); dogFBO2 = fbos[0]
            }
        }

        private fun ensureFsrIntermediate(w: Int, h: Int) {
            val tw = w.coerceAtLeast(2); val th = h.coerceAtLeast(2)
            if (fsrIntermediateTex != 0 && fsrIntermediateW == tw && fsrIntermediateH == th) return
            if (fsrIntermediateTex != 0) GLES20.glDeleteTextures(1, intArrayOf(fsrIntermediateTex), 0)
            if (fsrIntermediateFBO != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fsrIntermediateFBO), 0)
            fsrIntermediateTex = 0; fsrIntermediateFBO = 0
            val texs = IntArray(1); GLES20.glGenTextures(1, texs, 0); fsrIntermediateTex = texs[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fsrIntermediateTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tw, th, 0, GLES20.GL_RGBA, colorTexType, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val fbos = IntArray(1); GLES20.glGenFramebuffers(1, fbos, 0); fsrIntermediateFBO = fbos[0]
            fsrIntermediateW = tw; fsrIntermediateH = th
        }

        private fun renderBlit() {
            ensureBlitProgram()
            if (blitProgram == 0) return
            GLES20.glUseProgram(blitProgram)
            bindTex(0, GLES20.GL_TEXTURE_2D, drsTexId, blitSamplerLoc)
            GLES20.glUniformMatrix4fv(blitTexMatrixLoc, 1, false, identityMat, 0)
            u1f(blitVFlipLoc, 1f)
            drawQuad(blitPosLoc, blitTexLoc)
        }

        private fun renderBicubic(srcW: Int, srcH: Int) {
            ensureBicubicProgram()
            if (bicubicProgram == 0) return renderBlit()
            GLES20.glUseProgram(bicubicProgram)
            bindTex(0, GLES20.GL_TEXTURE_2D, drsTexId, bicubicSamplerLoc)
            GLES20.glUniformMatrix4fv(bicubicTexMatrixLoc, 1, false, identityMat, 0)
            u1f(bicubicVFlipLoc, 1f)
            u2f(bicubicTexelLoc, 1f / srcW.coerceAtLeast(1), 1f / srcH.coerceAtLeast(1))
            drawQuad(bicubicPosLoc, bicubicTexLoc)
        }

        private fun ensureRavuProgram() {
            if (ravuProgram != 0) return
            ravuProgram = buildProgram(vertexShader, ravuLiteShader)
            if (ravuProgram != 0) {
                ravuPosLoc = GLES20.glGetAttribLocation(ravuProgram, "aPosition")
                ravuTexLoc = GLES20.glGetAttribLocation(ravuProgram, "aTexCoord")
                ravuSamplerLoc = GLES20.glGetUniformLocation(ravuProgram, "uTex")
                ravuTexMatrixLoc = GLES20.glGetUniformLocation(ravuProgram, "uTexMatrix")
                ravuVFlipLoc = GLES20.glGetUniformLocation(ravuProgram, "uVFlip")
                ravuInputSizeLoc = GLES20.glGetUniformLocation(ravuProgram, "uInputSize")
                ravuStrengthLoc = GLES20.glGetUniformLocation(ravuProgram, "uStrength")
            }
        }

        private fun renderRAVU(srcW: Int, srcH: Int, cfg: VideoEnhanceConfig) {
            ensureRavuProgram()
            if (ravuProgram == 0) return renderBlit()
            GLES20.glUseProgram(ravuProgram)
            bindTex(0, GLES20.GL_TEXTURE_2D, drsTexId, ravuSamplerLoc)
            GLES20.glUniformMatrix4fv(ravuTexMatrixLoc, 1, false, identityMat, 0)
            u1f(ravuVFlipLoc, 1f)
            u2f(ravuInputSizeLoc, srcW.toFloat(), srcH.toFloat())
            val strength = (0.6f + cfg.getSharpness() * 0.8f).coerceIn(0.4f, 1.4f)
            u1f(ravuStrengthLoc, strength)
            drawQuad(ravuPosLoc, ravuTexLoc)
        }

        private fun ensureKXProgram() {
            if (kxProgram != 0) return
            kxProgram = buildProgram(vertexShader, kxHybridShader)
            if (kxProgram != 0) {
                kxPosLoc = GLES20.glGetAttribLocation(kxProgram, "aPosition")
                kxTexLoc = GLES20.glGetAttribLocation(kxProgram, "aTexCoord")
                kxSamplerLoc = GLES20.glGetUniformLocation(kxProgram, "uTex")
                kxTexMatrixLoc = GLES20.glGetUniformLocation(kxProgram, "uTexMatrix")
                kxVFlipLoc = GLES20.glGetUniformLocation(kxProgram, "uVFlip")
                kxInputSizeLoc = GLES20.glGetUniformLocation(kxProgram, "uInputSize")
                kxSharpLoc = GLES20.glGetUniformLocation(kxProgram, "uSharpness")
                kxPass2Loc = GLES20.glGetUniformLocation(kxProgram, "uPass2Enabled")
            }
        }

        private fun renderKX(srcW: Int, srcH: Int, cfg: VideoEnhanceConfig) {
            ensureKXProgram()
            if (kxProgram == 0) return renderBlit()
            GLES20.glUseProgram(kxProgram)
            bindTex(0, GLES20.GL_TEXTURE_2D, drsTexId, kxSamplerLoc)
            GLES20.glUniformMatrix4fv(kxTexMatrixLoc, 1, false, identityMat, 0)
            u1f(kxVFlipLoc, 1f)
            u2f(kxInputSizeLoc, srcW.toFloat(), srcH.toFloat())
            val sharp = (0.5f + cfg.getSharpness() * 1.5f).coerceIn(0.3f, 1.8f)
            u1f(kxSharpLoc, sharp)
            val highTier = com.karin.streamtv.util.DeviceProfile.get(context).tier == com.karin.streamtv.util.DeviceProfile.Tier.HIGH
            u1f(kxPass2Loc, if (highTier) 1f else 0f)
            drawQuad(kxPosLoc, kxTexLoc)
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
            bindTex(0, GLES20.GL_TEXTURE_2D, drsTexId, dogLumaSamplerLoc)
            GLES20.glUniformMatrix4fv(dogLumaTexMatrixLoc, 1, false, identityMat, 0)
            u1f(dogLumaVFlipLoc, 1f)
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
            bindTex(0, GLES20.GL_TEXTURE_2D, dogTex1, dogGaussXSamplerLoc)
            GLES20.glUniformMatrix4fv(dogGaussXTexMatrixLoc, 1, false, identityMat, 0)
            u1f(dogGaussXVFlipLoc, 1f)
            u2f(dogGaussXTexelLoc, texelX, texelY)
            drawQuad(dogGaussXPosLoc, dogGaussXTexLoc)
            // Pass 3: Gaussian Y -> dogTex1
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dogFBO1)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, dogTex1, 0)
            GLES20.glUseProgram(dogGaussYProgram)
            bindTex(0, GLES20.GL_TEXTURE_2D, dogTex2, dogGaussYSamplerLoc)
            GLES20.glUniformMatrix4fv(dogGaussYTexMatrixLoc, 1, false, identityMat, 0)
            u1f(dogGaussYVFlipLoc, 1f)
            u2f(dogGaussYTexelLoc, texelX, texelY)
            drawQuad(dogGaussYPosLoc, dogGaussYTexLoc)
            // Pass 4: Apply -> screen
            val rect = aspectRect()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(rect[0], rect[1], rect[2], rect[3])
            GLES20.glUseProgram(dogApplyProgram)
            bindTex(0, GLES20.GL_TEXTURE_2D, drsTexId, dogApplyInputSamplerLoc)
            bindTex(1, GLES20.GL_TEXTURE_2D, dogTex1, dogApplyGaussSamplerLoc)
            GLES20.glUniformMatrix4fv(dogApplyTexMatrixLoc, 1, false, identityMat, 0)
            u1f(dogApplyVFlipLoc, 1f)
            u1f(dogApplyStrengthLoc, strength)
            drawQuad(dogApplyPosLoc, dogApplyTexLoc)
        }

        private fun renderFSR(srcW: Int, srcH: Int, cfg: VideoEnhanceConfig) {
            ensureFsrPrograms()
            if (fsrEasuProgram == 0 || fsrRcasProgram == 0) {
                renderBlit()
                return
            }
            val ratio = cfg.getFsrQualityScale()
            val outW = (srcW * ratio).toInt().coerceAtLeast(2); val outH = (srcH * ratio).toInt().coerceAtLeast(2)
            ensureFsrIntermediate(outW, outH)
            var easuInputTex = drsTexId
            // Acumulación temporal (estilo FSR 2.0): fusiona el frame anterior reproyectado
            // con el actual a resolución DRS. Solo cuando hay interpolación y motion disponible.
            if (fsrTemporalProgram != 0 && motionTexId != 0 && motionW > 0 && interpolationActive) {
                ensurePrevDrsTarget(srcW, srcH)
                if (temporalOutFbo != 0 && prevDrsTexId != 0) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, temporalOutFbo)
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, temporalOutTex, 0)
                    if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                        GLES20.glViewport(0, 0, srcW, srcH)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        GLES20.glUseProgram(fsrTemporalProgram)
                        bindTex(0, GLES20.GL_TEXTURE_2D, drsTexId, fsrTempSamplerLoc)
                        bindTex(1, GLES20.GL_TEXTURE_2D, prevDrsTexId, fsrTempDrsLoc)
                        bindTex(2, GLES20.GL_TEXTURE_2D, motionTexId, fsrTempMotionLoc)
                        u2f(fsrTempMotionScaleLoc, 1f / motionW, 1f / motionH)
                        u2f(fsrTempGlobalVecLoc, if (globalVecReady) globalVec[0] else 0f, if (globalVecReady) globalVec[1] else 0f)
                        u1f(fsrTempFactorLoc, lastFactorFloat)
                        u2f(fsrTempInputSizeLoc, srcW.toFloat(), srcH.toFloat())
                        GLES20.glUniformMatrix4fv(fsrTempTexMatrixLoc, 1, false, identityMat, 0)
                        u1f(fsrTempVFlipLoc, 1f)
                        drawQuad(fsrTempPosLoc, fsrTempTexLoc)
                        easuInputTex = temporalOutTex
                    }
                }
            }
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
            bindTex(0, GLES20.GL_TEXTURE_2D, easuInputTex, fsrEasuSamplerLoc)
            GLES20.glUniformMatrix4fv(fsrEasuTexMatrixLoc, 1, false, identityMat, 0)
            u1f(fsrEasuVFlipLoc, 1f)
            u2f(fsrEasuInputSizeLoc, srcW.toFloat(), srcH.toFloat())
            u2f(fsrEasuOutputSizeLoc, outW.toFloat(), outH.toFloat())
            drawQuad(fsrEasuPosLoc, fsrEasuTexLoc)
            // Pass 2: RCAS -> screen (full)
            val rect = aspectRect()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(rect[0], rect[1], rect[2], rect[3])
            GLES20.glUseProgram(fsrRcasProgram)
            bindTex(0, GLES20.GL_TEXTURE_2D, fsrIntermediateTex, fsrRcasSamplerLoc)
            GLES20.glUniformMatrix4fv(fsrRcasTexMatrixLoc, 1, false, identityMat, 0)
            u1f(fsrRcasVFlipLoc, 1f)
            u2f(fsrRcasTexelLoc, 1f / outW.coerceAtLeast(1), 1f / outH.coerceAtLeast(1))
            u1f(fsrRcasSharpLoc, (cfg.getSharpness() * 0.5f).coerceIn(0f, 2f))
            drawQuad(fsrRcasPosLoc, fsrRcasTexLoc)
            // Guardar el frame DRS actual como "anterior" para la próxima acumulación temporal.
            if (prevDrsTexId != 0 && prevDrsFbo != 0) {
                ensureBlitProgram()
                if (blitProgram != 0) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevDrsFbo)
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, prevDrsTexId, 0)
                    GLES20.glViewport(0, 0, srcW, srcH)
                    GLES20.glUseProgram(blitProgram)
                    bindTex(0, GLES20.GL_TEXTURE_2D, drsTexId, blitSamplerLoc)
                    GLES20.glUniformMatrix4fv(blitTexMatrixLoc, 1, false, identityMat, 0)
                    u1f(blitVFlipLoc, 1f)
                    drawQuad(blitPosLoc, blitTexLoc)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                }
            }
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
            debugMode = mode.coerceIn(0, 8)
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
        private const val STATIC_READ_INTERVAL = 30
        private const val STALL_RESET_NS = 1_500_000_000L
    }
}
