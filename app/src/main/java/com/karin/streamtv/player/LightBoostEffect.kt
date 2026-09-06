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

class LightBoostEffect(
    private var strength: Float,
    private var warmth: Float = 0f
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return LightBoostShaderProgram(context, strength, warmth)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) { strength = newStrength.coerceIn(0f, 1f) }
    fun updateWarmth(newWarmth: Float) { warmth = newWarmth.coerceIn(-1f, 1f) }
}

class LightBoostShaderProgram(
    context: Context,
    private var strength: Float,
    private var warmth: Float
) : BaseGlShaderProgram(false, 1) {

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
        glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uStrength", strength)
            glProgram.setFloatUniform("uWarmth", warmth)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    fun updateStrength(newStrength: Float) { strength = newStrength }
    fun updateWarmth(newWarmth: Float) { warmth = newWarmth }

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
            uniform float uStrength;
            uniform float uWarmth;
            uniform vec2 uTexelSize;

            const vec3 LUM_COEFF = vec3(0.2126, 0.7152, 0.0722);

            float luma(vec3 c) { return dot(c, LUM_COEFF); }

            // 5-tap quasi-gaussian (cross + center) - better quality than 4-tap box, cheaper than 9-tap
            vec3 gaussianBlur5(sampler2D tex, vec2 uv, vec2 texel) {
                vec3 c = texture2D(tex, uv).rgb;
                vec3 n = texture2D(tex, uv + vec2(0.0, -texel.y)).rgb;
                vec3 s = texture2D(tex, uv + vec2(0.0,  texel.y)).rgb;
                vec3 w = texture2D(tex, uv + vec2(-texel.x, 0.0)).rgb;
                vec3 e = texture2D(tex, uv + vec2( texel.x, 0.0)).rgb;
                // Weights: center=0.4, 4-neighbors=0.15 each = 1.0
                return c * 0.4 + (n + s + w + e) * 0.15;
            }

            // Hue-preserving luminance adjustment
            vec3 setLumaPreservingHue(vec3 rgb, float targetLuma) {
                float currentLuma = luma(rgb);
                if (currentLuma < 0.001) return vec3(targetLuma);
                return rgb * (targetLuma / currentLuma);
            }

            // Warmth: subtle temperature shift in linear space
            vec3 applyWarmth(vec3 rgb, float amount) {
                if (amount == 0.0) return rgb;
                // Orange/blue shift preserving luminance
                vec3 warmTint = vec3(1.04, 1.0, 0.96);
                vec3 coolTint = vec3(0.96, 1.0, 1.04);
                return amount > 0.0 ? rgb * mix(vec3(1.0), warmTint, amount * 0.5)
                                    : rgb * mix(vec3(1.0), coolTint, -amount * 0.5);
            }

            // Highlight rolloff - keeps log for quality (only runs on highlights)
            float highlightRolloff(float x, float threshold, float knee) {
                float d = x - threshold;
                if (d <= 0.0) return x;
                return threshold + knee * log(1.0 + d / knee);
            }

            // Soft shadow lift - smooth parabola
            float shadowLift(float x, float lift, float range) {
                float mask = smoothstep(0.0, range, x);
                return x + lift * mask * (1.0 - mask);
            }

            // S-curve centered at 0.5
            float contrastCurve(float x, float contrast) {
                return clamp((x - 0.5) * contrast + 0.5, 0.0, 1.0);
            }

            void main() {
                vec2 tx = uTexelSize;
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;

                float cen = luma(c);

                // Early exit: pure blacks/whites (1 fetch only)
                if (cen < 0.003 || cen > 0.997) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }

                // 1. Local contrast via 5-tap quasi-gaussian (5 fetches)
                vec3 blur = gaussianBlur5(uTexSampler, vTexCoord, tx);
                float avg = luma(blur);
                float localContrast = (cen - avg) * 1.5;

                // 2. Detail mask - edge aware, noise suppressing
                float detail = abs(cen - avg);
                float detailMask = smoothstep(0.01, 0.12, detail) * (1.0 - smoothstep(0.03, 1.0, detail));

                // 3. Global tone mapping
                float x = cen;

                // Shadow lift (0-30% range)
                float lifted = shadowLift(x, 0.10 * uStrength, 0.3);

                // Highlight rolloff (above 75%) - log for perceptual accuracy
                float protectedHighlights = highlightRolloff(lifted, 0.75, 0.18);

                // Midtone contrast
                float contrasted = contrastCurve(protectedHighlights, 1.0 + uStrength * 0.25);

                float toneDelta = contrasted - x;

                // 4. Zone masks
                float shadowMask = smoothstep(0.0, 0.35, x);
                float highlightMask = smoothstep(0.7, 1.0, x);

                // 5. Combine luminance (additive, hue-safe)
                float newLum = x + toneDelta
                    + shadowMask * 0.18 * uStrength
                    - highlightMask * 0.10 * uStrength
                    + (1.0 - shadowMask - highlightMask) * 0.08 * uStrength
                    + localContrast * 0.3 * uStrength * detailMask;

                newLum = clamp(newLum, 0.0, 1.0);

                // 6. Reconstruct with hue preservation
                vec3 rgbOut = setLumaPreservingHue(c, newLum);

                // 7. Warmth (only if non-zero)
                if (uWarmth != 0.0) {
                    rgbOut = applyWarmth(rgbOut, uWarmth * uStrength * 0.4);
                }

                // 8. Final blend
                vec3 result = mix(c, rgbOut, uStrength);

                gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
            }
        """
    }
}