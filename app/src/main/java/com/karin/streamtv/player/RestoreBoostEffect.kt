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
 * RESTORE BOOST: limpieza + reconstrucción + detalle en UN solo pase.
 *
 * Fusiona Depixel + Retro + Detail (antes 3 pases que leían el mismo
 * vecindario 3x3 por separado y hasta peleaban: Detail afilaba lo que
 * Depixel acababa de suavizar). Aquí el vecindario 3x3 se lee UNA vez
 * (centro + cruz siempre; diagonales solo si alguna etapa las necesita)
 * y cada píxel toma UNA decisión conjunta:
 *  - plano con ruido/bloques -> suavizar (depixel: grano, grilla 4px,
 *    croma, debanding),
 *  - checkerboard/líneas -> reconstruir (retro: de-dither, xBR-lite,
 *    line-darken),
 *  - borde real NO suavizado -> afilar (detail direccional con clamp).
 *
 * La clave de calidad: el afilado se frena por píxel según cuánto se
 * suavizó ese mismo píxel (smoothK), en vez de la atenuación gruesa por
 * sliders de la cadena vieja. Todo en luma preservando tono + dither.
 * Cada etapa se apaga por uniform; las 3 en 0 = early-out total.
 * GLES2 compatible. Coste: 5-9 taps según etapas activas y gama.
 */
class RestoreBoostEffect(
    private var depixel: Float,
    private var retro: Float,
    private var detail: Float,
    private var lowPower: Boolean = false,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: RestoreBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return RestoreBoostShaderProgram(context, useHdr, depixel, retro, detail, lowPower, demoSplit)
            .also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        depixel <= 0f && retro <= 0f && detail <= 0f

    fun updateStages(newDepixel: Float, newRetro: Float, newDetail: Float) {
        depixel = newDepixel.coerceIn(0f, 1f)
        retro = newRetro.coerceIn(0f, 1f)
        detail = newDetail.coerceIn(0f, 1f)
        program?.updateStages(depixel, retro, detail)
    }
}

class RestoreBoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var depixel: Float,
    private var retro: Float,
    private var detail: Float,
    private var lowPower: Boolean,
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
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            if (presentationTimeUs < 500_000L) {
                android.util.Log.d("RestoreBoost", "TMP stages dep=$depixel retro=$retro det=$detail low=$lowPower")
            }
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            glProgram.setFloatUniform("uDepixel", depixel)
            glProgram.setFloatUniform("uRetro", retro)
            glProgram.setFloatUniform("uDetail", detail)
            glProgram.setIntUniform("uLowPower", if (lowPower) 1 else 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    fun updateStages(newDepixel: Float, newRetro: Float, newDetail: Float) {
        depixel = newDepixel.coerceIn(0f, 1f)
        retro = newRetro.coerceIn(0f, 1f)
        detail = newDetail.coerceIn(0f, 1f)
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
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uDepixel;
            uniform float uRetro;
            uniform float uDetail;
            uniform int uLowPower;
            uniform int uDemoSplit;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            vec3 setLumaPreservingHue(vec3 rgb, float targetLuma) {
                float currentLuma = luma(rgb);
                if (currentLuma < 0.001) return vec3(targetLuma);
                return rgb * (targetLuma / currentLuma);
            }

            float bayer2(vec2 a) {
                a = floor(a);
                return fract(a.x * 0.5 + a.y * a.y * 0.75);
            }
            float bayer4(vec2 a) { return bayer2(a * 0.5) * 0.25 + bayer2(a); }

            void main() {
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                if (uDepixel <= 0.0 && uRetro <= 0.0 && uDetail <= 0.0) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }
                vec2 tx = uTexelSize;

                // ---- Vecindario compartido: centro + cruz siempre (5 taps).
                vec3 l1 = texture2D(uTexSampler, vTexCoord - vec2(tx.x, 0.0)).rgb;
                vec3 r1 = texture2D(uTexSampler, vTexCoord + vec2(tx.x, 0.0)).rgb;
                vec3 u1 = texture2D(uTexSampler, vTexCoord - vec2(0.0, tx.y)).rgb;
                vec3 d1 = texture2D(uTexSampler, vTexCoord + vec2(0.0, tx.y)).rgb;
                float lc = luma(c);
                float lL = luma(l1); float lR = luma(r1);
                float lU = luma(u1); float lD = luma(d1);
                vec3 blurRGB = (l1 + r1 + u1 + d1) * 0.25;
                float blurL = luma(blurRGB);
                float var4 = (lL * lL + lR * lR + lU * lU + lD * lD) * 0.25 - blurL * blurL;
                float minC = min(min(lL, lR), min(min(lU, lD), lc));
                float maxC = max(max(lL, lR), max(max(lU, lD), lc));
                float range4 = maxC - minC;
                vec2 texelPos = vTexCoord / uTexelSize;

                // ---- Diagonales (4 taps) solo si alguna etapa las necesita:
                // debanding (depixel) o rutas completas (retro/detail).
                bool fullPath = (uLowPower == 0);
                bool needDiag = (uDepixel > 0.0)
                    || (uRetro > 0.0 && fullPath)
                    || (uDetail > 0.0 && fullPath);
                vec3 nw = vec3(0.0); vec3 ne = vec3(0.0);
                vec3 sw = vec3(0.0); vec3 se = vec3(0.0);
                float lNW = 0.0; float lNE = 0.0; float lSW = 0.0; float lSE = 0.0;
                if (needDiag) {
                    nw = texture2D(uTexSampler, vTexCoord + vec2(-tx.x, -tx.y)).rgb;
                    ne = texture2D(uTexSampler, vTexCoord + vec2( tx.x, -tx.y)).rgb;
                    sw = texture2D(uTexSampler, vTexCoord + vec2(-tx.x,  tx.y)).rgb;
                    se = texture2D(uTexSampler, vTexCoord + vec2( tx.x,  tx.y)).rgb;
                    lNW = luma(nw); lNE = luma(ne); lSW = luma(sw); lSE = luma(se);
                }

                vec3 outc = c;
                // smoothK: cuánto se suavizó este píxel (0..1). La decisión
                // conjunta: el afilado se frena donde se limpió.
                float smoothK = 0.0;

                // ================= 1) DEPIXEL: limpieza =================
                if (uDepixel > 0.0) {
                    // 1a) Grano en zonas planas -> media local.
                    float flatM = (1.0 - smoothstep(0.0003, 0.0025, var4))
                        * (1.0 - smoothstep(0.02, 0.07, range4));
                    if (flatM > 0.0) {
                        outc = setLumaPreservingHue(c, mix(lc, blurL, flatM * uDepixel * 0.85));
                        smoothK = max(smoothK, flatM * uDepixel);
                    }
                    // 1b) Bordes de bloque (grilla 4px: 4x4/8x8/16x16).
                    // Escalón chico = bloque; grande = borde real... salvo que
                    // ambos lados sean planos (beta H.264): pixelado fuerte.
                    vec2 blockPos = mod(texelPos, vec2(4.0));
                    float falloff = (uLowPower == 1) ? 1.0 : 2.0;
                    float horizW = max(1.0 - clamp(blockPos.x / falloff, 0.0, 1.0),
                                       1.0 - clamp((4.0 - blockPos.x) / falloff, 0.0, 1.0));
                    float vertW = max(1.0 - clamp(blockPos.y / falloff, 0.0, 1.0),
                                      1.0 - clamp((4.0 - blockPos.y) / falloff, 0.0, 1.0));
                    float edgeW = max(horizW, vertW);
                    if (edgeW > 0.0) {
                        float stepH = abs(lL - lR) * 0.5;
                        float stepV = abs(lU - lD) * 0.5;
                        float alpha1 = mix(0.04, 0.11, uDepixel);
                        if (uLowPower == 1) alpha1 = mix(0.03, 0.07, uDepixel);
                        float gateH = 1.0 - smoothstep(0.012, alpha1, stepH);
                        float gateV = 1.0 - smoothstep(0.012, alpha1, stepV);
                        float b1 = 0.018 + 0.05 * uDepixel;
                        float b2 = 0.05 + 0.09 * uDepixel;
                        float betaH = (1.0 - smoothstep(b1, b2, abs(lL - lc)))
                            * (1.0 - smoothstep(b1, b2, abs(lR - lc)));
                        float betaV = (1.0 - smoothstep(b1, b2, abs(lU - lc)))
                            * (1.0 - smoothstep(b1, b2, abs(lD - lc)));
                        gateH = max(gateH, betaH);
                        gateV = max(gateV, betaV);
                        vec3 filtH = (l1 + c + r1) * 0.3333333;
                        vec3 filtV = (u1 + c + d1) * 0.3333333;
                        float wH = horizW * gateH;
                        float wV = vertW * gateV;
                        float totalW = wH + wV;
                        if (totalW > 0.0) {
                            vec3 blended = (filtH * wH + filtV * wV) / totalW;
                            float baseL = luma(outc);
                            float newLuma = mix(baseL, luma(blended), clamp(totalW, 0.0, 1.0) * uDepixel);
                            outc = setLumaPreservingHue(outc, newLuma);
                            smoothK = max(smoothK, clamp(totalW, 0.0, 1.0) * uDepixel);
                        }
                    }
                    // 1c) Croma guiado por luma (0 fetches extra).
                    {
                        float wL = clamp(1.0 - abs(lc - lL) * 8.0, 0.0, 1.0);
                        float wR = clamp(1.0 - abs(lc - lR) * 8.0, 0.0, 1.0);
                        float wU = clamp(1.0 - abs(lc - lU) * 8.0, 0.0, 1.0);
                        float wD = clamp(1.0 - abs(lc - lD) * 8.0, 0.0, 1.0);
                        float wsum = wL + wR + wU + wD;
                        float cgate = (1.0 - smoothstep(0.03, 0.12, range4)) * uDepixel;
                        float lOut = luma(outc);
                        vec3 chromaAvg = ((l1 - vec3(lL)) * wL + (r1 - vec3(lR)) * wR
                            + (u1 - vec3(lU)) * wU + (d1 - vec3(lD)) * wD) / max(wsum, 0.0001);
                        vec3 chromaOut = mix(outc - vec3(lOut), chromaAvg, clamp(cgate, 0.0, 1.0));
                        outc = vec3(lOut) + chromaOut;
                    }
                    // 1d) Debanding automático en gradientes planos.
                    {
                        float flat8 = (abs(lc - lL) + abs(lc - lR) + abs(lc - lU) + abs(lc - lD)
                            + abs(lc - lNW) + abs(lc - lNE) + abs(lc - lSW) + abs(lc - lSE)) * 0.125;
                        float bandM = (1.0 - smoothstep(0.0, 0.03, flat8)) * uDepixel * 0.55;
                        if (bandM > 0.0) {
                            vec3 deba = (l1 + r1 + u1 + d1 + nw + ne + sw + se) * 0.125;
                            outc = setLumaPreservingHue(outc, mix(luma(outc), luma(deba), bandM * 0.8));
                            smoothK = max(smoothK, bandM);
                        }
                    }
                }

                // ================= 2) RETRO: reconstrucción =================
                if (uRetro > 0.0) {
                    // Actividad local (varianza en cruz) modula por píxel:
                    // plano -> 0.2x, textura/borde -> 1.0x.
                    float avg4 = (lL + lR + lU + lD) * 0.25;
                    float varR = max((lL * lL + lR * lR + lU * lU + lD * lD) * 0.25 - avg4 * avg4, 0.0);
                    float activity = smoothstep(0.00004, 0.0008, varR);
                    float sA = uRetro * mix(0.2, 1.0, activity);

                    // 2a) De-dither checkerboard (ruta completa): diagonales
                    // parecidas entre sí pero distintas del centro.
                    float lCcur = luma(outc);
                    if (fullPath) {
                        float diagEq = abs((lNW + lSE) - (lNE + lSW));
                        float diagAvg = (lNW + lNE + lSW + lSE) * 0.25;
                        float centerDiff = abs(lCcur - diagAvg);
                        float dd = (1.0 - smoothstep(0.02, 0.06, diagEq))
                            * smoothstep(0.015, 0.06, centerDiff);
                        vec3 diagRgb = (nw + ne + sw + se) * 0.25;
                        outc = mix(outc, diagRgb, clamp(dd * sA * 0.6, 0.0, 1.0));
                        lCcur = luma(outc);
                    }

                    // 2b) xBR-lite: borde horizontal -> mezcla L/R, borde
                    // vertical -> mezcla U/D. Planos y esquinas intactos.
                    float gH = abs(lL - lR);
                    float gV = abs(lU - lD);
                    float edge = max(gH, gV);
                    float gate = smoothstep(0.02, 0.09, edge);
                    float horiz = smoothstep(0.15, 0.6, (gV - gH) / (edge + 1.0e-4));
                    float vert = smoothstep(0.15, 0.6, (gH - gV) / (edge + 1.0e-4));
                    vec3 avgH = (l1 + r1) * 0.5;
                    vec3 avgV = (u1 + d1) * 0.5;
                    vec3 edgeRgb = mix(outc, avgH, horiz);
                    edgeRgb = mix(edgeRgb, avgV, vert);
                    outc = mix(outc, edgeRgb, clamp(gate * sA * 0.5, 0.0, 1.0));

                    // 2c) Line-darken: línea oscura fina rodeada de claro.
                    // Solo luma baja: no toca piel ni cielos.
                    float lOut = luma(outc);
                    float minN = min(min(lL, lR), min(lU, lD));
                    float maxN = max(max(lL, lR), max(lU, lD));
                    float isDark = 1.0 - smoothstep(0.18, 0.38, lOut);
                    float isThin = smoothstep(0.03, 0.10, maxN - lOut);
                    float flatCap = 1.0 - smoothstep(0.20, 0.35, maxN - minN);
                    float targetL = lOut * (1.0 - isDark * isThin * flatCap * sA * 0.35);
                    if (lOut > 0.001) {
                        outc = outc * (targetL / lOut);
                    }
                }

                // ================= 3) DETAIL: afilado dirigido =================
                // Decisión conjunta: donde se limpió (smoothK alto) el afilado
                // se frena solo; donde no, actúa completo. Así nunca se
                // reintroduce el pixelado que se acaba de quitar.
                if (uDetail > 0.0) {
                    float lCcur = luma(outc);
                    if (lCcur >= 0.005 && lCcur <= 0.995) {
                        float vgate = smoothstep(0.0006, 0.004, max(var4, 0.0));
                        float crisp = smoothstep(0.015, 0.09, range4);
                        float adapt = mix(1.25, 0.55, crisp);
                        float zone = smoothstep(0.02, 0.15, lCcur)
                            * (1.0 - smoothstep(0.85, 0.98, lCcur));
                        float gx = abs(lR - lL);
                        float gy = abs(lU - lD);
                        float dirW = gy / max(gx + gy, 0.0001);
                        float dv = lc - (lU + lD) * 0.5;
                        float dh = lc - (lL + lR) * 0.5;
                        float dirDetail = dv * dirW + dh * (1.0 - dirW);
                        // HQ DIAGONAL (rejilla rotada): si el borde va en
                        // diagonal, afilar A LO LARGO del trazo en vez de
                        // cruzarlo (no remarca el diente). La diagonal con
                        // MENOR gradiente corre a lo largo del borde: el
                        // centro se reconstruye desde ella. Si las diagonales
                        // no se pidieron (valen 0), diagDom da 0: no-op.
                        float gD1 = abs(lNW - lSE);
                        float gD2 = abs(lNE - lSW);
                        float gAxis = max(gx, gy);
                        float gDiag = max(gD1, gD2);
                        float diagDom = smoothstep(0.10, 0.45,
                            (gDiag - gAxis) / max(gDiag + gAxis, 0.0001));
                        float alongEdge = (gD1 > gD2)
                            ? (lNE + lSW) * 0.5
                            : (lNW + lSE) * 0.5;
                        float diagDetail = mix(dirDetail, lc - alongEdge, diagDom);
                        float rmg = outc.r - outc.g;
                        float rmb = outc.r - outc.b;
                        float skin = smoothstep(0.02, 0.1, rmg) * smoothstep(0.01, 0.08, rmb);
                        skin *= smoothstep(0.25, 0.45, outc.r) * (1.0 - smoothstep(0.7, 0.85, outc.r));
                        skin *= smoothstep(0.15, 0.3, outc.g) * (1.0 - smoothstep(0.6, 0.75, outc.g));
                        skin = clamp(skin, 0.0, 1.0);
                        float detailAmt;
                        float minK = minC;
                        float maxK = maxC;
                        if (fullPath) {
                            minK = min(minK, min(min(lNW, lNE), min(lSW, lSE)));
                            maxK = max(maxK, max(max(lNW, lNE), max(lSW, lSE)));
                            float lap = 8.0 * lc - (lL + lR + lU + lD + lNW + lNE + lSW + lSE);
                            detailAmt = (diagDetail * 1.5 + lap * 0.06) * adapt * mix(1.0, 0.25, skin);
                        } else {
                            detailAmt = diagDetail * adapt * mix(1.0, 0.3, skin);
                        }
                        // CAS (contrast-adaptive): mordida extra proporcional al
                        // contraste local de la cruz. Más filo justo en bordes
                        // reales, nada en plano (puerta por pico). Sin fetches
                        // nuevos (reusa lL/lR/lU/lD/lc) y ~8 ALU: cabe en el
                        // mismo pase, tiempo real intacto. Las máscaras (zona,
                        // piel, vgate, sharpK) y el clamp anti-halo de abajo
                        // aplican igual: solo sube el techo, no el riesgo.
                        float casMn = min(min(lL, lR), min(lU, lD));
                        float casMx = max(max(lL, lR), max(lU, lD));
                        float casPeak = max(casMx - lc, lc - casMn);
                        float casGate = smoothstep(0.004, 0.03, casPeak);
                        float casAmt = (lc - (casMx + casMn) * 0.5) *
                            (1.5 + 2.0 * clamp(casPeak * 8.0, 0.0, 1.0));
                        detailAmt += casAmt * casGate * mix(1.0, 0.25, skin);
                        float sharpK = 1.0 - 0.65 * clamp(smoothK, 0.0, 1.0);
                        float over = range4 * 0.25 * uDetail;
                        float newLuma = clamp(lCcur + detailAmt * uDetail * 1.5 * vgate * zone * sharpK,
                            minK - over, maxK + over);
                        outc = setLumaPreservingHue(outc, newLuma);
                    }
                }

                // Dither anti-banda del depixel (solo si limpió).
                if (uDepixel > 0.0) {
                    outc += (bayer4(texelPos) - 0.5) * (1.5 / 255.0) * uDepixel;
                }
                vec3 demoRgb = outc;
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) {
                    demoRgb = texture2D(uTexSampler, vTexCoord).rgb;
                }
                gl_FragColor = vec4(demoRgb, 1.0);
            }
        """
    }
}
