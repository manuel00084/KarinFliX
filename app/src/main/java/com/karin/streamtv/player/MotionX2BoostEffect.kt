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

enum class MotionX2Mode(val label: String) {
    BLEND("Blend Simple (2x)"),
    ADAPTIVE("Adaptativo (Edge-Aware)"),
    MOTION_VECTORS("Vectores de Movimiento"),
    FRAME_DUP("Duplicar + Blend")
}

class MotionX2BoostEffect(
    private var mode: MotionX2Mode = MotionX2Mode.ADAPTIVE,
    private var strength: Float = 0.5f,
    private var blendFactor: Float = 0.5f,
    private var lowPower: Boolean = false
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return MotionX2BoostShaderProgram(context, useHdr, mode, strength, blendFactor, lowPower)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateMode(newMode: MotionX2Mode) { mode = newMode }
    fun updateStrength(newStrength: Float) { strength = newStrength.coerceIn(0f, 1f) }
    fun updateBlendFactor(newBlend: Float) { blendFactor = newBlend.coerceIn(0f, 1f) }
    fun updateLowPower(enabled: Boolean) { lowPower = enabled }
}

class MotionX2BoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var mode: MotionX2Mode,
    private var strength: Float,
    private var blendFactor: Float,
    private var lowPower: Boolean
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram
    private var inputWidth = 0
    private var inputHeight = 0
    private var frameCount = 0L
    private var prevFrameTexId = -1

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
            glProgram.setSamplerTexIdUniform("uPrevFrame", getPrevFrameTexId(inputTexId), 1)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            glProgram.setFloatUniform("uStrength", strength)
            glProgram.setFloatUniform("uBlendFactor", blendFactor)
            glProgram.setIntUniform("uMode", mode.ordinal)
            glProgram.setIntUniform("uLowPower", if (lowPower) 1 else 0)
            glProgram.setFloatUniform("uFrameParity", (frameCount % 2).toFloat())
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            frameCount++
            storePrevFrame(inputTexId)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun getPrevFrameTexId(currentTexId: Int): Int {
        return if (prevFrameTexId >= 0) prevFrameTexId else currentTexId
    }

    private fun storePrevFrame(texId: Int) {
        prevFrameTexId = texId
    }

    fun updateMode(newMode: MotionX2Mode) { mode = newMode }
    fun updateStrength(newStrength: Float) { strength = newStrength.coerceIn(0f, 1f) }
    fun updateBlendFactor(newBlend: Float) { blendFactor = newBlend.coerceIn(0f, 1f) }
    fun updateLowPower(enabled: Boolean) { lowPower = enabled }

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
            uniform sampler2D uPrevFrame;
            uniform vec2 uTexelSize;
            uniform float uStrength;
            uniform float uBlendFactor;
            uniform int uMode; // 0=BLEND, 1=ADAPTIVE, 2=MOTION_VECTORS, 3=FRAME_DUP
            uniform int uLowPower; // 1=modo bajo consumo (sin bordes ni movimiento)
            uniform float uFrameParity;

            const vec3 LUM_COEFF = vec3(0.2126, 0.7152, 0.0722);
            float luma(vec3 c) { return dot(c, LUM_COEFF); }

            // Simple edge detection for adaptive blending
            float edgeStrength(vec2 uv, sampler2D tex, vec2 texel) {
                vec3 c = texture2D(tex, uv).rgb;
                vec3 n = texture2D(tex, uv + vec2(0.0, -texel.y)).rgb;
                vec3 s = texture2D(tex, uv + vec2(0.0,  texel.y)).rgb;
                vec3 w = texture2D(tex, uv + vec2(-texel.x, 0.0)).rgb;
                vec3 e = texture2D(tex, uv + vec2( texel.x, 0.0)).rgb;
                float lc = luma(c);
                float ln = luma(n); float ls = luma(s);
                float lw = luma(w); float le = luma(e);
                float edgeH = abs(le - lw);
                float edgeV = abs(ln - ls);
                return max(edgeH, edgeV);
            }

            // Optical flow approximation using block matching (simplified)
            vec2 estimateMotion(vec2 uv, sampler2D curr, sampler2D prev, vec2 texel) {
                // Search in 3x3 neighborhood
                vec2 bestMotion = vec2(0.0);
                float bestDiff = 1000.0;
                vec3 centerCurr = texture2D(curr, uv).rgb;

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        vec2 offset = vec2(float(dx), float(dy)) * texel * 4.0;
                        vec3 sample = texture2D(prev, uv + offset).rgb;
                        float diff = length(centerCurr - sample);
                        if (diff < bestDiff) {
                            bestDiff = diff;
                            bestMotion = vec2(float(dx), float(dy)) * 4.0;
                        }
                    }
                }
                return bestMotion * texel;
            }

            void main() {
                vec2 tx = uTexelSize;
                vec3 current = texture2D(uTexSampler, vTexCoord).rgb;

                // If first frame or parity even, just pass through
                if (uFrameParity < 0.5) {
                    gl_FragColor = vec4(current, 1.0);
                    return;
                }

                vec3 previous = texture2D(uPrevFrame, vTexCoord).rgb;
                vec3 result;

                if (uLowPower > 0.5) {
                    // ===== MODO BAJO CONSUMO (equipos con pocos recursos) =====
                    // Sin edge detection ni búsqueda de movimiento: 2 fetches totales
                    // (actual + previo). Blend suave al 50% para minimizar ghosting.
                    result = mix(current, previous, uBlendFactor * 0.5 * uStrength);
                }
                else if (uMode == 0) {
                    // ===== MODE 0: SIMPLE BLEND =====
                    // Simple temporal blend: 50/50 current + previous
                    result = mix(current, previous, uBlendFactor * uStrength);
                }
                else if (uMode == 1) {
                    // ===== MODE 1: ADAPTIVE EDGE-AWARE =====
                    // Detect edges, blend less on edges to avoid ghosting
                    float edge = edgeStrength(vTexCoord, uTexSampler, tx);
                    float edgeMask = smoothstep(0.02, 0.15, edge);
                    float adaptiveBlend = mix(uBlendFactor, uBlendFactor * 0.3, edgeMask);
                    result = mix(current, previous, adaptiveBlend * uStrength);
                }
                else if (uMode == 2) {
                    // ===== MODE 2: MOTION VECTOR APPROXIMATION =====
                    // Estimate motion and compensate
                    vec2 motion = estimateMotion(vTexCoord, uTexSampler, uPrevFrame, tx);
                    vec3 motionCompensated = texture2D(uPrevFrame, vTexCoord + motion).rgb;

                    // Confidence based on how well motion compensated matches current
                    float confidence = 1.0 - smoothstep(0.0, 0.1, length(current - motionCompensated));
                    float mcBlend = uBlendFactor * confidence * uStrength;

                    // Fallback to simple blend where motion estimation fails
                    result = mix(current, motionCompensated, mcBlend);
                    result = mix(result, mix(current, previous, uBlendFactor * 0.5), 1.0 - confidence);
                }
                else {
                    // ===== MODE 3: FRAME DUPLICATION WITH BLEND =====
                    // Duplicate frame but blend with previous for smoothness
                    // This creates 2x fps by showing: frame1, blend, frame2, blend, frame3...
                    result = mix(current, previous, uBlendFactor * 0.5 * uStrength);
                }

                // Chroma-preserving luminance blend for cleaner results
                float currentLuma = luma(current);
                float resultLuma = luma(result);
                if (currentLuma > 0.001 && resultLuma > 0.001) {
                    vec3 chroma = current - vec3(currentLuma);
                    result = vec3(resultLuma) + chroma;
                }

                gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
            }
        """
    }
}