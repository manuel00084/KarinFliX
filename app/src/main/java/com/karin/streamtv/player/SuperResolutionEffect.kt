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
 *    (camino texOff), con el análisis y los pesos guiados por luma y
 *    aplicados a RGB (aprox. 16 fetches vec4, equivalente en coste a
 *    Light Boost). RCAS de un solo pase usa vecinos bilineales del origen
 *    como aproximación de la salida EASU (estándar en FSR single-pass).
 *    Mejor calidad por consumo del lote (sin halos ni exceso de suavizado):
 *    sustituye al viejo Bicúbico Catmull-Rom.
 *
 *  - **Anime4K rápido**: unsharp por difference-of-Gaussians sobre el
 *    bilineal GL_LINEAR (cruz 1px + anillo 2px, 9 taps), tuneado para
 *    contenido anime; sin CNN (no son tiempo real en Mali).
 *
 * Sizing: devuelve el doble de ancho/alto. Modo `restorePass` (cuando
 * sustituye la pasada de restauración de Light Boost half-res) restaura la
 * resolución completa de la fuente (tope 2160p); modo independiente
 * (el propio upscaler como efecto) re-escala 2x con tope 1080p.
 *
 * Nitidez en vivo via [updateSharpness]: en FSR/Anime4K baja el "sharpness"
 * de RCAS (más valor = más afilado).
 *
 * Demo split-screen ([demoSplit]): la mitad izquierda conserva el upscale
 * bilineal del hardware (muestreo directo con GL_LINEAR, sin calidad) y la
 * derecha el upscale real. Sin costo extra: early-out antes de los taps.
 * Es lo que permite comparar en videos SD/720p, donde este efecto sí
 * trabaja (en 1080p+ es no-op y el demo de la cadena ya funcionaba).
 *
 * GLES2 compatible (GLSL ES 1.00: sin uintBitsToFloat/gather/textureSize,
 * rcp/inversesqrt genéricos, min/max de 2 argumentos, highp).
 */
