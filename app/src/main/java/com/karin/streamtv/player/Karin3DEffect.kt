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

/**
 * Efecto 3D estereoscópico de KarinFLiX (1 pase, GLES2).
 *
 * - SBS_2D: muestrea la mitad izq/der y la estira a pantalla completa.
 * - TAB_2D: muestrea la mitad sup/inf y la estira.
 * - ANAGLYPH ([anaglyph] 0=rojo-cian Dubois, 1=rojo-azul, 2=rojo-verde).
 *   Con [inputKind]=SBS/TAB mezcla ambos ojos reales; con 2D genera
 *   pseudo-3D EXPERIMENTAL por paralaje de luma ([depth] = intensidad:
 *   0 intacto). No es profundidad real: ver AIDepthDibr para IA.
 * - VR_SBS: COMPATIBILIDAD Cardboard (no es 3D): con fuente 2D duplica
 *   el cuadro en ambas mitades; con fuente SBS la pasa tal cual.
 *   Disparidad = 0, sin seguimiento de cabeza.
 * - PULFRICH TEMPORAL (Fabulojos 1997 + 3Deeps): retardo real de 1 cuadro
 *   en un ojo (buffer propio, como MotionX2). L=actual, R=previo;
 *   el movimiento lateral se vuelve disparidad anaglifo. En quieto
 *   (L≈R) la mezcla por movimiento deja la imagen intacta: sin lentes
 *   se ve normal, con bicolor hay profundidad solo en movimiento.
 *   depth=0 → passthrough 2D puro (modo clásico de lente oscuro).
 *
 * Va AL FINAL de la cadena (tras Shader, antes de la línea Demo) porque
 * reformatea la imagen de salida: ver Karin3DController.compatWarnings()
 * para qué filtros previos lo degradan (B/N, CRT, Upscaler, MotionX2...).
 * Con demoSplit la mitad izquierda queda intacta para comparar.
 */
class Karin3DEffect(
    private val mode: Int = Karin3DController.MODE_OFF,
    private var depth: Float = 0.4f,
    private val swapEye: Boolean = false,
    private val demoSplit: Boolean = false,
    private val anaglyph: Int = Karin3DController.ANAG_RED_CYAN,
    private val inputKind: Int = Karin3DController.INPUT_2D,
) : GlEffect {

    private var program: Karin3DShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return Karin3DShaderProgram(
            context, useHdr, mode, depth, swapEye, inputKind, anaglyph, demoSplit,
        ).also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        mode == Karin3DController.MODE_OFF

    fun updateDepth(newDepth: Float) {
        depth = newDepth.coerceIn(0f, 1f)
        program?.updateDepth(depth)
    }
}

