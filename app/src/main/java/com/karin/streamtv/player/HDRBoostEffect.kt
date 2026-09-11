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
 * HDR Boost: look HDR para videos SDR (y realce extra si ya es HDR).
 *
 * Flujo correcto (como en cine/video profesional): se trabaja en luz
 * lineal y la gamma se aplica al final. Aplicar el tonemap sobre gamma
 * aplasta medios y sobresatura: por eso se veía mal.
 *
 * Pipeline por píxel (escuela BT.2390 como MPV/MadVR):
 * 1) sRGB -> lineal.
 * 2) Exposición moderada en lineal.
 * 3) Rodilla spline Hermite: debajo pasa intacto (sin quiebre de
 *    pendiente), arriba comprime suave hacia el pico. Normalizado para
 *    que el blanco máximo siempre llegue a 1.0, sin clip duro.
 * 4) Lineal -> sRGB.
 * 5) Contraste con pivote preservando tono: separa luces y sombras.
 * 6) Desaturación fílmica cerca del blanco + saturación y vibrance que
 *    respetan piel.
 *
 * Va al final de la cadena de color (después de Cinemática). Barato:
 * sin taps extra (1 fetch) ni loops. GLES2 compatible.
 */
class HDRBoostEffect(
    private var strength: Float = 0.6f,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: HDRBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return HDRBoostShaderProgram(context, useHdr, strength, demoSplit)
            .also { program = it }
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = strength <= 0f

    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f)
        program?.updateStrength(newStrength)
    }
}

class HDRBoostShaderProgram(
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
            vec3 toLinear(vec3 c) { return pow(clamp(c, 0.0, 1.0), vec3(2.2)); }
            vec3 toSRGB(vec3 c) { return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2)); }

            // Rodilla spline estilo BT.2390: 1:1 debajo de la rodilla
            // (misma pendiente, sin quiebre visible), compresion suave
            // hacia el pico arriba. Todo por canal en luz lineal.
            vec3 splineKnee(vec3 x, float knee, float peak) {
                float span = max(1.0 - knee, 0.0001);
                vec3 t = clamp((x - knee) / span, 0.0, 1.0);
                vec3 t2 = t * t;
                vec3 t3 = t2 * t;
                vec3 h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
                vec3 h10 = t3 - 2.0 * t2 + t;
                vec3 h01 = -2.0 * t3 + 3.0 * t2;
                vec3 y = h00 * knee + h10 * span + h01 * peak;
                // Cola mas alla de 1.0: pendiente casi plana, sin clip duro.
                y = mix(y, peak + (x - 1.0) * 0.05, step(1.0, x));
                // Debajo de la rodilla: pasa intacto.
                y = mix(x, y, step(knee, x));
                return y / peak;
            }

            void main() {
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                if (uStrength <= 0.0) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }
                float s = uStrength;
                // 1+2) A lineal + exposicion moderada.
                vec3 lin = toLinear(c) * (1.0 + s * 0.5);
                // 3) Rodilla spline en lineal + de vuelta a sRGB.
                float knee = mix(0.45, 0.65, s);
                float peak = 1.0 + 0.8 * s;
                vec3 outc = toSRGB(splineKnee(lin, knee, peak));
                // 4) Contraste con pivote, preservando tono: profundidad, no solo brillo.
                float l1 = max(luma(outc), 0.001);
                float piv = 0.42;
                float cl = clamp((l1 - piv) * (1.0 + 0.45 * uStrength) + piv, 0.0, 1.0);
                outc = outc * (cl / l1);
                vec3 graded = mix(c, clamp(outc, 0.0, 1.0), clamp(uStrength * 1.2, 0.0, 1.0));
                // 5) Blancos filmicos: desatura cerca del clip, sin parche duro.
                float lum = luma(graded);
                float hl = smoothstep(0.6, 0.95, lum) * uStrength;
                graded = mix(graded, vec3(lum), hl * 0.4);
                // 6) Saturacion leve + vibrance que respeta piel y saturados.
                vec3 gray0 = vec3(lum);
                graded = mix(gray0, graded, 1.0 + 0.12 * uStrength);
                float mx = max(graded.r, max(graded.g, graded.b));
                float mn = min(graded.r, min(graded.g, graded.b));
                float vib = 0.3 * uStrength * (1.0 - clamp((mx - mn) * 1.5, 0.0, 1.0));
                vec3 gray = vec3(luma(graded));
                graded = mix(gray, graded, 1.0 + vib);
                vec3 demoRgb = clamp(graded, 0.0, 1.0);
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { demoRgb = texture2D(uTexSampler, vTexCoord).rgb; }
                gl_FragColor = vec4(demoRgb, 1.0);
            }
        """
    }
}
