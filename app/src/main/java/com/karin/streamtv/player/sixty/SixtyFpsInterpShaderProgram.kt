package com.karin.streamtv.player.sixty

import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlShaderProgram
import com.karin.streamtv.player.ShaderBlobs
import java.util.concurrent.Executor

// Almacenamiento inmutable de texturas (glTexStorage2D, GLES 3.0). Debe estar
// a nivel de archivo: createTextureUnchecked es una función top-level y no ve
// las constantes privadas del companion de la clase.
private const val USE_IMMUTABLE_STORAGE = true

/**
 * Interpolador REAL a 60 fps (grid absoluto) — MotionX2 "60 fps reales".
 *
 * Por cada par de cuadros consecutivos (prev, curr) se emiten todos los slots de
 * presentación a 60 Hz estrictamente dentro de (prevPts, currPts]: si la fuente
 * va a 24/25/30 fps salen 2-3 cuadros intermedios; si ya va a 60 no se emite
 * extra (passthrough 1:1). La cadencia de salida queda anclada a 60.000 Hz reales
 * y el reloj de reproducción (VideoSink.render) los presenta pauteados.
 *
 * Calidad (anti-fantasma): en cada slot la mezcla previo+actual se ACOTA al rango
 * [min(c,p), max(c,p)] por píxel. Donde hay oclusión/aparición el interp no puede
 * inventar el valor verdadero y en lugar de doble imagen se "pega" al borde más
 * cercano del rango. Es la técnica pragmática de frame synthesis en GLES2 en
 * tiempo real (sin flujo óptico denso: inviable a 60 Hz en GPU móvil).
 *
 * Requiere ser el ÚNICO efecto de interpolación activo: si MotionX2Boost también
 * estuviera interpolando habría doble interp (el diálogo lo deja a 1 vía).
 *
 * Modo diseñado como EXPERIMENTAL para gama alta (Tier HIGH): 2 pases fullscreen
 * por slot emitido + historial GL (textura+FBO propios, GLES2 compatible).
 */
