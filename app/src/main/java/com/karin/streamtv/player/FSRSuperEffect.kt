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
 * Escalador FSR: núcleo espacial EASU + RCAS del FSR 1.0 original de AMD
 * (ffx_fsr1.h), adaptado a un solo pase GLES2 para equipos modestos.
 * FSR 3.1/4.0 son temporales (vectores de movimiento + historial + ML) y no
 * pueden correr en este pipeline 1:1 de una sola textura.
 *
 * Mejoras aplicadas sobre el código previo:
 * - Taps direccionales en píxeles convertidos a UV (antes muestreaban hasta
 *   2 pantallas de distancia).
 * - Límite RCAS FSR_RCAS_LIMIT = 0.25 - 1/16 = 0.1875 (antes -0.25 causaba
 *   división por cero e imagen distorsionada).
 * - Nitidez por "stops" como AMD (0 = máxima): el slider sí responde.
 * - Gradiente de 12 taps del EASU original en calidad Alta.
 * - 3 calidades: RENDIMIENTO (EASU-lite + RCAS 5-tap estilo AMD exacto),
 *   EQUILIBRADO y CALIDAD.
 */
enum class FsrQuality(val label: String) {
    RENDIMIENTO("Rendimiento (rápido)"),
    EQUILIBRADO("Equilibrado"),
    CALIDAD("Calidad (lento)"),
}

class FSRSuperEffect(
    private var sharpness: Float = 0.6f,
    private var quality: FsrQuality = FsrQuality.EQUILIBRADO,
    private var demoSplit: Boolean = false,
) : GlEffect {
    private var program: FSRSuperProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return FSRSuperProgram(context, useHdr, sharpness, quality, demoSplit).also { program = it }
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = sharpness <= 0f

    // Para sliders en tiempo real sin recompilar
    fun updateSharpness(newSharpness: Float) {
        sharpness = newSharpness.coerceIn(0f, 1f)
        program?.updateSharpness(newSharpness)
    }

    fun updateQuality(newQuality: FsrQuality) {
        quality = newQuality
        program?.updateQuality(newQuality)
    }
}

