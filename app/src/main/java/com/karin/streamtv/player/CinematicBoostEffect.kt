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
 * Iluminación Cinemática (Enhanced Lighting): look remaster oscuro con
 * volumen, al estilo de juegos PS1 con DLSS / neural rendering.
 *
 * Pipeline por píxel (todo en luma preservando tono):
 * 1) Exposición: multiplica la imagen (look brillante de remaster).
 * 2) AO aproximado: oscurece donde el píxel está "hundido" respecto a su
 *    entorno (grietas y recovecos), como un SSAO de un solo pase.
 * 3) Gamma + curva S cinematográfica (medios con cuerpo, negros con peso).
 * 4) Vibrance (no saturación simple): realza más los colores apagados y
 *    respeta los ya intensos y la piel.
 * 5) Split-tone teal & orange: cálidos en luces, fríos en sombras.
 * 6) Viñeta sutil de objetivo de cine.
 * Sin sharpening: no afila (para eso están Detail/DOG/FSR).
 *
 * Costo: 5 fetchs + 1 pow. Sin loops, sin exp/sqrt/log. GLES2 compatible.
 * Pensado para gama media-alta; en gama baja se topa la intensidad.
 */
class CinematicBoostEffect(
    private var master: Float = 0.6f,
    private var ao: Float = 0.35f,
    private var sat: Float = 0.3f,
    private var expo: Float = 1.0f,
    private var demoSplit: Boolean = false,
    private var softCurve: Boolean = false,
) : GlEffect {

    private var program: CinematicBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CinematicBoostShaderProgram(context, useHdr, master, ao, sat, expo, demoSplit, softCurve)
            .also { program = it }
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = master <= 0f

    // Updates en vivo: llegan al programa en ejecución, sin recompilar.
    fun updateMaster(v: Float) { master = v.coerceIn(0f, 1f); program?.updateMaster(v) }
    fun updateAO(v: Float) { ao = v.coerceIn(0f, 1f); program?.updateAO(v) }
    fun updateSat(v: Float) { sat = v.coerceIn(0f, 1f); program?.updateSat(v) }
    fun updateExpo(v: Float) { expo = v.coerceIn(0.7f, 1.3f); program?.updateExpo(v) }
}

class CinematicBoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var master: Float,
    private var ao: Float,
    private var sat: Float,
    private var expo: Float,
    private var demoSplit: Boolean = false,
    private var softCurve: Boolean = false,
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
        glProgram.setFloatUniform("uSoftCurve", if (softCurve) 1f else 0f)
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
            glProgram.setFloatUniform("uMaster", master)
            glProgram.setFloatUniform("uAO", ao)
            glProgram.setFloatUniform("uSat", sat)
            glProgram.setFloatUniform("uExpo", expo)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    fun updateMaster(v: Float) { master = v.coerceIn(0f, 1f) }
    fun updateAO(v: Float) { ao = v.coerceIn(0f, 1f) }
    fun updateSat(v: Float) { sat = v.coerceIn(0f, 1f) }
    fun updateExpo(v: Float) { expo = v.coerceIn(0.7f, 1.3f) }

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
            uniform float uMaster; // intensidad general 0..1
            uniform float uAO;     // sombras / AO 0..1
            uniform float uSat;    // vibrance 0..1
            uniform float uExpo;   // exposición 0.7..1.3
            uniform int uDemoSplit; // 1 = demo: mitad izquierda intacta
            uniform float uSoftCurve; // 1 = curva suave para combinar con HDR

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            vec3 setLumaPreservingHue(vec3 rgb, float targetLuma) {
                float cl = max(luma(rgb), 0.001);
                return rgb * clamp(targetLuma / cl, 0.25, 2.5);
            }

            void main() {
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                if (uMaster <= 0.0) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }

                // 1) Exposición: base brillante del look remaster.
                vec3 w = c * uExpo;

                // 2) Vecindario en cruz (luma del entorno).
                vec2 tx = uTexelSize;
                float lN = luma(texture2D(uTexSampler, vTexCoord + vec2(0.0, -tx.y)).rgb);
                float lS = luma(texture2D(uTexSampler, vTexCoord + vec2(0.0,  tx.y)).rgb);
                float lW = luma(texture2D(uTexSampler, vTexCoord + vec2(-tx.x, 0.0)).rgb);
                float lE = luma(texture2D(uTexSampler, vTexCoord + vec2( tx.x, 0.0)).rgb);
                float lc = luma(w);
                float avgL = (lN + lS + lW + lE) * 0.25;

                // 3) AO aproximado: hunde grietas (centro más oscuro que entorno).
                float ao = clamp((avgL - lc) * uAO * uMaster * 2.0, 0.0, 0.4);
                float lumAO = lc * (1.0 - ao);

                // 4) Gamma + curva S cinematográfica (sin unsharp: no afila).
                // Con HDR prendido se suaviza: HDR ya expande el rango y Cine
                // solo aporta volumen + tonos + viñeta, sin re-oscurecer.
                float curveAmt = mix(1.0, 0.45, uSoftCurve);
                float g = pow(clamp(lumAO, 0.0, 1.0), mix(1.0, 1.07, uMaster * 0.7 * curveAmt));
                float s = (g - 0.5) * (1.0 + 0.12 * uMaster * curveAmt) + 0.5;
                float graded = clamp(s, 0.0, 1.0);

                // 5) Reconstrucción con tono + vibrance (respeta saturados y piel).
                vec3 base = setLumaPreservingHue(w, graded);
                float mx = max(base.r, max(base.g, base.b));
                float mn = min(base.r, min(base.g, base.b));
                float vib = uSat * uMaster * (1.0 - clamp((mx - mn) * 1.5, 0.0, 1.0));
                vec3 gray = vec3(luma(base));
                vec3 outc = mix(gray, base, 1.0 + vib);

                // 6) Split-tone cine: cálidos arriba, fríos abajo (firma teal & orange).
                float lum6 = luma(outc);
                float hw = smoothstep(0.45, 0.9, lum6) * uMaster;
                float sw = (1.0 - smoothstep(0.1, 0.55, lum6)) * uMaster;
                outc *= mix(vec3(1.0), vec3(1.04, 0.99, 0.93), hw * 0.6);
                outc *= mix(vec3(1.0), vec3(0.94, 0.98, 1.06), sw * 0.6);

                // 7) Viñeta sutil: cierra las esquinas como objetivo de cine.
                float vd = distance(vTexCoord, vec2(0.5, 0.5));
                outc *= mix(1.0, smoothstep(0.85, 0.3, vd), 0.28 * uMaster);

                vec3 demoRgb = clamp(outc, 0.0, 1.0);
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { demoRgb = texture2D(uTexSampler, vTexCoord).rgb; }
                gl_FragColor = vec4(demoRgb, 1.0);
            }
        """
    }
}
