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

class LowBitrateBoostEffect(private var strength: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return LowBitrateBoostShaderProgram(context, useHdr, strength)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) { strength = newStrength.coerceIn(0f, 1f) }
}

class LowBitrateBoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var strength: Float,
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
            glProgram.setFloatUniform("uTime", (presentationTimeUs % 1000000) / 1000000f)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    // Para sliders en tiempo real sin recompilar
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
            uniform float uStrength;
            uniform float uTime;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            // Fast noise - single permute
            float hash(vec2 p) {
                p = fract(p * vec2(127.1, 311.7));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y * 93.73);
            }

            void main() {
                vec2 tx = uTexelSize;
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                float lc = luma(c);

                // ================================================================
                // SAMPLING: 5 fetches (center + 4 cross) - reuse for everything
                // ================================================================
                vec3 n1 = texture2D(uTexSampler, vTexCoord + vec2( 0.0, -tx.y)).rgb;
                vec3 s1 = texture2D(uTexSampler, vTexCoord + vec2( 0.0,  tx.y)).rgb;
                vec3 w1 = texture2D(uTexSampler, vTexCoord + vec2(-tx.x,  0.0)).rgb;
                vec3 e1 = texture2D(uTexSampler, vTexCoord + vec2( tx.x,  0.0)).rgb;

                float ln = luma(n1); float ls = luma(s1);
                float lw = luma(w1); float le = luma(e1);

                // ================================================================
                // 1. EDGE DETECTION + FLAT REGION (combined)
                // ================================================================
                float edgeH = abs(le - lw);
                float edgeV = abs(ln - ls);
                float edgeMax = max(edgeH, edgeV);
                float edgeMask = smoothstep(0.02, 0.15, edgeMax);

                // Flat detection from cross neighbors only (4 fetches, no diagonals needed)
                float minN = min(min(ln, ls), min(lw, le));
                float maxN = max(max(ln, ls), max(lw, le));
                float localRange = maxN - minN;
                float flatness = 1.0 - smoothstep(0.0, 0.08, localRange);
                float flatMask = flatness * (1.0 - edgeMask * 0.4);

                // ================================================================
                // 2. DEBANDING: Simple bilateral-like (cross only, directional weights)
                // ================================================================
                // Weights favor directions with less gradient
                float wH = 1.0 / (edgeH * 30.0 + 0.5);
                float wV = 1.0 / (edgeV * 30.0 + 0.5);
                float wTotal = wH + wV;

                vec3 smoothH = (w1 + c + e1) * (1.0 / 3.0);
                vec3 smoothV = (n1 + c + s1) * (1.0 / 3.0);
                vec3 debandDir = (smoothH * wH + smoothV * wV) / wTotal;

                // Chroma-preserving: only smooth luminance
                float debandLuma = luma(debandDir);
                vec3 debanded = vec3(debandLuma) + (c - vec3(lc));

                // Blend
                float blendFactor = flatMask * uStrength;
                vec3 result = mix(c, debanded, blendFactor);

                // ================================================================
                // 3. HIGH-PASS DETAIL RECOVERY (2-radius cross, 4 extra fetches = 9 total)
                // ================================================================
                vec3 n2 = texture2D(uTexSampler, vTexCoord + vec2( 0.0, -tx.y * 2.0)).rgb;
                vec3 s2 = texture2D(uTexSampler, vTexCoord + vec2( 0.0,  tx.y * 2.0)).rgb;
                vec3 w2 = texture2D(uTexSampler, vTexCoord + vec2(-tx.x * 2.0,  0.0)).rgb;
                vec3 e2 = texture2D(uTexSampler, vTexCoord + vec2( tx.x * 2.0,  0.0)).rgb;

                vec3 lowFreq = (n2 + s2 + w2 + e2) * 0.25;
                vec3 hiFreq = c - lowFreq;
                float hiLen = length(hiFreq);
                float detailMask = smoothstep(0.005, 0.03, hiLen) * flatMask;
                result += hiFreq * uStrength * 1.5 * detailMask;

                // ================================================================
                // 4. ANTI-RINGING (clamp to 1-radius cross range)
                // ================================================================
                vec3 loCross = min(min(n1, s1), min(w1, e1));
                vec3 hiCross = max(max(n1, s1), max(w1, e1));
                vec3 clamped = clamp(result, loCross, hiCross);
                float overshoot = length(result - clamped);
                result = mix(result, clamped, smoothstep(0.005, 0.04, overshoot) * 0.5);

                // ================================================================
                // 5. TEMPORAL DITHERING (breaks banding in flat regions)
                // ================================================================
                vec2 noiseCoord = vTexCoord / uTexelSize + vec2(uTime * 137.0, uTime * 79.0);
                float d1 = hash(noiseCoord);
                float d2 = hash(noiseCoord + vec2(53.0, 29.0));
                float dither = (d1 + d2 - 1.0) * 0.5;
                result += dither * (1.5 / 255.0) * flatMask * uStrength;

                gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
            }
        """
    }
}