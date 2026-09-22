package com.karin.streamtv.player

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * SuperResolutionEffect: upscaler de calidad que sustituye al upsample
 * bilineal puro (UpsampleEffect). Dos kernels en un solo pase:
 *
 *  - **FSR** (default): AMD FidelityFX Super Resolution v1.0.2, EASU
 *    (edge-adaptive spatial upsampling, 12 taps) seguido de RCAS (robust
 *    contrast adaptive sharpening, 3x3). Portado a GLES 2.0 sin `gather`
 *    (camino texOff), con el anÃ¡lisis y los pesos guiados por luma y
 *    aplicados a RGB (aprox. 16 fetches vec4, equivalente en coste a
 *    Light Boost). RCAS de un solo pase usa vecinos bilineales del origen
 *    como aproximaciÃ³n de la salida EASU (estÃ¡ndar en FSR single-pass).
 *    Mejor calidad por consumo del lote (sin halos ni exceso de suavizado):
 *    sustituye al viejo BicÃºbico Catmull-Rom.
 *
 *  - **Anime4K rÃ¡pido**: unsharp por difference-of-Gaussians sobre el
 *    bilineal GL_LINEAR (cruz 1px + anillo 2px, 9 taps), tuneado para
 *    contenido anime; sin CNN (no son tiempo real en Mali).
 *
 * Sizing: devuelve el doble de ancho/alto. Modo `restorePass` (cuando
 * sustituye la pasada de restauraciÃ³n de Light Boost half-res) restaura la
 * resoluciÃ³n completa de la fuente (tope 2160p); modo independiente
 * (el propio upscaler como efecto) re-escala 2x con tope 1080p.
 *
 * Nitidez en vivo via [updateSharpness]: en FSR/Anime4K baja el "sharpness"
 * de RCAS (mÃ¡s valor = mÃ¡s afilado).
 *
 * GLES2 compatible (GLSL ES 1.00: sin uintBitsToFloat/gather/textureSize,
 * rcp/inversesqrt genÃ©ricos, min/max de 2 argumentos, highp).
 */
class SuperResolutionEffect(
    private val mode: Int = MODE_FSR,
    private val sharpness: Float = 0.2f,
    private val restorePass: Boolean = false,
    private val separateRcas: Boolean = false,
    /** Reporta en runtime los tamaños REALES de entrada/salida del pase GL
     *  (lo que Media3 configuró), para el OSD y el diálogo de stats. */
    private val onConfigured: ((inW: Int, inH: Int, outW: Int, outH: Int) -> Unit)? = null,
) : GlEffect {

    private var program: SuperResProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return SuperResProgram(context, useHdr, mode, sharpness, restorePass, separateRcas, onConfigured).also {
            program = it
        }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        // Sin margen de re-escala: el efecto no aporta nada.
        return inputHeight >= if (restorePass) MAX_RESTORE_HEIGHT else MAX_UPSCALE_HEIGHT
    }

    /** Nitidez en vivo (0..1) para el slider. */
    fun updateSharpness(v: Float) {
        program?.updateSharpness(v)
    }

    companion object {
        const val MODE_FSR = 0
        const val MODE_ANIME4K = 2

        /** Tope de re-escala de calidad 2x (efecto independiente). */
        const val MAX_UPSCALE_HEIGHT = 1080

        /** Tope al restaurar la resoluciÃ³n completa tras half-res de Light Boost. */
        const val MAX_RESTORE_HEIGHT = 2160

        /** ResoluciÃ³n de salida real del upscaler para unas dimensiones de
         *  entrada; espejo de [SuperResProgram.configure] (2x con tope). */
        fun outputSizeFor(inputWidth: Int, inputHeight: Int, restorePass: Boolean): Size {
            // Entrada degenerada (p. ej. 0x0 durante una reconfiguración):
            // evita la división por cero y devuelve el mínimo seguro.
            if (inputWidth <= 0 || inputHeight <= 0) return Size(2, 2)
            val cap = if (restorePass) MAX_RESTORE_HEIGHT else MAX_UPSCALE_HEIGHT
            if (inputHeight >= cap) return Size(inputWidth, inputHeight)
            val outH = inputHeight * 2
            val targetH = minOf(outH, cap)
            var outW = (inputWidth * targetH) / inputHeight
            var targetH2 = targetH
            if (outW % 2 != 0) outW += 1
            if (targetH2 % 2 != 0) targetH2 += 1
            return Size(outW.coerceAtLeast(2), targetH2.coerceAtLeast(2))
        }
    }
}

