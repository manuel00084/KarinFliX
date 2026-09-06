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

class FSRRcasEffect(private val sharpness: Float = 0.5f) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return FSRRcasProgram(context, useHdr, sharpness)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = sharpness <= 0f
}

class FSRRcasProgram(
    context: Context,
    useHdr: Boolean,
    private var sharpness: Float,
) : BaseGlShaderProgram(useHdr, 1) {
    private val glProgram: GlProgram

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

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uSharpness", sharpness)
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
            uniform float uSharpness;
            void main() {
                vec2 sp = vTexCoord;
                vec2 texel = 1.0 / vec2(textureSize(uTexSampler, 0));
                vec3 b = texture2D(uTexSampler, sp + vec2(0.0, -texel.y)).rgb;
                vec3 d = texture2D(uTexSampler, sp + vec2(-texel.x, 0.0)).rgb;
                vec3 e = texture2D(uTexSampler, sp).rgb;
                vec3 f = texture2D(uTexSampler, sp + vec2(texel.x, 0.0)).rgb;
                vec3 h = texture2D(uTexSampler, sp + vec2(0.0, texel.y)).rgb;
                vec3 lumW = vec3(0.2126, 0.7152, 0.0722);
                float bL = dot(b, lumW);
                float dL = dot(d, lumW);
                float eL = dot(e, lumW);
                float fL = dot(f, lumW);
                float hL = dot(h, lumW);
                float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;
                float maxL = max(max(bL, dL), max(fL, hL));
                float minL = min(min(bL, dL), min(fL, hL));
                nz = clamp(abs(nz) / max(maxL - minL, 0.0001), 0.0, 1.0);
                nz = -0.5 * nz + 1.0;
                float mn4R = min(min(b.r, d.r), min(f.r, h.r));
                float mn4G = min(min(b.g, d.g), min(f.g, h.g));
                float mn4B = min(min(b.b, d.b), min(f.b, h.b));
                float mx4R = max(max(b.r, d.r), max(f.r, h.r));
                float mx4G = max(max(b.g, d.g), max(f.g, h.g));
                float mx4B = max(max(b.b, d.b), max(f.b, h.b));
                float hitMinR = min(mn4R, e.r) / (4.0 * mx4R + 0.0001);
                float hitMinG = min(mn4G, e.g) / (4.0 * mx4G + 0.0001);
                float hitMinB = min(mn4B, e.b) / (4.0 * mx4B + 0.0001);
                float hitMaxR = (1.0 - max(mx4R, e.r)) / (4.0 * mn4R - 4.0 + 0.0001);
                float hitMaxG = (1.0 - max(mx4G, e.g)) / (4.0 * mn4G - 4.0 + 0.0001);
                float hitMaxB = (1.0 - max(mx4B, e.b)) / (4.0 * mn4B - 4.0 + 0.0001);
                float lobe = max(-0.25, min(max(max(max(-hitMinR, hitMaxR), max(-hitMinG, hitMaxG)), max(-hitMinB, hitMaxB)), 0.0)) * uSharpness;
                lobe *= nz;
                float rcpL = 1.0 / (4.0 * lobe + 1.0);
                vec3 sharp = vec3(
                    (lobe * b.r + lobe * d.r + lobe * h.r + lobe * f.r + e.r) * rcpL,
                    (lobe * b.g + lobe * d.g + lobe * h.g + lobe * f.g + e.g) * rcpL,
                    (lobe * b.b + lobe * d.b + lobe * h.b + lobe * f.b + e.b) * rcpL
                );
                float lumaS = dot(sharp, lumW);
                vec3 grayS = vec3(lumaS);
                vec3 chromaS = sharp - grayS;
                float lumaE = dot(e, lumW);
                vec3 grayE = vec3(lumaE);
                vec3 chromaE = e - grayE;
                vec3 refined = grayS + chromaE * (length(chromaS) / max(length(chromaE), 1e-5) * 0.5 + 0.5);
                gl_FragColor = vec4(mix(sharp, refined, 0.4), 1.0);
            }
        """
    }
}
