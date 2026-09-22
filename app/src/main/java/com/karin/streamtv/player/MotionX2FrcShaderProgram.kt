package com.karin.streamtv.player

import android.opengl.GLES20
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor

/**
 * Spike (Bloque 1): interpolador 1:N dentro de la cadena Media3.
 *
 * [BaseGlShaderProgram] es estricto 1:1 y su pool de texturas de salida es
 * package-private, asi que este programa implementa [GlShaderProgram] a mano
 * (la interfaz permite emitir varios cuadros por entrada). Por cada input REAL
 * se emiten los cuadros intermedios del segmento previo->actual en un grid
 * absoluto de 60 Hz (PTS escalonados). El grafo de video los presenta pauteado
 * por el reloj de reproduccion (VideoSink.render), por lo que la cadencia
 * final deberia ser ~60 fps reales.
 *
 * Este spike SOLO hace crossfade adaptativo (sin flujo optico): sirve para
 * validar la cadencia de presentacion antes de invertir en estimadores.
 * NOTA: requiere que detras NO haya programas 1:1 (el demo va despues; si esta
 * activo hay que re-integrarlo en el Bloque 4).
 */
class MotionX2FrcShaderProgram(
    private val useHdr: Boolean,
    private var strength: Float = 1f,
    private var demoSplit: Boolean = false,
) : GlShaderProgram {

    private val glProgram: GlProgram
    private val copyProgram: GlProgram

    private var inputListener: GlShaderProgram.InputListener? = null
    private var outputListener: GlShaderProgram.OutputListener? = null
    private var errorListenerExecutor: Executor? = null
    private var errorListener: GlShaderProgram.ErrorListener? = null

    private var inputWidth = 0
    private var inputHeight = 0

    // Historial real del cuadro previo (copia propia, GLES2-safe).
    private var histTexId = 0
    private var histFboId = 0
    private var hasHistory = false
    private var prevPtsUs = -1L

    // Pool propio de texturas de salida (la de Media3 es package-private).
    private val freeTextures = ArrayDeque<GlTextureInfo>()
    private val inUseTextures = mutableSetOf<GlTextureInfo>()
    private var poolSizeW = 0
    private var poolSizeH = 0
    private var poolCreated = false

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, INTERP_FRAGMENT_SHADER)
            copyProgram = GlProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        for (p in arrayOf(glProgram, copyProgram)) {
            p.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            p.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
            p.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        }
    }

    override fun setInputListener(listener: GlShaderProgram.InputListener) {
        inputListener = listener
        signalReady()
    }

    override fun setOutputListener(listener: GlShaderProgram.OutputListener) {
        outputListener = listener
    }

    override fun setErrorListener(executor: Executor, listener: GlShaderProgram.ErrorListener) {
        errorListenerExecutor = executor
        errorListener = listener
    }

    override fun queueInputFrame(
        glObjectsProvider: GlObjectsProvider,
        inputFrame: GlTextureInfo,
        presentationTimeUs: Long,
    ) {
        try {
            if (inputFrame.width != inputWidth || inputFrame.height != inputHeight) {
                inputWidth = inputFrame.width
                inputHeight = inputFrame.height
                deleteHistory()
            }
            ensurePool()
            processInput(inputFrame, presentationTimeUs)
            inputListener?.onInputFrameProcessed(inputFrame)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        if (inUseTextures.remove(outputTexture)) {
            freeTextures.addLast(outputTexture)
        }
        signalReady()
    }

    override fun flush() {
        freeTextures.addAll(inUseTextures)
        inUseTextures.clear()
        hasHistory = false
        prevPtsUs = -1L
        inputListener?.onFlush()
        signalReady()
    }

    override fun signalEndOfCurrentInputStream() {
        outputListener?.onCurrentOutputStreamEnded()
    }

    override fun release() {
        deleteHistory()
        deletePool()
        try {
            glProgram.delete()
            copyProgram.delete()
        } catch (_: Exception) {
        }
    }

    private fun processInput(inputFrame: GlTextureInfo, pts: Long) {
        ensureHistory()
        val gap = if (hasHistory) pts - prevPtsUs else -1L
        val discont = !hasHistory || gap <= 0L || gap > MAX_GAP_US
        if (discont) {
            // Primer cuadro / seek / pausa larga: solo passthrough del actual.
            emitPassthrough(inputFrame.texId, pts)
            copyToHistory(inputFrame.texId)
            prevPtsUs = pts
            hasHistory = true
            return
        }

        // Grid absoluto de 60 Hz: slots estrictamente dentro de (prev, actual).
        var t = ((prevPtsUs + STEP_US - 1) / STEP_US) * STEP_US
        var emitted = 0
        while (t < pts && emitted < MAX_INTERP_OUTPUTS) {
            // Reservar al menos 1 textura libre para el passthrough final.
            if (freeTextures.size <= 1) break
            val factor = ((t - prevPtsUs).toFloat() / gap.toFloat()).coerceIn(0f, 1f)
            emitInterp(inputFrame.texId, histTexId, factor, t)
            t += STEP_US
            emitted++
        }

        // Cuadro nativo actual (el grid llega a "actual" inclusive).
        emitPassthrough(inputFrame.texId, pts)
        copyToHistory(inputFrame.texId)
        prevPtsUs = pts
        android.util.Log.d(TAG, "in_pts_us=$pts emits=${emitted + 1} gap_ms=${gap / 1000}")
    }

    private fun emitInterp(currTex: Int, prevTex: Int, factor: Float, outPts: Long) {
        val out = useOutputTexture()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.fboId)
        GLES20.glViewport(0, 0, out.width, out.height)
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", currTex, 0)
        glProgram.setSamplerTexIdUniform("uPrevFrame", prevTex, 1)
        glProgram.setFloatUniform("uFactor", factor)
        glProgram.setIntUniform("uDemoSplit", if (demoSplit) 1 else 0)
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        outputListener?.onOutputFrameAvailable(out, outPts)
    }

    private fun emitPassthrough(currTex: Int, outPts: Long) {
        val out = useOutputTexture()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.fboId)
        GLES20.glViewport(0, 0, out.width, out.height)
        copyProgram.use()
        copyProgram.setSamplerTexIdUniform("uTexSampler", currTex, 0)
        copyProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        outputListener?.onOutputFrameAvailable(out, outPts)
    }

    private fun useOutputTexture(): GlTextureInfo {
        val out = freeTextures.removeFirstOrNull()
            ?: throw IllegalStateException("MotionX2Frc: pool de salida vacio")
        inUseTextures.add(out)
        return out
    }

    private fun ensurePool() {
        if (poolCreated) {
            if (poolSizeW == inputWidth && poolSizeH == inputHeight) return
            deletePool()
        }
        poolSizeW = inputWidth
        poolSizeH = inputHeight
        repeat(OUTPUT_POOL_CAPACITY) {
            freeTextures.addLast(createTexture(inputWidth, inputHeight))
        }
        poolCreated = true
    }

    private fun createTexture(w: Int, h: Int): GlTextureInfo {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex[0], 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return GlTextureInfo(fbo[0], tex[0], 0, w, h)
    }

    private fun deletePool() {
        val all = freeTextures + inUseTextures
        for (t in all) {
            GLES20.glDeleteTextures(1, intArrayOf(t.texId), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(t.fboId), 0)
        }
        freeTextures.clear()
        inUseTextures.clear()
        poolCreated = false
    }

    private fun ensureHistory() {
        if (histTexId != 0 && inputWidth > 0 && inputHeight > 0) return
        deleteHistory()
        if (inputWidth <= 0 || inputHeight <= 0) return
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        histTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            inputWidth, inputHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        histFboId = fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, histFboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, histTexId, 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        hasHistory = false
    }

    private fun copyToHistory(texId: Int) {
        if (histFboId == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, histFboId)
        GLES20.glViewport(0, 0, inputWidth, inputHeight)
        copyProgram.use()
        copyProgram.setSamplerTexIdUniform("uTexSampler", texId, 0)
        copyProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun deleteHistory() {
        if (histFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(histFboId), 0)
            histFboId = 0
        }
        if (histTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(histTexId), 0)
            histTexId = 0
        }
        hasHistory = false
        prevPtsUs = -1L
    }

    private fun signalReady() {
        if (freeTextures.size >= MIN_FREE_TO_ACCEPT) {
            inputListener?.onReadyToAcceptInputFrame()
        }
    }

    companion object {
        private const val TAG = "MotionX2Frc"
        private const val STEP_US = 16_667L
        private const val MAX_GAP_US = 200_000L
        private const val MAX_INTERP_OUTPUTS = 3
        private const val OUTPUT_POOL_CAPACITY = 12
        private const val MIN_FREE_TO_ACCEPT = 4

        private val VERTEX_SHADER = ShaderBlobs.motionx2Vertex
        private val COPY_FRAGMENT_SHADER = ShaderBlobs.motionx2CopyFragment
        private val INTERP_FRAGMENT_SHADER = ShaderBlobs.motionx2InterpFragment
    }
}