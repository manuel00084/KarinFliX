package com.karin.streamtv.enhancer.gpu

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.karin.streamtv.enhancer.parameters.KarinLightBoostParameters

/**
 * LIGHT BOOST v2 — HDR falso bien calibrado en un solo pase GL.
 *
 * Reescritura desde cero. Lecciones de la v1 aplicadas al diseño:
 *  - Sin pow() en sombras: lift cuadrático anclado (Y=0 queda en 0, sin
 *    velo gris, más barato en Mali/Adreno).
 *  - Reempaque conservador 0.75..1.6 (la v1 llegaba a 2.2 y revelaba el
 *    ruido del negro).
 *  - UN SOLO cálculo de color (base + extra combinados, un pivote, un
 *    clamp) con gate único sobre el luma ORIGINAL (anti-morado).
 *  - Sin tinte cálido: la v1 lo metía atado al boost y discutía con la
 *    saturación.
 *  - uWhiteBoost por fin cableado (en la v1 era parámetro muerto):
 *    aire suave en altas luces antes del hombro.
 *
 * Etapas por píxel (espacio gamma SDR 0..1):
 *  LUZ: lift anclado -> punto negro -> aire de blancos + hombro ->
 *       clarity local -> gamma -> reempaque.
 *  COLOR: saturación + vibrance + extra en una sola mezcla, piel
 *       direccional (R-G/G-B), gate de sombras. Dither Bayer único.
 *  AUTO: medidor de escena con sesgo a oscuridad (min-bias) + rodilla
 *  suave: escenas normales no mueven el boost (sin bombeo).
 *
 * Coste: 9 taps + 1 pow (solo gamma) por píxel. Color en 0 y luz en 0
 *  -> early-out total.
 */
class KarinLightBoostEffect(
    private var params: KarinLightBoostParameters,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: KarinLightBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return KarinLightBoostShaderProgram(context, useHdr, params, demoSplit).also {
            program = it
        }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = params.isNoOp

    fun update(newParams: KarinLightBoostParameters) {
        params = newParams
        program?.update(newParams)
    }

    fun updateColorStrength(v: Float) {
        params = params.copy(colorStrength = v.coerceIn(0f, 1f))
        program?.updateColorStrength(v)
    }
}

class KarinLightBoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var params: KarinLightBoostParameters,
    private var demoSplit: Boolean,
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
        val minDim = minOf(inputWidth, inputHeight)
        glProgram.setFloatUniform("uDetailRadius", (minDim / 480f).coerceAtLeast(2f))
        glProgram.setFloatUniform("uSceneRadius", (minDim / 12f).coerceAtLeast(16f))
        glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uPrefValue", params.prefValue)
            glProgram.setFloatUniform("uShadowLift", params.shadowBoost)
            glProgram.setFloatUniform("uBlackCorr", params.blackLevel)
            glProgram.setFloatUniform("uWhiteBoost", params.whiteBoost)
            glProgram.setFloatUniform("uHighlightProt", params.highlightControl)
            glProgram.setFloatUniform("uLocalContrast", params.localContrast)
            glProgram.setFloatUniform("uGammaInv", params.gammaInv)
            glProgram.setFloatUniform("uSaturation", params.saturation)
            glProgram.setFloatUniform("uVibrance", params.vibrance)
            glProgram.setFloatUniform("uColorStrength", params.colorStrength)
            glProgram.setIntUniform("uRangeMode", params.rangeMode.coerceIn(0, 2))
            glProgram.setIntUniform("uAutoMode", if (params.autoMode) 1 else 0)
            glProgram.setIntUniform("uDemoSplit", if (demoSplit) 1 else 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    fun update(newParams: KarinLightBoostParameters) {
        params = newParams
    }

    fun updateColorStrength(v: Float) {
        params = params.copy(colorStrength = v.coerceIn(0f, 1f))
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
            uniform float uDetailRadius;
            uniform float uSceneRadius;
            uniform int uAutoMode;
            uniform float uPrefValue;
            uniform float uShadowLift;
            uniform float uBlackCorr;
            uniform float uWhiteBoost;
            uniform float uHighlightProt;
            uniform float uLocalContrast;
            uniform float uGammaInv;
            uniform float uSaturation;
            uniform float uVibrance;
            uniform float uColorStrength;
            uniform int uRangeMode;
            uniform int uDemoSplit;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            vec2 px(float r, vec2 d) { return vTexCoord + d * r * uTexelSize; }
            vec3 tap(vec2 uv) { return texture2D(uTexSampler, uv).rgb; }

            float bayer2(vec2 a) {
                a = floor(a);
                return fract(a.x * 0.5 + a.y * a.y * 0.75);
            }
            float bayer4(vec2 a) { return bayer2(a * 0.5) * 0.25 + bayer2(a); }
            float bayer8(vec2 a) { return bayer4(a * 0.5) * 0.25 + bayer4(a); }

            void main() {
                vec3 c = tap(vTexCoord);
                // 0) COMPENSACION DE RANGO (opcional, antes de todo): para
                //    streams mal etiquetados. Expandir recupera negros
                //    lavados (16-235 tratados como 0-255); comprimir doma
                //    negros aplastados. Todo el analisis posterior ya la ve.
                if (uRangeMode == 1) {
                    c = clamp((c - vec3(0.0627)) * 1.1644, 0.0, 1.0);
                } else if (uRangeMode == 2) {
                    c = c * 0.8588 + vec3(0.0627);
                }
                float Y = clamp(luma(c), 0.0, 1.0);

                // ---- Media local: 8 vecinos (ejes + diagonales) + centro.
                // Mueve las CURVAS, nunca el color del pixel (sin blur).
                float tN  = luma(tap(px(uDetailRadius, vec2( 0.0, -1.0))));
                float tS  = luma(tap(px(uDetailRadius, vec2( 0.0,  1.0))));
                float tE  = luma(tap(px(uDetailRadius, vec2( 1.0,  0.0))));
                float tW  = luma(tap(px(uDetailRadius, vec2(-1.0,  0.0))));
                float tNE = luma(tap(px(uDetailRadius, vec2( 1.0, -1.0))));
                float tNW = luma(tap(px(uDetailRadius, vec2(-1.0, -1.0))));
                float tSE = luma(tap(px(uDetailRadius, vec2( 1.0,  1.0))));
                float tSW = luma(tap(px(uDetailRadius, vec2(-1.0,  1.0))));
                float muLocal = (2.0 * Y + tN + tS + tE + tW + tNE + tNW + tSE + tSW) / 10.0;

                // ---- Medidor de escena (solo AUTO): anillo interior +
                // exterior con muestreo por MINIMO. Un brillo que cruza el
                // anillo infla una sola muestra -> el minimo lo ignora ->
                // el boost no respira. Valor global por frame (sin halos).
                float muScene = 0.5;
                if (uAutoMode == 1) {
                    vec2 sceneOff = uSceneRadius * uTexelSize * 0.45;
                    vec2 sceneC = vec2(0.5, 0.5);
                    float i1 = luma(tap(clamp(sceneC + vec2( sceneOff.x,  sceneOff.y), 0.0, 1.0)));
                    float i2 = luma(tap(clamp(sceneC + vec2(-sceneOff.x,  sceneOff.y), 0.0, 1.0)));
                    float i3 = luma(tap(clamp(sceneC + vec2( sceneOff.x, -sceneOff.y), 0.0, 1.0)));
                    float i4 = luma(tap(clamp(sceneC + vec2(-sceneOff.x, -sceneOff.y), 0.0, 1.0)));
                    vec2 outOff = sceneOff * 1.9;
                    float o1 = luma(tap(clamp(sceneC + vec2( outOff.x,  outOff.y), 0.0, 1.0)));
                    float o2 = luma(tap(clamp(sceneC + vec2(-outOff.x,  outOff.y), 0.0, 1.0)));
                    float o3 = luma(tap(clamp(sceneC + vec2( outOff.x, -outOff.y), 0.0, 1.0)));
                    float o4 = luma(tap(clamp(sceneC + vec2(-outOff.x, -outOff.y), 0.0, 1.0)));
                    float mean8 = (i1 + i2 + i3 + i4 + o1 + o2 + o3 + o4) / 8.0;
                    float min8x = min(min(min(i1, i2), min(i3, i4)), min(min(o1, o2), min(o3, o4)));
                    muScene = mix(mean8, min8x, 0.5);
                }

                // ---- Energia maestra: rodilla suave en AUTO (escena normal
                // -> boost plano, sin bombeo; solo la oscuridad real lo sube).
                float darkness = 1.0 - smoothstep(0.30, 0.60, muScene);
                float knee = smoothstep(0.44, 0.66, darkness);
                float boost = uPrefValue;
                if (uAutoMode == 1) {
                    boost = clamp(0.18 + 0.82 * knee, 0.18, 1.0);
                }

                vec3 outRgb = c;
                if (boost > 0.001 || uColorStrength > 0.001) {
                    if (boost > 0.001) {
                        // 1) LIFT ANCLADO (sin pow): 0 en Y=0 (el negro puro
                        //    no se mueve), pico en sombras bajas, ~0 en luces.
                        //    Calibrado: a boost 1 y slider 0.7 sube ~0.21 max.
                        float anchor = smoothstep(0.0, 0.015, Y);
                        float shW = 1.0 - smoothstep(0.03, 0.55, Y);
                        float omY = 1.0 - Y;
                        float Y1 = Y + uShadowLift * boost * 0.30 * shW * omY * omY * anchor;

                        // 2) PUNTO NEGRO: devuelve el piso que subio el lift
                        //    (anti-lavado). Solo actua abajo.
                        float Y2 = Y1 - uBlackCorr * boost * 0.05
                            * (1.0 - smoothstep(0.0, 0.28, Y1));
                        Y2 = clamp(Y2, 0.0, 1.0);

                        // 3) AIRE DE BLANCOS + HOMBRO: empuja altas y comprime
                        //    el tope con parabola (sin clip). El hombro solo
                        //    muerde por encima de 0.85.
                        float hiW = smoothstep(0.55, 0.95, Y2);
                        float Y3 = Y2 + uWhiteBoost * boost * 0.07 * hiW * (1.0 - Y2);
                        float over = max(Y3 - 0.85, 0.0);
                        Y3 -= uHighlightProt * boost * over * over * 6.0;
                        Y3 = clamp(Y3, 0.0, 1.0);

                        // 4) CLARITY LOCAL: Y + k*(Y-media). En plano no hace
                        //    nada (sin velo); en bordes define. Gateado para
                        //    no tocar negro puro ni blanco puro.
                        float kL = min(0.42 * uLocalContrast * (0.30 + 0.70 * boost), 0.55);
                        float gate = smoothstep(0.02, 0.12, Y3)
                            * (1.0 - smoothstep(0.85, 0.99, Y3));
                        float Y4 = clamp(Y3 + kL * (Y3 - muLocal) * gate, 0.0, 1.0);

                        // 5) GAMMA del usuario (unico pow del pase).
                        float Yg = pow(max(Y4, 0.0001), uGammaInv);

                        // 6) REEMPAQUE 0.75..1.9: preserva el tono (escala RGB
                        //    por igual) con techo suficiente para que el brillo
                        //    sí se note, sin llegar al 2.2 que revelaba ruido.
                        outRgb = c * clamp(Yg / max(Y, 0.0001), 0.75, 1.9);
                    }

                    // 7) COLOR UNICO: base (manual+vibrance) + extra en UNA
                    //    sola mezcla, un pivote, un clamp. Piel direccional
                    //    (R-G/G-B): la piel real queda protegida, los apagados
                    //    ganan vida. Gate unico sobre el luma ORIGINAL:
                    //    en negro puro no se satura el ruido (anti-morado).
                    float lOut = luma(outRgb);
                    float mx = max(outRgb.r, max(outRgb.g, outRgb.b));
                    float mn = min(outRgb.r, min(outRgb.g, outRgb.b));
                    float chroma = clamp((mx - mn) * 2.2, 0.0, 1.0);
                    float db = outRgb.r - outRgb.g;
                    float dg = outRgb.g - outRgb.b;
                    float skin = smoothstep(0.02, 0.09, db)
                        * (1.0 - smoothstep(0.42, 0.58, db))
                        * (1.0 - smoothstep(0.10, 0.22, dg))
                        * smoothstep(0.06, 0.16, lOut)
                        * (1.0 - smoothstep(0.75, 0.92, lOut));
                    float satDef = 1.0 - clamp((mx - mn) * 2.0, 0.0, 1.0);
                    float toneW = smoothstep(0.02, 0.18, lOut)
                        * (1.0 - smoothstep(0.75, 0.98, lOut));
                    float drive = clamp(satDef * (0.35 + 0.65 * toneW), 0.0, 1.0)
                        * (1.0 - skin * 0.85);
                    float gBase = (uSaturation - 1.0) * (0.55 + 0.45 * (1.0 - chroma))
                        + uVibrance * 0.55 * (1.0 - chroma) * (1.0 - 0.75 * skin);
                    float gExtra = uColorStrength * (0.25 + drive)
                        + 0.35 * uColorStrength * drive * (1.0 - clamp((mx - mn) * 1.5, 0.0, 1.0));
                    float g = (gBase + gExtra) * smoothstep(0.012, 0.06, Y);
                    outRgb = mix(vec3(lOut), outRgb, clamp(1.0 + g, 0.0, 1.35));
                    outRgb = clamp(outRgb, 0.0, 1.0);

                    // 8) DITHER Bayer unico en luma (anti-bandas 8-bit,
                    //    estable en el tiempo).
                    float dith = (bayer8(gl_FragCoord.xy) - 0.5) * (2.2 / 255.0);
                    float yf = luma(outRgb);
                    outRgb *= clamp((yf + dith) / max(yf, 0.0005), 0.0, 6.0);
                    outRgb = clamp(outRgb, 0.0, 1.0);
                }

                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    outRgb = tap(vTexCoord);
                }
                gl_FragColor = vec4(outRgb, 1.0);
            }
        """
    }
}
