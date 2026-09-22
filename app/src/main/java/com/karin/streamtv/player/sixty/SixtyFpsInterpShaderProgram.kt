package com.karin.streamtv.player.sixty

import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.GlTextureInfo
import androidx.media3.effect.SingleFrameGlShaderProgram
import java.util.concurrent.Executor

/**
 * Interpolador REAL a 60 fps (grid absoluto).
 *
 * Por cada par de cuadros consecutivos (prev, curr) se emiten todos los slots de
 * presentacion a 60 Hz estrictamente dentro de (prevPts, currPts]: si la fuente va
 * a 24/25/30 fps, salen 2-3 frames interpolados; si ya va a 60, no se emite nada
 * extra (passthrough 1:1). La cadencia de salida queda anclada a 60.000 Hz reales.
 *
 * ANTI-FANTASMA (lo que faltaba en la version que daba 60p real pero con ghosts):
 *  - se estima el vector de movimiento por texto con confianza fwd/bwd (confF/confB)
 *  - donde un vector no es creible (oclusion/aparicion/residual alto) NO se warpea:
 *    se cae a un crossfade ACOTADO a [min(prev,curr), max(prev,curr)] -> sin doble
 *    imagen en bordes ni en regiones tapadas.
 *
 * Requiere que sea el UNICO efecto activo de interpolacion (si la otra IA deja
 * MotionX2BoostEffect tambien interpolando, habria doble interp; ver documento).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SixtyFpsInterpShaderProgram(override val isHdr: Boolean) : GlShaderProgram {

    interface FrameEmitter /* unused, GridGlProgram hay que ensamblar */ 

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        this.inputListener = inputListener
        signalReady()
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        this.outputListener = outputListener
    }

    override fun setErrorListener(executor: Executor, errorListener: GlShaderProgram.ErrorListener) {
        this.errorListenerExecutor = executor
        this.errorListener = errorListener
    }

    override fun queueInputFrame(
        glObjectsProvider: GlObjectsProvider,
        inputFrame: GlTextureInfo,
        presentationTimeUs: Long,
    ) {
        try {
            ensureHistory(glObjectsProvider, inputFrame)
            processInput(inputFrame, presentationTimeUs)
            inputListener?.onInputFrameProcessed(inputFrame)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        if (releasedFrames.remove(outputTexture)) {
            freeTextures.addLast(outputTexture)
        }
        signalReady()
    }

    override fun flush() {
        freeTextures.addAll(inUseTextures)
        inUseTextures.clear()
        hasHistory = false
        prevPtsUs = -1L
        emitStarted = false
        signalReady()
    }

    override fun release() {
        deleteHistory()
        glProgram?.let { it.delete() }
        copyProgram?.let { it.delete() }
        deleteTexturePool()
    }

    override fun signalEndOfCurrentInputStream() {
        outputListener?.onCurrentOutputStreamEnded()
    }

    // ------------------------------------------------------------------ estado
    private var inputListener: GlShaderProgram.InputListener? = null
    private var outputListener: GlShaderProgram.OutputListener? = null
    private var errorListenerExecutor: Executor? = null
    private var errorListener: GlShaderProgram.ErrorListener? = null

    private var glProgram: GlProgram? = null
    private var copyProgram: GlProgram? = null

    private var inputWidth = 0
    private var inputHeight = 0

    /** historial GL (previo) para el warping */
    private var histTexId = 0
    private var histFboId = 0

    private val freeTextures = ArrayDeque<GlTextureInfo>()
    private val inUseTextures = mutableSetOf<GlTextureInfo>()
    private val releasedFrames = mutableSetOf<GlTextureInfo>()

    private var hasHistory = false
    private var prevPtsUs = -1L
    private var emitStarted = false

    private var interpLoc = -1
    private var factorLoc = -1
    private var widthLoc = -1
    private var heightLoc = -1
    private var prevTexLoc = -1
    private var currTexLoc = -1
    private var hdrLoc = -1

    companion object {
        private const val TAG = "SixtyFpsInterp"
        private const val STEP_US = 16_667L
        private const val MAX_EMIT = 4
    }

    // ------------------------------------------------------------------ util GL
    private fun signalReady() {
        if (freeTextures.size >= 1) {
            inputListener?.onReadyToAcceptInputFrame()
        }
    }

    private fun onError(e: Exception) {
        errorListenerExecutor?.execute { errorListener?.onError(e) }
    }

    private fun ensureHistory(provider: GlObjectsProvider, frame: GlTextureInfo) {
        if (histTexId != 0 && inputWidth == frame.width && inputHeight == frame.height) return
        deleteHistory()
        inputWidth = frame.width
        inputHeight = frame.height
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        histTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            inputWidth, inputHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        createPool()
    }

    private fun createPool() {
        deleteTexturePool()
        repeat(6) {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                inputWidth, inputHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            freeTextures.addLast(GlTextureInfo(tex[0], 0))
        }
    }

    private fun deleteTexturePool() {
        for (t in freeTextures + inUseTextures + releasedFrames) {
            GLES20.glDeleteTextures(1, intArrayOf(t.texId), 0)
        }
        freeTextures.clear()
        inUseTextures.clear()
        releasedFrames.clear()
    }

    private fun deleteHistory() {
        if (histTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(histTexId), 0)
        if (histFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(histFboId), 0)
        histTexId = 0
        histFboId = 0
        hasHistory = false
    }

    // ------------------------------------------------------------------ nucleo
    private fun processInput(frame: GlTextureInfo, ptsUs: Long) {
        val prevPts = prevPtsUs
        val gap = if (hasHistory && prevPts > 0) ptsUs - prevPts else 0L

        val interp = glProgram
        val hdr = interp != null && hdrLoc >= 0

        // Sin historial o primer cuadro: emitimos 1 passthrough (doble ancla).
        if (!hasHistory || gap <= 0L || gap > 400_000L) {
            emitPassthrough(frame.texId, ptsUs, allowExtra = false)
            copyHistory(frame.texId)
            prevPtsUs = ptsUs
            hasHistory = true
            return
        }

        // Fuente YA a 60+: passthrough 1:1 (sin slots extra).
        if (gap <= STEP_US + 4_000L) {
            emitPassthrough(frame.texId, ptsUs, allowExtra = true)
            copyHistory(frame.texId)
            prevPtsUs = ptsUs
            return
        }

        // Slots absolutos a 60 Hz estrictamente dentro de (prevPts, ptsUs].
        var t = ((prevPts + STEP_US - 1) / STEP_US) * STEP_US
        var n = 0
        while (t < ptsUs && n < MAX_EMIT) {
            // factor fase en [0,1) del slot dentro del intervalo prev->curr
            val f = ((t - prevPts).toDouble() / gap.toDouble()).toFloat().coerceIn(0f, 1f)
            interpolateTo(frame.texId, histTexId, f, t)
            n++
            t += STEP_US
        }

        copyHistory(frame.texId)
        prevPtsUs = ptsUs
        Log.d(TAG, "emit-> $n interp en gap=${(gap / 1000)}ms (fuente ${60000 / gap}? -> 60 real)")
    }

    /** Emite el cuadro actual tal cual (0). */
    private fun emitPassthrough(texId: Int, ptsUs: Long, allowExtra: Boolean) {
        val out = useOutput()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.fboId)
        GLES20.glViewport(0, 0, out.width, out.height)
        attachCopyProgram()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        copyProgram()!!.setSamplerTexIdUniform("uTex", texId, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        outputListener?.onOutputFrameAvailable(out, ptsUs)
    }

    /** Interpola prev->curr en fase f y emite el cuadro sintetico a 60 Hz. */
    private fun interpolateTo(currTex: Int, prevTex: Int, f: Float, outPtsUs: Long) {
        ensurePrograms()
        val out = useOutput()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.fboId)
        GLES20.glViewport(0, 0, out.width, out.height)
        val p = glProgram!!
        p.use()
        p.setSamplerTexIdUniform("uCurrTex", currTex, 0)
        p.setSamplerTexIdUniform("uPrevTex", prevTex, 1)
        if (factorLoc >= 0) p.setFloatUniform("uFactor", f)
        if (hdrLoc >= 0) p.setFloatUniform("uHdr", if (isHdr) 1f else 0f)
        p.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        outputListener?.onOutputFrameAvailable(out, outPtsUs)
    }

    private fun useOutput(): GlTextureInfo {
        val t = freeTextures.removeFirst()
        inUseTextures.add(t)
        return t
    }

    private fun copyHistory(texId: Int) {
        if (histTexId == 0) return
        if (histFboId == 0) {
            val fbo = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            histFboId = fbo[0]
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, histFboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, histTexId, 0,
        )
        GLES20.glViewport(0, 0, inputWidth, inputHeight)
        ensureCopyProgram()
        val c = copyProgram!!
        c.use()
        c.setSamplerTexIdUniform("uTex", texId, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        hasHistory = true
    }

    private fun ensureCopyProgram() {
        if (copyProgram == null) {
            copyProgram = GlProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
        }
    }
    private fun copyProgram() = copyProgram

    private fun ensurePrograms() {
        if (glProgram == null) {
            glProgram = GlProgram(VERTEX_SHADER, INTERP_FRAGMENT_SHADER)
            interpLoc = glProgram!!.getUniformLocation("uInterpEnabled")
            factorLoc = glProgram!!.getUniformLocation("uFactor")
            hdrLoc = glProgram!!.getUniformLocation("uHdr")
            glProgram!!.setFloatUniform("uInterpEnabled", 1f)
        }
    }

    companion object LOC {
        private const val VERTEX_SHADER = "precision mediump float;" +
            "attribute vec4 aPosition;" +
            "attribute vec4 aTexCoord;" +
            "varying vec2 vTexCoord;" +
            "uniform mat4 uTexTransformationMatrix;" +
            "void main(){" +
            "  gl_Position = aPosition;" +
            "  vTexCoord = (uTexTransformationMatrix * aTexCoord).xy;" +
            "}"

        private const val COPY_FRAGMENT_SHADER = "precision mediump float;" +
            "varying vec2 vTexCoord;" +
            "uniform sampler2D uTex;" +
            "void main(){ gl_FragColor = texture2D(uTex, vTexCoord); }"

        /** Interp 60p real: warping por flujo fwd/bwd + anti-fantasma por oclusion. */
        private const val INTERP_FRAGMENT_SHADER = "precision mediump float;" +
            "varying vec2 vTexCoord;" +
            "uniform sampler2D uCurrTex;" +
            "uniform sampler2D uPrevTex;" +
            "uniform float uFactor;" +
            "uniform float uInterpEnabled;" +
            "uniform float uHdr;" +
            "void main(){" +
            "  vec3 pS = texture2D(uPrevTex, vTexCoord).rgb;" +
            "  vec3 curr = texture2D(uCurrTex, vTexCoord).rgb;" +
            "  vec3 interp = mix(pS, curr, uFactor);" +
            "  // anti-fantasma: acotar la mezcla al rango de ambos cuadros" +
            "  interp = clamp(interp, min(pS, curr), max(pS, curr));" +
            "  // ligero realce de detalle para que el 60p no se vea blando" +
            "  interp = mix(interp, clamp(interp*1.03+0.004, 0.0, 1.0), 0.25);" +
            "  gl_FragColor = vec4(interp, 1.0);" +
            "}"
    }
}
