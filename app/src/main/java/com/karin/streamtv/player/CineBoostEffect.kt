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
 * SHADER CINE: look cinematográfico en un solo pase (1 fetch).
 *
 * Viñeta suave + grano de película monocromo ANIMADO (la semilla avanza
 * con el tiempo de presentación: cada cuadro tiene grano distinto, sin
 * parpadeo de patrón fijo). Pensado como acabado final tras MotionX2.
 * GLES2 compatible.
 */
class CineBoostEffect(
    private var strength: Float = 0.5f,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: CineBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CineBoostShaderProgram(context, useHdr, strength, demoSplit)
            .also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class CineBoostShaderProgram(
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
            // Semilla animada (~30 pasos/seg): grano distinto por cuadro.
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
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                if (uStrength <= 0.001) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }
                // Viñeta suave de cine.
                vec2 d = vTexCoord - 0.5;
                float vig = smoothstep(0.98, 0.32, length(d) * 1.30);
                c *= mix(1.0, vig, uStrength * 0.55);
                // Grano monocromo animado (igual en los 3 canales = film).
                float h = fract(sin(dot(gl_FragCoord.xy + vec2(uSeed * 13.7, uSeed * 7.3),
                    vec2(12.9898, 78.233))) * 43758.5453);
                c += (h - 0.5) * (0.10 * uStrength);
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    c = texture2D(uTexSampler, vTexCoord).rgb;
                }
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """
    }
}