class FSRSuperProgram(
    context: Context,
    useHdr: Boolean,
    private var sharpness: Float,
    private var quality: FsrQuality,
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
            glProgram.setFloatUniform("uSharpness", sharpness)
            glProgram.setIntUniform("uQuality", quality.ordinal)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    // Para sliders en tiempo real sin recompilar
    fun updateSharpness(newSharpness: Float) { sharpness = newSharpness.coerceIn(0f, 1f) }
    fun updateQuality(newQuality: FsrQuality) { quality = newQuality }

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
            uniform float uSharpness;
            uniform int uQuality; // 0=Rendimiento, 1=Equilibrado, 2=Calidad
            uniform int uDemoSplit; // 1 = demo: mitad izquierda intacta
            // Límite RCAS del FSR 1.0 original: evita la división por cero.
            const float FSR_RCAS_LIMIT = 0.25 - (1.0 / 16.0);
            float lumaOf(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            vec4 FsrTap(vec2 off, vec2 dir, vec2 len, float lob) {
                // off viene en píxeles: se convierte a UV con uTexelSize.
                vec2 pos = vTexCoord + dir * off * uTexelSize;
                vec2 v = off * len;
                float w = 0.5 - abs(v.x) - abs(v.y);
                v += 0.5;
                return texture2D(uTexSampler, pos).rgba * exp2(lob * max(abs(v.x), abs(v.y)));
            }
            // Nitidez por "stops" como AMD: 1.0 = máxima, se atenúa al bajar.
            float sharpStops() { return exp2(-(1.0 - uSharpness) * 4.0); }
            void main() {
                vec2 inputSize = 1.0 / uTexelSize;
                vec2 pp = vTexCoord * inputSize - 0.5;
                vec2 fp = floor(pp);
                vec2 p0 = (fp + 0.5) / inputSize;
                vec2 one = 1.0 / inputSize;
                vec3 e0 = texture2D(uTexSampler, p0).rgb;
                vec3 e1 = texture2D(uTexSampler, p0 + vec2(one.x, 0.0)).rgb;
                vec3 e2 = texture2D(uTexSampler, p0 + vec2(0.0, one.y)).rgb;
                vec3 e3 = texture2D(uTexSampler, p0 + one).rgb;
                float a = lumaOf(e0);
                float b = lumaOf(e1);
                float c = lumaOf(e2);
                float d = lumaOf(e3);

                if (uQuality == 0) {
                    // RENDIMIENTO: EASU-lite (base bilineal) + RCAS 5-tap estilo AMD.
                    vec2 phase0 = fract(pp);
                    vec3 liteBase = mix(mix(e0, e1, phase0.x), mix(e2, e3, phase0.x), phase0.y);
                    vec3 rB0 = texture2D(uTexSampler, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
                    vec3 rD0 = texture2D(uTexSampler, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
                    vec3 rF0 = texture2D(uTexSampler, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 rH0 = texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
                    float rbL0 = lumaOf(rB0); float rdL0 = lumaOf(rD0);
                    float rfL0 = lumaOf(rF0); float rhL0 = lumaOf(rH0);
                    float reL0 = lumaOf(liteBase);
                    float nz0 = clamp(abs(0.25 * rbL0 + 0.25 * rdL0 + 0.25 * rfL0 + 0.25 * rhL0 - reL0) / max(max(max(rbL0, rdL0), max(rfL0, rhL0)) - min(min(rbL0, rdL0), min(rfL0, rhL0)), 0.0001), 0.0, 1.0);
                    nz0 = -0.5 * nz0 + 1.0;
                    float mn4R = min(min(rB0.r, rD0.r), min(rF0.r, rH0.r));
                    float mn4G = min(min(rB0.g, rD0.g), min(rF0.g, rH0.g));
                    float mn4B = min(min(rB0.b, rD0.b), min(rF0.b, rH0.b));
                    float mx4R = max(max(rB0.r, rD0.r), max(rF0.r, rH0.r));
                    float mx4G = max(max(rB0.g, rD0.g), max(rF0.g, rH0.g));
                    float mx4B = max(max(rB0.b, rD0.b), max(rF0.b, rH0.b));
                    float hitMinR = min(mn4R, liteBase.r) / (4.0 * mx4R + 0.0001);
                    float hitMinG = min(mn4G, liteBase.g) / (4.0 * mx4G + 0.0001);
                    float hitMinB = min(mn4B, liteBase.b) / (4.0 * mx4B + 0.0001);
                    float hitMaxR = (1.0 - max(mx4R, liteBase.r)) / (4.0 * mn4R - 4.0 + 0.0001);
                    float hitMaxG = (1.0 - max(mx4G, liteBase.g)) / (4.0 * mn4G - 4.0 + 0.0001);
                    float hitMaxB = (1.0 - max(mx4B, liteBase.b)) / (4.0 * mn4B - 4.0 + 0.0001);
                    float lobeR = max(-hitMinR, hitMaxR);
                    float lobeG = max(-hitMinG, hitMaxG);
                    float lobeB = max(-hitMinB, hitMaxB);
                    float lobe0 = max(-FSR_RCAS_LIMIT, min(max(max(lobeR, lobeG), lobeB), 0.0)) * sharpStops();
                    lobe0 *= nz0;
                    float rcpL0 = 1.0 / (4.0 * lobe0 + 1.0);
                    vec3 liteOut = vec3(
                        (lobe0 * rB0.r + lobe0 * rD0.r + lobe0 * rH0.r + lobe0 * rF0.r + liteBase.r) * rcpL0,
                        (lobe0 * rB0.g + lobe0 * rD0.g + lobe0 * rH0.g + lobe0 * rF0.g + liteBase.g) * rcpL0,
                        (lobe0 * rB0.b + lobe0 * rD0.b + lobe0 * rH0.b + lobe0 * rF0.b + liteBase.b) * rcpL0
                    );
                    vec3 demoRgb = clamp(liteOut, 0.0, 1.0);
                    if (uDemoSplit == 1 && vTexCoord.x < 0.5) { demoRgb = texture2D(uTexSampler, vTexCoord).rgb; }
                    gl_FragColor = vec4(demoRgb, 1.0);
                    return;
                }

                // Dirección del gradiente: 12 taps (original FSR 1.0) en Calidad,
                // 4 taps en Equilibrado.
                vec2 dir;
                if (uQuality >= 2) {
                    vec3 n0 = texture2D(uTexSampler, p0 + vec2(-one.x, 0.0)).rgb;
                    vec3 n1 = texture2D(uTexSampler, p0 + vec2(0.0, -one.y)).rgb;
                    vec3 n2 = texture2D(uTexSampler, p0 + vec2(2.0 * one.x, 0.0)).rgb;
                    vec3 n3 = texture2D(uTexSampler, p0 + vec2(0.0, 2.0 * one.y)).rgb;
                    vec3 n4 = texture2D(uTexSampler, p0 + vec2(-one.x, -one.y)).rgb;
                    vec3 n5 = texture2D(uTexSampler, p0 + vec2(one.x, -one.y)).rgb;
                    vec3 n6 = texture2D(uTexSampler, p0 + vec2(-one.x, one.y)).rgb;
                    vec3 n7 = texture2D(uTexSampler, p0 + vec2(one.x, one.y)).rgb;
                    float ln0 = lumaOf(n0); float ln1 = lumaOf(n1);
                    float ln2 = lumaOf(n2); float ln3 = lumaOf(n3);
                    float ln4 = lumaOf(n4); float ln5 = lumaOf(n5);
                    float ln6 = lumaOf(n6); float ln7 = lumaOf(n7);
                    float gx = -a - b + c + d;
                    float gy = -a + b - c + d;
                    float gx2 = -ln4 - ln0 + ln5 + b;
                    float gy2 = -ln4 - ln6 + ln0 + a;
                    float gx3 = -ln0 - ln1 + b + ln2;
                    float gy3 = -ln1 - ln7 + ln3 + d;
                    float gx4 = -a - ln6 + d + ln3;
                    float gy4 = -c - a + d + ln3;
                    dir = vec2(gx + gx2 + gx3 + gx4, gy + gy2 + gy3 + gy4);
                } else {
                    dir = vec2(-b - a + c + d, -a + b - c + d);
                }
                float dirLen = sqrt(dot(dir, dir));
                if (dirLen > 0.00001) dir = dir * (1.0 / dirLen);
                vec2 nnd = one;
                vec2 lenH = nnd / vec2(max(abs(dir.x), 0.02));
                vec2 lenV = nnd / vec2(max(abs(dir.y), 0.02));
                float lobH = -1.0 / (max(abs(dir.x), 0.25) >= 0.25 ? 5.0 : 8.0);
                float lobV = -1.0 / (max(abs(dir.y), 0.25) >= 0.25 ? 5.0 : 8.0);
                vec2 dirS = sign(dir);
                float dMx = abs(dir.x) > abs(dir.y) ? dirS.x : dirS.y;
                vec4 tHm2 = FsrTap(vec2(dMx * -2.0, 0.0), dir, lenH, lobH);
                vec4 tHm1 = FsrTap(vec2(dMx * -1.0, 0.0), dir, lenH, lobH);
                vec4 tH0  = FsrTap(vec2(0.0, 0.0), dir, lenH, lobH);
                vec4 tHp1 = FsrTap(vec2(dMx *  1.0, 0.0), dir, lenH, lobH);
                vec4 tHp2 = FsrTap(vec2(dMx *  2.0, 0.0), dir, lenH, lobH);
                vec4 tVm2 = FsrTap(vec2(0.0, dMx * -2.0), dir, lenV, lobV);
                vec4 tVm1 = FsrTap(vec2(0.0, dMx * -1.0), dir, lenV, lobV);
                vec4 tV0  = FsrTap(vec2(0.0, 0.0), dir, lenV, lobV);
                vec4 tVp1 = FsrTap(vec2(0.0, dMx *  1.0), dir, lenV, lobV);
                vec4 tVp2 = FsrTap(vec2(0.0, dMx *  2.0), dir, lenV, lobV);
                vec4 sumH = tHm2 + tHm1 + tH0 + tHp1 + tHp2;
                vec4 sumV = tVm2 + tVm1 + tV0 + tVp1 + tVp2;
                sumH.rgb = sumH.rgb / vec3(max(sumH.w, 1e-5));
                sumV.rgb = sumV.rgb / vec3(max(sumV.w, 1e-5));
                float domH = step(0.5, abs(dir.x) + abs(dir.y) * 0.5);
                vec3 easu = mix((sumH.rgb + sumV.rgb) * vec3(0.5), sumH.rgb, domH);
                vec2 phase = fract(pp);
                vec3 bil = mix(mix(e0, e1, phase.x), mix(e2, e3, phase.x), phase.y);
                // Blend direccional adaptativo: en bordes rectos confiamos más en el
                // direccional; en zonas planas suavizamos con bilineal.
                float gMag = max(max(abs(a-c), abs(b-d)), max(abs(a-b), abs(c-d)));
                float edgeW = smoothstep(0.02, 0.25, gMag);
                float bilW = mix(0.35, 0.12, edgeW);
                easu = mix(easu, bil, bilW);
                // Vecindario 3x3 del píxel actual: cruz + diagonales. Reutilizado por
                // el clamp anti-overshoot, el noise-guard y el sharpening luma.
                vec3 rB = texture2D(uTexSampler, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
                vec3 rD = texture2D(uTexSampler, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
                vec3 rF = texture2D(uTexSampler, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
                vec3 rH = texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
                vec3 rNW = texture2D(uTexSampler, vTexCoord - uTexelSize).rgb;
                vec3 rNE = texture2D(uTexSampler, vTexCoord + vec2(uTexelSize.x, -uTexelSize.y)).rgb;
                vec3 rSW = texture2D(uTexSampler, vTexCoord + vec2(-uTexelSize.x, uTexelSize.y)).rgb;
                vec3 rSE = texture2D(uTexSampler, vTexCoord + uTexelSize).rgb;
                // Clamp anti-overshoot ampliado (vecindario 3x3 completo).
                vec3 mnAll = min(min(min(min(e0, e1), min(e2, e3)), min(rNW, rNE)), min(rSW, rSE));
                vec3 mxAll = max(max(max(max(e0, e1), max(e2, e3)), max(rNW, rNE)), max(rSW, rSE));
                easu = clamp(easu, mnAll, mxAll);
                vec3 rE = easu;
                float rbL = lumaOf(rB); float rdL = lumaOf(rD);
                float reL = lumaOf(rE); float rfL = lumaOf(rF); float rhL = lumaOf(rH);
                float nz = 0.25*rbL+0.25*rdL+0.25*rfL+0.25*rhL-reL;
                float maxRL = max(max(rbL, rdL), max(rfL, rhL));
                float minRL = min(min(rbL, rdL), min(rfL, rhL));
                nz = clamp(abs(nz)/max(maxRL-minRL, 0.0001), 0.0, 1.0);
                nz = -0.5*nz+1.0;
                // Noise-guard: detecta grano con la varianza local y reduce el
                // sharpening donde amplificaría ruido.
                float varL =
                      abs(lumaOf(rNW)-reL)+abs(lumaOf(rNE)-reL)+
                      abs(lumaOf(rSW)-reL)+abs(lumaOf(rSE)-reL)+
                      abs(rbL-reL)+abs(rdL-reL)+abs(rfL-reL)+abs(rhL-reL);
                float localVar = varL * (1.0/8.0);
                float noiseGuard = 1.0 - smoothstep(0.02, 0.22, localVar);
                noiseGuard = noiseGuard * 0.5 + 0.5;
                nz *= noiseGuard;
                float mn8R=min(min(rB.r,rD.r),min(min(rF.r,rH.r),min(min(rNW.r,rNE.r),min(rSW.r,rSE.r))));
                float mn8G=min(min(rB.g,rD.g),min(min(rF.g,rH.g),min(min(rNW.g,rNE.g),min(rSW.g,rSE.g))));
                float mn8B=min(min(rB.b,rD.b),min(min(rF.b,rH.b),min(min(rNW.b,rNE.b),min(rSW.b,rSE.b))));
                float mx8R=max(max(rB.r,rD.r),max(max(rF.r,rH.r),max(max(rNW.r,rNE.r),max(rSW.r,rSE.r))));
                float mx8G=max(max(rB.g,rD.g),max(max(rF.g,rH.g),max(max(rNW.g,rNE.g),max(rSW.g,rSE.g))));
                float mx8B=max(max(rB.b,rD.b),max(max(rF.b,rH.b),max(max(rNW.b,rNE.b),max(rSW.b,rSE.b))));
                float hitMinR=min(mn8R,rE.r)/(8.0*mx8R+0.0001);
                float hitMinG=min(mn8G,rE.g)/(8.0*mx8G+0.0001);
                float hitMinB=min(mn8B,rE.b)/(8.0*mx8B+0.0001);
                float hitMaxR=(1.0-max(mx8R,rE.r))/(8.0*mn8R-8.0+0.0001);
                float hitMaxG=(1.0-max(mx8G,rE.g))/(8.0*mn8G-8.0+0.0001);
                float hitMaxB=(1.0-max(mx8B,rE.b))/(8.0*mn8B-8.0+0.0001);
                float lobe=max(-FSR_RCAS_LIMIT,min(max(max(max(-hitMinR,hitMaxR),max(-hitMinG,hitMaxG)),max(-hitMinB,hitMaxB)),0.0));
                lobe*=nz;
                lobe*=sharpStops();
                // Sharpening en luma con croma intacta (sin halos de color).
                float lumaNE = lumaOf(rE);
                float lumaB = lumaOf(rB); float lumaD = lumaOf(rD);
                float lumaF = lumaOf(rF); float lumaH = lumaOf(rH);
                float rcpL = 1.0 / (4.0 * lobe + 1.0);
                float sharpL = (lobe * (lumaB + lumaD + lumaH + lumaF) + lumaNE) * rcpL;
                vec3 chromaE = rE - vec3(lumaNE);
                vec3 rcasResult = vec3(sharpL) + chromaE;
                // Clamp final del RCAS contra el vecindario 3x3 completo.
                rcasResult = clamp(rcasResult, vec3(mn8R, mn8G, mn8B), vec3(mx8R, mx8G, mx8B));
                vec3 result = mix(easu, rcasResult, 0.7);
                vec3 demoRgb = clamp(result, 0.0, 1.0);
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { demoRgb = texture2D(uTexSampler, vTexCoord).rgb; }
                gl_FragColor = vec4(demoRgb, 1.0);
            }
        """
    }
}
