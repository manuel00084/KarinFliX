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

class DemoEffect(
    private val detailStrength: Float,
    private val lightStrength: Float = 0f,
    private val lowBitrateStrength: Float = 0f,
    private val motionX2Strength: Float = 0f,
    private val motionX2Mode: Int = 1,
    private val motionX2Blend: Float = 0.5f,
    private val colorBoostSaturation: Float = 0f,
    private val colorBoostVibrance: Float = 0f,
    private val colorBoostHue: Float = 0f,
    private val colorBoostColorfulness: Float = 0f,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DemoShaderProgram(
            context, useHdr,
            detailStrength, lightStrength, lowBitrateStrength,
            motionX2Strength, motionX2Mode, motionX2Blend,
            colorBoostSaturation, colorBoostVibrance, colorBoostHue, colorBoostColorfulness
        )
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

class DemoShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val detailStrength: Float,
    private val lightStrength: Float,
    private val lowBitrateStrength: Float,
    private val motionX2Strength: Float,
    private val motionX2Mode: Int,
    private val motionX2Blend: Float,
    private val colorBoostSaturation: Float,
    private val colorBoostVibrance: Float,
    private val colorBoostHue: Float,
    private val colorBoostColorfulness: Float,
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
            glProgram.setFloatUniform("uDetailBoost", detailStrength)
            glProgram.setFloatUniform("uLightBoost", lightStrength)
            glProgram.setFloatUniform("uLowBitrateBoost", lowBitrateStrength)
            glProgram.setFloatUniform("uMotionX2Strength", motionX2Strength)
            glProgram.setIntUniform("uMotionX2Mode", motionX2Mode)
            glProgram.setFloatUniform("uMotionX2Blend", motionX2Blend)
            glProgram.setFloatUniform("uColorBoostSaturation", colorBoostSaturation)
            glProgram.setFloatUniform("uColorBoostVibrance", colorBoostVibrance)
            glProgram.setFloatUniform("uColorBoostHue", colorBoostHue)
            glProgram.setFloatUniform("uColorBoostColorfulness", colorBoostColorfulness)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = uTransformationMatrix * aFramePosition;
              vec4 tp = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
              vTexSamplingCoord = (uTexTransformationMatrix * tp).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #ifdef GL_ES
            precision highp float;
            #endif
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uDetailBoost;
            uniform float uLightBoost;
            uniform float uLowBitrateBoost;
            uniform float uMotionX2Strength;
            uniform int uMotionX2Mode;
            uniform float uMotionX2Blend;
            uniform float uColorBoostSaturation;
            uniform float uColorBoostVibrance;
            uniform float uColorBoostHue;
            uniform float uColorBoostColorfulness;
            float lumaOf(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            // Hue-preserving luminance adjustment
            vec3 setLumaPreservingHue(vec3 rgb, float targetLuma) {
                float currentLuma = lumaOf(rgb);
                if (currentLuma < 0.001) return vec3(targetLuma);
                return rgb * (targetLuma / currentLuma);
            }

            // Shadow lift with smooth rolloff
            float shadowLift(float x, float lift, float range) {
                float mask = smoothstep(0.0, range, x);
                return x + lift * (1.0 - mask) * mask;
            }

            // Highlight rolloff
            float highlightRolloff(float x, float threshold, float knee) {
                float d = x - threshold;
                if (d <= 0.0) return x;
                return threshold + knee * log(1.0 + d / knee);
            }

            // S-curve centered at 0.5
            float contrastCurve(float x, float contrast) {
                float s = (x - 0.5) * contrast + 0.5;
                return clamp(s, 0.0, 1.0);
            }

            // LightBoost - unified with LightBoostEffect
            vec3 lightBoost(vec3 c, float str) {
                if (str <= 0.0) return c;
                float cen = lumaOf(c);
                if (cen < 0.003 || cen > 0.997) return c;

                vec2 tx = uTexelSize;
                vec3 n  = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -tx.y)).rgb;
                vec3 s  = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0,  tx.y)).rgb;
                vec3 w  = texture2D(uTexSampler, vTexSamplingCoord + vec2(-tx.x, 0.0)).rgb;
                vec3 e  = texture2D(uTexSampler, vTexSamplingCoord + vec2( tx.x, 0.0)).rgb;

                vec3 blur4 = (n + s + w + e) * 0.25;
                float avg = lumaOf(blur4);
                float localContrast = (cen - avg) * 1.5;

                float detail = abs(cen - avg);
                float detailMask = smoothstep(0.01, 0.12, detail);
                detailMask *= smoothstep(1.0, 0.03, detail);

                float x = clamp(cen, 0.0, 1.0);
                float lifted = shadowLift(x, 0.10 * str, 0.3);
                float protectedHighlights = highlightRolloff(lifted, 0.75, 0.18);
                float contrasted = contrastCurve(protectedHighlights, 1.0 + str * 0.25);
                float toneDelta = contrasted - x;

                float shadowMask = smoothstep(0.0, 0.35, x);
                float highlightMask = smoothstep(0.7, 1.0, x);
                float midMask = 1.0 - shadowMask - highlightMask;

                float shadowBoost = shadowMask * 0.18 * str;
                float highlightProtect = -highlightMask * 0.10 * str;
                float midContrast = midMask * 0.08 * str;
                float localBoost = localContrast * 0.3 * str * detailMask;

                float newLum = clamp(x + toneDelta + shadowBoost + highlightProtect + midContrast + localBoost, 0.0, 1.0);

                vec3 rgbOut = setLumaPreservingHue(c, newLum);
                return mix(c, rgbOut, str);
            }

            // MotionX2: Frame interpolation for 2x FPS
            vec3 motionX2(vec3 c, vec2 uv, float str, int mode, float blend) {
                if (str <= 0.0) return c;

                vec2 tx = uTexelSize;
                vec3 prev = texture2D(uTexSampler, uv).rgb; // In demo, we use same frame as prev (limitation)

                vec3 result;

                if (mode == 0) {
                    // MODE 0: Simple blend
                    result = mix(c, prev, blend * str);
                }
                else if (mode == 1) {
                    // MODE 1: Edge-aware adaptive
                    float edgeH = abs(lumaOf(texture2D(uTexSampler, uv + vec2(tx.x, 0.0)).rgb) - lumaOf(texture2D(uTexSampler, uv + vec2(-tx.x, 0.0)).rgb));
                    float edgeV = abs(lumaOf(texture2D(uTexSampler, uv + vec2(0.0, tx.y)).rgb) - lumaOf(texture2D(uTexSampler, uv + vec2(0.0, -tx.y)).rgb));
                    float edge = max(edgeH, edgeV);
                    float edgeMask = smoothstep(0.02, 0.15, edge);
                    float adaptiveBlend = mix(blend, blend * 0.3, edgeMask);
                    result = mix(c, prev, adaptiveBlend * str);
                }
                else if (mode == 2) {
                    // MODE 2: Motion vector approximation (simplified)
                    // Sample in a small search pattern
                    vec3 bestMatch = prev;
                    float bestDiff = 1000.0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            vec2 offset = vec2(float(dx), float(dy)) * tx * 4.0;
                            vec3 sample = texture2D(uTexSampler, uv + offset).rgb;
                            float diff = length(c - sample);
                            if (diff < bestDiff) {
                                bestDiff = diff;
                                bestMatch = sample;
                            }
                        }
                    }
                    float confidence = 1.0 - smoothstep(0.0, 0.1, length(c - bestMatch));
                    float mcBlend = blend * confidence * str;
                    result = mix(c, bestMatch, mcBlend);
                    result = mix(result, mix(c, prev, blend * 0.5), 1.0 - confidence);
                }
                else {
                    // MODE 3: Frame duplication with blend
                    result = mix(c, prev, blend * 0.5 * str);
                }

                // Chroma preservation
                float currentLuma = lumaOf(c);
                float resultLuma = lumaOf(result);
                if (currentLuma > 0.001 && resultLuma > 0.001) {
                    vec3 chroma = c - vec3(currentLuma);
                    result = vec3(resultLuma) + chroma;
                }

                return result;
            }

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

            // Color Boost: Saturation, Vibrance, Hue, Colorfulness
            vec3 colorBoost(vec3 c, float sat, float vib, float hue, float col) {
                if (sat <= 0.0 && vib <= 0.0 && col <= 0.0 && hue == 0.0) return c;

                float l = lumaOf(c);
                vec3 result = c;

                // 1. Global saturation
                if (sat > 0.0) {
                    vec3 gray = vec3(l);
                    result = mix(result, gray, -sat);
                }

                // 2. Vibrance (smart saturation - protects skin tones)
                if (vib > 0.0) {
                    float maxC = max(result.r, max(result.g, result.b));
                    float minC = min(result.r, min(result.g, result.b));
                    float saturation = maxC - minC;
                    float vibranceFactor = 1.0 - saturation;
                    vec3 gray = vec3(l);
                    result = mix(result, gray, -vib * vibranceFactor * 0.5);
                }

                // 3. Colorfulness (perceptual saturation boost)
                if (col > 0.0) {
                    vec3 hsv = rgb2hsv(result);
                    float lumaMask = 4.0 * l * (1.0 - l);
                    hsv.y = min(hsv.y + col * lumaMask * 0.5, 1.0);
                    result = hsv2rgb(hsv);
                }

                // 4. Hue shift
                if (hue != 0.0) {
                    vec3 hsv = rgb2hsv(result);
                    hsv.x = fract(hsv.x + hue * 0.1 + 1.0);
                    result = hsv2rgb(hsv);
                }

                return clamp(result, 0.0, 1.0);
            }

            float mod289(float x) { return x - floor(x / 289.0) * 289.0; }
            float permute(float x) { return mod289((34.0 * x + 1.0) * x); }
            float ignNoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = permute(i.x + i.y * 57.0);
                float b = permute(a + 1.0);
                float cn = permute(i.x + (i.y + 1.0) * 57.0);
                float d = permute(cn + 1.0);
                return mix(mix(permute(a + f.x), permute(b + f.x), f.x),
                           mix(permute(cn + f.x), permute(d + f.x), f.x), f.y) / 289.0;
            }

            vec3 lowBitrateDeblock(vec2 uv, float str) {
                vec2 tx = uTexelSize;
                vec3 c = texture2D(uTexSampler, uv).rgb;
                float lc = lumaOf(c);
                vec3 n = texture2D(uTexSampler, uv + vec2(0.0, -tx.y)).rgb;
                vec3 s = texture2D(uTexSampler, uv + vec2(0.0,  tx.y)).rgb;
                vec3 w = texture2D(uTexSampler, uv + vec2(-tx.x, 0.0)).rgb;
                vec3 e = texture2D(uTexSampler, uv + vec2( tx.x, 0.0)).rgb;
                vec3 nw = texture2D(uTexSampler, uv + vec2(-tx.x, -tx.y)).rgb;
                vec3 ne = texture2D(uTexSampler, uv + vec2( tx.x, -tx.y)).rgb;
                vec3 sw = texture2D(uTexSampler, uv + vec2(-tx.x,  tx.y)).rgb;
                vec3 se = texture2D(uTexSampler, uv + vec2( tx.x,  tx.y)).rgb;
                float ln = lumaOf(n); float ls = lumaOf(s);
                float lw = lumaOf(w); float le = lumaOf(e);
                float edgeH = abs(le - lw);
                float edgeV = abs(ln - ls);
                float edgeD1 = abs(lumaOf(nw) - lumaOf(se));
                float edgeD2 = abs(lumaOf(ne) - lumaOf(sw));
                float edgeMax = max(max(edgeH, edgeV), max(edgeD1, edgeD2));
                float edgeMask = smoothstep(0.02, 0.15, edgeMax);
                float minN = min(min(ln, ls), min(lw, le));
                float maxN = max(max(ln, ls), max(lw, le));
                float localRange = maxN - minN;
                float flatness = 1.0 - smoothstep(0.0, 0.08, localRange);
                float flatMask = flatness * (1.0 - edgeMask * 0.4);
                float wH = 1.0 / (edgeH * 50.0 + 0.5);
                float wV = 1.0 / (edgeV * 50.0 + 0.5);
                float wD1 = 1.0 / (edgeD1 * 50.0 + 0.5);
                float wD2 = 1.0 / (edgeD2 * 50.0 + 0.5);
                float wTotal = wH + wV + wD1 + wD2;
                vec3 smoothH = (w + c + e) * 0.333;
                vec3 smoothV = (n + c + s) * 0.333;
                vec3 smoothD1 = (nw + c + se) * 0.333;
                vec3 smoothD2 = (ne + c + sw) * 0.333;
                vec3 debandDir = (smoothH * wH + smoothV * wV + smoothD1 * wD1 + smoothD2 * wD2) / wTotal;
                float debandLuma = lumaOf(debandDir);
                vec3 chromaOrig = c - vec3(lc);
                vec3 debanded = vec3(debandLuma) + chromaOrig;
                vec3 result = mix(c, debanded, flatMask * str);
                vec2 noiseCoord = uv / uTexelSize + vec2(0.0, 0.0);
                float d1 = ignNoise(noiseCoord);
                float d2 = ignNoise(noiseCoord + vec2(53.0, 29.0));
                float dither = (d1 + d2 - 1.0) * 0.5;
                result += dither * (1.5 / 255.0) * flatMask * str;
                return clamp(result, 0.0, 1.0);
            }

            void main() {
                vec2 uv = vTexSamplingCoord;
                if (uv.x < 0.5) {
                    gl_FragColor = texture2D(uTexSampler, uv);
                    return;
                }
                vec3 c  = texture2D(uTexSampler, uv).rgb;
                vec3 n  = texture2D(uTexSampler, uv + vec2(0.0, -uTexelSize.y)).rgb;
                vec3 s  = texture2D(uTexSampler, uv + vec2(0.0,  uTexelSize.y)).rgb;
                vec3 w  = texture2D(uTexSampler, uv + vec2(-uTexelSize.x, 0.0)).rgb;
                vec3 e  = texture2D(uTexSampler, uv + vec2( uTexelSize.x, 0.0)).rgb;
                vec3 nw = texture2D(uTexSampler, uv + vec2(-uTexelSize.x, -uTexelSize.y)).rgb;
                vec3 ne = texture2D(uTexSampler, uv + vec2( uTexelSize.x, -uTexelSize.y)).rgb;
                vec3 sw = texture2D(uTexSampler, uv + vec2(-uTexelSize.x,  uTexelSize.y)).rgb;
                vec3 se = texture2D(uTexSampler, uv + vec2( uTexelSize.x,  uTexelSize.y)).rgb;
                vec3 blur4 = (n + s + w + e) * 0.25;
                float unsharp = lumaOf(c) - lumaOf(blur4);
                float lap = dot(8.0 * c - (n + s + w + e + nw + ne + sw + se), vec3(0.2126, 0.7152, 0.0722));
                float detail = (unsharp * 4.0 + lap * 0.25) * uDetailBoost;
                float colorLuma = lumaOf(c);
                float newLuma = clamp(colorLuma + detail, 0.0, 1.0);
                vec3 chroma = c - vec3(colorLuma);
                vec3 outc = clamp(vec3(newLuma) + chroma, 0.0, 1.0);
                if (uLowBitrateBoost > 0.001) {
                    outc = lowBitrateDeblock(uv, uLowBitrateBoost);
                    vec3 detailC = texture2D(uTexSampler, uv).rgb;
                    vec3 n2 = texture2D(uTexSampler, uv + vec2(0.0, -uTexelSize.y * 2.0)).rgb;
                    vec3 s2 = texture2D(uTexSampler, uv + vec2(0.0,  uTexelSize.y * 2.0)).rgb;
                    vec3 w2 = texture2D(uTexSampler, uv + vec2(-uTexelSize.x * 2.0, 0.0)).rgb;
                    vec3 e2 = texture2D(uTexSampler, uv + vec2( uTexelSize.x * 2.0, 0.0)).rgb;
                    vec3 lowF = (n2 + s2 + w2 + e2) * 0.25;
                    vec3 hiF = detailC - lowF;
                    vec3 detailAdj = outc + hiF * uDetailBoost * 1.2;
                    outc = clamp(detailAdj, 0.0, 1.0);
                }
                if (uLightBoost > 0.001) {
                    outc = lightBoost(outc, uLightBoost);
                }
                // MotionX2: frame interpolation
                if (uMotionX2Strength > 0.001) {
                    outc = motionX2(outc, uv, uMotionX2Strength, uMotionX2Mode, uMotionX2Blend);
                }
                // Color Boost: saturation, vibrance, hue, colorfulness
                if (uColorBoostSaturation > 0.001 || uColorBoostVibrance > 0.001 || uColorBoostColorfulness > 0.001 || uColorBoostHue != 0.0) {
                    outc = colorBoost(outc, uColorBoostSaturation, uColorBoostVibrance, uColorBoostHue, uColorBoostColorfulness);
                }
                if (abs(uv.x - 0.5) < 0.0015) {
                    outc = vec3(1.0);
                }
                gl_FragColor = vec4(outc, 1.0);
            }
        """
    }
}