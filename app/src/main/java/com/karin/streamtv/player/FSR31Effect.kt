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

class FSR31Effect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return FSR31Program(context, useHdr)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

class FSR31Program(
    context: Context,
    useHdr: Boolean,
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
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
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
            vec3 lumW = vec3(0.2126, 0.7152, 0.0722);
            float luma(vec3 c) { return dot(c, lumW); }
            vec4 FsrTap(vec2 off, vec2 dir, vec2 len, float lob) {
                vec2 pos = vTexCoord + dir * off;
                vec2 v = off * len;
                float w = 0.5 - abs(v.x) - abs(v.y);
                v += 0.5;
                return texture2D(uTexSampler, pos) * exp2(lob * max(abs(v.x), abs(v.y)));
            }
            void main() {
                vec2 inputSize = vec2(textureSize(uTexSampler, 0));
                vec2 pp = vTexCoord * inputSize - 0.5;
                vec2 fp = floor(pp);
                vec2 p0 = (fp + 0.5) / inputSize;
                vec2 one = 1.0 / inputSize;
                vec3 e0 = texture2D(uTexSampler, p0).rgb;
                vec3 e1 = texture2D(uTexSampler, p0 + vec2(one.x, 0.0)).rgb;
                vec3 e2 = texture2D(uTexSampler, p0 + vec2(0.0, one.y)).rgb;
                vec3 e3 = texture2D(uTexSampler, p0 + one).rgb;
                float a = luma(e0); float b = luma(e1); float c = luma(e2); float d = luma(e3);
                vec2 dir = vec2(-b - a + c + d, -a + b - c + d);
                float dirLen = sqrt(dot(dir, dir));
                if (dirLen > 0.00001) dir /= dirLen;
                vec2 nnd = one;
                vec2 lenH = nnd / max(abs(dir.x), 0.02);
                vec2 lenV = nnd / max(abs(dir.y), 0.02);
                float lobH = -1.0 / (abs(dir.x) >= 0.25 ? 5.0 : 8.0);
                float lobV = -1.0 / (abs(dir.y) >= 0.25 ? 5.0 : 8.0);
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
                sumH.rgb /= max(sumH.w, 1e-5);
                sumV.rgb /= max(sumV.w, 1e-5);
                float domH = step(0.5, abs(dir.x) + abs(dir.y) * 0.5);
                vec3 result = mix((sumH.rgb + sumV.rgb) * 0.5, sumH.rgb, domH);
                vec2 phase = fract(pp);
                vec3 bil = mix(mix(e0, e1, phase.x), mix(e2, e3, phase.x), phase.y);
                result = mix(result, bil, 0.35);
                vec3 mn = min(min(e0, e1), min(e2, e3));
                vec3 mx = max(max(e0, e1), max(e2, e3));
                result = clamp(result, mn, mx);
                vec3 b = texture2D(uTexSampler, vTexCoord + vec2(0.0, -one.y)).rgb;
                vec3 d2 = texture2D(uTexSampler, vTexCoord + vec2(-one.x, 0.0)).rgb;
                vec3 e = result;
                vec3 f = texture2D(uTexSampler, vTexCoord + vec2(one.x, 0.0)).rgb;
                vec3 h = texture2D(uTexSampler, vTexCoord + vec2(0.0, one.y)).rgb;
                float bL = luma(b); float dL = luma(d2); float eL = luma(e); float fL = luma(f); float hL = luma(h);
                float nz = 0.25*bL + 0.25*dL + 0.25*fL + 0.25*hL - eL;
                float maxL = max(max(bL, dL), max(fL, hL));
                float minL = min(min(bL, dL), min(fL, hL));
                nz = clamp(abs(nz)/max(maxL-minL, 0.0001), 0.0, 1.0);
                nz = -0.5*nz + 1.0;
                float mn4R=min(min(b.r,d2.r),min(f.r,h.r));
                float mn4G=min(min(b.g,d2.g),min(f.g,h.g));
                float mn4B=min(min(b.b,d2.b),min(f.b,h.b));
                float mx4R=max(max(b.r,d2.r),max(f.r,h.r));
                float mx4G=max(max(b.g,d2.g),max(f.g,h.g));
                float mx4B=max(max(b.b,d2.b),max(f.b,h.b));
                float hitMinR=min(mn4R,e.r)/(4.0*mx4R+0.0001);
                float hitMinG=min(mn4G,e.g)/(4.0*mx4G+0.0001);
                float hitMinB=min(mn4B,e.b)/(4.0*mx4B+0.0001);
                float hitMaxR=(1.0-max(mx4R,e.r))/(4.0*mn4R-4.0+0.0001);
                float hitMaxG=(1.0-max(mx4G,e.g))/(4.0*mn4G-4.0+0.0001);
                float hitMaxB=(1.0-max(mx4B,e.b))/(4.0*mn4B-4.0+0.0001);
                float lobe=max(-0.25,min(max(max(max(-hitMinR,hitMaxR),max(-hitMinG,hitMaxG)),max(-hitMinB,hitMaxB)),0.0));
                lobe*=nz;
                float sharpness = 0.6;
                lobe *= sharpness;
                float rcpL=1.0/(4.0*lobe+1.0);
                vec3 sharp = vec3(
                    (lobe*b.r+lobe*d2.r+lobe*h.r+lobe*f.r+e.r)*rcpL,
                    (lobe*b.g+lobe*d2.g+lobe*h.g+lobe*f.g+e.g)*rcpL,
                    (lobe*b.b+lobe*d2.b+lobe*h.b+lobe*f.b+e.b)*rcpL
                );
                float lumaS = dot(sharp, lumW);
                vec3 grayS = vec3(lumaS);
                vec3 chromaS = sharp - grayS;
                float lumaE = dot(e, lumW);
                vec3 grayE = vec3(lumaE);
                vec3 chromaE = e - grayE;
                vec3 refined = grayS + chromaE * (length(chromaS) / max(length(chromaE), 1e-5) * 0.5 + 0.5);
                gl_FragColor = vec4(mix(sharp, refined, 0.4), 1.0);
            }
        """
    }
}
