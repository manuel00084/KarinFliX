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

class BicubicSamplerEffect(private var demoSplit: Boolean = false) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return BicubicSamplerProgram(context, useHdr, demoSplit)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

class BicubicSamplerProgram(
    context: Context,
    useHdr: Boolean,
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
            uniform vec2 uTexelSize;
            uniform int uDemoSplit; // 1 = demo: mitad izquierda intacta
            float cubic(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return -0.5 * x3 + x2 - 0.5 * x;
            }
            float cubic2(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return 1.5 * x3 - 2.5 * x2 + 1.0;
            }
            float cubic3(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return -1.5 * x3 + 2.0 * x2 + 0.5 * x;
            }
            float cubic4(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return 0.5 * x3 - 0.5 * x2;
            }
            vec4 textureBicubic(sampler2D tex, vec2 uv) {
                vec2 texel = uTexelSize;
                vec2 pos = uv / texel - 0.5;
                vec2 f = fract(pos);
                vec2 base = floor(pos);
                vec4 cx = vec4(cubic(f.x), cubic2(f.x), cubic3(f.x), cubic4(f.x));
                vec4 cy = vec4(cubic(f.y), cubic2(f.y), cubic3(f.y), cubic4(f.y));
                vec4 c = cx.x * (cy.x * texture2D(tex, (base + vec2(-1.0, -1.0)) * texel) +
                                 cy.y * texture2D(tex, (base + vec2(-1.0,  0.0)) * texel) +
                                 cy.z * texture2D(tex, (base + vec2(-1.0,  1.0)) * texel) +
                                 cy.w * texture2D(tex, (base + vec2(-1.0,  2.0)) * texel));
                c += cx.y * (cy.x * texture2D(tex, (base + vec2(0.0, -1.0)) * texel) +
                             cy.y * texture2D(tex, (base + vec2(0.0,  0.0)) * texel) +
                             cy.z * texture2D(tex, (base + vec2(0.0,  1.0)) * texel) +
                             cy.w * texture2D(tex, (base + vec2(0.0,  2.0)) * texel));
                c += cx.z * (cy.x * texture2D(tex, (base + vec2(1.0, -1.0)) * texel) +
                             cy.y * texture2D(tex, (base + vec2(1.0,  0.0)) * texel) +
                             cy.z * texture2D(tex, (base + vec2(1.0,  1.0)) * texel) +
                             cy.w * texture2D(tex, (base + vec2(1.0,  2.0)) * texel));
                c += cx.w * (cy.x * texture2D(tex, (base + vec2(2.0, -1.0)) * texel) +
                             cy.y * texture2D(tex, (base + vec2(2.0,  0.0)) * texel) +
                             cy.z * texture2D(tex, (base + vec2(2.0,  1.0)) * texel) +
                             cy.w * texture2D(tex, (base + vec2(2.0,  2.0)) * texel));
                return c;
            }
            void main() {
                vec4 c = textureBicubic(uTexSampler, vTexCoord);
                vec2 texel = uTexelSize;
                vec2 base = (floor(vTexCoord / texel - 0.5) + 0.5) * texel;
                vec4 mn = min(min(texture2D(uTexSampler, base),
                                  texture2D(uTexSampler, base + vec2(texel.x, 0.0))),
                              min(texture2D(uTexSampler, base + vec2(0.0, texel.y)),
                                  texture2D(uTexSampler, base + texel)));
                vec4 mx = max(max(texture2D(uTexSampler, base),
                                  texture2D(uTexSampler, base + vec2(texel.x, 0.0))),
                              max(texture2D(uTexSampler, base + vec2(0.0, texel.y)),
                                  texture2D(uTexSampler, base + texel)));
                vec4 demoOut = clamp(c, mn, mx);
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { demoOut.rgb = texture2D(uTexSampler, vTexCoord).rgb; }
                gl_FragColor = demoOut;
            }
        """
    }
}
