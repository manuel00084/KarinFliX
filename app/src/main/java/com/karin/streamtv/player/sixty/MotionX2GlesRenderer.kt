package com.karin.streamtv.player.sixty

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.exoplayer.ExoPlayer
import com.karin.streamtv.player.ExoPlayerSettingsHelper
import com.karin.streamtv.player.MotionX2Mode
import com.karin.streamtv.player.ShaderBlobs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Base propia de 60 fps reales con GLES 2.0 — MotionX2 sin el grafo de Media3.
 *
 * Arquitectura:
 * - El decodificador (MediaCodec directo, SIN setVideoEffects) vuelca a un
 *   SurfaceTexture propio ([inputSurface] -> ExoPlayer.setVideoSurface).
 * - Cada vsync de pantalla (Choreographer.FrameCallback, API 16+) se dibuja el
 *   slot de 60 Hz que toca: mezcla prev+actual con el factor del grid absoluto
 *   (misma matemática y mismos shaders que el pipeline REAL60/INTERP probado).
 * - La salida va a un TextureView propio con EGL/GLES2 gestionado aquí.
 *
 * Todo el GL corre en el hilo principal (los callbacks de Choreographer y de
 * SurfaceTexture llegan ahí): sin hilos GL extra ni sincronización cruzada.
 * Cada tick dibuja como máximo 2 pases fullscreen a resolución de video.
 */
