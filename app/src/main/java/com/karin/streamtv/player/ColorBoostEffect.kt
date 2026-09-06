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

class ColorBoostEffect(
    private var saturation: Float = 0.3f,
    private var vibrance: Float = 0.2f,
    private var hueShift: Float = 0f,
    private var colorfulness: Float = 0.15f
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ColorBoostShaderProgram(context, useHdr, saturation, vibrance, hueShift, colorfulness)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = saturation <= 0f && vibrance <= 0f && colorfulness <= 0f

    fun updateSaturation(v: Float) { saturation = v.coerceIn(0f, 1f) }
    fun updateVibrance(v: Float) { vibrance = v.coerceIn(0f, 1f) }
    fun updateHueShift(v: Float) { hueShift = v.coerceIn(-0.5f, 0.5f) }
    fun updateColorfulness(v: Float) { colorfulness = v.coerceIn(0f, 1f) }
}

class ColorBoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var saturation: Float,
    private var vibrance: Float,
    private var hueShift: Float,
    private var colorfulness: Float
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
            glProgram.setFloatUniform("uSaturation", saturation)
            glProgram.setFloatUniform("uVibrance", vibrance)
            glProgram.setFloatUniform("uHueShift", hueShift)
            glProgram.setFloatUniform("uColorfulness", colorfulness)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    fun updateSaturation(v: Float) { saturation = v.coerceIn(0f, 1f) }
    fun updateVibrance(v: Float) { vibrance = v.coerceIn(0f, 1f) }
    fun updateHueShift(v: Float) { hueShift = v.coerceIn(-0.5f, 0.5f) }
    fun updateColorfulness(v: Float) { colorfulness = v.coerceIn(0f, 1f) }

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
            uniform float uSaturation;
            uniform float uVibrance;
            uniform float uHueShift;
            uniform float uColorfulness;

            const vec3 LUM_COEFF = vec3(0.2126, 0.7152, 0.0722);

            float luma(vec3 c) { return dot(c, LUM_COEFF); }

            // HSV conversion
            vec3 rgb2hsv(vec3 c) {
                vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
                vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
                vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
                float d = q.x - min(q.w, q.y);
                float e = 1.0e-10;
                return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
            }

            vec3 hsv2rgb(vec3 c) {
                vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
                vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }

            // Vibrance: saturates only less-saturated pixels (protects skin tones)
            vec3 applyVibrance(vec3 rgb, float amount) {
                float maxC = max(rgb.r, max(rgb.g, rgb.b));
                float minC = min(rgb.r, min(rgb.g, rgb.b));
                float sat = maxC - minC;
                // Vibrance affects low-saturation areas more
                float vibranceFactor = 1.0 - sat;
                vec3 gray = vec3(luma(rgb));
                return mix(rgb, gray, -amount * vibranceFactor * 0.5);
            }

            // Colorfulness: boosts saturation proportionally to luminance (perceptual)
            vec3 applyColorfulness(vec3 rgb, float amount) {
                float l = luma(rgb);
                vec3 hsv = rgb2hsv(rgb);
                // Boost saturation more in midtones, less in shadows/highlights
                float lumaMask = 4.0 * l * (1.0 - l); // Peaks at 0.5
                hsv.y = min(hsv.y + amount * lumaMask * 0.5, 1.0);
                return hsv2rgb(hsv);
            }

            // Hue shift
            vec3 applyHueShift(vec3 rgb, float shift) {
                if (shift == 0.0) return rgb;
                vec3 hsv = rgb2hsv(rgb);
                hsv.x = fract(hsv.x + shift + 1.0);
                return hsv2rgb(hsv);
            }

            void main() {
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                float l = luma(c);

                // Early exit for pure blacks/whites
                if (l < 0.005 || l > 0.995) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }

                vec3 result = c;

                // 1. Global saturation (uniform boost)
                if (uSaturation > 0.0) {
                    vec3 gray = vec3(l);
                    result = mix(result, gray, -uSaturation);
                }

                // 2. Vibrance (smart saturation - protects skin tones)
                if (uVibrance > 0.0) {
                    result = applyVibrance(result, uVibrance);
                }

                // 3. Colorfulness (perceptual saturation boost)
                if (uColorfulness > 0.0) {
                    result = applyColorfulness(result, uColorfulness);
                }

                // 4. Hue shift (subtle color grading)
                if (uHueShift != 0.0) {
                    result = applyHueShift(result, uHueShift * 0.1);
                }

                // Clamp and output
                gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
            }
        """
    }
}