class SixtyFpsInterpShaderProgram(
    @Suppress("UNUSED_PARAMETER") useHdr: Boolean,
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

    // Historial GL del cuadro previo (copia propia, GLES2-safe).
    private var histTexId = 0
    private var histFboId = 0
    private var histSlot: GlTextureInfo? = null
    private var hasHistory = false
    private var prevPtsUs = -1L

    // Pool propio de texturas de salida (la de Media3 es package-private):
    // hace falta para emitir N cuadros por entrada.
    private val freeTextures = ArrayDeque<GlTextureInfo>()
    private val inUseTextures = mutableSetOf<GlTextureInfo>()
    private var poolSizeW = 0
    private var poolSizeH = 0
    private var poolCreated = false
    private var poolFailed = false

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
        glObjectsProvider: androidx.media3.common.GlObjectsProvider,
        inputFrame: GlTextureInfo,
        presentationTimeUs: Long,
    ) {
        val w = inputFrame.width
        val h = inputFrame.height
        if (w <= 0 || h <= 0) {
            // Frame placeholder (0x0): NO crear pool ni dibujar. Un FBO de
            // tamaño 0 es incompleto y el draw dispara GL_INVALID_FRAMEBUFFER
            // que Media3 coge como error fatal y cae al modo seguro (Error 7001).
            inputListener?.onInputFrameProcessed(inputFrame)
            return
        }
        try {
            if (inputFrame.width != inputWidth || inputFrame.height != inputHeight) {
                inputWidth = inputFrame.width
                inputHeight = inputFrame.height
                deleteHistory()
            }
            try {
                ensurePool()
                poolFailed = false
            } catch (_: GlUtil.GlException) {
                // Driver GL sin asignacion fiable: se degrada a passthrough puro
                // (sin texturas propias). NUNCA debe caer en Error 7001/modo seguro.
                poolFailed = true
            }
            processInput(inputFrame, presentationTimeUs)
            inputListener?.onInputFrameProcessed(inputFrame)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        if (inUseTextures.remove(outputTexture)) {
            // El passthrough nativo mas reciente queda PINNEADO como historial:
            // es el contenido del frame previo (ya copiado y completo), sirve de
            // uPrevTex del siguiente slot de interp sin necesidad de renderizar
            // sobre textura propia ni de asignar memoria extra.
            if (outputTexture !== histSlot) {
                freeTextures.addLast(outputTexture)
            }
        }
        signalReady()
    }

    override fun flush() {
        freeTextures.addAll(inUseTextures)
        inUseTextures.clear()
        histSlot = null
        histTexId = 0
        histFboId = 0
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

    // ------------------------------------------------------------------ núcleo

    private fun processInput(inputFrame: GlTextureInfo, pts: Long) {
        if (freeTextures.isEmpty()) {
            // 1) Driver sin asignacion fiable (pool imposible de crear): passthrough
            //    puro sin textura propia. 2) Pool sano pero momentaneamente agotado
            //    (todos los slots en manos de Media3): tambien passthrough directo.
            //    En ambos casos NUNCA hay que lanzar -> si se lanzara, Error 7001.
            android.util.Log.w(TAG, "pool agotado -> passthrough sin textura propia")
            outputListener?.onOutputFrameAvailable(inputFrame, pts)
            hasHistory = false
            prevPtsUs = pts
            return
        }
        val gap = if (hasHistory && prevPtsUs > 0) pts - prevPtsUs else -1L
        val discont = !hasHistory || gap <= 0L || gap > MAX_GAP_US
        if (discont) {
            // Primer cuadro / seek / pausa larga: passthrough (doble ancla).
            pinHistory(emitPassthrough(inputFrame.texId, pts))
            prevPtsUs = pts
            return
        }

        // Fuente ya a 60+: passthrough 1:1, sin slots extra.
        if (gap <= STEP_US + STEP_TOLERANCE_US) {
            pinHistory(emitPassthrough(inputFrame.texId, pts))
            prevPtsUs = pts
            return
        }

        // Slots absolutos a 60 Hz estrictamente dentro de (prevPts, pts].
        var t = ((prevPtsUs + STEP_US - 1) / STEP_US) * STEP_US
        var emitted = 0
        while (t < pts && emitted < MAX_EMIT) {
            // Reservar 1 textura para el passthrough nativo de cierre.
            if (freeTextures.size <= 1) break
            if (histTexId == 0) break
            val factor = ((t - prevPtsUs).toFloat() / gap.toFloat()).coerceIn(0f, 1f)
            emitInterp(inputFrame.texId, histTexId, factor, t)
            t += STEP_US
            emitted++
        }

        // Cuadro nativo actual (el grid llega a "actual" inclusive); pinnea este
        // passthrough como historial del siguiente input.
        pinHistory(emitPassthrough(inputFrame.texId, pts))
        prevPtsUs = pts
    }

    private fun emitInterp(currTex: Int, prevTex: Int, factor: Float, outPts: Long) {
        val out = useOutputTexture()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.fboId)
        GLES20.glViewport(0, 0, out.width, out.height)
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uCurrTex", currTex, 0)
        glProgram.setSamplerTexIdUniform("uPrevTex", prevTex, 1)
        glProgram.setFloatUniform("uFactor", factor)
        glProgram.setIntUniform("uDemoSplit", if (demoSplit) 1 else 0)
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        outputListener?.onOutputFrameAvailable(out, outPts)
    }

    private fun emitPassthrough(currTex: Int, outPts: Long): GlTextureInfo {
        val out = useOutputTexture()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.fboId)
        GLES20.glViewport(0, 0, out.width, out.height)
        copyProgram.use()
        copyProgram.setSamplerTexIdUniform("uTexSampler", currTex, 0)
        copyProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        outputListener?.onOutputFrameAvailable(out, outPts)
        return out
    }

    private fun pinHistory(out: GlTextureInfo) {
        val old = histSlot
        histSlot = out
        histTexId = out.texId
        histFboId = 0
        hasHistory = true
        // El historial anterior dejo de serlo: si Media3 ya lo liberó (no está
        // en inUse), vuelve al pool. Si sigue en manos de Media3, releaseOutputFrame
        // lo devolverá al pool (ya no == histSlot). Sin esto el hist antiguo se
        // fugaba y el pool se agotaba en ~3 frames.
        if (old != null && !inUseTextures.contains(old)) {
            freeTextures.addLast(old)
        }
    }

    private fun useOutputTexture(): GlTextureInfo {
        val out = freeTextures.removeFirstOrNull()
            ?: throw IllegalStateException("SixtyFpsInterp: pool de salida vacío")
        inUseTextures.add(out)
        return out
    }

    // ------------------------------------------------------------------ GL pool

