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

/**
 * SHADER Anime Line Enhancement: realza líneas para anime en un solo pase.
 *
 * Gradiente de luma con 4 taps: donde hay borde fuerte oscurece la línea
 * hacia su propio tono (sin aplastar a negro) y en plano aplica un micro-
 * contraste que limpia el cel. Acabado final tras MotionX2. GLES2 compatible.
 */
class AnimeLineBoostEffect(
    private var strength: Float = 0.7f,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: AnimeLineBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return AnimeLineBoostShaderProgram(context, useHdr, strength, demoSplit)
            .also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class AnimeLineBoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var strength: Float,
    private var demoSplit: Boolean = false,
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
        glProgram.setIntUniform("uDemoSplit", if (demoSplit) 1 else 0)
    }

    override fun release() {
        try {
            glProgram.delete()
        } catch (_: Exception) {
        }
        super.release()
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        glProgram.setFloatsUniform(
            "uTexelSize",
            floatArrayOf(1f / inputWidth, 1f / inputHeight),
        )
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
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
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uStrength;
            uniform int uDemoSplit;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            void main() {
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                if (uStrength <= 0.001) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }
                float lC = luma(c);
                float lL = luma(texture2D(uTexSampler, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb);
                float lR = luma(texture2D(uTexSampler, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb);
                float lU = luma(texture2D(uTexSampler, vTexCoord - vec2(0.0, uTexelSize.y)).rgb);
                float lD = luma(texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexelSize.y)).rgb);
                // Magnitud del borde sin length() y sin asignaciones
                // compuestas: el traductor GL del emulador se atraganta con
                // ellas (7001: 'illegal vector field selection').
                float gx = lR - lL;
                float gy = lD - lU;
                float edge = clamp((gx * gx + gy * gy) * 9.0, 0.0, 1.0);
                edge = edge * edge * (3.0 - 2.0 * edge);
                float eAmt = edge * uStrength;
                // Línea: oscurece hacia su propio tono (hasta 40%), no a negro.
                vec3 lined = c * vec3(1.0 - 0.40 * eAmt);
                // Plano: micro-contraste que limpia el cel sin tocar la línea.
                float avg = (lL + lR + lU + lD) * 0.25;
                float micro = (lC - avg) * 0.5 * uStrength * (1.0 - edge);
                vec3 outc = lined + vec3(micro);
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    outc = c;
                }
                gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            }
        """
    }
}
