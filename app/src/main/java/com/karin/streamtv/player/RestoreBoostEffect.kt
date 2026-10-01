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
 *    line-darken + coherencia CRT sutil antes del detalle),
 *  - borde real NO suavizado -> afilar (detail direccional con clamp).
 *
 * La clave de calidad: el afilado se frena por píxel según cuánto se
 * suavizó ese mismo píxel (smoothK), en vez de la atenuación gruesa por
 * sliders de la cadena vieja. Todo en luma preservando tono + dither.
 * Cada etapa se apaga por uniform; las 4 en 0 = early-out total.
 * GLES2 compatible. Coste: 5-9 taps según etapas activas y gama.
 * La 4ª etapa (profundidad/clarity) es 0 fetches extra: reutiliza
 * lc/blurL/var4/range ya leídos para el pop frente-fondo.
 */
class RestoreBoostEffect(
    private var depixel: Float,
    private var retro: Float,
    private var detail: Float,
    private var depth: Float = 0f,
    private var lowPower: Boolean = false,
    private var demoSplit: Boolean = false,
) : GlEffect {

    private var program: RestoreBoostShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return RestoreBoostShaderProgram(context, useHdr, depixel, retro, detail, depth, lowPower, demoSplit)
            .also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        depixel <= 0f && retro <= 0f && detail <= 0f && depth <= 0f

    fun updateStages(newDepixel: Float, newRetro: Float, newDetail: Float, newDepth: Float = depth) {
        depixel = newDepixel.coerceIn(0f, 1f)
        retro = newRetro.coerceIn(0f, 1f)
        detail = newDetail.coerceIn(0f, 1f)
        depth = newDepth.coerceIn(0f, 1f)
        program?.updateStages(depixel, retro, detail, depth)
    }
}

class RestoreBoostShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var depixel: Float,
    private var retro: Float,
    private var detail: Float,
    private var depth: Float,
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
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            glProgram.setFloatUniform("uDepixel", depixel)
            glProgram.setFloatUniform("uRetro", retro)
            glProgram.setFloatUniform("uDetail", detail)
            glProgram.setFloatUniform("uDepth", depth)
            glProgram.setIntUniform("uLowPower", if (lowPower) 1 else 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    fun updateStages(newDepixel: Float, newRetro: Float, newDetail: Float, newDepth: Float = depth) {
        depixel = newDepixel.coerceIn(0f, 1f)
        retro = newRetro.coerceIn(0f, 1f)
        detail = newDetail.coerceIn(0f, 1f)
        depth = newDepth.coerceIn(0f, 1f)
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
            uniform float uDepth;
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
                if (uDepixel <= 0.0 && uRetro <= 0.0 && uDetail <= 0.0 && uDepth <= 0.0) {
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
                        // Linea fina de 1px sobre la grilla: no es bloque.
                        // Si el centro es extremo (oscuro o claro) frente a la
                        // cruz, el beta no debe ablandarlo. Reusa la cruz ya
                        // leida, 0 fetches; vale tambien en lowPower.
                        float min4b = min(min(lL, lR), min(lU, lD));
                        float max4b = max(max(lL, lR), max(lU, lD));
                        float thinGb = max(smoothstep(0.03, 0.10, min4b - lc),
                            smoothstep(0.03, 0.10, lc - max4b));
                        gateH = max(gateH, betaH * (1.0 - thinGb));
                        gateV = max(gateV, betaV * (1.0 - thinGb));
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

                    // 2b) xBR-lite: en un borde HORIZONTAL (gradiente
                    // vertical, gV grande) el pixel se reconstruye desde el
                    // par VERTICAL (U/D, del mismo lado del borde); en un
                    // borde VERTICAL, desde el par horizontal (L/R).
                    // ANTES estaba invertido: `horiz` (alto con gV) mezclaba
                    // con avgH y `vert` con avgV, o sea reconstruia CRUZANDO
                    // el borde -> lo difuminaba en vez de Afilarlo.
                    float gH = abs(lL - lR);
                    float gV = abs(lU - lD);
                    float edge = max(gH, gV);
                    // Gate perceptual estilo FXAA-consola (1/16 + suelo,
                    // 1/8 + suelo, relativo al luma local maxC): en sombra
                    // abre antes (detecta linea oscura), en luz cierra
                    // (menos disparos en brillos). A luma 0.5 ~= (0.031,
                    // 0.0625). horizEdge/vertEdge no se tocan (ya relativos).
                    float thrLo = max(0.008, maxC * 0.0625);
                    float thrHi = max(0.030, maxC * 0.125);
                    float gate = smoothstep(thrLo, thrHi, edge);
                    float horizEdge = smoothstep(0.15, 0.6, (gV - gH) / (edge + 1.0e-4));
                    float vertEdge = smoothstep(0.15, 0.6, (gH - gV) / (edge + 1.0e-4));
                    vec3 avgH = (l1 + r1) * 0.5;
                    vec3 avgV = (u1 + d1) * 0.5;
                    vec3 edgeRgb = mix(outc, avgH, vertEdge);
                    edgeRgb = mix(edgeRgb, avgV, horizEdge);
                    // 2b-bis) Diagonales: cuando el gradiente diagonal domina
                    // (borde a 45°, muy comun en anime) horizEdge/vertEdge caen
                    // a ~0 y no se reconstruia nada. Se usa la pareja
                    // diagonal de MENOR gradiente, que es la que corre a lo
                    // largo del borde. Sin coste extra: las diagonales ya
                    // estan leidas para el de-dither. diagDom/avgD se reusan
                    // abajo en el bloque CRT (idea Anime4K: direccion real del
                    // gradiente, 0 fetches extra, solo ALU; en lowPower quedan
                    // en 0 y no se usan).
                    float diagDom = 0.0;
                    vec3 avgD = vec3(0.0);
                    if (fullPath) {
                        float gD1 = abs(lNW - lSE);
                        float gD2 = abs(lNE - lSW);
                        float gAxis = max(gH, gV);
                        float gDiag = max(gD1, gD2);
                        diagDom = smoothstep(0.10, 0.45,
                            (gDiag - gAxis) / max(gDiag + gAxis, 0.0001));
                        avgD = (gD1 > gD2) ? ((ne + sw) * 0.5) : ((nw + se) * 0.5);
                        edgeRgb = mix(edgeRgb, avgD, diagDom);
                    }
                    outc = mix(outc, edgeRgb, clamp(gate * sA * 0.5, 0.0, 1.0));

                    // 2c) Line-darken: línea oscura fina rodeada de claro.
                    // Solo luma baja: no toca piel ni cielos.
                    float lOut = luma(outc);
                    float minN = min(min(lL, lR), min(lU, lD));
                    float maxN = max(max(lL, lR), max(lU, lD));
                    float isDark = 1.0 - smoothstep(0.18, 0.38, lOut);
                    float isThin = smoothstep(0.03, 0.10, maxN - lOut);
                    float flatCap = 1.0 - smoothstep(0.20, 0.35, maxN - minN);
                    // Brillo fino simetrico (reflejo de pelo/ojo/metal): centro
                    // claro rodeado de oscuro. Misma puerta que el oscuro pero
                    // invertida y mas suave (0.25 vs 0.35) para no clipear.
                    // Reusa minN/maxN/flatCap, ~4 ALU, 0 fetches. Mutuamente
                    // excluyente con isDark (oscuro <0.38, brillo >0.62).
                    float isBrightL = smoothstep(0.62, 0.82, lOut);
                    float isThinBL = smoothstep(0.03, 0.10, lOut - minN);
                    float targetL = lOut * (1.0 - isDark * isThin * flatCap * sA * 0.35
                        + isBrightL * isThinBL * flatCap * sA * 0.25);
                    targetL = min(targetL, 1.0);
                    if (lOut > 0.001) {
                        outc = outc * (targetL / lOut);
                    }

                    // 2d) Coherencia de pixel inspirada en CRT (sutil):
                    // los pixeles no son cuadrados perfectos aislados, se
                    // mezclan ligeramente entre si y suavizan transiciones.
                    // Solo luma, sin scanlines / mascara / glow / curvatura.
                    // Corre ANTES del Detail para que el detalle afile encima.
                    // 0 fetches extra: reusa l1/r1/u1/d1 y edge/gate/sA.
                    // REGLA DURA: misma orientacion que 2b, nunca mezcla fija.
                    // 2b dice: borde HORIZONTAL (horizEdge~1) -> avgV (U/D),
                    // borde VERTICAL (vertEdge~1) -> avgH (L/R). Este bloque
                    // usa exactamente esos mismos pesos, no 0.6/0.25 fijos
                    // que mezclaban AMBOS pares y cruzaban el borde siempre.
                    // Asi no redondea lineas finas claras (cables, hakama,
                    // pelo) despues de reconstruirlas. K=0.15 (antes 0.35
                    // enceraba estilo Eagle X2). Linea fina (oscura O clara)
                    // -> se salta el bloque.
                    {
                        float crtK = sA * 0.15;
                        // Linea fina clara: centro claro rodeado de oscuro.
                        // (La oscura ya existe como isDark*isThin; aqui se
                        // cubre la polaridad que faltaba y que ablandaba
                        // cables/pelo/hakama claros de 1px.)
                        float isBright = smoothstep(0.62, 0.82, lOut);
                        float isThinB = smoothstep(0.03, 0.10, lOut - minN);
                        float thinGuard = max(isDark * isThin, isBright * isThinB);
                        // Solo donde hay estructura (no en plano: ya lo limpio
                        // Depixel). Sin flatCap aqui: una linea fuerte es justo
                        // la que hay que proteger, no la que hay que mezclar.
                        float crtGate = gate * (1.0 - thinGuard);
                        if (crtK > 0.001 && crtGate > 0.001) {
                            // Direccional con la misma convencion que 2b:
                            // horizEdge -> avgV, vertEdge -> avgH. Sin
                            // direccion clara -> mezcla iso minima (dither).
                            float totDir = horizEdge + vertEdge + 1.0e-4;
                            vec3 dirRgb = (avgV * horizEdge + avgH * vertEdge) / totDir;
                            vec3 isoRgb = (avgH + avgV) * 0.5;
                            float dirConf = clamp(max(horizEdge, vertEdge), 0.0, 1.0);
                            vec3 crtRgb = mix(isoRgb, dirRgb, dirConf);
                            // Diagonal dominante (borde a 45°): misma pareja
                            // que 2b-bis, sin fetches extra. Solo en fullPath;
                            // en lowPower diagDom=0 y no cambia nada. Se pondera
                            // por crtGate para no tocar lineas finas/planos.
                            if (fullPath) {
                                crtRgb = mix(crtRgb, avgD, clamp(diagDom * crtGate, 0.0, 1.0));
                            }
                            float crtL = luma(crtRgb);
                            float curL = luma(outc);
                            float w = clamp(crtGate * crtK * 0.5, 0.0, 1.0);
                            outc = setLumaPreservingHue(outc, mix(curL, crtL, w));
                        }
                    }
                }

                // ================= 3) DETAIL: capa aditiva de detalle =================
                // BUG QUE SE ARREGLA AQUI: las dos versiones previas median
                // el detalle sobre `outc` (ya suavizado por depixel/retro) y
                // ademas con la formula "centro - promedio del par a lo
                // largo del borde", que vale CERO en un borde simetrico (el
                // caso mas comun). Peor aun: retro 2b ya mueve outc hacia ese
                // mismo promedio, asi que las dos etapas se anulaban y el
                // efecto era invisible.
                // Ahora: la SENAL se toma de la entrada ORIGINAL (lc contra
                // el promedio de los vecinos originales) y se SUMA sobre la
                // salida de las otras etapas. Asi el detalle es una capa
                // aditiva real que las etapasPrevious no pueden borrar.
                // Anti-dientes: guarda de escalon (jaggyW) + overshoot
                // acotado (min(0.03, 20% del rango)) -> filo sin halos.
                if (uDetail > 0.0) {
                    float lOut = luma(outc);
                    if (lOut >= 0.005 && lOut <= 0.995) {
                        float mnL = min(min(lL, lR), min(lU, lD));
                        float mxL = max(max(lL, lR), max(lU, lD));
                        float rangeC = max(mxL - mnL, 0.0001);
                        float gx = abs(lR - lL);
                        float gy = abs(lU - lD);
                        // Guarda anti-diente (escalon 1px en diagonal): cruz
                        // activa en ambos ejes + diagonales dispares.
                        float stair = 0.0;
                        if (fullPath) {
                            float gD1 = abs(lNW - lSE);
                            float gD2 = abs(lNE - lSW);
                            float axisM = max(gx, gy);
                            float axism = min(gx, gy);
                            float diagM = max(gD1, gD2);
                            stair = clamp(axism / max(axisM, 0.0001), 0.0, 1.0)
                                * clamp(abs(gD1 - gD2) / max(diagM, 0.0001), 0.0, 1.0)
                                * smoothstep(0.01, 0.05, rangeC);
                        }
                        float jaggyW = 1.0 - 0.7 * clamp(stair, 0.0, 1.0);
                        // SENAL original: lc (centro original) menos la media
                        // de los vecinos originales. No se toca outc aqui, asi
                        // que depixel/retro no pueden cancelarla.
                        float mean4 = (lL + lR + lU + lD) * 0.25;
                        // DoG selectivo (idea Anime4K, 0 fetches extra): fino
                        // (centro vs cruz 1px) contra amplio (cruz 1px vs
                        // diagonales ~1.4px, ya leidas). Solo afila donde ambos
                        // coinciden en signo = linea real; el grano aislado
                        // (fino grande, amplio ~0) se atenua solo. En lowPower
                        // (fullPath=0) se usa el laplaciano original.
                        float lapOrig = lc - mean4;
                        float lap = lapOrig;
                        if (fullPath) {
                            float diagMean = (lNW + lNE + lSW + lSE) * 0.25;
                            float lapWide = mean4 - diagMean;
                            float agree = clamp(lapOrig * lapWide * 400.0, 0.0, 1.0);
                            lap = mix(lapOrig, lapOrig * agree, 0.7);
                        }
                        // Puertas perceptuales estilo FXAA-consola (relativas al
                        // luma local mxL, con suelo absoluto anti-negro): a
                        // luma 0.5 quedan (0.005, 0.015) = como antes; en
                        // sombra abren antes, en luz cierran. Solo atenúan.
                        float relVar = max(var4, 0.0) / max(mean4 * mean4, 0.0001);
                        float gate = smoothstep(max(0.0015, mxL * 0.01), max(0.006, mxL * 0.03), rangeC);
                        float vgate = smoothstep(0.0015, 0.012, relVar);
                        // Rodilla baja 0.01-0.10 (antes 0.02-0.15): la linea de
                        // anime vive en 0.05-0.10 y recibia poco filo. Siguen
                        // protegiendo el negro puro el vgate/gate de rango.
                        float zone = smoothstep(0.01, 0.10, lOut)
                            * (1.0 - smoothstep(0.85, 0.98, lOut));
                        float rmg = outc.r - outc.g;
                        float rmb = outc.r - outc.b;
                        float skin = smoothstep(0.02, 0.1, rmg) * smoothstep(0.01, 0.08, rmb);
                        skin *= smoothstep(0.25, 0.45, outc.r) * (1.0 - smoothstep(0.7, 0.85, outc.r));
                        skin *= smoothstep(0.15, 0.3, outc.g) * (1.0 - smoothstep(0.6, 0.75, outc.g));
                        skin = clamp(skin, 0.0, 1.0);
                        // Donde se limpio este pixel, menos filo (smoothK).
                        float sharpK = 1.0 - 0.35 * clamp(smoothK, 0.0, 1.0);
                        float gain = uDetail * 3.0 * jaggyW * sharpK * mix(1.0, 0.3, skin);
                        float boost = lap * gain * gate * vgate * zone;
                        // Overshoot acotado: maximo 0.03 absoluto y nunca mas
                        // del 20% del rango local (borde de bajo contraste no
                        // se debe disparar). Antes era 25% -> colmillos/halos.
                        float ringEps = min(0.03, rangeC * 0.2);
                        float newLuma = clamp(lOut + boost, mnL - ringEps, mxL + ringEps);
                        outc = setLumaPreservingHue(outc, newLuma);
                    }
                }

                // ================= 4) DEPTH: pop frente-fondo (0 fetches) =================
                // Clarity local suave: lc (original) contra blurL (media de la
                // cruz ya leida). No es 3D estereo: separa planos por
                // micro-contraste. Se frena donde se limpio (smoothK), en
                // linea fina (thinGuard del bloque CRT: cables/pelo de 1px) y
                // en piel (suave), con clamp como el detalle. Solo ALU.
                if (uDepth > 0.0) {
                    float lOutD = luma(outc);
                    if (lOutD > 0.005 && lOutD < 0.995) {
                        float mnD = min(min(lL, lR), min(lU, lD));
                        float mxD = max(max(lL, lR), max(lU, lD));
                        float rangeD = max(mxD - mnD, 0.0001);
                        float meanD = (lL + lR + lU + lD) * 0.25;
                        float depthSig = lc - blurL;
                        float dgate = smoothstep(0.004, 0.02, rangeD);
                        float relVd = max(var4, 0.0) / max(meanD * meanD, 0.0001);
                        float vgd = smoothstep(0.0015, 0.012, relVd);
                        // Linea fina (misma receta que 2c/2d, 0 fetches):
                        // centro extremo frente a la cruz = no tocar.
                        float isDarkD = 1.0 - smoothstep(0.18, 0.38, lOutD);
                        float isThinD = smoothstep(0.03, 0.10, mxD - lOutD);
                        float isBrightD = smoothstep(0.62, 0.82, lOutD);
                        float isThinBD = smoothstep(0.03, 0.10, lOutD - mnD);
                        float thinD = max(isDarkD * isThinD, isBrightD * isThinBD);
                        // Piel suave (misma receta que Detail, 0 fetches):
                        // el pop duro en caras se ve harsh.
                        float rmgD = outc.r - outc.g;
                        float rmbD = outc.r - outc.b;
                        float skinD = smoothstep(0.02, 0.1, rmgD) * smoothstep(0.01, 0.08, rmbD);
                        skinD *= smoothstep(0.25, 0.45, outc.r) * (1.0 - smoothstep(0.7, 0.85, outc.r));
                        skinD *= smoothstep(0.15, 0.3, outc.g) * (1.0 - smoothstep(0.6, 0.75, outc.g));
                        skinD = clamp(skinD, 0.0, 1.0);
                        float zoneD = smoothstep(0.01, 0.10, lOutD)
                            * (1.0 - smoothstep(0.85, 0.98, lOutD));
                        float dk = (1.0 - 0.5 * clamp(smoothK, 0.0, 1.0))
                            * (1.0 - 0.8 * clamp(thinD, 0.0, 1.0))
                            * mix(1.0, 0.5, skinD) * zoneD;
                        float boostD = depthSig * (uDepth * 0.9) * dgate * vgd * dk;
                        float ringD = min(0.03, rangeD * 0.2);
                        float newLD = clamp(lOutD + boostD, mnD - ringD, mxD + ringD);
                        outc = setLumaPreservingHue(outc, newLD);
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
