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
 *   pseudo-3D por paralaje de luma ([depth]).
 * - VR_SBS: duplica el cuadro 2D en ambas mitades para Cardboard.
 * - PULFRICH (Fabulojos 1997): prepara imagen 2D para lente oscuro en un
 *   ojo (realce horizontal sutil que refuerza bordes en movimiento).
 *   Sin lentes se ve normal. Fuente 2D; quieto no hay 3D.
 *
 * Va AL FINAL de la cadena (tras Shader, antes de la línea Demo) porque
 * reformatea la imagen de salida: ver Karin3DController.compatWarnings()
 * para qué filtros previos lo degradan (B/N, CRT, Upscaler, MotionX2...).
 * Con demoSplit la mitad izquierda queda intacta para comparar.
 */
class Karin3DEffect(
    private var mode: Int = Karin3DController.MODE_OFF,
    private var depth: Float = 0.4f,
    private var swapEye: Boolean = false,
    private var stereoInput: Boolean = false,
    private var demoSplit: Boolean = false,
    private var anaglyph: Int = Karin3DController.ANAG_RED_CYAN,
    private var inputKind: Int = -1, // -1 = auto (SBS_2D->SBS, TAB_2D->TAB, resto según stereoInput)
) : GlEffect {

    private var program: Karin3DShaderProgram? = null

    private fun resolvedInput(): Int {
        if (inputKind in 0..2) return inputKind
        return when (mode) {
            Karin3DController.MODE_SBS_2D -> Karin3DController.INPUT_SBS
            Karin3DController.MODE_TAB_2D -> Karin3DController.INPUT_TAB
            Karin3DController.MODE_PULFRICH -> Karin3DController.INPUT_2D
            Karin3DController.MODE_ANAGLYPH ->
                if (stereoInput) Karin3DController.INPUT_SBS else Karin3DController.INPUT_2D
            else -> Karin3DController.INPUT_2D
        }
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return Karin3DShaderProgram(
            context, useHdr, mode, depth, swapEye, resolvedInput(), anaglyph, demoSplit,
        ).also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        mode == Karin3DController.MODE_OFF

    fun update(mode: Int, depth: Float, swapEye: Boolean, stereoInput: Boolean) {
        updateFull(mode, depth, swapEye, if (stereoInput) Karin3DController.INPUT_SBS else Karin3DController.INPUT_2D, anaglyph)
    }

    fun updateFull(mode: Int, depth: Float, swapEye: Boolean, inputKind: Int, anaglyph: Int) {
        this.mode = mode.coerceIn(0, Karin3DController.MODE_COUNT - 1)
        this.depth = depth.coerceIn(0f, 1f)
        this.swapEye = swapEye
        this.inputKind = inputKind.coerceIn(0, 2)
        this.stereoInput = this.inputKind != Karin3DController.INPUT_2D
        this.anaglyph = anaglyph.coerceIn(0, Karin3DController.ANAG_COUNT - 1)
        program?.updateFull(this.mode, this.depth, this.swapEye, this.inputKind, this.anaglyph)
    }

    fun updateDepth(newDepth: Float) {
        depth = newDepth.coerceIn(0f, 1f)
        program?.updateDepth(depth)
    }
}

class Karin3DShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var mode: Int,
    private var depth: Float,
    private var swapEye: Boolean,
    private var inputKind: Int,
    private var anaglyph: Int,
    private var demoSplit: Boolean = false,
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram
    private var srcW = 0f
    private var srcH = 0f

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
        super.release()
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        // El 3D no cambia la resolución del buffer: reformatea dentro del
        // mismo cuadro. Se guarda la resolución para taps de 1px
        // (Pulfrich) sobre la FUENTE, no el panel.
        srcW = inputWidth.toFloat()
        srcH = inputHeight.toFloat()
        try {
            glProgram.setFloatsUniform("uResolution", floatArrayOf(srcW, srcH))
        } catch (_: Exception) { }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setIntUniform("uMode", mode)
            // Paralaje real: depth 0..1 -> 0..3% del ancho (sutil, sin mareo).
            glProgram.setFloatUniform("uDepth", depth.coerceIn(0f, 1f) * 0.03f)
            glProgram.setIntUniform("uSwap", if (swapEye) 1 else 0)
            glProgram.setIntUniform("uInput", inputKind.coerceIn(0, 2))
            glProgram.setIntUniform("uAnaglyph", anaglyph.coerceIn(0, 2))
            try {
                glProgram.setFloatsUniform("uResolution", floatArrayOf(srcW, srcH))
            } catch (_: Exception) { }
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    fun update(mode: Int, depth: Float, swapEye: Boolean, stereoInput: Boolean) {
        updateFull(
            mode, depth, swapEye,
            if (stereoInput) Karin3DController.INPUT_SBS else Karin3DController.INPUT_2D, anaglyph,
        )
    }

    fun updateFull(mode: Int, depth: Float, swapEye: Boolean, inputKind: Int, anaglyph: Int) {
        this.mode = mode
        this.depth = depth.coerceIn(0f, 1f)
        this.swapEye = swapEye
        this.inputKind = inputKind.coerceIn(0, 2)
        this.anaglyph = anaglyph.coerceIn(0, 2)
    }

    fun updateDepth(newDepth: Float) { depth = newDepth.coerceIn(0f, 1f) }

    companion object {
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
                    // SBS (default, también si uInput==0 pero se pide estéreo).
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
                vec3 outc = texture2D(uTexSampler, uv).rgb;

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
                        float luma = dot(outc, vec3(0.299, 0.587, 0.114));
                        float shift = (luma - 0.5) * uDepth * 2.0;
                        vec3 cl = texture2D(uTexSampler, vec2(clamp(uv.x - shift, 0.0, 1.0), uv.y)).rgb;
                        vec3 cr = texture2D(uTexSampler, vec2(clamp(uv.x + shift, 0.0, 1.0), uv.y)).rgb;
                        if (uAnaglyph == 0) {
                            float r = dot(cl, vec3(0.299, 0.587, 0.114));
                            float g = dot(cr, vec3(0.587, 0.114, 0.299));
                            float b = dot(cr, vec3(0.114, 0.299, 0.587));
                            outc = mix(outc, vec3(r, g, b), clamp(uDepth * 25.0, 0.35, 1.0));
                        } else {
                            outc = anaglyphMix(cl, cr);
                        }
                    }
                } else if (uMode == 4) {
                    // 2D -> SBS para Cardboard: misma imagen en ambas mitades.
                    float half = step(0.5, uv.x);
                    outc = texture2D(uTexSampler, vec2(uv.x * 2.0 - half, uv.y)).rgb;
                } else if (uMode == 5) {
                    // PULFRICH (Fabulojos 1997): la profundidad la pone un
                    // lente OSCURO en un ojo (el ojo oscurecido procesa ~1
                    // cuadro más lento y el movimiento lateral se vuelve
                    // profundidad). Sin lentes se ve normal, como debe ser.
                    // La app solo refuerza bordes horizontales en movimiento
                    // (más señal para el efecto) preservando tono.
                    vec2 hpx = vec2(1.0 / max(uResolution.x, 1.0), 0.0);
                    vec3 lh = texture2D(uTexSampler, uv - hpx).rgb;
                    vec3 rh = texture2D(uTexSampler, uv + hpx).rgb;
                    vec3 KL = vec3(0.299, 0.587, 0.114);
                    float lc0 = dot(outc, KL);
                    float lh0 = dot(lh, KL);
                    float rh0 = dot(rh, KL);
                    float edge = clamp((abs(lc0 - lh0) + abs(lc0 - rh0)) * 3.0, 0.0, 1.0);
                    float amt = clamp(uDepth * 20.0, 0.0, 0.5) * edge;
                    float nl = clamp(lc0 + (lc0 - (lh0 + rh0) * 0.5) * amt, 0.0, 1.5);
                    outc = clamp(outc * (nl / max(lc0, 0.0001)), 0.0, 1.0);
                }

                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    outc = texture2D(uTexSampler, vTexCoord).rgb;
                }
                gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            }
        """
    }
}
