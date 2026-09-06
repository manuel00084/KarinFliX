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

class DogSharpenEffect(private var strength: Float = 1.0f) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DogSharpenProgram(context, useHdr, strength)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) { strength = newStrength.coerceIn(0f, 1f) }
}

class DogSharpenProgram(
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
            glProgram.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(1f / inputWidth, 1f / inputHeight),
            )
            glProgram.setFloatUniform("uStrength", strength)
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
            void main() {
                vec3 lumW = vec3(0.2126, 0.7152, 0.0722);
                vec2 uv = vTexCoord;
                vec3 c = texture2D(uTexSampler, uv).rgb;
                vec3 c1 = texture2D(uTexSampler, uv + vec2(-uTexelSize.x, 0.0)).rgb;
                vec3 c2 = texture2D(uTexSampler, uv + vec2( uTexelSize.x, 0.0)).rgb;
                vec3 c3 = texture2D(uTexSampler, uv + vec2(0.0, -uTexelSize.y)).rgb;
                vec3 c4 = texture2D(uTexSampler, uv + vec2(0.0,  uTexelSize.y)).rgb;
                vec3 c5 = texture2D(uTexSampler, uv + vec2(-uTexelSize.x, -uTexelSize.y)).rgb;
                vec3 c6 = texture2D(uTexSampler, uv + vec2( uTexelSize.x, -uTexelSize.y)).rgb;
                vec3 c7 = texture2D(uTexSampler, uv + vec2(-uTexelSize.x,  uTexelSize.y)).rgb;
                vec3 c8 = texture2D(uTexSampler, uv + vec2( uTexelSize.x,  uTexelSize.y)).rgb;
                vec3 gauss = (c1 + c2 + c3 + c4) * 0.2 + (c5 + c6 + c7 + c8) * 0.05 + c * 0.2;
                float lumaOrig = dot(c, lumW);
                float lumaGauss = dot(gauss, lumW);
                float diff = lumaOrig - lumaGauss;
                float lumaNew = clamp(lumaOrig + diff * uStrength, 0.0, 1.0);
                vec3 chroma = c - vec3(lumaOrig);
                gl_FragColor = vec4(clamp(vec3(lumaNew) + chroma, 0.0, 1.0), 1.0);
            }
        """
    }
}
