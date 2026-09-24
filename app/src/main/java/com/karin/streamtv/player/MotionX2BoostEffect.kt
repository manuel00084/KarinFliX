package com.karin.streamtv.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

private const val TAG = "MotionX2BoostEffect"

/**
 * MotionX2 Boost: hace que el video SE VEA mÃ¡s fluido (efecto telenovela).
 *
 * Guarda el cuadro anterior REAL en una textura propia (FBO) para mezclar.
 * Nota honesta: el shader es 1:1 (entra 1 cuadro, sale 1), asÃ­ que no emite
 * cuadros extra; DOUBLING muestra cada cuadro nÃ­tido sin mezcla y la
 * repeticiÃ³n visible la hace el panel solo.
 *
 * Modos:
 * - HYBRID (recomendado): cuadro nÃ­tido + micro-mezcla del anterior. Mejor balance.
 * - DOUBLING: Frame x2, cada cuadro tal cual, sin mezcla. MÃ¡s ligero, menos suave.
 * - BLEND: mezcla suave entre anterior y actual. Suave, pero puede verse fantasma.
 */
enum class MotionX2Mode(val label: String) {
    HYBRID("HYBRID (Doubling + Micro-Blend)"),
    DOUBLING("DOUBLING (Frame x2)"),
    BLEND("BLEND (Suavizado)"),
    @Deprecated("SPIKE eliminado: se migra a REAL60")
    INTERP("Interpolación 60 (SPIKE)"),
    REAL60("60 fps reales (GRID anti-ghost · experimental)"),
    ECO60("ECO60 (60fps liviano · bajo consumo)");

    companion object {
        /** True si el ordinal guardado en prefs emite cuadros intermedios (fps reales extra). */
        fun isRealFps(ordinal: Int): Boolean =
            ordinal == INTERP.ordinal || ordinal == REAL60.ordinal ||
                ordinal == ECO60.ordinal || ordinal == DOUBLING.ordinal

        /**
         * Resuelve el ordinal guardado en prefs al modo efectivo. El modo
         * INTERP/SPIKE (3) está eliminado: migra a REAL60, su sucesor. Así los
         * ajustes viejos con 3 se comportan como REAL60 sin tocar prefs.
         * Los ordinales del enum NO se tocan (estabilidad de prefs).
         */
        fun resolveStored(ordinal: Int): MotionX2Mode =
            when (ordinal) {
                INTERP.ordinal -> REAL60
                else -> values().getOrNull(ordinal) ?: HYBRID
            }
    }
}

/** True si el modo emite cuadros intermedios (fps reales extra). */
fun MotionX2Mode.isRealFps(): Boolean =
    this == MotionX2Mode.INTERP || this == MotionX2Mode.REAL60 ||
        this == MotionX2Mode.ECO60 || this == MotionX2Mode.DOUBLING

class MotionX2BoostEffect(
    private var mode: MotionX2Mode = MotionX2Mode.HYBRID,
    private var strength: Float = 0.5f,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: MotionX2BoostShaderProgram? = null
    private var frcProgram: MotionX2FrcShaderProgram? = null
    private var real60Program: com.karin.streamtv.player.sixty.SixtyFpsInterpShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        android.util.Log.d(TAG, "toGlShaderProgram mode=$mode useHdr=$useHdr")
        return when (mode) {
            MotionX2Mode.INTERP -> MotionX2FrcShaderProgram(useHdr, strength, demoSplit)
                .also { frcProgram = it }
            MotionX2Mode.REAL60 ->
                com.karin.streamtv.player.sixty.SixtyFpsInterpShaderProgram(useHdr, demoSplit)
                    .also { real60Program = it }
            else -> MotionX2BoostShaderProgram(context, useHdr, mode, strength, demoSplit)
                .also { program = it }
        }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        strength <= 0f || mode == MotionX2Mode.DOUBLING

    fun updateMode(newMode: MotionX2Mode) {
        mode = newMode
        program?.updateMode(newMode)
    }

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class MotionX2BoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var mode: MotionX2Mode,
    private var strength: Float,
    private var demoSplit: Boolean = false,
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram
    private val copyProgram: GlProgram
    private var inputWidth = 0
    private var inputHeight = 0

    // Historial real del cuadro previo (textura + FBO propios, GLES2 compatible).
    private var histTexId = 0
    private var histFboId = 0
    private var histWidth = 0
    private var histHeight = 0
    private var hasHistory = false
    private var lastPtsUs = -1L

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
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
        glProgram.setIntUniform("uDemoSplit", if (demoSplit) 1 else 0)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        if (inputWidth != histWidth || inputHeight != histHeight) {
            deleteHistory()
            hasHistory = false
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            // DOUBLING no usa el cuadro previo: evita alocar/copiar el
            // historial (1 pase fullscreen + ~8MB VRAM a 1080p) y libera
            // lo que hubiera de un modo anterior.
            val needHistory = mode != MotionX2Mode.DOUBLING
            if (!needHistory) {
                if (histTexId != 0) deleteHistory()
                hasHistory = false
            } else {
                ensureHistory()
            }
            if (needHistory && presentationTimeUs < lastPtsUs) {
                // Seek hacia atrás: el historial ya no vale.
                hasHistory = false
            }
            lastPtsUs = presentationTimeUs

            // 1. Pasada principal (al FBO de salida de Media3).
            // Sin historial (DOUBLING/primer cuadro) se enlaza la propia
            // entrada como previa para no muestrear la textura 0.
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setSamplerTexIdUniform("uPrevFrame", if (histTexId != 0) histTexId else inputTexId, 1)
            glProgram.setFloatUniform("uStrength", strength)
            glProgram.setIntUniform("uMode", mode.ordinal)
            glProgram.setIntUniform("uFirstFrame", if (hasHistory) 0 else 1)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // 2. Guardar cuadro actual como historial (copia GLES2-safe).
            // Solo cuando el modo lo necesita (BLEND/HYBRID).
            if (!needHistory) return
            val prevFbo = IntArray(1)
            val prevVp = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevVp, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, histFboId)
            GLES20.glViewport(0, 0, histWidth, histHeight)
            copyProgram.use()
            copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            copyProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
            GLES20.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])
            hasHistory = true
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        deleteHistory()
        super.release()
    }

    private fun ensureHistory() {
        if (histTexId != 0 && histWidth == inputWidth && histHeight == inputHeight) return
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
        histWidth = inputWidth
        histHeight = inputHeight
        hasHistory = false
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
        histWidth = 0
        histHeight = 0
    }

    fun updateMode(newMode: MotionX2Mode) { mode = newMode }
    fun updateStrength(newStrength: Float) { strength = newStrength.coerceIn(0f, 1f) }

    companion object {
        private val VERTEX_SHADER = ShaderBlobs.motionx2Vertex

        private val COPY_FRAGMENT_SHADER = ShaderBlobs.motionx2CopyFragment

        private val FRAGMENT_SHADER = ShaderBlobs.motionx2Fragment
    }
}
