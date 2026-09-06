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

class DetailBoostEffect(private var strength: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DetailBoostShaderProgram(context, useHdr, strength)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) { strength = newStrength.coerceIn(0f, 1f) }
}

class DetailBoostShaderProgram(
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
            glProgram.setFloatUniform("uDetailBoost", strength)
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

                // Early exit for flat/near-black/white
                if (lC < 0.005 || lC > 0.995) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }

                // ================================================================
                // SAMPLING: 8 fetches (cross 1-ring + diagonals)
                // ================================================================
                vec3 n = texture2D(uTexSampler, vTexCoord + vec2( 0.0, -tx.y)).rgb;
                vec3 s = texture2D(uTexSampler, vTexCoord + vec2( 0.0,  tx.y)).rgb;
                vec3 w = texture2D(uTexSampler, vTexCoord + vec2(-tx.x,  0.0)).rgb;
                vec3 e = texture2D(uTexSampler, vTexCoord + vec2( tx.x,  0.0)).rgb;
                vec3 nw = texture2D(uTexSampler, vTexCoord + vec2(-tx.x, -tx.y)).rgb;
                vec3 ne = texture2D(uTexSampler, vTexCoord + vec2( tx.x, -tx.y)).rgb;
                vec3 sw = texture2D(uTexSampler, vTexCoord + vec2(-tx.x,  tx.y)).rgb;
                vec3 se = texture2D(uTexSampler, vTexCoord + vec2( tx.x,  tx.y)).rgb;

                float lN = luma(n); float lS = luma(s);
                float lW = luma(w); float lE = luma(e);
                float lNW = luma(nw); float lNE = luma(ne);
                float lSW = luma(sw); float lSE = luma(se);

                // ================================================================
                // 1. CAS (Contrast Adaptive Sharpening) - AMD FSR style
                // ================================================================
                vec3 min3x3 = min(min(min(nw, ne), min(sw, se)), min(min(n, s), min(w, e)));
                vec3 max3x3 = max(max(max(nw, ne), max(sw, se)), max(max(n, s), max(w, e)));
                min3x3 = min(min3x3, c);
                max3x3 = max(max3x3, c);

                vec3 casMin = min(min3x3, 2.0 - max3x3);
                vec3 casAmp = casMin / max(max3x3, 0.001);
                float casSharp = sqrt(min(casAmp.r, min(casAmp.g, casAmp.b)));

                float casPeak = 3.0 * uDetailBoost - 8.0;
                casPeak = min(casPeak, -0.01);
                float casW = casSharp / casPeak;

                vec3 casDiff = (n + s + w + e) * 0.25 - c;
                vec3 casResult = c + casDiff * casW;
                casResult = casResult / (4.0 * casW + 1.0);
                casResult = clamp(casResult, 0.0, 1.0);

                // ================================================================
                // 2. MULTI-SCALE DETAIL (luma only)
                // ================================================================
                float fineBlur = (lN + lS + lW + lE) * 0.25;
                float fineUnsharp = lC - fineBlur;

                float lap = 8.0 * lC - (lN + lS + lW + lE + lNW + lNE + lSW + lSE);

                // Combined detail (fine + laplacian edge)
                float detail = fineUnsharp * 3.0 + lap * 0.125;

                // ================================================================
                // 3. MASKS (combined into single expression)
                // ================================================================
                // Contrast mask: suppress in high-contrast areas
                float localContrast = max(max(lN, lS), max(lW, lE)) - min(min(lN, lS), min(lW, lE));
                float contrastMask = 1.0 - smoothstep(0.05, 0.4, localContrast);

                // Zone mask: suppress deep shadows and blown highlights
                float zoneMask = smoothstep(0.02, 0.15, lC) * (1.0 - smoothstep(0.85, 0.98, lC));

                // Skin mask: simple hue-based (r-g, r-b)
                float rmg = c.r - c.g;
                float rmb = c.r - c.b;
                float skinMask = smoothstep(0.02, 0.1, rmg) * smoothstep(0.01, 0.08, rmb);
                skinMask *= smoothstep(0.25, 0.45, c.r) * (1.0 - smoothstep(0.7, 0.85, c.r));
                skinMask *= smoothstep(0.15, 0.3, c.g) * (1.0 - smoothstep(0.6, 0.75, c.g));
                skinMask = clamp(skinMask, 0.0, 1.0);

                // Noise mask: variance in cross neighbors
                float variance = (lN*lN + lS*lS + lW*lW + lE*lE) * 0.25 - fineBlur*fineBlur;
                float noiseMask = 1.0 - smoothstep(0.002, 0.008, variance);

                // Combine all masks
                float totalMask = contrastMask * zoneMask * mix(1.0, 0.2, skinMask) * mix(1.0, 0.15, noiseMask);
                detail *= totalMask * uDetailBoost;

                // Clamp to valid luminance range
                detail = clamp(detail, -lC, 1.0 - lC);
                float newLuma = clamp(lC + detail, 0.0, 1.0);

                // ================================================================
                // 4. RECONSTRUCT WITH HUE PRESERVATION
                // ================================================================
                vec3 afterDetail = setLumaPreservingHue(c, newLuma);

                // ================================================================
                // 5. LIGHT RCAS (4-tap luma only) - lighter than full RCAS
                // ================================================================
                float rbL = luma(n); float rdL = luma(w);
                float rfL = luma(e); float rhL = luma(s);
                float reL = luma(afterDetail);

                float nz = 0.25 * (rbL + rdL + rfL + rhL) - reL;
                float maxRL = max(max(rbL, rdL), max(rfL, rhL));
                float minRL = min(min(rbL, rdL), min(rfL, rhL));
                nz = clamp(abs(nz) / max(maxRL - minRL, 0.0001), 0.0, 1.0);
                nz = -0.5 * nz + 1.0;

                float lobe = max(-0.25, 0.0);  // Simplified: no hit-test, just noise guard
                lobe *= nz * uDetailBoost * 0.5;

                float rcpL = 1.0 / (4.0 * lobe + 1.0);
                vec3 sharp = vec3(
                    (lobe * n.r + lobe * w.r + lobe * s.r + lobe * e.r + afterDetail.r) * rcpL,
                    (lobe * n.g + lobe * w.g + lobe * s.g + lobe * e.g + afterDetail.g) * rcpL,
                    (lobe * n.b + lobe * w.b + lobe * s.b + lobe * e.b + afterDetail.b) * rcpL
                );

                // Chroma from afterDetail (preserves hue)
                vec3 rcasResult = setLumaPreservingHue(sharp, luma(afterDetail));

                // ================================================================
                // 6. FINAL BLEND: CAS (structure) + RCAS (texture)
                // ================================================================
                vec3 combined = mix(casResult, rcasResult, 0.6);
                vec3 result = mix(c, combined, uDetailBoost);

                gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
            }
        """
    }
}