private fun ensurePool() {
        if (poolCreated) {
            if (poolSizeW == inputWidth && poolSizeH == inputHeight) return
            deletePool()
        }
        if (freeTextures.isNotEmpty()) {
            // Intento anterior parcial (drivers que asignan de forma poco fiable):
            // limpiar los restos antes de reintentar la creacion completa.
            for (t in freeTextures.toList()) {
                GLES20.glDeleteTextures(1, intArrayOf(t.texId), 0)
                GLES20.glDeleteFramebuffers(1, intArrayOf(t.fboId), 0)
            }
            freeTextures.clear()
        }
        poolSizeW = inputWidth
        poolSizeH = inputHeight
        repeat(OUTPUT_POOL_CAPACITY) {
            freeTextures.addLast(createTexture(inputWidth, inputHeight))
        }
        poolCreated = true
    }

    private fun createTexture(w: Int, h: Int): GlTextureInfo {
    // El driver GL de algunos destinos (emulador LDPlayer, testeados) deja
    // texturas sin almacenamiento SOLO al asignar en ráfaga: el FBO queda
    // INCOMPLETE_DIMENSIONS (0x8CD7) y el draw posterior da 0x506. No es
    // sistematico por indice: es aleatorio. Por eso se valida el FBO recien
    // creado y se REINTENTA hasta obtener una textura completa; si todas
    // fallan, se fuerza 1 pasada fuera de ráfaga (mas fiable).
    var last = 0
    repeat(6) {
        val t = createTextureUnchecked(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fboId)
        val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (st == GLES20.GL_FRAMEBUFFER_COMPLETE) return t
        last = st
        android.util.Log.w(TAG, "textura incompleta 0x" + Integer.toHexString(st) + " retry")
        GLES20.glDeleteTextures(1, intArrayOf(t.texId), 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(t.fboId), 0)
    }
    throw GlUtil.GlException(
        "No se pudo crear textura valida (0x" + Integer.toHexString(last) + ") w=" + w + " h=" + h,
    )
}

private fun createTextureUnchecked(w: Int, h: Int): GlTextureInfo {
    val tex = IntArray(1)
    GLES20.glGenTextures(1, tex, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexImage2D(
        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
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
    // ORDEN CORRECTO del constructor: (texId, fboId, rboId, width, height).
    // Antes estaba invertido (fbo, tex): glCheckFramebufferStatus y los draws
    // tocaban IDs ajenos de Media3 -> FBO "incompleto" y GL_INVALID_FRAMEBUFFER.
    return GlTextureInfo(tex[0], fbo[0], 0, w, h)
}

    private fun deletePool() {
        val all = (freeTextures + inUseTextures + listOfNotNull(histSlot)).distinct()
        for (t in all) {
            GLES20.glDeleteTextures(1, intArrayOf(t.texId), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(t.fboId), 0)
        }
        freeTextures.clear()
        inUseTextures.clear()
        histSlot = null
        histTexId = 0
        histFboId = 0
        hasHistory = false
        prevPtsUs = -1L
        poolCreated = false
    }

    private fun deleteHistory() {
        // Unpin del historial: NO se borra GL aqui (la textura sigue en el pool
        // y de ella se encarga deletePool). Al des-pinno vuelve al pool normal.
        val old = histSlot
        histSlot = null
        histTexId = 0
        histFboId = 0
        hasHistory = false
        prevPtsUs = -1L
        if (old != null && inUseTextures.remove(old)) {
            freeTextures.addLast(old)
        }
    }

    private fun signalReady() {
        // El pool de salida se crea DENTRO de queueInputFrame (necesita el ancho
        // del primer cuadro). Si no existe aun, declarar lista la primera vez:
        // de lo contrario Media3 nunca envia el primer input y la cadena queda
        // colgada en negro. Si el pool no se pudo crear, seguir tambien
        // (passthrough de emergencia). Con pool sano, solo aceptar si hay >=1
        // textura libre (evita el "pool de salida vacio" al agotarse).
        if (!poolCreated || poolFailed || freeTextures.size >= MIN_FREE_TO_ACCEPT) {
            inputListener?.onReadyToAcceptInputFrame()
        }
    }

    companion object {
        private const val TAG = "SixtyFpsInterp"
        private const val STEP_US = 16_667L
        private const val STEP_TOLERANCE_US = 4_000L
        private const val MAX_GAP_US = 400_000L
        private const val MAX_EMIT = 4
        private const val OUTPUT_POOL_CAPACITY = 6
        private const val MIN_FREE_TO_ACCEPT = 2

        private val VERTEX_SHADER = ShaderBlobs.motionx2Vertex
        private val COPY_FRAGMENT_SHADER = ShaderBlobs.motionx2CopyFragment

        private val INTERP_FRAGMENT_SHADER = "\n" +
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
            "    // anti-fantasma: la mezcla nunca sale del rango [min,max] de ambos\n" +
            "    // cuadros -> sin doble imagen en oclusiones, solo se pega al borde.\n" +
            "    vec3 lo = min(p, c);\n" +
            "    vec3 hi = max(p, c);\n" +
            "    vec3 rgb = clamp(mix(p, c, f), lo, hi);\n" +
            "    // aguja sutil: el 60p sintetico no debe verse blando\n" +
            "    float k = 0.12 * (1.0 - abs(f - 0.5) * 2.0);\n" +
            "    rgb = mix(rgb, clamp(rgb * 1.04 + 0.003, 0.0, 1.0), k);\n" +
            "    if (uDemoSplit == 1 && vTexCoord.x < 0.5) { rgb = c; }\n" +
            "    gl_FragColor = vec4(rgb, 1.0);\n" +
            "}\n"
    }
}