class Karin3DShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val mode: Int,
    private var depth: Float,
    private val swapEye: Boolean,
    private val inputKind: Int,
    private val anaglyph: Int,
    private val demoSplit: Boolean = false,
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram
    private var srcW = 0f
    private var srcH = 0f

    // Historial de 1 cuadro para Pulfrich temporal (patrón MotionX2:
    // textura + FBO propios, GLES2-safe). Solo se aloca en modo 5.
    private var histTexId = 0
    private var histFboId = 0
    private var histW = 0
    private var histH = 0
    private var hasHistory = false
    private var lastPtsUs = -1L
    private var copyGlProgram: GlProgram? = null
    private fun getCopyProgram(): GlProgram {
        var p = copyGlProgram
        if (p == null) {
            p = GlProgram(VERTEX_SHADER, COPY_SHADER)
            p.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            p.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
            p.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
            copyGlProgram = p
        }
        return p
    }

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
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
        glProgram.setIntUniform("uDemoSplit", if (demoSplit) 1 else 0)
    }

    override fun release() {
        try {
            glProgram.delete()
        } catch (_: Exception) {
        }
        try {
            copyGlProgram?.delete()
        } catch (_: Exception) { }
        copyGlProgram = null
        try {
            if (histFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(histFboId), 0)
        } catch (_: Exception) { }
        try {
            if (histTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(histTexId), 0)
        } catch (_: Exception) { }
        histFboId = 0
        histTexId = 0
        super.release()
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        // El 3D no cambia la resolución del buffer: reformatea dentro del
        // mismo cuadro. Se guarda la resolución para taps de 1px
        // (Pulfrich) sobre la FUENTE, no el panel.
        srcW = inputWidth.toFloat()
        srcH = inputHeight.toFloat()
        if (inputWidth != histW || inputHeight != histH) {
            // Resolución cambió: el historial ya no vale (igual que MotionX2).
            hasHistory = false
            lastPtsUs = -1L
            deleteHistory()
        }
        try {
            glProgram.setFloatsUniform("uResolution", floatArrayOf(srcW, srcH))
        } catch (_: Exception) { }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val needHistory = mode == Karin3DController.MODE_PULFRICH
            if (needHistory) ensureHistory(srcW.toInt(), srcH.toInt())
            else if (histTexId != 0) deleteHistory()
            if (needHistory && presentationTimeUs < lastPtsUs) {
                // Seek atrás: el cuadro previo ya no vale.
                hasHistory = false
            }
            lastPtsUs = presentationTimeUs

            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setSamplerTexIdUniform(
                "uPrevFrame", if (hasHistory && histTexId != 0) histTexId else inputTexId, 1,
            )
            glProgram.setIntUniform("uMode", mode)
            // Paralaje real: depth 0..1 -> 0..3% del ancho (sutil, sin mareo).
            glProgram.setFloatUniform("uDepth", depth.coerceIn(0f, 1f) * 0.03f)
            glProgram.setIntUniform("uSwap", if (swapEye) 1 else 0)
            glProgram.setIntUniform("uInput", inputKind.coerceIn(0, 2))
            glProgram.setIntUniform("uAnaglyph", anaglyph.coerceIn(0, 2))
            glProgram.setIntUniform("uFirst", if (hasHistory) 0 else 1)
            try {
                glProgram.setFloatsUniform("uResolution", floatArrayOf(srcW, srcH))
            } catch (_: Exception) { }
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Guardar cuadro actual como previo (copia GLES2-safe, como MotionX2).
            if (needHistory && histFboId != 0) {
                val prevFbo = IntArray(1)
                val prevVp = IntArray(4)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevVp, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, histFboId)
                GLES20.glViewport(0, 0, histW, histH)
                val cp = getCopyProgram()
                cp.use()
                cp.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                cp.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
                GLES20.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])
                hasHistory = true
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun ensureHistory(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (histTexId != 0 && histW == w && histH == h) return
        deleteHistory()
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        histTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
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
        histW = w
        histH = h
        hasHistory = false
    }

    private fun deleteHistory() {
        if (histFboId != 0) {
            try {
                GLES20.glDeleteFramebuffers(1, intArrayOf(histFboId), 0)
            } catch (_: Exception) { }
            histFboId = 0
        }
        if (histTexId != 0) {
            try {
                GLES20.glDeleteTextures(1, intArrayOf(histTexId), 0)
            } catch (_: Exception) { }
            histTexId = 0
        }
        histW = 0
        histH = 0
    }

    fun updateDepth(newDepth: Float) { depth = newDepth.coerceIn(0f, 1f) }

    companion object {
        private const val COPY_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            void main() {
                gl_FragColor = vec4(texture2D(uTexSampler, vTexCoord).rgb, 1.0);
            }
        """
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uTransformationMatrix * aFramePosition;
                vec4 tp = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
                vTexCoord = (uTexTransformationMatrix * tp).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform sampler2D uPrevFrame; // cuadro previo (Pulfrich temporal)
            uniform int uFirst;     // 1=sin historial (primer cuadro / seek)
            uniform int uMode;      // 1=SBS2D 2=TAB2D 3=ANAGLYPH 4=VR_SBS 5=PULFRICH
            uniform float uDepth;   // paralaje (fracción del ancho)
            uniform int uSwap;      // 1=ojo derecho/inferior/líneas impares
            uniform int uInput;     // 0=2D 1=SBS 2=TAB (fuente estéreo)
            uniform int uAnaglyph;  // 0=rojo-cian 1=rojo-azul 2=rojo-verde
            uniform vec2 uResolution;
            uniform int uDemoSplit;

            // Ojos desde SBS o TAB (swap invierte). Devuelve par L/R.
            void fetchStereo(vec2 uv, out vec3 L, out vec3 R) {
                if (uInput == 2) {
                    // TAB: sup/inf. Y invertida según transform: el swap cubre ambas.
                    vec3 top = texture2D(uTexSampler, vec2(uv.x, uv.y * 0.5)).rgb;
                    vec3 bot = texture2D(uTexSampler, vec2(uv.x, uv.y * 0.5 + 0.5)).rgb;
                    L = (uSwap == 1) ? bot : top;
                    R = (uSwap == 1) ? top : bot;
                } else {
                    // SBS (uInput==1; uInput==2 se trata arriba).
                    vec3 l = texture2D(uTexSampler, vec2(uv.x * 0.5, uv.y)).rgb;
                    vec3 r = texture2D(uTexSampler, vec2(uv.x * 0.5 + 0.5, uv.y)).rgb;
                    L = (uSwap == 1) ? r : l;
                    R = (uSwap == 1) ? l : r;
                }
            }

            // Anaglifo half-color por luma (predecible en los 3 lentes).
            vec3 anaglyphMix(vec3 L, vec3 R) {
                float ll = dot(L, vec3(0.299, 0.587, 0.114));
                float rl = dot(R, vec3(0.299, 0.587, 0.114));
                if (uAnaglyph == 1) return vec3(ll, 0.0, rl);       // rojo-azul
                if (uAnaglyph == 2) return vec3(ll, rl, 0.0);       // rojo-verde
                return vec3(ll, rl, rl);                            // rojo-cian simple
            }

            void main() {
                vec2 uv = vTexCoord;
                // outc solo se muestrea donde se usa: los modos SBS/TAB/VR y
                // el anaglifo estéreo sobrescribirían el fetch inicial.
                vec3 outc;

                if (uMode == 1) {
                    // SBS -> 2D: cada mitad ocupa todo el ancho.
                    float eye = (uSwap == 1) ? 1.0 : 0.0;
                    outc = texture2D(uTexSampler, vec2((uv.x + eye) * 0.5, uv.y)).rgb;
                } else if (uMode == 2) {
                    // TAB -> 2D: cada mitad ocupa todo el alto.
                    float eye = (uSwap == 1) ? 1.0 : 0.0;
                    outc = texture2D(uTexSampler, vec2(uv.x, (uv.y + eye) * 0.5)).rgb;
                } else if (uMode == 3) {
                    if (uInput == 1 || uInput == 2) {
                        vec3 L; vec3 R;
                        fetchStereo(uv, L, R);
                        if (uAnaglyph == 0) {
                            // Dubois simplificado rojo-cian (mejor color).
                            float rl = dot(L, vec3(0.299, 0.587, 0.114));
                            float gl = dot(R, vec3(0.299, 0.587, 0.114));
                            float bl = dot(R, vec3(0.299, 0.587, 0.114));
                            outc = vec3(
                                0.456 * rl + 0.500 * gl + 0.176 * bl,
                                -0.040 * rl - 0.038 * gl - 0.016 * bl + gl,
                                -0.015 * rl - 0.021 * gl + 0.100 * bl + bl
                            );
                            outc = clamp(outc, 0.0, 1.0);
                        } else {
                            outc = anaglyphMix(L, R);
                        }
                    } else {
                        // Pseudo-3D desde 2D: paralaje horizontal por luma.
                        // La INTENSIDAD la manda uDepth: 0 = imagen intacta,
                        // 1 = efecto completo. (Antes mezclaba un mínimo
                        // fijo y teñía incluso al 0%.)
                        outc = texture2D(uTexSampler, uv).rgb;
                        float luma = dot(outc, vec3(0.299, 0.587, 0.114));
                        float shift = (luma - 0.5) * uDepth * 2.0;
                        vec3 cl = texture2D(uTexSampler, vec2(clamp(uv.x - shift, 0.0, 1.0), uv.y)).rgb;
                        vec3 cr = texture2D(uTexSampler, vec2(clamp(uv.x + shift, 0.0, 1.0), uv.y)).rgb;
                        // uDepth llega escalado (0..0.03): x33 lo devuelve a 0..1.
                        float amt = clamp(uDepth * 33.0, 0.0, 1.0);
                        if (uAnaglyph == 0) {
                            float r = dot(cl, vec3(0.299, 0.587, 0.114));
                            float lcr = dot(cr, vec3(0.299, 0.587, 0.114));
                            outc = mix(outc, vec3(r, lcr, lcr), amt);
                        } else {
                            outc = mix(outc, anaglyphMix(cl, cr), amt);
                        }
                    }
                } else if (uMode == 4) {
                    // VR Cardboard: con fuente 2D se duplica el cuadro en
                    // ambas mitades; con fuente SBS ya es SBS y se pasa tal
                    // cual (duplicarla daría 4 ojos rotos).
                    if (uInput == 1) {
                        outc = texture2D(uTexSampler, uv).rgb;
                    } else {
                        float right = step(0.5, uv.x);
                        outc = texture2D(uTexSampler, vec2(uv.x * 2.0 - right, uv.y)).rgb;
                    }
                } else if (uMode == 5) {
                    // PULFRICH TEMPORAL (retardo real de 1 cuadro):
                    // L = cuadro actual, R = cuadro previo (uSwap invierte).
                    // El movimiento lateral se vuelve disparidad anaglifo.
                    // En quieto L≈R → motion≈0 → se deja la imagen intacta
                    // (sin lentes se ve normal). depth=0 → 2D puro clásico
                    // (para lente oscuro físico, sin síntesis).
                    vec3 cur = texture2D(uTexSampler, uv).rgb;
                    vec3 prv = (uFirst == 1) ? cur : texture2D(uPrevFrame, uv).rgb;
                    vec3 KLp = vec3(0.299, 0.587, 0.114);
                    float yc = dot(cur, KLp);
                    float yp = dot(prv, KLp);
                    // Energía de movimiento por pixel (0 quieto → 1 corte).
                    float motion = clamp(abs(yc - yp) * 6.0, 0.0, 1.0);
                    // uDepth llega escalado (0..0.03): x33 → 0..1.
                    float delayAmt = clamp(uDepth * 33.0, 0.0, 1.0);
                    float amt = motion * delayAmt;
                    vec3 L = (uSwap == 1) ? prv : cur;
                    vec3 R = (uSwap == 1) ? cur : prv;
                    vec3 ana;
                    if (uAnaglyph == 1) {
                        ana = vec3(dot(L, KLp), 0.0, dot(R, KLp));
                    } else if (uAnaglyph == 2) {
                        ana = vec3(dot(L, KLp), dot(R, KLp), 0.0);
                    } else {
                        ana = vec3(dot(L, KLp), dot(R, KLp), dot(R, KLp));
                    }
                    // Realce leve de bordes horizontales solo donde hay
                    // movimiento (más señal Pulfrich, sin teñir el quieto).
                    vec2 hpx = vec2(1.0 / max(uResolution.x, 1.0), 0.0);
                    float lh0 = dot(texture2D(uTexSampler, uv - hpx).rgb, KLp);
                    float rh0 = dot(texture2D(uTexSampler, uv + hpx).rgb, KLp);
                    float edge = clamp((abs(yc - lh0) + abs(yc - rh0)) * 3.0, 0.0, 1.0);
                    vec3 sharp = clamp(cur * (1.0 + edge * amt * 0.25), 0.0, 1.0);
                    outc = mix(sharp, ana, amt);
                } else {
                    // Modo desconocido/off: passthrough defensivo.
                    outc = texture2D(uTexSampler, uv).rgb;
                }

                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    outc = texture2D(uTexSampler, vTexCoord).rgb;
                }
                gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            }
        """
    }
}
