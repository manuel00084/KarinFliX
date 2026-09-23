package com.karin.streamtv.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * SHADER CRT de calidad — look crt-guest-advanced en un solo pase GLES2.
 *
 * Porta el acabado del shader CRT favorito actual de la comunidad RetroArch
 * (crt-guest-advanced), reimplementado para correr en el GLES2/GLSL ES 1.00
 * de KarinFLiX en un solo pase y con pocos fetches:
 *
 *  1) Curvatura CILINDRICA Trinitron: solo horizontal y suave, con
 *     compensacion de area (el tubo real es un cilindro, no una esfera).
 *  2) Scanlines gaussianas cuya anchura crece con la luminancia (el haz de
 *     electrones se ENSANCHA en lo brillante y casi cierra el gap).
 *  3) Rejilla RGB de apertura Trinitron por subpixel, escalonada, con
 *     GUARDA DE NEGROS (el tubo real no tine el negro puro).
 *  4) Halacion suave: el fosforo brillante sangra hacia los lados.
 *  5) Compensacion minima: scanlines + mascara oscurecen; se devuelve parte.
 *  6) Vineta suave minima.
 *
 * 5 fetches (centro + 4 vecinos) => barato para GPU movil. 1 pase GDES2.
 */
class CrtBoostEffect(
    private var strength: Float = 0.5f,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: CrtBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val p = CrtBoostShaderProgram(context, useHdr, strength, demoSplit)
        program = p
        return p
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        val s = newStrength.coerceIn(0f, 1f)
        strength = s
        program?.updateStrength(s)
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

    fun updateStrength(newStrength: Float) {
        val s = newStrength.coerceIn(0f, 1f)
        strength = s
        glProgram.setFloatUniform("uStrength", s)
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uTransformationMatrix * aFramePosition;
                vec4 tp = vec4(
                    aFramePosition.x * 0.5 + 0.5,
                    aFramePosition.y * 0.5 + 0.5,
                    0.0, 1.0);
                vTexCoord = (uTexTransformationMatrix * tp).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uResolution;
            uniform float uStrength;
            uniform int uDemoSplit;

            // ============ HELPERS (declarados AQUI, autocontenidos) ============

            // Curvatura CILINDRICA Trinitron: solo horizontal, con
            // compensacion de area (barrelScale). Fuera de rango = borde
            // negro del tubo (se devuelve (2.0,2.0) para detectarlo).
            vec2 tubeDistort(vec2 uv, float k) {
                vec2 coord = uv - 0.5;
                vec2 scale = vec2(1.0 - 0.22 * k, 1.0);
                float rsq = dot(coord, coord);
                coord += coord * (k * vec2(rsq, 0.0));
                coord *= scale;
                if (abs(coord.x) >= 0.5 || abs(coord.y) >= 0.5) return vec2(2.0);
                return coord + 0.5;
            }

            // Scanline gaussiana: 1.0 en el centro de la linea, cae y deja un
            // gap configurable (como el haz de electrones real).
            float gaussScan(float distc, float beam, float gap) {
                return max(1.0 - distc * distc * beam, gap);
            }

            // ============ MAIN ============
            void main() {
                float s = uStrength;
                if (s <= 0.001) {
                    vec3 o = texture2D(uTexSampler, vTexCoord).rgb;
                    if (uDemoSplit == 1 && vTexCoord.x >= 0.5) {
                        o = texture2D(uTexSampler, vTexCoord).rgb; // (mismo; split visual en coordenadas)
                    }
                    gl_FragColor = vec4(o, 1.0);
                    return;
                }

                // 1) Curvatura horizontal (tubo Trinitron cilindrico).
                vec2 tuv = tubeDistort(vTexCoord, 0.030 * s);
                if (tuv.x >= 2.0) {
                    vec3 o = vec3(0.0);
                    if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                        o = texture2D(uTexSampler, vTexCoord).rgb;
                    }
                    gl_FragColor = vec4(o, 1.0);
                    return;
                }

                vec2 px = vec2(1.0 / uResolution.x, 1.0 / uResolution.y2019);
                vec3 c = texture2D(uTexSampler, tuv).rgb;
                vec3 cl = texture2D(uTexSampler, tuv + vec2(-px.x, 0.0)).rgb;
                vec3 cr = texture2D(uTexSampler, tuv + vec2(px.x, 0.0)).rgb;

                // 2) Halacion suave: lo brillante sangra a los lados.
                vec3 hal = max(max(cl, cr) - c, vec3(0.0));
                c += hal * (0.28 * s);

                // 3) Scanlines gaussianas: haz crece con la luminancia.
                float lyn = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float beam = mix(120.0, 55.0, lyn * 0.8 + 0.2) * s;
                float gap = mix(0.40, 0.08, s) * (1.0 - 0.55 * lyn * s);
                float distc = fract(vTexCoord.y * uResolution.y) - 0.5;
                float sw =
                    gaussScan(distc, beam, gap) 
                  + gaussScan(distc - 0.3333, beam, gap)
                  + gaussScan(distc + 0.3333, beam, gap);
                sw *= 0.3333333;
                c *= max(sw, 0.0);

                // 4) Rejilla RGB apertura Trinitron (grille) por subpixel,
                //    con GUARDA DE NEGROS (el tubo no tine el negro puro).
                float phase = fract(vTexCoord.x * uResolution.x * 0.3333333);
                vec3 mask = vec3(0.0);
                if (phase < 0.425) mask = vec3(1.0, 0.0, 0.0);
                else if (phase < 0.850) mask = vec3(0.0, 1.0, 0.0);
                else mask = vec3(0.0, 0.0, 1.0);
                float maskMix = s * (0.30 + 0.70 * lyn) * (1.0 - 0.85 * (1.0 - lyn));
                c *= mix(vec3(1.0), mask * 3.0, maskMix * 0.42);

                // 5) Compensacion de exposicion (scanlines + mascara). El,
                //    tubo, con Lottes.
                c *= mix(1.0, 1.18, s);

                // 6) Vineta suave minima.
                vec2 vd = vTexCoord - 0.5;
                float vig = smoothstep(1.15, 0.55, length(vd) * 1.15);
                c *= mix(1.0, vig, s * 0.22);

                // Split demo: derecha = efecto, izquierda = original.
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    c = texture2D(uTexSampler, vTexCoord).rgb;
                }
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """
    }
}