class MotionX2GlesRenderer : Choreographer.FrameCallback,
    SurfaceHolder.Callback,
    SurfaceTexture.OnFrameAvailableListener {

    private data class PSlot(var texId: Int = 0, var fboId: Int = 0)
    // tsUs = timestamp crudo del buffer (reloj uptime). El pts de media se
    // deriva al vuelo como tsUs - clockOffsetUs, así los cambios del offset
    // (servo de fase) aplican a todo el historial sin reescribirlo.
    private data class Frame(val tsUs: Long, val slot: PSlot)

    private fun ptsOf(f: Frame): Long = f.tsUs - (clockOffsetUs ?: 0L)

    private var outputView: SurfaceView? = null
    private var playerRef: ExoPlayer? = null
    private var interpMode: MotionX2Mode = MotionX2Mode.REAL60
    private var demoSplit: Boolean = false
    private var aspectProvider: () -> Int = { ExoPlayerSettingsHelper.MODE_ORIGINAL }

    private val choreographer = Choreographer.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var running = false
    private var playing = false
    private var eglReady = false
    private var released = false

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputSurface: Surface? = null

    private var decoderTexId = 0
    private var decoderST: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val stTransform = FloatArray(16)

    private var progCopyOes: MiniProg? = null
    private var progCopy: MiniProg? = null
    private var progInterp: MiniProg? = null
    private var quadBuffer: FloatBuffer? = null

    private var videoW = 1280
    private var videoH = 720
    private var viewW = 0
    private var viewH = 0

    private val pool = ArrayDeque<PSlot>()
    private var ringIdx = 0
    private val frames = ArrayDeque<Frame>()
    private var lastDecoderTsNs = -1L
    // Mapeo reloj uptime->media: los buffers que entrega MediaCodec a un
    // SurfaceTexture llevan el RENDER timestamp (uptime, para pautar el latch),
    // no el pts de media. El offset (ts - posición) es constante por sesión y
    // se re-estima tras cada seek. Con él, pts_media = ts_uptime - offset.
    private var clockOffsetUs: Long? = null
    private var lastArrivalUpMs = 0L

    private var drawCount = 0L
    private var lastDrawLogUptime = 0L

    // ------------------------------------------------------------ ciclo de vida

    fun attach(
        output: SurfaceView,
        player: ExoPlayer,
        mode: MotionX2Mode,
        demoSplit: Boolean,
        aspectMode: () -> Int,
    ) {
        outputView = output
        playerRef = player
        interpMode = mode
        this.demoSplit = demoSplit
        aspectProvider = aspectMode
        released = false
        output.holder.addCallback(this)
        val surf = output.holder.surface
        if (surf != null && surf.isValid) {
            surfaceCreated(output.holder)
            val fw = output.width
            val fh = output.height
            if (fw > 0 && fh > 0) surfaceChanged(output.holder, 0, fw, fh)
        }
        Log.d(TAG, "attach mode=$mode demoSplit=$demoSplit")
    }

    fun detach() {
        stopLoop()
        try {
            playerRef?.setVideoSurface(null)
        } catch (_: Exception) {
        }
        playerRef = null
        try {
            outputView?.holder?.removeCallback(this)
        } catch (_: Exception) {
        }
        teardownGl()
        outputView = null
        released = true
    }

    fun setVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == videoW && h == videoH) return
        videoW = w
        videoH = h
        try {
            decoderST?.setDefaultBufferSize(w, h)
        } catch (_: Exception) {
        }
        recreatePool()
        reset()
        Log.d(TAG, "setVideoSize ${w}x$h")
    }

    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
        if (isPlaying) startLoop() else stopLoop()
    }

    fun onActivityResume() {
        if (playing) startLoop()
    }

    fun onActivityPause() {
        stopLoop()
    }

    fun reset() {
        frames.clear()
        lastDecoderTsNs = -1L
        clockOffsetUs = null
        lastArrivalUpMs = 0L
    }

    // ------------------------------------------------------- SurfaceView (salida)

    override fun surfaceCreated(holder: SurfaceHolder) {
        val v = outputView
        viewW = v?.width ?: 0
        viewH = v?.height ?: 0
        if (eglReady || released) return
        try {
            initEgl(holder.surface)
        } catch (e: Exception) {
            Log.e(TAG, "initEgl falló", e)
        }
        if (playing) startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        viewW = w
        viewH = h
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
        teardownGl()
    }

    // ------------------------------------------------- SurfaceTexture (decodificador)

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (!eglReady || released) return
        try {
            ensureCurrent()
            st.updateTexImage()
            val ts = st.timestamp
            if (ts == lastDecoderTsNs) return
            lastDecoderTsNs = ts
            st.getTransformMatrix(stTransform)
            val tsUs = ts / 1000L
            var posUs = -1L
            try {
                val pos = playerRef?.currentPosition ?: -1L
                if (pos >= 0) posUs = pos * 1000L
            } catch (_: Exception) {
            }
            if (clockOffsetUs == null) {
                if (posUs < 0) return
                clockOffsetUs = tsUs - posUs
                Log.d(TAG, "clockOffsetUs=$clockOffsetUs (ts=$tsUs pos=$posUs)")
            }
            val ptsUs = tsUs - (clockOffsetUs ?: 0L)
            lastArrivalUpMs = SystemClock.uptimeMillis()
            val slot = poolSlot()
            if (slot != null) {
                copyOesToSlot(slot)
                frames.addLast(Frame(tsUs, slot))
                while (frames.size > MAX_HISTORY) frames.removeFirst()
            }
        } catch (e: Exception) {
            Log.w(TAG, "onFrameAvailable: ${e.message}")
        }
    }

    // ------------------------------------------------------------- bucle vsync

    private fun startLoop() {
        if (running || released) return
        running = true
        choreographer.postFrameCallback(this)
    }

    private fun stopLoop() {
        running = false
        try {
            choreographer.removeFrameCallback(this)
        } catch (_: Exception) {
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || released) return
        try {
            drawFrame()
        } catch (e: Exception) {
            Log.w(TAG, "drawFrame: ${e.message}")
        }
        if (running) choreographer.postFrameCallback(this)
    }

    // ------------------------------------------------------------------ dibujo

    private fun drawFrame() {
        if (!eglReady) return
        ensureCurrent()
        val vw = if (viewW > 0) viewW else videoW
        val vh = if (viewH > 0) viewH else videoH

        var mNowUs = -1L
        try {
            val pos = playerRef?.currentPosition ?: -1L
            if (pos >= 0) mNowUs = pos * 1000L
        } catch (_: Exception) {
        }
        val slotT = if (mNowUs >= 0) (mNowUs / STEP_US) * STEP_US else -1L

        // Servo de fase: el decodificador puede ir varios cuadros por delante
        // (lote inicial / plomo del software) y la estimación inicial del
        // offset quedaría con fase constante. Se corrige para que el cuadro más
        // nuevo lidere al slot ~1 cuadro (just-in-time). Solo con cuadros
        // frescos: si el decodificador está parado no se toca el offset.
        if (slotT >= 0 && frames.size >= 2 && clockOffsetUs != null &&
            SystemClock.uptimeMillis() - lastArrivalUpMs <= 250
        ) {
            val newestPts = ptsOf(frames.last())
            val err = (newestPts - slotT) - SERVO_LEAD_US
            clockOffsetUs = if (err > 400_000L || err < -400_000L) {
                (clockOffsetUs ?: 0L) + err
            } else {
                (clockOffsetUs ?: 0L) + (err * 0.02).toLong()
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val vp = computeViewport(vw, vh)
        GLES20.glViewport(0, 0, vw, vh)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (frames.isEmpty()) {
            EGL14.eglSwapBuffers(display, windowSurface)
            return
        }
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])

        val newest = frames.last()
        val oldest = frames.first()
        val newestPts = ptsOf(newest)
        val oldestPts = ptsOf(oldest)
        var drawnSlot = -1L
        var drawnPrev = -1L
        var drawnF = -1f
        when {
            frames.size == 1 || slotT < 0 || slotT >= newestPts -> {
                drawCopy(newest.slot)
                drawnSlot = newestPts
            }
            slotT <= oldestPts -> {
                drawCopy(oldest.slot)
                drawnSlot = oldestPts
            }
            else -> {
                var a = oldest
                var b = newest
                var aPts = oldestPts
                var bPts = newestPts
                for (i in frames.size - 1 downTo 1) {
                    val cur = frames[i]
                    val prv = frames[i - 1]
                    val curPts = ptsOf(cur)
                    val prvPts = ptsOf(prv)
                    if (prvPts <= slotT && slotT <= curPts) {
                        a = prv
                        b = cur
                        aPts = prvPts
                        bPts = curPts
                        break
                    }
                }
                val gap = bPts - aPts
                if (gap <= STEP_US + STEP_TOL_US || gap > MAX_GAP_US || gap <= 0) {
                    drawCopy(b.slot)
                    drawnSlot = bPts
                } else {
                    val f = ((slotT - aPts).toFloat() / gap.toFloat()).coerceIn(0f, 1f)
                    drawInterp(a.slot, b.slot, f)
                    drawnSlot = slotT
                    drawnPrev = aPts
                    drawnF = f
                }
            }
        }

        EGL14.eglSwapBuffers(display, windowSurface)
        drawCount++
        if (drawCount % DRAW_LOG_EVERY == 0L) {
            val now = SystemClock.uptimeMillis()
            val avg = if (lastDrawLogUptime > 0) {
                (now - lastDrawLogUptime).toFloat() / DRAW_LOG_EVERY
            } else -1f
            lastDrawLogUptime = now
            val n0 = frames.firstOrNull()?.let { ptsOf(it) } ?: -1L
            val n1 = frames.lastOrNull()?.let { ptsOf(it) } ?: -1L
            Log.d(TAG, "DRAW n=$drawCount slot=$drawnSlot prev=$drawnPrev f=$drawnF avgMs=$avg hist=${frames.size} [$n0..$n1] off=$clockOffsetUs")
        }
    }

    private fun drawCopy(slot: PSlot) {
        val p = progCopy ?: return
        p.use()
        bindQuad(p)
        setMatrices(p, IDENTITY, IDENTITY)
        bindTex2D(p, "uTexSampler", slot.texId, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawInterp(prev: PSlot, curr: PSlot, f: Float) {
        val p = progInterp ?: return
        p.use()
        bindQuad(p)
        setMatrices(p, IDENTITY, IDENTITY)
        if (interpMode == MotionX2Mode.REAL60) {
            bindTex2D(p, "uCurrTex", curr.texId, 0)
            bindTex2D(p, "uPrevTex", prev.texId, 1)
        } else {
            bindTex2D(p, "uTexSampler", curr.texId, 0)
            bindTex2D(p, "uPrevFrame", prev.texId, 1)
        }
        GLES20.glUniform1f(p.uniform("uFactor"), f)
        GLES20.glUniform1i(p.uniform("uDemoSplit"), if (demoSplit) 1 else 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun copyOesToSlot(slot: PSlot) {
        val p = progCopyOes ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.fboId)
        GLES20.glViewport(0, 0, videoW, videoH)
        p.use()
        bindQuad(p)
        setMatrices(p, IDENTITY, stTransform)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, decoderTexId)
        GLES20.glUniform1i(p.uniform("uTexSampler"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // ------------------------------------------------------------- viewport/fit

    private fun computeViewport(vw: Int, vh: Int): IntArray {
        val videoAspect = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 16f / 9f
        val mode = try {
            aspectProvider()
        } catch (_: Exception) {
            ExoPlayerSettingsHelper.MODE_ORIGINAL
        }
        if (mode == ExoPlayerSettingsHelper.MODE_STRETCH) {
            return intArrayOf(0, 0, vw, vh)
        }
        if (mode == ExoPlayerSettingsHelper.MODE_ZOOM) {
            // Cover: el rectángulo más chico con aspecto de video que CUBRE la vista.
            val s = maxOf(vw / videoAspect, vh.toFloat())
            val w = (s * videoAspect).toInt()
            val h = s.toInt()
            val x = (vw - w) / 2
            val yTop = (vh - h) / 2
            return intArrayOf(x, vh - (yTop + h), w, h)
        }
        val frameAspect = when (mode) {
            ExoPlayerSettingsHelper.MODE_4_3 -> 4f / 3f
            ExoPlayerSettingsHelper.MODE_16_9 -> 16f / 9f
            ExoPlayerSettingsHelper.MODE_2_35 -> 2.35f
            else -> videoAspect
        }
        // Marco: el rect más grande con aspecto forzado dentro de la vista.
        var fw = vw
        var fh = (fw / frameAspect).toInt()
        if (fh > vh) {
            fh = vh
            fw = (fh * frameAspect).toInt()
        }
        val fx = (vw - fw) / 2
        val fyTop = (vh - fh) / 2
        // Video: el rect más grande con aspecto de video dentro del marco.
        var dw = fw
        var dh = (dw / videoAspect).toInt()
        if (dh > fh) {
            dh = fh
            dw = (dh * videoAspect).toInt()
        }
        val dx = fx + (fw - dw) / 2
        val dyTop = fyTop + (fh - dh) / 2
        return intArrayOf(dx, vh - (dyTop + dh), dw, dh)
    }

    // ---------------------------------------------------------------------- EGL

    private fun initEgl(nativeSurface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("eglGetDisplay")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize")
        }
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
            throw IllegalStateException("eglChooseConfig")
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext")

        outputSurface = nativeSurface
        windowSurface = EGL14.eglCreateWindowSurface(
            display, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (windowSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface")
        ensureCurrent()
        EGL14.eglSwapInterval(display, 1)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_DITHER)

        quadBuffer = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }

        progCopyOes = MiniProg(VERTEX_SHADER, COPY_OES_FRAGMENT_SHADER).apply {
            if (!build()) throw IllegalStateException("progCopyOes")
        }
        progCopy = MiniProg(VERTEX_SHADER, ShaderBlobs.motionx2CopyFragment).apply {
            if (!build()) throw IllegalStateException("progCopy")
        }
        val interpFs = if (interpMode == MotionX2Mode.REAL60) {
            REAL60_FRAGMENT_SHADER
        } else {
            ShaderBlobs.motionx2InterpFragment
        }
        progInterp = MiniProg(VERTEX_SHADER, interpFs).apply {
            if (!build()) throw IllegalStateException("progInterp")
        }

        // Entrada del decodificador: textura OES atada a este contexto.
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        decoderTexId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, decoderTexId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        decoderST = SurfaceTexture(decoderTexId).apply {
            setDefaultBufferSize(videoW, videoH)
            setOnFrameAvailableListener(this@MotionX2GlesRenderer, mainHandler)
        }
        recreatePool()
        inputSurface = Surface(decoderST)
        try {
            playerRef?.setVideoSurface(inputSurface)
        } catch (e: Exception) {
            Log.w(TAG, "setVideoSurface: ${e.message}")
        }
        eglReady = true
        Log.d(TAG, "EGL listo mode=$interpMode video=${videoW}x$videoH")
    }

    private fun ensureCurrent() {
        if (display != EGL14.EGL_NO_DISPLAY && windowSurface != EGL14.EGL_NO_SURFACE &&
            context != EGL14.EGL_NO_CONTEXT
        ) {
            EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)
        }
    }

    private fun teardownGl() {
        eglReady = false
        try {
            progCopyOes?.delete()
            progCopy?.delete()
            progInterp?.delete()
        } catch (_: Exception) {
        }
        progCopyOes = null
        progCopy = null
        progInterp = null
        deletePool()
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null
        try {
            decoderST?.release()
        } catch (_: Exception) {
        }
        decoderST = null
        decoderTexId = 0
        try {
            if (windowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, windowSurface)
            }
        } catch (_: Exception) {
        }
        windowSurface = EGL14.EGL_NO_SURFACE
        // outputSurface es PRESTADO por el SurfaceView: no se libera aquí.
        outputSurface = null
        try {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                }
                EGL14.eglTerminate(display)
            }
        } catch (_: Exception) {
        }
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        frames.clear()
        lastDecoderTsNs = -1L
    }

    // ---------------------------------------------------------------------- pool

    private fun poolSlot(): PSlot? {
        if (pool.isEmpty()) return null
        val slot = pool[ringIdx % pool.size]
        ringIdx++
        return slot
    }

    private fun recreatePool() {
        deletePool()
        repeat(POOL_CAPACITY) {
            createSlot()?.let { pool.addLast(it) }
        }
        ringIdx = 0
        frames.clear()
    }

    private fun createSlot(): PSlot? {
        repeat(4) {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, videoW, videoH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            val fbo = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex[0], 0,
            )
            val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            if (st == GLES20.GL_FRAMEBUFFER_COMPLETE) return PSlot(tex[0], fbo[0])
            GLES20.glDeleteTextures(1, tex, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
        }
        Log.w(TAG, "createSlot: FBO incompleto ${videoW}x$videoH")
        return null
    }

    private fun deletePool() {
        for (s in pool) {
            try {
                GLES20.glDeleteTextures(1, intArrayOf(s.texId), 0)
                GLES20.glDeleteFramebuffers(1, intArrayOf(s.fboId), 0)
            } catch (_: Exception) {
            }
        }
        pool.clear()
    }

    // ------------------------------------------------------------------ helpers

    private fun bindQuad(p: MiniProg) {
        val loc = p.attrib("aFramePosition")
        if (loc < 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
    }

    private fun setMatrices(p: MiniProg, m: FloatArray, tm: FloatArray) {
        GLES20.glUniformMatrix4fv(p.uniform("uTransformationMatrix"), 1, false, m, 0)
        GLES20.glUniformMatrix4fv(p.uniform("uTexTransformationMatrix"), 1, false, tm, 0)
    }

    private fun bindTex2D(p: MiniProg, name: String, texId: Int, unit: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(p.uniform(name), unit)
    }

    private class MiniProg(private val vs: String, private val fs: String) {
        var id: Int = 0
        private val locs = HashMap<String, Int>()
        private val attribs = HashMap<String, Int>()

        fun build(): Boolean {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            if (v == 0) return false
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            if (f == 0) {
                GLES20.glDeleteShader(v)
                return false
            }
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, v)
            GLES20.glAttachShader(prog, f)
            GLES20.glLinkProgram(prog)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            if (linked[0] == 0) {
                Log.e(TAG, "link: ${GLES20.glGetProgramInfoLog(prog)}")
                GLES20.glDeleteProgram(prog)
                return false
            }
            id = prog
            return true
        }

        fun use() {
            GLES20.glUseProgram(id)
        }

        fun uniform(name: String): Int =
            locs.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

        fun attrib(name: String): Int =
            attribs.getOrPut(name) { GLES20.glGetAttribLocation(id, name) }

        fun delete() {
            if (id != 0) {
                try {
                    GLES20.glDeleteProgram(id)
                } catch (_: Exception) {
                }
                id = 0
            }
        }

        private fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            if (sh == 0) return 0
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.e(TAG, "compile($type): ${GLES20.glGetShaderInfoLog(sh)}")
                GLES20.glDeleteShader(sh)
                return 0
            }
            return sh
        }
    }

    companion object {
        private const val TAG = "MotionX2Gles"
        private const val STEP_US = 16_667L
        private const val STEP_TOL_US = 4_000L
        private const val MAX_GAP_US = 400_000L
        private const val POOL_CAPACITY = 6
        private const val MAX_HISTORY = 8
        private const val DRAW_LOG_EVERY = 120L
        // El cuadro recién decodificado debe liderar al slot actual ~1 cuadro
        // de 25 fps (just-in-time): el servo de fase lo mantiene ahí.
        private const val SERVO_LEAD_US = 40_000L

        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        private const val VERTEX_SHADER =
            "attribute vec4 aFramePosition;\n" +
                "uniform mat4 uTransformationMatrix;\n" +
                "uniform mat4 uTexTransformationMatrix;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = uTransformationMatrix * aFramePosition;\n" +
                "    vec4 tp = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\n" +
                "    vTexCoord = (uTexTransformationMatrix * tp).xy;\n" +
                "}\n"

        private const val COPY_OES_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexSampler;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexSampler, vTexCoord);\n" +
                "}\n"

        // Idéntico al INTERP_FRAGMENT_SHADER de SixtyFpsInterpShaderProgram
        // (mezcla acotada anti-fantasma + aguja + demo split).
        private const val REAL60_FRAGMENT_SHADER =
            "precision highp float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform sampler2D uCurrTex;\n" +
                "uniform sampler2D uPrevTex;\n" +
                "uniform float uFactor;\n" +
                "uniform int uDemoSplit;\n" +
                "void main() {\n" +
                "    vec3 c = texture2D(uCurrTex, vTexCoord).rgb;\n" +
                "    vec3 p = texture2D(uPrevTex, vTexCoord).rgb;\n" +
                "    float f = clamp(uFactor, 0.0, 1.0);\n" +
                "    vec3 lo = min(p, c);\n" +
                "    vec3 hi = max(p, c);\n" +
                "    vec3 rgb = clamp(mix(p, c, f), lo, hi);\n" +
                "    float k = 0.12 * (1.0 - abs(f - 0.5) * 2.0);\n" +
                "    rgb = mix(rgb, clamp(rgb * 1.04 + 0.003, 0.0, 1.0), k);\n" +
                "    if (uDemoSplit == 1 && vTexCoord.x < 0.5) { rgb = c; }\n" +
                "    gl_FragColor = vec4(rgb, 1.0);\n" +
                "}\n"
    }
}
