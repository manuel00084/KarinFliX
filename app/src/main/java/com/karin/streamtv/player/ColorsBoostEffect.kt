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
 * Colors Boost: colores más vívidos con barra de intensidad.
 *
 * Pipeline por píxel (adaptativo por contenido, sin historial):
 * 1) Mide lo apagado del píxel, cuida sombras/blancos y detecta piel.
 * 2) Saturación adaptativa: escenas apagadas reciben más, vívidas casi
 *    nada, piel al mínimo.
 * 3) Vibrance de remate con la misma respuesta adaptativa.
 *
 * Remate de color al final de la cadena (después de HDR/Cine), antes de
 * MotionX2. Barato: sin taps extra (1 fetch) ni loops. GLES2 compatible.
 */
class ColorsBoostEffect(
    private var strength: Float = 0.6f,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: ColorsBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ColorsBoostShaderProgram(context, useHdr, strength, demoSplit)
            .also { program = it }
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class ColorsBoostShaderProgram(
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

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
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
            #ifdef GL_ES
            precision highp float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform float uStrength; // 0..1
            uniform int uDemoSplit; // 1 = demo: mitad izquierda intacta

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            void main() {
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                if (uStrength <= 0.0) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }
                float l0 = luma(c);
                float mx0 = max(c.r, max(c.g, c.b));
                float mn0 = min(c.r, min(c.g, c.b));
                // Mascara de piel sobre el original (deteccion estable).
                float skin = smoothstep(0.02, 0.1, c.r - c.g) * smoothstep(0.01, 0.08, c.r - c.b);
                skin *= smoothstep(0.25, 0.45, c.r) * (1.0 - smoothstep(0.7, 0.85, c.r));
                skin = clamp(skin, 0.0, 1.0);
                // Adaptativo: lo apagado pide mas, lo vivido casi nada.
                float satDeficit = 1.0 - clamp((mx0 - mn0) * 2.0, 0.0, 1.0);
                // ...cuidando sombras (ruido) y blancos (clipping).
                float toneW = smoothstep(0.02, 0.18, l0) * (1.0 - smoothstep(0.75, 0.98, l0));
                float drive = clamp(satDeficit * (0.35 + 0.65 * toneW), 0.0, 1.0);
                drive *= 1.0 - skin * 0.85;
                // 1) Saturacion adaptativa.
                vec3 outc = mix(vec3(l0), c, 1.0 + uStrength * (0.25 + 1.0 * drive));
                // 2) Vibrance de remate, tambien adaptativa.
                float mx = max(outc.r, max(outc.g, outc.b));
                float mn = min(outc.r, min(outc.g, outc.b));
                float vib = 0.35 * uStrength * drive * (1.0 - clamp((mx - mn) * 1.5, 0.0, 1.0));
                outc = mix(vec3(luma(outc)), outc, 1.0 + vib);
                vec3 demoRgb = clamp(outc, 0.0, 1.0);
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { demoRgb = texture2D(uTexSampler, vTexCoord).rgb; }
                gl_FragColor = vec4(demoRgb, 1.0);
            }
        """
    }
}