class SuperResProgram(
    context: Context,
    useHdr: Boolean,
    private val mode: Int,
    private var sharpness: Float,
    private val restorePass: Boolean,
    private val separateRcas: Boolean = false,
    private val onConfigured: ((inW: Int, inH: Int, outW: Int, outH: Int) -> Unit)? = null,
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram
    private var outputWidth = 0
    private var outputHeight = 0

    init {
        val fragment = when (mode) {
            SuperResolutionEffect.MODE_ANIME4K -> FRAGMENT_ANIME4K
            else -> if (separateRcas) FRAGMENT_FSR_EASU else FRAGMENT_FSR
        }
        try {
            glProgram = GlProgram(VERTEX_SHADER, fragment)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    fun updateSharpness(v: Float) {
        sharpness = v.coerceIn(0f, 1f)
    }

    override fun release() {
        try {
            glProgram.delete()
        } catch (_: Exception) {
        }
        super.release()
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val output = SuperResolutionEffect.outputSizeFor(inputWidth, inputHeight, restorePass)
        outputWidth = output.width
        outputHeight = output.height
        Log.d("SuperResolutionEffect", "Upscale ${inputWidth}x${inputHeight} -> ${outputWidth}x${outputHeight} (mode=$mode, restorePass=$restorePass)")
        onConfigured?.invoke(inputWidth, inputHeight, outputWidth, outputHeight)
        when (mode) {
            SuperResolutionEffect.MODE_ANIME4K ->
                glProgram.setFloatsUniform("uOutputTexelSize", floatArrayOf(1f / outputWidth, 1f / outputHeight))
            else -> {
                glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
                glProgram.setFloatsUniform("uInputSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
                if (!separateRcas) {
                    // Pase Ãºnico EASU+RCAS: RCAS lee vecinos del origen.
                    glProgram.setFloatsUniform("uOutputTexelSize", floatArrayOf(1f / outputWidth, 1f / outputHeight))
                }
            }
        }
        return Size(outputWidth, outputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            when (mode) {
                SuperResolutionEffect.MODE_ANIME4K ->
                    glProgram.setFloatUniform("uSharpness", sharpness)
                SuperResolutionEffect.MODE_FSR ->
                    if (!separateRcas) glProgram.setFloatUniform("uSharpness", (1f - sharpness) * 0.35f)
            }
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    companion object {
        private val VERTEX_SHADER = ShaderBlobs.superresVertex

        // FRAME_PREAMBLE se fusiona en los fragmentos (ver ShaderBlobs.preamble).

        /**
         * FSR 1.0.2 (EASU + RCAS) en un solo pase adaptado a GLES 2.0.
         * Camino sin gather (texOff): los taps usan posiciones exactas de
         * texel ((fp + 0.5) * uTexelSize) y son exactos aunque la textura
         * se muestree con GL_LINEAR. AnÃ¡lisis y pesos guiados por luma
         * (vec3) y aplicados a los tres canales a la vez.
         */
        private val FRAGMENT_FSR = ShaderBlobs.superresFsr

        /**
         * FSR EASU solo (sin RCAS dentro). Cuando se activa la pasada RCAS
         * separada (estilo madVR, dos pases: primero escalar, luego afilar el
         * resultado real), la nitidez se aplica en un segundo efecto que
         * muestrea los pÃ­xeles YA escalados. AsÃ­ RCAS no lee vecinos bilineales
         * del origen sino la salida EASU verdadera.
         */
        private val FRAGMENT_FSR_EASU = ShaderBlobs.superresFsrEasu

        /**
         * Anime4K rÃ¡pido (sin CNN, tiempo real en Mali): upscale bilineal
         * GL_LINEAR + unsharp por difference-of-Gaussians. Cruz a 1px
         * (radio fino) y anillo a 2px (radio amplio), guiado por luma para
         * no amplificar ruido en zonas lisas ni extremos de luminancia.
         */
        private val FRAGMENT_ANIME4K = ShaderBlobs.superresAnime4k

    }
}
