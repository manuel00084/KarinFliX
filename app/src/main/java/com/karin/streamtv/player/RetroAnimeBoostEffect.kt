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
 * SHADER RETRO ANIME: look de anime de los 80s/90s en un solo pase (3 fetches).
 *
 * Recrea el aire de cel impreso en fílmico de esa época, no una tecnología
 * concreta: negros subidos, blancos con hombro suave, paleta levemente
 * desaturada con tinte cálido en luces y frío en sombras, halación del cel,
 * suavizado por fotocopia y grano fino teñido. Es un acabado estético puro,
 * pensado para ir AL FINAL de la cadena (tras MotionX2). GLES2 compatible.
 */
class RetroAnimeBoostEffect(
    private var strength: Float = 0.5f,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: RetroAnimeBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return RetroAnimeBoostShaderProgram(context, useHdr, strength, demoSplit)
            .also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class RetroAnimeBoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var strength: Float,
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

    override fun release() {
        try {
            glProgram.delete()
        } catch (_: Exception) {
        }
        super.release()
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        glProgram.setFloatsUniform("uResolution", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
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
            uniform vec2 uResolution;
            uniform float uStrength;
            uniform float uSeed;
            uniform int uDemoSplit;

            void main() {
                if (uStrength <= 0.001) {
                    gl_FragColor = vec4(texture2D(uTexSampler, vTexCoord).rgb, 1.0);
                    return;
                }
                float s = uStrength;
                // Deriva vertical sutil del fílmico (gate weave sub-píxel).
                vec2 tc = vTexCoord + vec2(0.0, sin(uSeed * 1.3) * 0.0012 * s);

                // 3 fetches: centro + un píxel a cada lado (halación y suave).
                vec3 c = texture2D(uTexSampler, tc).rgb;
                vec2 hpx = vec2(1.4 / uResolution.x, 0.0);
                vec3 cl = texture2D(uTexSampler, tc - hpx).rgb;
                vec3 cr = texture2D(uTexSampler, tc + hpx).rgb;

                // HALACION del cel: la luz sangra al vecino mas oscuro. Solo suma
                // donde el vecino reluce mas, asi no enturbia las lineas negras.
                vec3 hal = max(max(cl, cr) - c, vec3(0.0));
                // Suavizado por fotocopia: un pelin de blando.
                vec3 soft = c * 0.50 + (cl + cr) * 0.25;

                float l = dot(c, vec3(0.2126, 0.7152, 0.0722));

                // 1) Desaturacion del filmico de la epoca, sin llegar a sepia.
                float sat = 1.0 - (0.18 + 0.22 * s);
                c = mix(vec3(l), c, sat);

                // 2) Negros subidos + blancos con hombro suave (cel impreso).
                c = c * (1.0 - 0.05 * s) + vec3(0.028 * s);
                c = c / (1.0 + 0.55 * s * c);

                // 3) Tinte calido en luces, frio en sombras (negativo de cine).
                c += vec3(0.06, 0.02, -0.05) * (l * s);
                c -= vec3(0.02, 0.01, -0.03) * ((1.0 - l) * s);

                // 4) Halacion + suavizado.
                c += hal * (0.20 * s);
                c = mix(c, soft, 0.30 * s);

                // 5) Grano fino teñido (como pintura de cel, no ruido VHS).
                float h = fract(sin(dot(gl_FragCoord.xy + vec2(uSeed * 37.7, uSeed * 13.1),
                    vec2(12.9898, 78.233))) * 43758.5453);
                c += (h - 0.5) * (0.045 * s);
                c.g += (h - 0.5) * (0.012 * s);

                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    c = texture2D(uTexSampler, vTexCoord).rgb;
                }
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """
    }
}