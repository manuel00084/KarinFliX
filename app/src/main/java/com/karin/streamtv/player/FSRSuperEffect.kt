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
 * Escalador FSR unificado: combina el upscale direccional EASU (con gradiente rico
 * de 8 taps de FSR 1.0/4.0 + adaptación por fuerza de borde) con el afilado RCAS
 * integrado en un solo pase. Reemplaza las 3 variantes separadas (1.0/3.1/4.0).
 */
class FSRSuperEffect(private var sharpness: Float = 0.6f) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return FSRSuperProgram(context, useHdr, sharpness)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = sharpness <= 0f

    // Para sliders en tiempo real sin recompilar
    fun updateSharpness(newSharpness: Float) { sharpness = newSharpness.coerceIn(0f, 1f) }
}

class FSRSuperProgram(
    context: Context,
    useHdr: Boolean,
    private var sharpness: Float,
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
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            glProgram.setFloatUniform("uSharpness", sharpness)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    // Para sliders en tiempo real sin recompilar
    fun updateSharpness(newSharpness: Float) { sharpness = newSharpness.coerceIn(0f, 1f) }

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
            float lumaOf(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            vec4 FsrTap(vec2 off, vec2 dir, vec2 len, float lob) {
                vec2 pos = vTexCoord + dir * off;
                vec2 v = off * len;
                float w = 0.5 - abs(v.x) - abs(v.y);
                v += 0.5;
                return texture2D(uTexSampler, pos).rgba * exp2(lob * max(abs(v.x), abs(v.y)));
            }
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
                vec2 dir = vec2(-b - a + c + d, -a + b - c + d);
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
                // Mejora 4: blend direccional adaptativo. En bordes rectos (alto contraste
                // de gradiente) confiamos más en el direccional; en zonas planas/diagonales
                // suavizamos con bilineal para evitar escalones. (FSR 2.0-style)
                float gMag = max(max(abs(a-c), abs(b-d)), max(abs(a-b), abs(c-d)));
                float edgeW = smoothstep(0.02, 0.25, gMag);
                float bilW = mix(0.35, 0.12, edgeW);
                easu = mix(easu, bil, bilW);
                // Muestreamos el vecindario 3x3 del pixel actual UNA sola vez: la cruz
                // (rB, rD, rF, rH) + diagonales (rNW, rNE, rSW, rSE). Reutilizado por el
                // clamp anti-overshoot, el noise-guard y el sharpening luma.
                vec3 rB = texture2D(uTexSampler, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
                vec3 rD = texture2D(uTexSampler, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
                vec3 rF = texture2D(uTexSampler, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
                vec3 rH = texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
                vec3 rNW = texture2D(uTexSampler, vTexCoord - uTexelSize).rgb;
                vec3 rNE = texture2D(uTexSampler, vTexCoord + vec2(uTexelSize.x, -uTexelSize.y)).rgb;
                vec3 rSW = texture2D(uTexSampler, vTexCoord + vec2(-uTexelSize.x, uTexelSize.y)).rgb;
                vec3 rSE = texture2D(uTexSampler, vTexCoord + uTexelSize).rgb;
                // Mejora 2: clamp anti-overshoot ampliado del EASU. Incluimos las diagonales
                // (vecindario 3x3 del origen e0-e3) para cerrar más contra halos sin perder
                // nitidez. (FSR 2.0 clampa contra un barrio más amplio)
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
                // Mejora 1: noise-guard reforzado. La varianza local (incluyendo diagonales)
                // detecta grano/textura plana de anime comprimido y reduce el sharpening
                // donde amplificaría ruido. (FSR 2.0 noise-guard)
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
                float lobe=max(-0.25,min(max(max(max(-hitMinR,hitMaxR),max(-hitMinG,hitMaxG)),max(-hitMinB,hitMaxB)),0.0));
                lobe*=nz;
                float sharpAmt = uSharpness * 0.01 + 0.25;
                lobe*=sharpAmt;
                // Mejora 3: sharpening estrictamente en luma. Aplicamos el RCAS solo a la
                // luma del vecindario y reconstruimos el RGB con la croma original intacta,
                // evitando halos de color en líneas finas de anime (FSR 2.0 preserva croma).
                float lumaNE = lumaOf(rE);
                float lumaB = lumaOf(rB); float lumaD = lumaOf(rD);
                float lumaF = lumaOf(rF); float lumaH = lumaOf(rH);
                float rcpL = 1.0 / (4.0 * lobe + 1.0);
                float sharpL = (lobe * (lumaB + lumaD + lumaH + lumaF) + lumaNE) * rcpL;
                // Croma original intacta; re-sintetizamos el color con la luma sharpened.
                // La preservación de tono de 3.1/4.0 se conserva: al operar solo sobre luma,
                // el hue no se distorsiona (equivalente al refinado tonal que ya teníamos).
                vec3 chromaE = rE - vec3(lumaNE);
                vec3 rcasResult = vec3(sharpL) + chromaE;
                // Mejora 2: clamp final del RCAS contra el vecindario 3x3 completo
                // (incluye diagonales), canal por canal, para evitar halos sin perder tono.
                rcasResult = clamp(rcasResult, vec3(mn8R, mn8G, mn8B), vec3(mx8R, mx8G, mx8B));
                vec3 result = mix(easu, rcasResult, 0.7);
                gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
            }
        """
    }
}
