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
 * SHADER FILM: look de película en un solo pase (1 fetch).
 *
 * Deriva vertical animada (gate weave sub-píxel) + negros levemente
 * lavados (fade fílmico) + flicker sutil + grano fino animado. Distinto
 * del Cine (viñeta marcada): esto es movimiento y aire de celuloide.
 * Acabado final tras MotionX2. GLES2 compatible.
 */
class FilmBoostEffect(
    private var strength: Float = 0.5f,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: FilmBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return FilmBoostShaderProgram(context, useHdr, strength, demoSplit)
            .also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class FilmBoostShaderProgram(
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
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uStrength", strength)
            glProgram.setFloatUniform("uSeed", ((presentationTimeUs / 33333L) % 64L).toFloat())
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
            uniform float uStrength;
            uniform float uSeed;
            uniform int uDemoSplit;

            void main() {
                if (uStrength <= 0.001) {
                    gl_FragColor = vec4(texture2D(uTexSampler, vTexCoord).rgb, 1.0);
                    return;
                }
                // Gate weave: deriva vertical animada, sub-píxel.
                float weave = sin(uSeed * 1.7) * 0.0012 * uStrength;
                vec3 c = texture2D(uTexSampler, vTexCoord + vec2(0.0, weave)).rgb;
                // Fade fílmico: negros levemente lavados.
                c = c * (1.0 - 0.10 * uStrength) + vec3(0.035 * uStrength);
                // Flicker sutil del proyector.
                c *= 1.0 + 0.025 * uStrength * sin(uSeed * 2.3);
                // Grano fino animado.
                float h = fract(sin(dot(gl_FragCoord.xy + vec2(uSeed * 11.3, uSeed * 5.1),
                    vec2(12.9898, 78.233))) * 43758.5453);
                c += (h - 0.5) * (0.06 * uStrength);
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    c = texture2D(uTexSampler, vTexCoord).rgb;
                }
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """
    }
}
