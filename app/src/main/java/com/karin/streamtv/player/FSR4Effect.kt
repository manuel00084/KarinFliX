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

class FSR4Effect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return FSR4Program(context, useHdr)
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

class FSR4Program(
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
                vec3 n0 = texture2D(uTexSampler, p0 + vec2(-one.x, 0.0)).rgb;
                vec3 n1 = texture2D(uTexSampler, p0 + vec2(0.0, -one.y)).rgb;
                vec3 n2 = texture2D(uTexSampler, p0 + vec2(2.0*one.x, 0.0)).rgb;
                vec3 n3 = texture2D(uTexSampler, p0 + vec2(0.0, 2.0*one.y)).rgb;
                vec3 n4 = texture2D(uTexSampler, p0 + vec2(-one.x, -one.y)).rgb;
                vec3 n5 = texture2D(uTexSampler, p0 + vec2(one.x, -one.y)).rgb;
                vec3 n6 = texture2D(uTexSampler, p0 + vec2(-one.x, one.y)).rgb;
                vec3 n7 = texture2D(uTexSampler, p0 + vec2(one.x, one.y)).rgb;
                float la=luma(e0),lb=luma(e1),lc=luma(e2),ld=luma(e3);
                float ln0=luma(n0),ln1=luma(n1),ln2=luma(n2),ln3=luma(n3);
                float ln4=luma(n4),ln5=luma(n5),ln6=luma(n6),ln7=luma(n7);
                float gx = -la-lb+lc+ld;
                float gy = -la+lb-lc+ld;
                float gx2 = -ln4-ln0+ln5+lb;
                float gy2 = -ln4-ln6+ln0+la;
                float gx3 = -ln0-ln1+lb+ln2;
                float gy3 = -ln1-ln7+ln3+ld;
                float gx4 = -la-ln6+ld+ln3;
                float gy4 = -lc-la+ld+ln3;
                vec2 dir = vec2(gx+gx2+gx3+gx4, gy+gy2+gy3+gy4);
                float dirLen = sqrt(dot(dir, dir));
                if (dirLen > 0.00001) dir /= dirLen;
                float contrast = (max(max(la,lb),max(lc,ld)) - min(min(la,lb),min(lc,ld)));
                float edgeStrength = smoothstep(0.05, 0.4, contrast);
                vec2 nnd = one;
                vec2 lenH = nnd / max(abs(dir.x), 0.02);
                vec2 lenV = nnd / max(abs(dir.y), 0.02);
                float lobH = -1.0 / (abs(dir.x) >= 0.25 ? 4.0 : 7.0);
                float lobV = -1.0 / (abs(dir.y) >= 0.25 ? 4.0 : 7.0);
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
                float adaptiveMix = mix(0.35, 0.15, edgeStrength);
                result = mix(result, bil, adaptiveMix);
                vec3 mn = min(min(n4, min(n0, n1)), min(min(e0, e1), min(e2, e3)));
                vec3 mx = max(max(n7, max(n2, n3)), max(max(e0, e1), max(e2, e3)));
                result = clamp(result, mn, mx);
                vec3 b2 = texture2D(uTexSampler, vTexCoord + vec2(0.0, -one.y)).rgb;
                vec3 d3 = texture2D(uTexSampler, vTexCoord + vec2(-one.x, 0.0)).rgb;
                vec3 e = result;
                vec3 f2 = texture2D(uTexSampler, vTexCoord + vec2(one.x, 0.0)).rgb;
                vec3 h2 = texture2D(uTexSampler, vTexCoord + vec2(0.0, one.y)).rgb;
                float bL=luma(b2),dL=luma(d3),eL=luma(e),fL=luma(f2),hL=luma(h2);
                float nz = 0.25*bL+0.25*dL+0.25*fL+0.25*hL-eL;
                float maxLv = max(max(bL, dL), max(fL, hL));
                float minLv = min(min(bL, dL), min(fL, hL));
                nz = clamp(abs(nz)/max(maxLv-minLv, 0.0001), 0.0, 1.0);
                nz = -0.5*nz+1.0;
                float mn4R=min(min(b2.r,d3.r),min(f2.r,h2.r));
                float mn4G=min(min(b2.g,d3.g),min(f2.g,h2.g));
                float mn4B=min(min(b2.b,d3.b),min(f2.b,h2.b));
                float mx4R=max(max(b2.r,d3.r),max(f2.r,h2.r));
                float mx4G=max(max(b2.g,d3.g),max(f2.g,h2.g));
                float mx4B=max(max(b2.b,d3.b),max(f2.b,h2.b));
                float hitMinR=min(mn4R,e.r)/(4.0*mx4R+0.0001);
                float hitMinG=min(mn4G,e.g)/(4.0*mx4G+0.0001);
                float hitMinB=min(mn4B,e.b)/(4.0*mx4B+0.0001);
                float hitMaxR=(1.0-max(mx4R,e.r))/(4.0*mn4R-4.0+0.0001);
                float hitMaxG=(1.0-max(mx4G,e.g))/(4.0*mn4G-4.0+0.0001);
                float hitMaxB=(1.0-max(mx4B,e.b))/(4.0*mn4B-4.0+0.0001);
                float lobe=max(-0.25,min(max(max(max(-hitMinR,hitMaxR),max(-hitMinG,hitMaxG)),max(-hitMinB,hitMaxB)),0.0));
                lobe*=nz;
                float sharpness = 0.7;
                lobe *= sharpness;
                float rcpL=1.0/(4.0*lobe+1.0);
                vec3 sharp = vec3(
                    (lobe*b2.r+lobe*d3.r+lobe*h2.r+lobe*f2.r+e.r)*rcpL,
                    (lobe*b2.g+lobe*d3.g+lobe*h2.g+lobe*f2.g+e.g)*rcpL,
                    (lobe*b2.b+lobe*d3.b+lobe*h2.b+lobe*f2.b+e.b)*rcpL
                );
                float lumaS = dot(sharp, lumW);
                vec3 grayS = vec3(lumaS);
                vec3 chromaS = sharp - grayS;
                float lumaE = dot(e, lumW);
                vec3 grayE = vec3(lumaE);
                vec3 chromaE = e - grayE;
                vec3 refined = grayS + chromaE * (length(chromaS) / max(length(chromaE), 1e-5) * 0.5 + 0.5);
                gl_FragColor = vec4(mix(sharp, refined, 0.35), 1.0);
            }
        """
    }
}
