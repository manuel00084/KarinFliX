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

class DetailBoostEffect(
    private var strength: Float,
    private var lowPower: Boolean = false,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: DetailBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DetailBoostShaderProgram(context, useHdr, strength, lowPower, demoSplit)
            .also { program = it }
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class DetailBoostShaderProgram(
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
            glProgram.setFloatUniform("uDetailBoost", strength)
            glProgram.setIntUniform("uLowPower", if (lowPower) 1 else 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    fun updateStrength(newStrength: Float) { strength = newStrength }

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
            uniform float uDetailBoost;
            uniform int uLowPower; // 1 = ruta barata (5 fetchs), 0 = ruta completa
            uniform int uDemoSplit; // 1 = demo: mitad izquierda intacta

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            // Hue-preserving luminance adjustment
            vec3 setLumaPreservingHue(vec3 rgb, float targetLuma) {
                float currentLuma = luma(rgb);
                if (currentLuma < 0.001) return vec3(targetLuma);
                return rgb * (targetLuma / currentLuma);
            }

            void main() {
                vec2 tx = uTexelSize;
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                float lC = luma(c);

                // Early exit for flat/near-black/white/off
                if (lC < 0.005 || lC > 0.995 || uDetailBoost <= 0.0) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }

                // Base 5-tap (centro + cruz): suficiente para nitidez sin ruido.
                vec3 n = texture2D(uTexSampler, vTexCoord + vec2( 0.0, -tx.y)).rgb;
                vec3 s = texture2D(uTexSampler, vTexCoord + vec2( 0.0,  tx.y)).rgb;
                vec3 w = texture2D(uTexSampler, vTexCoord + vec2(-tx.x,  0.0)).rgb;
                vec3 e = texture2D(uTexSampler, vTexCoord + vec2( tx.x,  0.0)).rgb;

                float lN = luma(n); float lS = luma(s);
                float lW = luma(w); float lE = luma(e);

                float blurL = (lN + lS + lW + lE) * 0.25;

                // min/max del vecindario (gratis): el clamp final imposibilita halos.
                float minC = min(min(lN, lS), min(min(lW, lE), lC));
                float maxC = max(max(lN, lS), max(max(lW, lE), lC));

                // Puerta por varianza (gratis): 0 donde solo hay ruido o plano.
                float var2 = (lN * lN + lS * lS + lW * lW + lE * lE) * 0.25 - blurL * blurL;
                float gate = smoothstep(0.0006, 0.004, var2);

                // Adaptativo: mide lo nítido que ya está y responde al revés.
                float range = maxC - minC;
                float crisp = smoothstep(0.015, 0.09, range);
                float adapt = mix(1.25, 0.55, crisp);

                // Zona: protege sombras profundas y brillos quemados.
                float zone = smoothstep(0.02, 0.15, lC) * (1.0 - smoothstep(0.85, 0.98, lC));

                // Direccional (preciso): afila CRUZANDO el borde, no a lo largo.
                float gx = abs(lE - lW);
                float gy = abs(lN - lS);
                float dirW = gy / max(gx + gy, 0.0001);
                float dv = lC - (lN + lS) * 0.5;
                float dh = lC - (lW + lE) * 0.5;
                float dirDetail = dv * dirW + dh * (1.0 - dirW);

                // Piel en ambas rutas: no marcar poros.
                float rmg = c.r - c.g;
                float rmb = c.r - c.b;
                float skin = smoothstep(0.02, 0.1, rmg) * smoothstep(0.01, 0.08, rmb);
                skin *= smoothstep(0.25, 0.45, c.r) * (1.0 - smoothstep(0.7, 0.85, c.r));
                skin *= smoothstep(0.15, 0.3, c.g) * (1.0 - smoothstep(0.6, 0.75, c.g));
                skin = clamp(skin, 0.0, 1.0);

                float detail;
                if (uLowPower == 1) {
                    // Ruta barata: 5 fetchs, sin sqrt ni divisiones.
                    detail = dirDetail * adapt * mix(1.0, 0.3, skin);
                } else {
                    // Ruta completa: + diagonales para laplaciano y clamp robusto.
                    vec3 nw = texture2D(uTexSampler, vTexCoord + vec2(-tx.x, -tx.y)).rgb;
                    vec3 ne = texture2D(uTexSampler, vTexCoord + vec2( tx.x, -tx.y)).rgb;
                    vec3 sw = texture2D(uTexSampler, vTexCoord + vec2(-tx.x,  tx.y)).rgb;
                    vec3 se = texture2D(uTexSampler, vTexCoord + vec2( tx.x,  tx.y)).rgb;
                    float lNW = luma(nw); float lNE = luma(ne);
                    float lSW = luma(sw); float lSE = luma(se);

                    minC = min(minC, min(min(lNW, lNE), min(lSW, lSE)));
                    maxC = max(maxC, max(max(lNW, lNE), max(lSW, lSE)));

                    float lap = 8.0 * lC - (lN + lS + lW + lE + lNW + lNE + lSW + lSE);

                    detail = (dirDetail * 1.5 + lap * 0.06) * adapt * mix(1.0, 0.25, skin);
                }

                // Intensidad UNA vez; el clamp a [minC,maxC] elimina halos/ringing.
                float newLuma = clamp(lC + detail * uDetailBoost * gate * zone, minC, maxC);

                vec3 demoRgb = setLumaPreservingHue(c, newLuma);
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { demoRgb = texture2D(uTexSampler, vTexCoord).rgb; }
                gl_FragColor = vec4(demoRgb, 1.0);
            }
        """
    }
}