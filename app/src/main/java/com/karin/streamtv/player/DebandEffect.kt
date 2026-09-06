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

class DebandEffect(private val strength: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DebandShaderProgram(context, useHdr, strength)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f
}

class DebandShaderProgram(
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
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uStrength;
            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            void main() {
                vec2 tx = uTexelSize;
                vec3 c  = texture2D(uTexSampler, vTexCoord).rgb;
                vec3 n  = texture2D(uTexSampler, vTexCoord + vec2( 0.0, -tx.y)).rgb;
                vec3 s  = texture2D(uTexSampler, vTexCoord + vec2( 0.0,  tx.y)).rgb;
                vec3 w  = texture2D(uTexSampler, vTexCoord + vec2(-tx.x,  0.0)).rgb;
                vec3 e  = texture2D(uTexSampler, vTexCoord + vec2( tx.x,  0.0)).rgb;
                vec3 nw = texture2D(uTexSampler, vTexCoord + vec2(-tx.x, -tx.y)).rgb;
                vec3 ne = texture2D(uTexSampler, vTexCoord + vec2( tx.x, -tx.y)).rgb;
                vec3 sw = texture2D(uTexSampler, vTexCoord + vec2(-tx.x,  tx.y)).rgb;
                vec3 se = texture2D(uTexSampler, vTexCoord + vec2( tx.x,  tx.y)).rgb;
                float lC = luma(c);
                float lN = luma(n);  float lS = luma(s);
                float lW = luma(w);  float lE = luma(e);
                float lNW = luma(nw); float lNE = luma(ne);
                float lSW = luma(sw); float lSE = luma(se);
                float blur = (lN + lS + lW + lE + lNW + lNE + lSW + lSE) * 0.125;
                float diff = abs(lC - blur);
                float minV = min(min(min(lN, lS), min(lW, lE)), min(min(lNW, lNE), min(lSW, lSE)));
                float maxV = max(max(max(lN, lS), max(lW, lE)), max(max(lNW, lNE), max(lSW, lSE)));
                float localContrast = maxV - minV;
                float threshold = 0.01 + localContrast * 0.1;
                float bandingMask = 1.0 - smoothstep(0.0, threshold, diff);
                bandingMask *= uStrength;
                vec3 smoothColor = (n + s + w + e) * 0.25;
                vec3 result = mix(c, smoothColor, bandingMask);
                gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
            }
        """
    }
}
