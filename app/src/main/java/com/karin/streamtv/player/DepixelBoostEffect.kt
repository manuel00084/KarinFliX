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
 * Depixel Boost: repara el pixelado/bloques de videos con bajo bitrate.
 *
 * Técnica (inspirada en el shader deblocking de Basis Universal para GPUs
 * móviles + el filtro in-loop de H.264):
 * - Solo actúa cerca de bordes de bloque 8x8 (H.264/H.265/VP9); el resto
 *   de píxeles sale intacto con 1 fetch.
 * - Cerca del borde aplica un pasa-bajos de 3 taps por eje (5 fetchs total).
 * - Puerta por escalón (estilo alpha H.264): solo suaviza escalones
 *   pequeños (artefacto de bloque); los bordes reales no se tocan.
 * - Todo en luma preservando tono para no sangrar color.
 *
 * Costo: 1 fetch en interior de bloque, 5 fetchs cerca de bordes, sin
 * sqrt/divisiones/exp en el camino común. GLES2 compatible.
 */
class DepixelBoostEffect(
    private var strength: Float,
    private var lowPower: Boolean = false,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: DepixelBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DepixelBoostShaderProgram(context, useHdr, strength, lowPower, demoSplit)
            .also { program = it }
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class DepixelBoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var strength: Float,
    private var lowPower: Boolean,
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

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            glProgram.setFloatUniform("uStrength", strength)
            glProgram.setIntUniform("uLowPower", if (lowPower) 1 else 0)
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
            uniform vec2 uTexelSize;
            uniform float uStrength;
            uniform int uLowPower;
            uniform int uDemoSplit; // 1 = demo: mitad izquierda intacta

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            vec3 setLumaPreservingHue(vec3 rgb, float targetLuma) {
                float currentLuma = luma(rgb);
                if (currentLuma < 0.001) return vec3(targetLuma);
                return rgb * (targetLuma / currentLuma);
            }

            // Bayer 4x4 sin seno (estable en el tiempo, barato en Mali).
            float bayer2(vec2 a) {
                a = floor(a);
                return fract(a.x * 0.5 + a.y * a.y * 0.75);
            }
            float bayer4(vec2 a) { return bayer2(a * 0.5) * 0.25 + bayer2(a); }

            void main() {
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                if (uStrength <= 0.0) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }

                vec3 l1 = texture2D(uTexSampler, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb;
                vec3 r1 = texture2D(uTexSampler, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
                vec3 u1 = texture2D(uTexSampler, vTexCoord - vec2(0.0, uTexelSize.y)).rgb;
                vec3 d1 = texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;

                float lc = luma(c);
                float lL = luma(l1); float lR = luma(r1);
                float lU = luma(u1); float lD = luma(d1);

                vec3 blurRGB = (l1 + r1 + u1 + d1) * 0.25;
                float blurL = luma(blurRGB);
                float var2 = (lL * lL + lR * lR + lU * lU + lD * lD) * 0.25 - blurL * blurL;
                float minC = min(min(lL, lR), min(min(lU, lD), lc));
                float maxC = max(max(lL, lR), max(max(lU, lD), lc));
                float range = maxC - minC;

                vec2 texelPos = vTexCoord / uTexelSize;

                vec3 outc = c;

                // 1) Grano y ruido en zonas planas: mezcla hacia la media local.
                float flatM = (1.0 - smoothstep(0.0003, 0.0025, var2)) * (1.0 - smoothstep(0.02, 0.07, range));
                if (flatM > 0.0) {
                    outc = setLumaPreservingHue(c, mix(lc, blurL, flatM * uStrength * 0.85));
                }

                // 2) Bordes de bloque 8x8 (H.264/H.265/VP9).
                vec2 blockPos = mod(texelPos, vec2(8.0));
                float falloff = (uLowPower == 1) ? 1.0 : 2.0;
                float leftProx = 1.0 - clamp(blockPos.x / falloff, 0.0, 1.0);
                float rightProx = 1.0 - clamp((8.0 - blockPos.x) / falloff, 0.0, 1.0);
                float topProx = 1.0 - clamp(blockPos.y / falloff, 0.0, 1.0);
                float bottomProx = 1.0 - clamp((8.0 - blockPos.y) / falloff, 0.0, 1.0);
                float horizW = max(leftProx, rightProx);
                float vertW = max(topProx, bottomProx);
                float edgeW = max(horizW, vertW);
                if (edgeW > 0.0) {
                    // Escalón chico = bloque; grande = borde real... salvo que ambos
                    // lados sean planos (beta H.264): pixelado fuerte.
                    float stepH = abs(lL - lR) * 0.5;
                    float stepV = abs(lU - lD) * 0.5;
                    float alpha1 = (uLowPower == 1) ? 0.06 : 0.09;
                    float gateH = 1.0 - smoothstep(0.015, alpha1, stepH);
                    float gateV = 1.0 - smoothstep(0.015, alpha1, stepV);
                    float betaH = (1.0 - smoothstep(0.02, 0.07, abs(lL - lc))) * (1.0 - smoothstep(0.02, 0.07, abs(lR - lc)));
                    float betaV = (1.0 - smoothstep(0.02, 0.07, abs(lU - lc))) * (1.0 - smoothstep(0.02, 0.07, abs(lD - lc)));
                    gateH = max(gateH, betaH);
                    gateV = max(gateV, betaV);

                    vec3 filtH = (l1 + c + r1) * 0.3333333;
                    vec3 filtV = (u1 + c + d1) * 0.3333333;
                    float wH = horizW * gateH;
                    float wV = vertW * gateV;
                    float totalW = wH + wV;
                    if (totalW > 0.0) {
                        vec3 blended = (filtH * wH + filtV * wV) / totalW;
                        float baseL = luma(outc);
                        float newLuma = mix(baseL, luma(blended), clamp(totalW, 0.0, 1.0) * uStrength);
                        outc = setLumaPreservingHue(outc, newLuma);
                    }
                }

                // 3) Croma guiado por luma (0 fetchs extra: reutiliza los 4 taps).
                // Suaviza manchas de color sin cruzar bordes reales de luma.
                {
                    float wL = clamp(1.0 - abs(lc - lL) * 8.0, 0.0, 1.0);
                    float wR = clamp(1.0 - abs(lc - lR) * 8.0, 0.0, 1.0);
                    float wU = clamp(1.0 - abs(lc - lU) * 8.0, 0.0, 1.0);
                    float wD = clamp(1.0 - abs(lc - lD) * 8.0, 0.0, 1.0);
                    float wsum = wL + wR + wU + wD;
                    float cgate = (1.0 - smoothstep(0.03, 0.12, range)) * uStrength;
                    float lOut = luma(outc);
                    vec3 chromaAvg = ((l1 - vec3(lL)) * wL + (r1 - vec3(lR)) * wR + (u1 - vec3(lU)) * wU + (d1 - vec3(lD)) * wD) / max(wsum, 0.0001);
                    vec3 chromaOut = mix(outc - vec3(lOut), chromaAvg, clamp(cgate, 0.0, 1.0));
                    outc = vec3(lOut) + chromaOut;
                }
                // Dithering anti-banding: 1 LSB en toda la imagen (sin fetchs extra).
                outc += (bayer4(texelPos) - 0.5) * (1.5 / 255.0) * uStrength;
                vec3 demoRgb = outc;
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { demoRgb = texture2D(uTexSampler, vTexCoord).rgb; }
                gl_FragColor = vec4(demoRgb, 1.0);
            }
        """
    }
}