class SuperResolutionEffect(
    private val mode: Int = MODE_FSR,
    private val sharpness: Float = 0.2f,
    private val restorePass: Boolean = false,
    private val separateRcas: Boolean = false,
    private val karinVariant: Int = KARIN_CRISP,
    /** Reporta en runtime los tamaños REALES de entrada/salida del pase GL
     *  (lo que Media3 configuró), para el OSD y el diálogo de stats. */
    private val onConfigured: ((inW: Int, inH: Int, outW: Int, outH: Int) -> Unit)? = null,
    private val demoSplit: Boolean = false,
) : GlEffect {

    private var program: SuperResProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return SuperResProgram(context, useHdr, mode, sharpness, restorePass, separateRcas, onConfigured, karinVariant, demoSplit).also {
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
        const val MODE_KARIN = 3
        const val KARIN_ECO = 0
        const val KARIN_CRISP = 1

        /** Tope de re-escala de calidad 2x (efecto independiente). */
        const val MAX_UPSCALE_HEIGHT = 1080

        /** Tope al restaurar la resolución completa tras half-res de Light Boost. */
        const val MAX_RESTORE_HEIGHT = 2160

        /** Resolución de salida real del upscaler para unas dimensiones de
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
    private val karinVariant: Int = SuperResolutionEffect.KARIN_CRISP,
    private val demoSplit: Boolean = false,
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram
    private var outputWidth = 0
    private var outputHeight = 0

    init {
        val fragment = when (mode) {
            SuperResolutionEffect.MODE_ANIME4K -> FRAGMENT_ANIME4K
            SuperResolutionEffect.MODE_KARIN ->
                if (separateRcas) FRAGMENT_KARIN_EASU
                else if (karinVariant == SuperResolutionEffect.KARIN_ECO) FRAGMENT_KARIN_ECO
                else FRAGMENT_KARIN
            else -> if (separateRcas) FRAGMENT_FSR_EASU else FRAGMENT_FSR
        }
        // Demo split (ver withDemoSplit): si el fragmento no trae los
        // marcadores, el efecto sigue sin demo (aviso en log, sin crash:
        // setear un uniform inexistente hace NPE en Media3).
        val demoFragment = withDemoSplit(fragment)
        if (demoFragment == null) {
            Log.w("SuperResolutionEffect", "fragmento sin punto de demo (mode=$mode): split no disponible")
        }
        try {
            glProgram = GlProgram(VERTEX_SHADER, demoFragment ?: fragment)
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
        if (demoFragment != null) {
            glProgram.setIntUniform("uDemoSplit", if (demoSplit) 1 else 0)
        }
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
            SuperResolutionEffect.MODE_KARIN -> {
                glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
                glProgram.setFloatsUniform("uInputSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
                glProgram.setFloatUniform("uSharpness", sharpness)
                val karinScale = if (inputWidth > 0) outputWidth.toFloat() / inputWidth else 2f
                glProgram.setFloatUniform("uScaleFactor", karinScale)
            }
            else -> {
                glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
                glProgram.setFloatsUniform("uInputSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
                if (!separateRcas) {
                    // Pase único EASU+RCAS: RCAS lee vecinos del origen.
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
                SuperResolutionEffect.MODE_KARIN ->
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
         * se muestree con GL_LINEAR. Análisis y pesos guiados por luma
         * (vec3) y aplicados a los tres canales a la vez.
         */
        private val FRAGMENT_FSR = ShaderBlobs.superresFsr

        /**
         * FSR EASU solo (sin RCAS dentro). Cuando se activa la pasada RCAS
         * separada (estilo madVR, dos pases: primero escalar, luego afilar el
         * resultado real), la nitidez se aplica en un segundo efecto que
         * muestrea los píxeles YA escalados. Así RCAS no lee vecinos bilineales
         * del origen sino la salida EASU verdadera.
         */
        private val FRAGMENT_FSR_EASU = ShaderBlobs.superresFsrEasu

        /**
         * Anime4K rápido (sin CNN, tiempo real en Mali): upscale bilineal
         * GL_LINEAR + unsharp por difference-of-Gaussians. Cruz a 1px
         * (radio fino) y anillo a 2px (radio amplio), guiado por luma para
         * no amplificar ruido en zonas lisas ni extremos de luminancia.
         */
        private val FRAGMENT_ANIME4K = ShaderBlobs.superresAnime4k

        private val FRAGMENT_KARIN_ECO = ShaderBlobs.superresKarinEco

        private val FRAGMENT_KARIN = ShaderBlobs.superresKarin

        private val FRAGMENT_KARIN_EASU = ShaderBlobs.superresKarinEasu

        private const val DEMO_SAMPLER_MARK = "uniform sampler2D uTexSampler;"
        private const val DEMO_MAIN_MARK = "void main() {"

        /**
         * Inyecta el split demo en un fragmento del upscaler: declara
         * `uDemoSplit` y un early-out al inicio del main() que conserva la
         * mitad izquierda con upscale bilineal del hardware (muestreo
         * directo; la textura de entrada es GL_LINEAR, ver comentario FSR).
         * Devuelve null si el fragmento no trae los marcadores.
         *
         * Es función pura (sin GL) para poder probarla en JVM: los 6
         * fragmentos comparten una sola declaración del sampler y un solo
         * main(), así que un solo punto cubre todos los modos.
         */
        internal fun withDemoSplit(fragment: String): String? {
            if (!fragment.contains(DEMO_SAMPLER_MARK) || !fragment.contains(DEMO_MAIN_MARK)) return null
            return fragment
                .replace(DEMO_SAMPLER_MARK, "uniform sampler2D uTexSampler;\nuniform int uDemoSplit;")
                .replace(
                    DEMO_MAIN_MARK,
                    "void main() {\n" +
                        "if (uDemoSplit == 1 && vTexCoord.x < 0.5) " +
                        "{ gl_FragColor = vec4(texture2D(uTexSampler, vTexCoord).rgb, 1.0); return; }",
                )
        }

    }
}
