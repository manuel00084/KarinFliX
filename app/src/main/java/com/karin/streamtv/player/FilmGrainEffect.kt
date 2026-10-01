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
 * SHADER Film Grain: textura cinematográfica con grano animado (1 fetch).
 *
 * Ruido hash por píxel que avanza a pasos de 24 fps (no tiembla a lo loco),
 * con máscara de luma: carga en medios tonos y se apaga en negro/blanco
 * puro para no ensuciar extremos. Acabado final tras MotionX2.
 * GLES2 compatible.
 */
class FilmGrainEffect(
    private var strength: Float = 0.7f,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: FilmGrainShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return FilmGrainShaderProgram(context, useHdr, strength, demoSplit)
            .also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class FilmGrainShaderProgram(
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
            "uPxScale",
            floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()),
        )
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uStrength", strength)
            glProgram.setFloatUniform("uTime", presentationTimeUs / 1000000f)
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
            uniform vec2 uPxScale;
            uniform float uStrength;
            uniform float uTime;
            uniform int uDemoSplit;

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            }

            void main() {
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                if (uStrength <= 0.001) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }
                float lC = dot(c, vec3(0.2126, 0.7152, 0.0722));
                // Grano anclado al píxel (vTexCoord * resolución, sin
                // gl_FragCoord) que avanza a 24 pasos/s (estable).
                float t = floor(uTime * 24.0);
                vec2 cell = vTexCoord * uPxScale + vec2(t * 17.0, t * 29.0);
                float n = hash(cell) - 0.5;
                // Máscara: lleno en medios, apagado en extremos.
                float mask = smoothstep(0.0, 0.15, lC) * (1.0 - smoothstep(0.85, 1.0, lC));
                float g = n * uStrength * 0.12 * (0.35 + 0.65 * mask);
                vec3 outc = c + vec3(g);
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    outc = c;
                }
                gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            }
        """
    }
}
