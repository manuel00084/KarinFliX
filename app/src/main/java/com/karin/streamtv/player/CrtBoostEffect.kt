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
 * SHADER CRT: acabado estético estilo televisor retro en un solo pase.
 *
 * Curvatura de pantalla (barril) + scanlines + rejilla de apertura RGB +
 * viñeta, todo procedural (1 solo fetch de textura, sin LUTs). La intensidad
 * maestra escala los ingredientes a la vez; en 0 es early-out total.
 * Pensado para ir AL FINAL de la cadena (tras MotionX2), como acabado.
 * GLES2 compatible.
 *
 * Técnicas portadas (reimplementadas, sin copiar código):
 *  - Curvatura con compensación de área + peso de scanline gaussiano con
 *    multisample + bloom + gamma lineal: crt-pi de davej (GPL v2).
 *  - Máscara Trinitron por tríadas y compensación de exposición:
 *    crt-lottes-fast de Timothy Lottes (dominio público, UNLICENSE).
 * crt-guest-dr-venom y CRT Royale se evaluaron y descartaron: multipase
 * con LUTs, inviables en el presupuesto GL móvil.
 *
 * v3 suma tubo real: haz dinámico por luminancia, máscara con guarda de
 * negros, esquinas redondeadas y halación horizontal (3 fetches en total).
 * v4 corrige el "efecto lupa Fresnel": curvatura cilíndrica Trinitron
 * (solo horizontal y mínima), fuera esquinas, viñeta casi nula. El tubo
 * se lee como pantalla por scanlines + máscara + haz, no por deformar.
 */
class CrtBoostEffect(
    private var strength: Float = 0.5f,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: CrtBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CrtBoostShaderProgram(context, useHdr, strength, demoSplit)
            .also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class CrtBoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var strength: Float,
    private var demoSplit: Boolean = false,
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram
    private var inputWidth = 0
    private var inputHeight = 0

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
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        glProgram.setFloatsUniform("uResolution", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uStrength", strength)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    fun updateStrength(newStrength: Float) { strength = newStrength.coerceIn(0f, 1f) }

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
            #ifdef GL_ES
            precision highp float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uResolution;
            uniform float uStrength;
            uniform int uDemoSplit;

            // Curvatura barril con compensación de área (técnica crt-pi): la
            // distorsión encoge el área visible, barrelScale la devuelve.
            // Fuera de rango = vec2(-1): borde negro clásico del tubo.
            vec2 distort(vec2 uv, vec2 curv) {
                vec2 barrelScale = 1.0 - (0.23 * curv);
                vec2 coord = uv - 0.5;
                float rsq = dot(coord, coord);
                coord += coord * (curv * rsq);
                coord *= barrelScale;
                if (abs(coord.x) >= 0.5 || abs(coord.y) >= 0.5) return vec2(-1.0);
                return coord + 0.5;
            }

            // Perfil gaussiano de scanline (técnica crt-pi): 1 en el centro
            // de la línea, gap abajo. Más real que el seno puro.
            float scanW(float dist, float w, float gap) {
                return max(1.0 - dist * dist * w, gap);
            }

            void main() {
                if (uStrength <= 0.001) {
                    gl_FragColor = vec4(texture2D(uTexSampler, vTexCoord).rgb, 1.0);
                    return;
                }
                // 1) Curvatura CILINDRICA Trinitron: solo horizontal y mínima
                // (0.035). Vertical plano como el tubo real. Nada de lupa.
                vec2 tuv = distort(vTexCoord, vec2(0.035, 0.0) * uStrength);
                if (tuv.x < 0.0) {
                    vec3 o = vec3(0.0);
                    if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                        o = texture2D(uTexSampler, vTexCoord).rgb;
                    }
                    gl_FragColor = vec4(o, 1.0);
                    return;
                }

                // 2) Snap al centro de scanline + micro-offset vertical.
                vec2 pix = tuv * uResolution;
                float tempY = floor(pix.y) + 0.5;
                float dy = pix.y - tempY;
                float signY = (dy >= 0.0) ? 1.0 : -1.0;
                float dy2 = dy * dy;
                float yCoord = (tempY + dy2 * dy2 * 8.0 * signY / uResolution.y) / uResolution.y;
                vec3 c = texture2D(uTexSampler, vec2(tuv.x, yCoord)).rgb;

                // 3) A lineal: scanlines + bloom + halación ahí (los medios
                // en gamma se oscurecen mal; en lineal quedan como el tubo).
                vec3 clin = pow(c, vec3(2.2));
                float yl = dot(clin, vec3(0.2126, 0.7152, 0.0722));
                // HAZ DINAMICO: lo brillante ensancha el haz (gaps finos),
                // lo oscuro lo adelgaza. Así respira como un CRT de verdad.
                float w = mix(3.0, 8.0, uStrength);
                float gap = mix(0.45, 0.10, uStrength)
                    * (1.0 - 0.6 * clamp(yl * 0.9, 0.0, 1.0));
                // Multisample de pesos: promedia 3 offsets, solo ALU. Mata el moiré.
                float sw = scanW(dy, w, gap)
                    + scanW(dy - 0.3333, w, gap)
                    + scanW(dy + 0.3333, w, gap);
                sw *= 0.3333333;
                sw *= mix(1.0, 1.5, uStrength);
                clin *= sw;
                // HALACION horizontal: sangrado suave de las altas luces
                // (2 fetches vecinos). Solo suma donde el vecino brilla más.
                vec2 hpx = vec2(2.0 / uResolution.x, 0.0);
                vec3 hl = (pow(texture2D(uTexSampler, vec2(tuv.x, yCoord) + hpx).rgb, vec3(2.2))
                    + pow(texture2D(uTexSampler, vec2(tuv.x, yCoord) - hpx).rgb, vec3(2.2))) * 0.5;
                clin += max(hl * sw - clin, vec3(0.0)) * (0.22 * uStrength);
                // Compensación de exposición (idea Lottes): scan+máscara
                // oscurecen; se devuelve parte en lineal.
                clin *= mix(1.0, 1.25, uStrength);
                c = pow(max(clin, vec3(0.0)), vec3(1.0 / 2.2));

                // 4) Máscara Trinitron por tríadas (idea Lottes, coseno sin
                // ramas para Mali), sobre píxeles de SALIDA sin warp. Con
                // GUARDA DE NEGROS: el tubo real no tiñe el negro.
                float ph = fract(gl_FragCoord.x * 0.3333333) * 6.2831853;
                vec3 mask = 0.80 + 0.20 * cos(ph + vec3(0.0, 2.0944, 4.1888));
                float maskMix = uStrength * smoothstep(0.0, 0.06, yl);
                c *= mix(vec3(1.0), mask, maskMix);

                // 5) Viñeta mínima: el tubo apenas oscurece (la lupa de la
                // v3 venía de curva fuerte + esquinas + viñeta marcada).
                vec2 vd = vTexCoord - 0.5;
                float vig = smoothstep(1.10, 0.50, length(vd) * 1.20);
                c *= mix(1.0, vig, uStrength * 0.30);

                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    c = texture2D(uTexSampler, vTexCoord).rgb;
                }
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """
    }
}
