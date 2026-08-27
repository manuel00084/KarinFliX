package com.karin.streamtv.player

object Shaders {
    val blitFragmentShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTexCoord);
            }
        """

    val bicubicFragmentShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            float cubic(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return -0.5*x3 + x2 - 0.5*x;
            }
            float cubic2(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return 1.5*x3 - 2.5*x2 + 1.0;
            }
            float cubic3(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return -1.5*x3 + 2.0*x2 + 0.5*x;
            }
            float cubic4(float x) {
                float x2 = x * x;
                float x3 = x2 * x;
                return 0.5*x3 - 0.5*x2;
            }
            vec4 textureBicubic(sampler2D tex, vec2 texCoords, vec2 texelSize) {
                vec2 texel = texCoords / texelSize - 0.5;
                vec2 f = fract(texel);
                vec2 texelFloor = floor(texel);
                vec4 cx = vec4(cubic(f.x), cubic2(f.x), cubic3(f.x), cubic4(f.x));
                vec4 cy = vec4(cubic(f.y), cubic2(f.y), cubic3(f.y), cubic4(f.y));
                vec4 c = cx.x * (cy.x * texture2D(tex, (texelFloor + vec2(-1.0, -1.0)) * texelSize) +
                                 cy.y * texture2D(tex, (texelFloor + vec2(-1.0, 0.0)) * texelSize) +
                                 cy.z * texture2D(tex, (texelFloor + vec2(-1.0, 1.0)) * texelSize) +
                                 cy.w * texture2D(tex, (texelFloor + vec2(-1.0, 2.0)) * texelSize));
                c += cx.y * (cy.x * texture2D(tex, (texelFloor + vec2(0.0, -1.0)) * texelSize) +
                             cy.y * texture2D(tex, (texelFloor + vec2(0.0, 0.0)) * texelSize) +
                             cy.z * texture2D(tex, (texelFloor + vec2(0.0, 1.0)) * texelSize) +
                             cy.w * texture2D(tex, (texelFloor + vec2(0.0, 2.0)) * texelSize));
                c += cx.z * (cy.x * texture2D(tex, (texelFloor + vec2(1.0, -1.0)) * texelSize) +
                             cy.y * texture2D(tex, (texelFloor + vec2(1.0, 0.0)) * texelSize) +
                             cy.z * texture2D(tex, (texelFloor + vec2(1.0, 1.0)) * texelSize) +
                             cy.w * texture2D(tex, (texelFloor + vec2(1.0, 2.0)) * texelSize));
                c += cx.w * (cy.x * texture2D(tex, (texelFloor + vec2(2.0, -1.0)) * texelSize) +
                             cy.y * texture2D(tex, (texelFloor + vec2(2.0, 0.0)) * texelSize) +
                             cy.z * texture2D(tex, (texelFloor + vec2(2.0, 1.0)) * texelSize) +
                             cy.w * texture2D(tex, (texelFloor + vec2(2.0, 2.0)) * texelSize));
                return c;
            }
            void main() {
                vec4 c = textureBicubic(uTex, vTexCoord, uTexel);
                // Anti-ringing: clamp al rango del vecindario 2x2 más cercano.
                vec2 base = (floor(vTexCoord / uTexel - 0.5) + 0.5) * uTexel;
                vec4 mn = min(min(texture2D(uTex, base),
                                  texture2D(uTex, base + vec2(uTexel.x, 0.0))),
                              min(texture2D(uTex, base + vec2(0.0, uTexel.y)),
                                  texture2D(uTex, base + uTexel)));
                vec4 mx = max(max(texture2D(uTex, base),
                                  texture2D(uTex, base + vec2(uTexel.x, 0.0))),
                              max(texture2D(uTex, base + vec2(0.0, uTexel.y)),
                                  texture2D(uTex, base + uTexel)));
                gl_FragColor = clamp(c, mn, mx);
            }
        """

    val dogLumaShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            void main() {
                vec4 c = texture2D(uTex, vTexCoord);
                float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                gl_FragColor = vec4(luma, 0.0, 0.0, 1.0);
            }
        """

    val dogGaussXShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            float max3v(float a, float b, float c) { return max(max(a, b), c); }
            float min3v(float a, float b, float c) { return min(min(a, b), c); }
            vec2 minmax3(vec2 pos, vec2 d) {
                float a = texture2D(uTex, pos - d).x;
                float b = texture2D(uTex, pos).x;
                float c = texture2D(uTex, pos + d).x;
                return vec2(min3v(a, b, c), max3v(a, b, c));
            }
            float lumGaussian7(vec2 pos, vec2 d) {
                float g = (texture2D(uTex, pos - (d + d)).x + texture2D(uTex, pos + (d + d)).x) * 0.06136;
                g += (texture2D(uTex, pos - d).x + texture2D(uTex, pos + d).x) * 0.24477;
                g += texture2D(uTex, pos).x * 0.38774;
                return g;
            }
            void main() {
                vec2 d = vec2(uTexel.x, 0.0);
                gl_FragColor = vec4(lumGaussian7(vTexCoord, d), minmax3(vTexCoord, d), 1.0);
            }
        """

    val dogGaussYShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            float max3v(float a, float b, float c) { return max(max(a, b), c); }
            float min3v(float a, float b, float c) { return min(min(a, b), c); }
            vec2 minmax3(vec2 pos, vec2 d) {
                float a0 = texture2D(uTex, pos - d).y;
                float b0 = texture2D(uTex, pos).y;
                float c0 = texture2D(uTex, pos + d).y;
                float a1 = texture2D(uTex, pos - d).z;
                float b1 = texture2D(uTex, pos).z;
                float c1 = texture2D(uTex, pos + d).z;
                return vec2(min3v(a0, b0, c0), max3v(a1, b1, c1));
            }
            float lumGaussian7(vec2 pos, vec2 d) {
                float g = (texture2D(uTex, pos - (d + d)).x + texture2D(uTex, pos + (d + d)).x) * 0.06136;
                g += (texture2D(uTex, pos - d).x + texture2D(uTex, pos + d).x) * 0.24477;
                g += texture2D(uTex, pos).x * 0.38774;
                return g;
            }
            void main() {
                vec2 d = vec2(0.0, uTexel.y);
                gl_FragColor = vec4(lumGaussian7(vTexCoord, d), minmax3(vTexCoord, d), 1.0);
            }
        """

    val dogApplyShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uInput;
            uniform sampler2D uGauss;
            uniform float uStrength;
            void main() {
                float lumaOrig = dot(texture2D(uInput, vTexCoord).rgb, vec3(0.299, 0.587, 0.114));
                vec4 gauss = texture2D(uGauss, vTexCoord);
                float diff = lumaOrig - gauss.x;
                float cc = clamp(diff * uStrength + lumaOrig, gauss.y, gauss.z) - lumaOrig;
                vec4 inCol = texture2D(uInput, vTexCoord);
                gl_FragColor = vec4(inCol.rgb + vec3(cc), 1.0);
            }
        """

    val fsrEasuShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uInputSize;
            uniform vec2 uOutputSize;

            // FSR 1.0 EASU (reconstrucción anisotrópica tipo FSR 4/EDA, base espacial
            // que FSR 3.1/4 reutilizan). Grid 4x4 con direccionamiento por gradiente
            // ponderado + kernel Lanczos2 direccional + clamp anti-overshoot.
            // Taps desplegados manualmente (sin bucles) por compatibilidad GLES 2.0.
            vec4 FsrTaps(vec2 pos) { return texture2D(uTex, pos); }
            vec4 FsrTap(vec2 off, vec2 dir, vec2 len, float lob) {
                vec2 pos = vTexCoord + dir * off;
                vec2 v = off * len;
                float w = 0.5 - abs(v.x) - abs(v.y);
                v += 0.5;
                return FsrTaps(pos).rgba * exp2(lob * max(abs(v.x), abs(v.y)));
            }
            void main() {
                vec2 pp = vTexCoord * uInputSize - 0.5;
                vec2 fp = floor(pp);
                vec2 p0 = (fp + 0.5) / uInputSize;
                vec2 one = 1.0 / uInputSize;
                vec2 tn = uInputSize / uOutputSize;

                // 4x4 local
                vec3 e0 = texture2D(uTex, p0).rgb;
                vec3 e1 = texture2D(uTex, p0 + vec2(one.x, 0.0)).rgb;
                vec3 e2 = texture2D(uTex, p0 + vec2(0.0, one.y)).rgb;
                vec3 e3 = texture2D(uTex, p0 + one).rgb;

                float a = dot(e0, vec3(0.2126, 0.7152, 0.0722));
                float b = dot(e1, vec3(0.2126, 0.7152, 0.0722));
                float c = dot(e2, vec3(0.2126, 0.7152, 0.0722));
                float d = dot(e3, vec3(0.2126, 0.7152, 0.0722));

                // Dirección anisotrópica (papel Yc1r)
                vec2 dir = vec2(-b - a + c + d, -a + b - c + d);
                float dirLen = sqrt(dot(dir, dir));
                if (dirLen > 0.00001) dir /= dirLen;
                vec2 nnd = tn;
                vec2 lenH = nnd / max(abs(dir.x), 0.02);
                vec2 lenV = nnd / max(abs(dir.y), 0.02);
                float lobH = -1.0 / (abs(dir.x) >= 0.25 ? 5.0 : 8.0);
                float lobV = -1.0 / (abs(dir.y) >= 0.25 ? 5.0 : 8.0);
                vec2 dirS = sign(dir);
                float dMx = abs(dir.x) > abs(dir.y) ? dirS.x : dirS.y;

                // Taps horizontales (a lo largo del eje X dominante)
                vec4 tHm2 = FsrTap(vec2(dMx * -2.0, 0.0), dir, lenH, lobH);
                vec4 tHm1 = FsrTap(vec2(dMx * -1.0, 0.0), dir, lenH, lobH);
                vec4 tH0  = FsrTap(vec2(0.0, 0.0), dir, lenH, lobH);
                vec4 tHp1 = FsrTap(vec2(dMx *  1.0, 0.0), dir, lenH, lobH);
                vec4 tHp2 = FsrTap(vec2(dMx *  2.0, 0.0), dir, lenH, lobH);

                // Taps verticales
                vec4 tVm2 = FsrTap(vec2(0.0, dMx * -2.0), dir, lenV, lobV);
                vec4 tVm1 = FsrTap(vec2(0.0, dMx * -1.0), dir, lenV, lobV);
                vec4 tV0  = FsrTap(vec2(0.0, 0.0), dir, lenV, lobV);
                vec4 tVp1 = FsrTap(vec2(0.0, dMx *  1.0), dir, lenV, lobV);
                vec4 tVp2 = FsrTap(vec2(0.0, dMx *  2.0), dir, lenV, lobV);

                vec4 sumH = tHm2 + tHm1 + tH0 + tHp1 + tHp2;
                vec4 sumV = tVm2 + tVm1 + tV0 + tVp1 + tVp2;
                sumH.rgb /= max(sumH.w, 1e-5);
                sumV.rgb /= max(sumV.w, 1e-5);

                // Dirección dominante + blend
                float domH = step(0.5, abs(dir.x) + abs(dir.y) * 0.5);
                vec3 result = mix((sumH.rgb + sumV.rgb) * 0.5, sumH.rgb, domH);

                // Offset de fase para alinear pixel con la celda (corrección de fase)
                vec2 phase = fract(pp);
                vec3 p0r = e0; vec3 p1r = e1; vec3 p2r = e2; vec3 p3r = e3;
                vec3 bil = mix(mix(p0r, p1r, phase.x), mix(p2r, p3r, phase.x), phase.y);
                result = mix(result, bil, 0.35);

                // Clamp anti-overshoot (estilo FSR 3.1: no crear halos)
                vec3 mn = min(min(e0, e1), min(e2, e3));
                vec3 mx = max(max(e0, e1), max(e2, e3));
                result = clamp(result, mn, mx);

                gl_FragColor = vec4(result, 1.0);
            }
        """

    val fsrRcasShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uSharpness;
            void main() {
                vec2 sp = vTexCoord;
                vec3 b = texture2D(uTex, sp + vec2(0.0, -uTexel.y)).rgb;
                vec3 d = texture2D(uTex, sp + vec2(-uTexel.x, 0.0)).rgb;
                vec3 e = texture2D(uTex, sp).rgb;
                vec3 f = texture2D(uTex, sp + vec2(uTexel.x, 0.0)).rgb;
                vec3 h = texture2D(uTex, sp + vec2(0.0, uTexel.y)).rgb;
                float bL = dot(b, vec3(0.2126, 0.7152, 0.0722));
                float dL = dot(d, vec3(0.2126, 0.7152, 0.0722));
                float eL = dot(e, vec3(0.2126, 0.7152, 0.0722));
                float fL = dot(f, vec3(0.2126, 0.7152, 0.0722));
                float hL = dot(h, vec3(0.2126, 0.7152, 0.0722));
                float nz = 0.25*bL+0.25*dL+0.25*fL+0.25*hL-eL;
                float maxL = max(max(bL, dL), max(fL, hL));
                float minL = min(min(bL, dL), min(fL, hL));
                nz = clamp(abs(nz)/max(maxL-minL, 0.0001), 0.0, 1.0);
                nz = -0.5*nz+1.0;
                float mn4R=min(min(b.r,d.r),min(f.r,h.r));
                float mn4G=min(min(b.g,d.g),min(f.g,h.g));
                float mn4B=min(min(b.b,d.b),min(f.b,h.b));
                float mx4R=max(max(b.r,d.r),max(f.r,h.r));
                float mx4G=max(max(b.g,d.g),max(f.g,h.g));
                float mx4B=max(max(b.b,d.b),max(f.b,h.b));
                float hitMinR=min(mn4R,e.r)/(4.0*mx4R+0.0001);
                float hitMinG=min(mn4G,e.g)/(4.0*mx4G+0.0001);
                float hitMinB=min(mn4B,e.b)/(4.0*mx4B+0.0001);
                float hitMaxR=(1.0-max(mx4R,e.r))/(4.0*mn4R-4.0+0.0001);
                float hitMaxG=(1.0-max(mx4G,e.g))/(4.0*mn4G-4.0+0.0001);
                float hitMaxB=(1.0-max(mx4B,e.b))/(4.0*mn4B-4.0+0.0001);
                float lobe=max(-0.25,min(max(max(max(-hitMinR,hitMaxR),max(-hitMinG,hitMaxG)),max(-hitMinB,hitMaxB)),0.0))*uSharpness;
                lobe*=nz;
                float rcpL=1.0/(4.0*lobe+1.0);
                vec3 sharp = vec3((lobe*b.r+lobe*d.r+lobe*h.r+lobe*f.r+e.r)*rcpL,(lobe*b.g+lobe*d.g+lobe*h.g+lobe*f.g+e.g)*rcpL,(lobe*b.b+lobe*d.b+lobe*h.b+lobe*f.b+e.b)*rcpL);
                // Refinamiento FSR 4-style: preservación de tono/saturación. El sharpening
                // puede amplificar la croma creando halos de color. Escaliza el delta de
                // luma y modula el boost de croma para no distorsionar el hue.
                float lumaS = dot(sharp, vec3(0.2126, 0.7152, 0.0722));
                vec3 grayS = vec3(lumaS);
                vec3 chromaS = sharp - grayS;
                float lumaE = dot(e, vec3(0.2126, 0.7152, 0.0722));
                vec3 grayE = vec3(lumaE);
                vec3 chromaE = e - grayE;
                // Color boost limitado: conserva el hue original, escala solo magnitud.
                vec3 refined = grayS + chromaE * (length(chromaS) / max(length(chromaE), 1e-5) * 0.5 + 0.5);
                // Mezcla suave: 60% sharpened, 40% tono-preservado (sin extra de passes).
                gl_FragColor = vec4(mix(sharp, refined, 0.4), 1.0);
            }
        """

    // Acumulación temporal inspirada en FSR 2.0: reconstruye detail y reduce ruido
    // acumulando el frame anterior reproyectado con el motion vector, en resolución
    // de render (DRS). Alimenta al EASU con una fusión prev-actual que recupera
    // información estable que el upscaler espacial por sí solo perdería. Usa la misma
    // fórmula de warp (proven) que el denoise temporal del pipeline principal.
    val fsrTemporalShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;         // frame actual (DRS)
            uniform sampler2D uPrevDrs;     // frame anterior (DRS)
            uniform sampler2D uMotion;      // motion vector field
            uniform vec2 uMotionScale;      // 1/motionW, 1/motionH
            uniform vec2 uGlobalVec;        // vector global (EMA)
            uniform vec2 uInputSize;        // tamaño del frame actual
            uniform float uFactor;          // interpolación 0..1
            void main() {
                vec3 cur = texture2D(uTex, vTexCoord).rgb;
                vec3 prevRaw = texture2D(uPrevDrs, vTexCoord).rgb;
                vec4 m = texture2D(uMotion, vTexCoord);
                float conf = clamp(m.b * m.a, 0.0, 1.0);
                // Fórmula de warp idéntica a la del denoise temporal del pipeline.
                vec2 mv = mix(uGlobalVec, m.xy * 2.0 - 1.0, conf) * 16.0 * uMotionScale;
                vec2 uvPrev = clamp(vTexCoord - mv * 0.5, vec2(0.0), vec2(1.0));
                vec3 prev = texture2D(uPrevDrs, uvPrev).rgb;
                // Residual prev-actual: si hay oclusión/cambio fuerte, no confiar en prev.
                // FSR 3.1-style disocclusion rejection: clamping contra ringing + switch
                // brusco al frame actual cuando el residual supera el umbral.
                float diff = length(prev - cur);
                float trust = 1.0 - smoothstep(0.04, 0.20, diff);
                // Clamp anti-ringing: restringe el resultado a los vecinos del frame
                // actual para no "inventar" valores fantasma en zonas redescubiertas.
                vec2 t = 1.0 / uInputSize;
                vec3 mn = min(min(min(
                    texture2D(uTex, vTexCoord + vec2(0.0, -t.y)).rgb,
                    texture2D(uTex, vTexCoord + vec2(-t.x, 0.0)).rgb),
                    texture2D(uTex, vTexCoord + vec2(t.x, 0.0)).rgb),
                    texture2D(uTex, vTexCoord + vec2(0.0, t.y)).rgb);
                vec3 mx = max(max(max(
                    texture2D(uTex, vTexCoord + vec2(0.0, -t.y)).rgb,
                    texture2D(uTex, vTexCoord + vec2(-t.x, 0.0)).rgb),
                    texture2D(uTex, vTexCoord + vec2(t.x, 0.0)).rgb),
                    texture2D(uTex, vTexCoord + vec2(0.0, t.y)).rgb);
                mn = min(mn, cur);
                mx = max(mx, cur);
                vec3 clamped = clamp(mix(prev, cur, uFactor), mn, mx);
                // Disoclusión: si el residual es demasiado alto, favorece el frame actual
                float mixAmt = trust * conf * 0.30;
                vec3 fused = mix(clamped, (cur * 0.6 + clamped * 0.4), mixAmt);
                gl_FragColor = vec4(fused, 1.0);
            }
        """

    // RAVU-lite (estilo bjin/RAVU): super-resolución direccional por luma.
    // Interpolación edge-directed (EEI): detecta la dirección del borde y
    // interpola a lo largo de él, afilando el arte de línea de anime sin
    // el desenfoque del bilineal. Opera sobre luma extraída del RGB.
    val ravuLiteShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uInputSize;
            uniform float uStrength;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            void main() {
                vec2 texel = 1.0 / uInputSize;
                vec2 srcPos = vTexCoord * uInputSize;
                vec2 ip = floor(srcPos - 0.5);
                vec2 fp = srcPos - ip - 0.5;
                vec2 tl = (ip + 0.5) * texel;

                float a = luma(texture2D(uTex, tl).rgb);
                float b = luma(texture2D(uTex, tl + vec2(texel.x, 0.0)).rgb);
                float c = luma(texture2D(uTex, tl + vec2(0.0, texel.y)).rgb);
                float d = luma(texture2D(uTex, tl + texel).rgb);

                float bx = mix(a, b, fp.x);
                float by = mix(c, d, fp.x);
                float Lbil = mix(bx, by, fp.y);

                // Muestras diagonales para detección de bordes en 45°/135°.
                float dl = luma(texture2D(uTex, tl + vec2(-texel.x, texel.y)).rgb);
                float dr = luma(texture2D(uTex, tl + vec2(2.0 * texel.x, texel.y)).rgb);
                float db = luma(texture2D(uTex, tl + vec2(texel.x, 2.0 * texel.y)).rgb);
                float dt = luma(texture2D(uTex, tl + vec2(0.0, 2.0 * texel.y)).rgb);

                // 4 direcciones (H, V, ↗, ↘) edge-directed.
                float candH = mix(mix(a, b, fp.x), mix(c, d, fp.x), step(0.5, fp.y));
                float candV = mix(mix(a, c, fp.y), mix(b, d, fp.y), step(0.5, fp.x));
                float candD1 = mix(a, d, (fp.x + fp.y) * 0.5);
                float candD2 = mix(c, b, (fp.x - fp.y + 1.0) * 0.5);
                float gH = abs(a - c) + abs(b - d);
                float gV = abs(a - b) + abs(c - d);
                float gD1 = abs(b - c) + abs(dr - db);
                float gD2 = abs(a - d) + abs(dl - dt);
                float wH = 1.0 / (gH + 1e-4);
                float wV = 1.0 / (gV + 1e-4);
                float wD1 = 1.0 / (gD1 + 1e-4);
                float wD2 = 1.0 / (gD2 + 1e-4);
                float wsum = wH + wV + wD1 + wD2;
                float Ldir = (candH * wH + candV * wV + candD1 * wD1 + candD2 * wD2) / wsum;
                float Lout = mix(Lbil, Ldir, uStrength);

                vec3 base = texture2D(uTex, vTexCoord).rgb;
                float Lbase = luma(base);
                vec3 outc = base + (Lout - Lbase);
                // Clamp anti-overshoot.
                vec3 n0 = texture2D(uTex, vTexCoord + vec2(0.0, -texel.y)).rgb;
                vec3 n1 = texture2D(uTex, vTexCoord + vec2(0.0, texel.y)).rgb;
                vec3 n2 = texture2D(uTex, vTexCoord + vec2(-texel.x, 0.0)).rgb;
                vec3 n3 = texture2D(uTex, vTexCoord + vec2(texel.x, 0.0)).rgb;
                vec3 mn = min(base, min(min(n0, n1), min(n2, n3)));
                vec3 mx = max(base, max(max(n0, n1), max(n2, n3)));
                gl_FragColor = vec4(clamp(clamp(outc, mn, mx), 0.0, 1.0), 1.0);
            }
        """

    // KX Híbrido: escalador edge-directed sobre luma combinado con base bicúbica 4x4.
    // Un solo pass, sin FBOs, 0 MB RAM extra. Reconstruye bordes por dirección de
    // gradiente (como FSR/EEDI) mientras usa bicúbico suavizado para el color.
    // Clamp anti-overshoot + preservación de tono (técnicas FSR 3.1/4).
    // Opcional: refine de nitidez dentro del mismo fragment (uPass2Enabled>0.5) sin FBO.
    val kxHybridShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uInputSize;
            uniform float uSharpness;
            uniform float uPass2Enabled;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            vec3 kxBicubic4x4(vec2 uv, vec2 texel) {
                vec2 coord = uv / texel - 0.5;
                vec2 f = fract(coord);
                vec2 f2 = f * f;
                vec2 f3 = f2 * f;
                vec2 w0 = -0.5 * f3 + f2 - 0.5 * f;
                vec2 w1 = 1.5 * f3 - 2.5 * f2 + 1.0;
                vec2 w2 = -1.5 * f3 + 2.0 * f2 + 0.5 * f;
                vec2 w3 = 0.5 * f3 - 0.5 * f2;
                vec2 s = (uv - 0.5 * texel) / texel - vec2(0.5, 0.5);
                vec2 i = floor(s);
                vec2 c00 = (i + vec2(0.0, 0.0)) * texel + 0.5 * texel;
                vec2 c10 = (i + vec2(1.0, 0.0)) * texel + 0.5 * texel;
                vec2 c20 = (i + vec2(2.0, 0.0)) * texel + 0.5 * texel;
                vec2 c30 = (i + vec2(3.0, 0.0)) * texel + 0.5 * texel;
                vec3 tc0 = w0.x * texture2D(uTex, c00).rgb + w1.x * texture2D(uTex, c10).rgb
                         + w2.x * texture2D(uTex, c20).rgb + w3.x * texture2D(uTex, c30).rgb;
                vec3 tc1 = w0.x * texture2D(uTex, c00 + vec2(0.0, texel.y)).rgb
                         + w1.x * texture2D(uTex, c10 + vec2(0.0, texel.y)).rgb
                         + w2.x * texture2D(uTex, c20 + vec2(0.0, texel.y)).rgb
                         + w3.x * texture2D(uTex, c30 + vec2(0.0, texel.y)).rgb;
                vec3 tc2 = w0.x * texture2D(uTex, c00 + vec2(0.0, 2.0 * texel.y)).rgb
                         + w1.x * texture2D(uTex, c10 + vec2(0.0, 2.0 * texel.y)).rgb
                         + w2.x * texture2D(uTex, c20 + vec2(0.0, 2.0 * texel.y)).rgb
                         + w3.x * texture2D(uTex, c30 + vec2(0.0, 2.0 * texel.y)).rgb;
                vec3 tc3 = w0.x * texture2D(uTex, c00 + vec2(0.0, 3.0 * texel.y)).rgb
                         + w1.x * texture2D(uTex, c10 + vec2(0.0, 3.0 * texel.y)).rgb
                         + w2.x * texture2D(uTex, c20 + vec2(0.0, 3.0 * texel.y)).rgb
                         + w3.x * texture2D(uTex, c30 + vec2(0.0, 3.0 * texel.y)).rgb;
                vec3 col = w0.y * tc0 + w1.y * tc1 + w2.y * tc2 + w3.y * tc3;
                // Preservación de tono: conserva croma de la muestra central original.
                vec3 base = texture2D(uTex, uv).rgb;
                float bc = luma(base);
                float lc = luma(col);
                float gain = (lc - bc) * 0.8;
                col = base + vec3(gain);
                return clamp(col, 0.0, 1.0);
            }

            vec3 kxEdgeDirected(vec2 uv, vec2 texel) {
                // Grid 4x4 de luma para dirección de borde precisa.
                vec2 coord = uv / texel - 0.5;
                vec2 f = fract(coord);
                vec2 tl = (floor(coord)) * texel + 0.5 * texel;
                float L0  = luma(texture2D(uTex, tl).rgb);
                float L1  = luma(texture2D(uTex, tl + vec2(texel.x, 0.0)).rgb);
                float L2  = luma(texture2D(uTex, tl + vec2(0.0, texel.y)).rgb);
                float L3  = luma(texture2D(uTex, tl + texel).rgb);
                float L4  = luma(texture2D(uTex, tl + vec2(-texel.x, texel.y)).rgb);
                float L5  = luma(texture2D(uTex, tl + vec2(2.0 * texel.x, texel.y)).rgb);
                float L6  = luma(texture2D(uTex, tl + vec2(texel.x, 2.0 * texel.y)).rgb);
                float L7  = luma(texture2D(uTex, tl + vec2(0.0, 2.0 * texel.y)).rgb);
                float Lc  = luma(texture2D(uTex, uv).rgb);
                // 4 direcciones (H, V, ↗, ↘) edge-directed.
                float candH = mix(mix(L0, L1, f.x), mix(L2, L3, f.x), step(0.5, f.y));
                float candV = mix(mix(L0, L2, f.y), mix(L1, L3, f.y), step(0.5, f.x));
                float candD1 = mix(L0, L3, (f.x + f.y) * 0.5);
                float candD2 = mix(L2, L1, (f.x - f.y + 1.0) * 0.5);
                float gH = abs(L0 - L2) + abs(L1 - L3);
                float gV = abs(L0 - L1) + abs(L2 - L3);
                float gD1 = abs(L1 - L2) + abs(L5 - L6);
                float gD2 = abs(L0 - L3) + abs(L4 - L7);
                float wH = 1.0 / (gH + 1e-4);
                float wV = 1.0 / (gV + 1e-4);
                float wD1 = 1.0 / (gD1 + 1e-4);
                float wD2 = 1.0 / (gD2 + 1e-4);
                float wsum = wH + wV + wD1 + wD2;
                float Lout = (candH * wH + candV * wV + candD1 * wD1 + candD2 * wD2) / wsum;
                // En zonas planas (gradientes bajos) vuelve suave (luma base) para evitar
                // sobresaturación / ruido.
                float gmax = max(max(gH, gV), max(gD1, gD2));
                float mixAmt = 1.0 - clamp(gmax / (gmax + 0.02), 0.0, 1.0);
                Lout = mix(Lc, Lout, mixAmt * 0.85);
                vec3 base = texture2D(uTex, uv).rgb;
                float Lbase = luma(base);
                vec3 col = base + (Lout - Lbase);
                // Clamp anti-overshoot a vecinos 4nex.
                vec3 t = texture2D(uTex, uv + vec2(0.0, -texel.y)).rgb;
                vec3 b = texture2D(uTex, uv + vec2(0.0, texel.y)).rgb;
                vec3 l = texture2D(uTex, uv + vec2(-texel.x, 0.0)).rgb;
                vec3 r = texture2D(uTex, uv + vec2(texel.x, 0.0)).rgb;
                vec3 mn = min(base, min(min(t, b), min(l, r)));
                vec3 mx = max(base, max(max(t, b), max(l, r)));
                return clamp(clamp(col, mn, mx), 0.0, 1.0);
            }

            void main() {
                vec2 texel = 1.0 / uInputSize;
                vec2 uv = vTexCoord;
                vec3 edgeCol = kxEdgeDirected(uv, texel);
                // Híbrido real: base bicúbica suave (kxBicubic4x4) en zonas planas,
                // edge-directed en bordes.
                vec3 bic = kxBicubic4x4(uv, texel);
                float lcE = luma(edgeCol);
                float lcB = luma(bic);
                float edgeAmt = clamp(abs(lcE - lcB) * 4.0, 0.0, 1.0);
                vec3 color = mix(bic, edgeCol, edgeAmt);
                if (uPass2Enabled > 0.5) {
                    // Refine barato: unsharp mask con high-pass local (sin re-ejecutar borde).
                    vec3 n0 = texture2D(uTex, uv + vec2(0.0, -texel.y)).rgb;
                    vec3 n1 = texture2D(uTex, uv + vec2(0.0, texel.y)).rgb;
                    vec3 n2 = texture2D(uTex, uv + vec2(-texel.x, 0.0)).rgb;
                    vec3 n3 = texture2D(uTex, uv + vec2(texel.x, 0.0)).rgb;
                    float lap = lcE - (luma(n0) + luma(n1) + luma(n2) + luma(n3)) * 0.25;
                    float boost = clamp(lap * uSharpness, -0.06, 0.06);
                    color = clamp(color + vec3(boost), 0.0, 1.0);
                }
                gl_FragColor = vec4(color, 1.0);
            }
        """

    val vertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uTexMatrix;
            uniform float uVFlip;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                vTexCoord.y = mix(vTexCoord.y, 1.0 - vTexCoord.y, uVFlip);
            }
        """

    val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform samplerExternalOES uCurrTex;
            uniform sampler2D uPrevTex;
            uniform sampler2D uMotionTex;
            uniform sampler2D uBwdTex;
            uniform sampler2D uDownTex;
            uniform float uFactor;
            uniform vec2 uMotionScale;
            uniform vec2 uMotionTexel;
            uniform vec2 uTexelSize;
            uniform vec2 uDownTexel;
            uniform vec2 uGlobalVec;
            uniform float uMode;
            uniform float uInterpEnabled;
            uniform float uEnabled;
            uniform float uStatic;
            uniform float uSaturation;
            uniform float uContrast;
            uniform float uBrightness;
            uniform float uSharpness;
            uniform float uAdaptiveSharp;
            uniform float uColorBoost;
            uniform float uDenoise;
            uniform float uDeband;
            uniform float uDeblock;
            uniform float uDesRinging;
            uniform float uLocalContrast;
            uniform float uGrain;
            uniform float uGrainSeed;
            uniform float uDehaze;
            uniform float uTint;
            uniform float uHdr;
            uniform float uDetailBoost;
              uniform float uLightBoost;
              uniform float uLightBoostHdr;
             uniform int uSrcTransfer;
             uniform int uSrcPrimaries;
             uniform int uSrcRange;
             uniform int uToneCurve;
             uniform float uDepth;
             uniform int u3DMode;
             uniform float u3DStrength;
             uniform float u3DCrossfeed;
              uniform float uLowBitrateBoost;
            uniform float uDbgMode;
            uniform vec2 uVideoRes;
            uniform sampler2D uBlueNoiseTex;
            uniform vec2 uBlueNoiseSize;
            uniform float uDitherEnabled;
            uniform float uDitherStrength;
            uniform float uContentType;

            // Tipo de contenido (inspirado en el upscaling adaptativo de FSR 4):
            // ajusta el afilado según el carácter del material para no realzar
            // artefactos de compresión. 0 = general/live, 1 = anime/lineal bajo bitrate.
            float contentSharpFactor() {
                return mix(1.15, 0.65, uContentType);
            }

            vec3 adjustSaturation(vec3 c, float s) {
                float g = dot(c, vec3(0.2126, 0.7152, 0.0722));
                vec3 sat = mix(vec3(g), c, s);
                float skinMask = smoothstep(0.15, 0.08, abs(sat.r - sat.g)) * smoothstep(0.15, 0.05, sat.r - sat.b);
                skinMask *= step(0.3, sat.r) * step(sat.r, 0.75) * step(0.15, sat.g) * step(sat.g, 0.65);
                float skinProtect = 1.0 - skinMask * clamp(s - 1.0, 0.0, 1.0) * 0.4;
                return mix(sat, mix(vec3(g), c, 1.0 + (s - 1.0) * 0.6), skinProtect);
            }

            float lumaOf(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            // Profundidad (2D sin gafas): look cine con relieve perceptible.
            // 1) Viñeta suave que da atmósfera. 2) Separación de planos por contraste
            // local (unsharp): el detalle de primer plano "sobresale" del fondo.
            // 3) Pop de saturación. No es 3D estéreo real.
            vec3 depthPass(vec3 c, vec2 uv, float amt) {
                vec2 d = uv - vec2(0.5);
                float vig = 1.0 - dot(d, d) * (amt * 0.8);
                c *= clamp(vig, 0.0, 1.0);
                // Contraste local: muestrea vecinos del frame de entrada y realza
                // la desviación (detalle) para separar planos.
                vec2 t = uTexelSize;
                vec3 n = texture2D(uCurrTex, uv + vec2(0.0, t.y)).rgb;
                vec3 s = texture2D(uCurrTex, uv - vec2(0.0, t.y)).rgb;
                vec3 e = texture2D(uCurrTex, uv + vec2(t.x, 0.0)).rgb;
                vec3 w = texture2D(uCurrTex, uv - vec2(t.x, 0.0)).rgb;
                vec3 center = texture2D(uCurrTex, uv).rgb;
                vec3 mean = (n + s + e + w + center) * 0.2;
                vec3 detail = (center - mean) * (1.0 + amt * 1.8);
                c = clamp(c + detail * amt, 0.0, 1.0);
                c = adjustSaturation(c, 1.0 + amt * 0.20);
                return clamp(c, 0.0, 1.0);
            }

            // 3D Vision: conversión 2D->3D por paralaje. La profundidad es un proxy
            // de luminancia (más brillo ~ más cerca). Crea un par estéreo
            // desplazado y lo combina según el modo (anaglifo o SBS cardboard).
            vec3 make3D(vec2 uv, int mode, float strength) {
                float depth = lumaOf(texture2D(uCurrTex, uv).rgb);
                float par = (depth - 0.5) * strength * 0.05;
                vec2 off = vec2(par, 0.0);
                vec3 left = texture2D(uCurrTex, uv + off).rgb;
                vec3 right = texture2D(uCurrTex, uv - off).rgb;
                // Crossfeed estéreo: mezcla suave entre ojos para reducir ghosting/molestia.
                if (u3DCrossfeed > 0.001) {
                    vec3 lo = left + u3DCrossfeed * (right - left);
                    vec3 ro = right + u3DCrossfeed * (left - right);
                    left = lo; right = ro;
                }
                if (mode == 1) {            // Anaglifo Rojo/Cian
                    return vec3(left.r, right.g, right.b);
                } else if (mode == 2) {     // Anaglifo Rojo/Verde
                    return vec3(left.r, right.g, 0.0);
                } else if (mode == 3) {     // Anaglifo Azul/Rojo
                    return vec3(right.r, 0.0, left.b);
                }
                // mode 4: Google Cardboard (SBS) - cada mitad es un ojo
                if (uv.x < 0.5) {
                    vec2 luv = vec2(uv.x * 2.0, uv.y);
                    float d = lumaOf(texture2D(uCurrTex, luv).rgb);
                    float p = (d - 0.5) * strength * 0.05;
                    return texture2D(uCurrTex, luv + vec2(p, 0.0)).rgb;
                } else {
                    vec2 ruv = vec2((uv.x - 0.5) * 2.0, uv.y);
                    float d = lumaOf(texture2D(uCurrTex, ruv).rgb);
                    float p = (d - 0.5) * strength * 0.05;
                    return texture2D(uCurrTex, ruv - vec2(p, 0.0)).rgb;
                }
            }

            // Gamut boost estilo Splash: expande la croma (gama de colores) con
            // curva "vibrance" adaptativa. Protege highlights, sombras y piel, y
            // evita recorte limitando el factor por pixel para que el color no se aplane.
            vec3 gamutBoost(vec3 c, float s) {
                if (abs(s - 1.0) < 0.001) return c;
                float luma = lumaOf(c);
                vec3 dev = c - vec3(luma);
                float chroma = length(dev);
                float sat = chroma / max(luma * 2.0, 0.0001);
                // vibrance: colores apagados se expanden mucho, vivos apenas se tocan
                float vibrance = 1.0 - smoothstep(0.2, 0.8, sat);
                // Opción 1: boost más fuerte en tonos medios (base 0.6→0.8, vibrance 0.4→0.6)
                float f = 1.0 + (s - 1.0) * (0.8 + 0.6 * vibrance);
                // proteccion highlights (más suave: 0.6→0.4)
                float hl = smoothstep(0.7, 0.95, luma);
                f = mix(f, 1.0, hl * 0.4);
                // proteccion sombras (relajada: 0.8→0.5)
                float sh = smoothstep(0.0, 0.12, luma);
                f = mix(f, 1.0, (1.0 - sh) * 0.5);
                // proteccion tonos de piel (relajada: 0.35→0.5)
                float skinMask = smoothstep(0.15, 0.08, abs(c.r - c.g)) * smoothstep(0.15, 0.05, c.r - c.b);
                skinMask *= step(0.3, c.r) * step(c.r, 0.75) * step(0.15, c.g) * step(c.g, 0.65);
                f = mix(f, 1.0 + (s - 1.0) * 0.5, skinMask);
                // limite de gama por pixel: no deja que ningun canal rebase [0,1]
                float maxDev = max(dev.r, max(dev.g, dev.b));
                float minDev = min(dev.r, min(dev.g, dev.b));
                float fUp = (1.0 - luma) / max(maxDev, 0.00001);
                float fDn = luma / max(-minDev, 0.00001);
                f = min(f, min(fUp, fDn));
                vec3 outC = vec3(luma) + dev * f;
                // Opción 2: expansión de gamut tipo DCI-P3 (riqueza tipo cine)
                float p3 = max(s - 1.0, 0.0);
                if (p3 > 0.0) {
                    vec3 g = outC - 0.5;
                    vec3 expanded = 0.5 + g * (1.0 + p3 * 0.3);
                    float newLuma = lumaOf(expanded);
                    expanded += (luma - newLuma);
                    outC = mix(outC, expanded, 0.7);
                }
                return clamp(outC, 0.0, 1.0);
            }

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            }

            // Blue Noise Dithering: reduce banding en 8-bit cuantizando con ruido azul
            // (frecuencias altas, sin patrón visible). Temporal rotation por frame.
            // Fallback IGN si no hay textura blue noise disponible.
            float interleavedGradientNoise(vec2 uv) {
                return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
            }

            vec3 blueNoiseDither(vec3 color, vec2 fragCoord, float strength) {
                if (uDitherEnabled < 0.5 || strength < 0.001) return color;

                float noise;
                if (uBlueNoiseSize.x > 0.5) {
                    // Usar textura blue noise pre-generada (64x64 tiled)
                    vec2 noiseCoord = fragCoord / uBlueNoiseSize;
                    noise = texture2D(uBlueNoiseTex, noiseCoord).r;
                } else {
                    // Fallback: Interleaved Gradient Noise (IGN)
                    noise = interleavedGradientNoise(fragCoord);
                }
                // Aplicar dither centrado en 0
                color += (noise - 0.5) * strength * (1.0 / 255.0);
                return clamp(color, 0.0, 1.0);
            }

            // Quita el "ringing" (halo/ecos) alrededor de bordes: detecta oscilación de luma en la dirección del gradiente.
            vec3 desRinging(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 h = texel * 1.0;
                vec3 r = texture2D(uCurrTex, uv + vec2(h.x, 0.0)).rgb;
                vec3 l = texture2D(uCurrTex, uv - vec2(h.x, 0.0)).rgb;
                vec3 t = texture2D(uCurrTex, uv + vec2(0.0, h.y)).rgb;
                vec3 b = texture2D(uCurrTex, uv - vec2(0.0, h.y)).rgb;
                vec3 r2 = texture2D(uCurrTex, uv + vec2(h.x * 2.0, 0.0)).rgb;
                vec3 l2 = texture2D(uCurrTex, uv - vec2(h.x * 2.0, 0.0)).rgb;
                vec3 t2 = texture2D(uCurrTex, uv + vec2(0.0, h.y * 2.0)).rgb;
                vec3 b2 = texture2D(uCurrTex, uv - vec2(0.0, h.y * 2.0)).rgb;
                float lc = lumaOf(color);
                float lr = lumaOf(r); float ll = lumaOf(l);
                float lt = lumaOf(t); float lb = lumaOf(b);
                float lr2 = lumaOf(r2); float ll2 = lumaOf(l2);
                float lt2 = lumaOf(t2); float lb2 = lumaOf(b2);
                float oscH1 = (lr - lc) * (ll - lc);
                float oscH2 = (lr2 - lr) * (lc - lr);
                float ringH = smoothstep(0.0, 0.02, oscH1) * smoothstep(0.0, 0.02, oscH2);
                float oscV1 = (lt - lc) * (lb - lc);
                float oscV2 = (lt2 - lt) * (lc - lt);
                float ringV = smoothstep(0.0, 0.02, oscV1) * smoothstep(0.0, 0.02, oscV2);
                float ring = clamp(max(ringH, ringV), 0.0, 1.0);
                vec3 avg = (t + b + l + r) * 0.25;
                return mix(color, avg, ring * strength * 0.8);
            }

            // Ajusta la temperatura de color: positivo = cálido (rojo), negativo = frío (azul).
            vec3 applyTint(vec3 c, float t) {
                float luma = lumaOf(c);
                if (t > 0.0) {
                    c.r = mix(c.r, min(c.r + t * 0.6, 1.0), 0.8 + luma * 0.2);
                    c.g = mix(c.g, c.g + t * 0.15, 0.7);
                    c.b = mix(c.b, c.b * (1.0 - t * 0.5), 0.8);
                } else {
                    float tt = -t;
                    c.r = mix(c.r, c.r * (1.0 - tt * 0.5), 0.8);
                    c.g = mix(c.g, c.g + tt * 0.08, 0.7);
                    c.b = mix(c.b, min(c.b + tt * 0.6, 1.0), 0.8 + luma * 0.2);
                }
                return clamp(c, 0.0, 1.0);
            }

            // Deblock por CONTENIDO y periodicidad: detecta el nodo de bloque (borde de celda
            // de 8px) por salto de luma a 1px + planitud interna de cada celda + repetición del
            // salto a ±8px (firma de rejilla). Suaviza hacia la media de ambas celdas con clamp
            // al rango local: no dibuja malla sobre textura real ni crea halos.
            float gridScore(float stepC, float cellFlat, float periodic) {
                float flatFactor = 1.0 - smoothstep(0.002, 0.03, cellFlat);
                float perFactor = clamp((periodic - stepC * 0.3) / (stepC * 0.55 + 0.0001), 0.0, 1.0);
                return stepC * flatFactor * perFactor;
            }

            vec3 deblock(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 m = 1.0 / uVideoRes;
                vec3 n1 = texture2D(uCurrTex, uv + vec2(0.0, m.y)).rgb;
                vec3 n2 = texture2D(uCurrTex, uv - vec2(0.0, m.y)).rgb;
                vec3 n3 = texture2D(uCurrTex, uv + vec2(m.x, 0.0)).rgb;
                vec3 n4 = texture2D(uCurrTex, uv - vec2(m.x, 0.0)).rgb;
                vec3 f1 = texture2D(uCurrTex, uv + vec2(0.0, m.y * 4.0)).rgb;
                vec3 f2 = texture2D(uCurrTex, uv - vec2(0.0, m.y * 4.0)).rgb;
                vec3 f3 = texture2D(uCurrTex, uv + vec2(m.x * 4.0, 0.0)).rgb;
                vec3 f4 = texture2D(uCurrTex, uv - vec2(m.x * 4.0, 0.0)).rgb;
                vec3 g1 = texture2D(uCurrTex, uv + vec2(0.0, m.y * 8.0)).rgb;
                vec3 g2 = texture2D(uCurrTex, uv - vec2(0.0, m.y * 8.0)).rgb;
                vec3 g3 = texture2D(uCurrTex, uv + vec2(m.x * 8.0, 0.0)).rgb;
                vec3 g4 = texture2D(uCurrTex, uv - vec2(m.x * 8.0, 0.0)).rgb;

                float stepV = abs(lumaOf(n1) - lumaOf(n2));
                float stepH = abs(lumaOf(n3) - lumaOf(n4));
                // Planitud DENTRO de cada celda (1px vs 4px, misma celda de 8px).
                float cellFlatV = max(abs(lumaOf(n1) - lumaOf(f1)), abs(lumaOf(n2) - lumaOf(f2)));
                float cellFlatH = max(abs(lumaOf(n3) - lumaOf(f3)), abs(lumaOf(n4) - lumaOf(f4)));
                // Periodicidad: el mismo salto se repite a ±8px (siguientes nodos de rejilla).
                float periodicV = max(abs(lumaOf(f1) - lumaOf(g1)), abs(lumaOf(f2) - lumaOf(g2)));
                float periodicH = max(abs(lumaOf(f3) - lumaOf(g3)), abs(lumaOf(f4) - lumaOf(g4)));

                float blockV = gridScore(stepV, cellFlatV, periodicV);
                float blockH = gridScore(stepH, cellFlatH, periodicH);
                float blockiness = max(blockV, blockH);
                float amount = smoothstep(0.015, 0.09, blockiness) * strength;
                amount = min(amount, 0.85);
                // Media de las dos celdas (muestreo a 4px) con clamp al rango local para no crear halo.
                vec3 blendV = clamp((f1 + f2) * 0.5, min(min(n1, n2), min(f1, f2)), max(max(n1, n2), max(f1, f2)));
                vec3 blendH = clamp((f3 + f4) * 0.5, min(min(n3, n4), min(f3, f4)), max(max(n3, n4), max(f3, f4)));
                float wV = stepV / (stepV + stepH + 0.0001);
                vec3 result = color;
                result = mix(result, blendV, amount * wV);
                result = mix(result, blendH, amount * (1.0 - wV));
                return clamp(result, 0.0, 1.0);
            }

            // Contraste local estilo CLAHE-lite: compara con la luma media local y empuja hacia los extremos.
            vec3 localContrast(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 h1 = texel * 2.0;
                vec3 n1 = texture2D(uCurrTex, uv + vec2(0.0, h1.y)).rgb
                        + texture2D(uCurrTex, uv - vec2(0.0, h1.y)).rgb
                        + texture2D(uCurrTex, uv + vec2(h1.x, 0.0)).rgb
                        + texture2D(uCurrTex, uv - vec2(h1.x, 0.0)).rgb
                        + texture2D(uCurrTex, uv).rgb;
                vec3 mean1 = n1 * 0.2;
                vec2 h2 = texel * 6.0;
                vec3 n2 = texture2D(uCurrTex, uv + vec2(0.0, h2.y)).rgb
                        + texture2D(uCurrTex, uv - vec2(0.0, h2.y)).rgb
                        + texture2D(uCurrTex, uv + vec2(h2.x, 0.0)).rgb
                        + texture2D(uCurrTex, uv - vec2(h2.x, 0.0)).rgb
                        + texture2D(uCurrTex, uv).rgb;
                vec3 mean2 = n2 * 0.2;
                float lm1 = lumaOf(mean1);
                float lm2 = lumaOf(mean2);
                float lc = lumaOf(color);
                float shift1 = (lc - lm1) * strength * 1.2;
                float shift2 = (lc - lm2) * strength * 0.6;
                float combined = shift1 + shift2;
                vec3 result = color + combined;
                float edgeProtect = smoothstep(0.02, 0.08, abs(combined));
                result = mix(color, result, 0.5 + edgeProtect * 0.5);
                return clamp(result, 0.0, 1.0);
            }

            // Granado fílmico coherente y animado por semilla de frame.
            vec3 addGrain(vec3 color, vec2 uv, float amount) {
                float g1 = (hash(uv * 57.0 + uGrainSeed) - 0.5);
                float g2 = (hash(uv * 113.0 + uGrainSeed * 1.7) - 0.5);
                float g = (g1 * 0.7 + g2 * 0.3) * amount;
                float flicker = (hash(vec2(uGrainSeed * 0.1, 0.0)) - 0.5) * amount * 0.15;
                float luma = lumaOf(color);
                float grainLuma = mix(1.0, 0.4, luma);
                return clamp(color + g * grainLuma + flicker, 0.0, 1.0);
            }

            // Quita gris lavado: sube el contraste global sutil con clamp asimétrico según la luma media local.
            vec3 dehaze(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 h = texel * 4.0;
                vec3 s0 = texture2D(uCurrTex, uv).rgb;
                vec3 s1 = texture2D(uCurrTex, uv + vec2(0.0, h.y)).rgb;
                vec3 s2 = texture2D(uCurrTex, uv - vec2(0.0, h.y)).rgb;
                vec3 s3 = texture2D(uCurrTex, uv + vec2(h.x, 0.0)).rgb;
                vec3 s4 = texture2D(uCurrTex, uv - vec2(h.x, 0.0)).rgb;
                vec3 s5 = texture2D(uCurrTex, uv + vec2(h.x, h.y)).rgb;
                vec3 s6 = texture2D(uCurrTex, uv - vec2(h.x, h.y)).rgb;
                vec3 s7 = texture2D(uCurrTex, uv + vec2(-h.x, h.y)).rgb;
                vec3 s8 = texture2D(uCurrTex, uv + vec2(h.x, -h.y)).rgb;
                vec3 darkMin = min(min(min(s1, s2), min(s3, s4)), min(min(s5, s6), min(s7, s8)));
                darkMin = min(darkMin, s0);
                float darkChannel = lumaOf(darkMin);
                float atmosLight = lumaOf(color);
                float transmission = 1.0 - darkChannel * strength * 1.5;
                transmission = clamp(transmission, 0.2, 1.0);
                vec3 result = (color - darkChannel * strength * 0.15) / max(transmission, 0.3);
                float localContrast = smoothstep(0.1, 0.6, atmosLight) * strength * 0.25;
                result = mix(result, result * (1.0 + localContrast), localContrast);
                return clamp(result, 0.0, 1.0);
            }

            // Tone mapping: curvas seleccionables (mpv/libplacebo). Opera en luma y
            // preserva croma escalando el color por la razón luma resultante/entrada.
            float tc_reinhard(float x) { return x / (1.0 + x); }
            float tc_hable(float x) {
                float A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30;
                float W = 2.0;
                float num = (x * (A * x + C * B) + D * E);
                float den = (x * (A * x + B) + D * F);
                float v = (num / den - E / F);
                float wnum = (W * (A * W + C * B) + D * E);
                float wden = (W * (A * W + B) + D * F);
                float w = (wnum / wden - E / F);
                return clamp(v / w, 0.0, 1.0);
            }
            float tc_mobius(float x) {
                float a = 0.2, b = 0.5;
                float r = (x * (a * x + b)) / (x * (x + b) + a * b);
                return clamp(r, 0.0, 1.0);
            }
            float tc_bt2390(float x) {
                float ks = 1.5, e1 = 0.30, e2 = 0.55;
                float xp = clamp(x, 0.0, 1.5);
                if (xp <= e1) return ks * xp;
                if (xp <= e2) {
                    float t = (xp - e1) / (e2 - e1);
                    return ks * e1 + (1.0 - ks * e1) * pow(t, 1.0 / 0.7);
                }
                return 1.0 + (xp - 1.0) * 0.1;
            }
            float tc_spline(float x) {
                return clamp(x / (1.0 + pow(x, 3.0)), 0.0, 1.0);
            }
            float tc_gamma(float x) {
                return clamp(pow(clamp(x / (1.0 + x), 0.0, 1.0), 0.85), 0.0, 1.0);
            }
            float toneCurveFn(float x, int curve) {
                if (curve == 1) return tc_hable(x);
                else if (curve == 2) return tc_mobius(x);
                else if (curve == 3) return tc_bt2390(x);
                else if (curve == 4) return tc_spline(x);
                else if (curve == 5) return tc_gamma(x);
                return tc_reinhard(x);
            }
            vec3 applyToneCurve(vec3 c, int curve) {
                float l = lumaOf(c);
                float x = max(l, 1e-4);
                float m = toneCurveFn(x, curve);
                float scale = (x > 1e-4) ? (m / x) : 1.0;
                return clamp(c * scale, 0.0, 1.0);
            }

            // ---- Conversión de color real (HDR->SDR / BT.2020->BT.709) ----
            // uSrcTransfer: 0=SDR(sRGB/BT.709), 1=PQ(ST2084), 2=HLG
            // uSrcPrimaries: 0=BT.709, 1=BT.2020
            // uSrcRange: 0=limited(16-235), 1=full
            // Sin colorInfo fiable el source es SDR/BT.709 full -> casi identidad.
            vec3 rangeExpand(vec3 c, int full) {
                if (full == 0) {
                    c = (c - (16.0 / 255.0)) / ((235.0 / 255.0) - (16.0 / 255.0));
                }
                return clamp(c, 0.0, 1.0);
            }

            // PQ (ST 2084) EOTF -> lineal en nits (normalizado a 10000).
            const float PQ_M1 = 0.1593017578125;
            const float PQ_M2 = 78.84375;
            const float PQ_C1 = 0.8359375;
            const float PQ_C2 = 18.8515625;
            const float PQ_C3 = 18.6875;
            vec3 pqToNits(vec3 c) {
                vec3 p = pow(max(c, 0.0), vec3(PQ_M1));
                vec3 lin = pow((PQ_C1 + PQ_C2 * p) / (1.0 + PQ_C3 * p), vec3(PQ_M2));
                return lin * 10000.0;
            }

            // HLG OETF inversa -> lineal en nits (referencia ~1000).
            const float HLG_A = 0.17883277;
            const float HLG_B = 0.28466892;
            const float HLG_C = 0.55991073;
            vec3 hlgToNits(vec3 c) {
                vec3 lo = c / 4.0 - HLG_B;
                vec3 hi = exp((c - HLG_C) / HLG_A);
                vec3 lin = mix(lo * lo, hi, step(vec3(0.5), c));
                return max(lin, 0.0) * 1000.0;
            }

            vec3 srgbToLinear(vec3 c) {
                return pow(c, vec3(2.4));
            }

            vec3 linearToSrgb(vec3 c) {
                return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.4));
            }

            // BT.2020 lineal -> BT.709 lineal (matriz BT.2087).
            vec3 bt2020ToBt709(vec3 c) {
                float r = 1.6605 * c.r - 0.5877 * c.g - 0.0728 * c.b;
                float g = -0.1246 * c.r + 1.1329 * c.g - 0.0083 * c.b;
                float b = -0.0182 * c.r - 0.1006 * c.g + 1.1187 * c.b;
                return vec3(r, g, b);
            }

            // Hable (filmic): forma en mids/sombras, con contraste y saturación naturales.
            vec3 hableFilmic(vec3 x) {
                const float A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30;
                vec3 num = x * (A * x + C * B) + D * E;
                vec3 den = x * (A * x + B) + D * F;
                return (num / den) - E / F;
            }

            // Tonemap híbrido HDR->SDR: Hable en mids + hombro desaturante tipo BT.2390
            // en altas luces (evita tonos quemados con color y preserva detalle en brillos).
            vec3 tonemapHdr(vec3 nits) {
                vec3 x = nits / 100.0;                       // pico SDR ~100 nits -> 1.0
                vec3 mapped = hableFilmic(x) / hableFilmic(vec3(1.0)); // normalizado: x=1 -> 1.0
                float l = lumaOf(mapped);
                // Hombro BT.2390: desatura suavemente las luces por encima de ~0.75 del pico.
                mapped = mix(mapped, vec3(l), smoothstep(0.75, 1.0, l));
                return clamp(mapped, 0.0, 1.0);
            }

            vec3 colorManage(vec3 c, int transfer, int primaries, int full) {
                c = rangeExpand(c, full);
                vec3 lin;
                if (transfer == 1) {
                    lin = pqToNits(c);
                } else if (transfer == 2) {
                    lin = hlgToNits(c);
                } else {
                    lin = srgbToLinear(c);
                }
                if (primaries == 1) {
                    lin = bt2020ToBt709(lin);
                }
                if (transfer == 1 || transfer == 2) {
                    lin = tonemapHdr(lin);
                }
                return linearToSrgb(clamp(lin, 0.0, 1.0));
            }

            vec3 applyHdr(vec3 color, float strength) {
                float luma = lumaOf(color);
                // 1. Expansión de rango dinámico (look HDR): curva S suave que empuja
                //    sombras a negros más profundos y altas luces a blancos más brillantes.
                vec3 sCurve = color * color * (3.0 - 2.0 * color);
                vec3 expanded = mix(color, sCurve, strength * 0.6);
                // 2. Tonemap según curva seleccionada (Reinhard/Hable/Mobius/bt.2390/Spline/Gamma).
                vec3 tm = applyToneCurve(expanded, uToneCurve);
                expanded = mix(expanded, tm, strength * 0.3);
                // 3. Glow natural en altas luces (bloom suave, sin halos duros).
                float hi = smoothstep(0.65, 1.0, luma);
                vec3 glow = expanded + hi * strength * 0.18 * vec3(1.0, 0.97, 0.92);
                // 4. Saturación vibrante, más fuerte en tonos medios.
                float sat = 1.0 + strength * 0.45 * (1.0 - abs(luma - 0.5) * 2.0);
                vec3 satResult = mix(vec3(lumaOf(glow)), glow, clamp(sat, 1.0, 1.8));
                // 5. Contraste local suave para dar "punch" sin aplastar.
                float localCont = (luma - 0.5) * strength * 0.45;
                vec3 result = satResult * (1.0 + localCont);
                return clamp(result, 0.0, 1.0);
            }

            // Detail Boost: Laplaciano + unsharp mask, curva balanceada.
            vec3 detailBoost(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec3 lumW = vec3(0.2126, 0.7152, 0.0722);

                vec3 c  = texture2D(uCurrTex, uv).rgb;
                vec3 n  = texture2D(uCurrTex, uv + vec2(0.0, -texel.y)).rgb;
                vec3 s  = texture2D(uCurrTex, uv + vec2(0.0,  texel.y)).rgb;
                vec3 w  = texture2D(uCurrTex, uv + vec2(-texel.x, 0.0)).rgb;
                vec3 e  = texture2D(uCurrTex, uv + vec2( texel.x, 0.0)).rgb;
                vec3 nw = texture2D(uCurrTex, uv + vec2(-texel.x, -texel.y)).rgb;
                vec3 ne = texture2D(uCurrTex, uv + vec2( texel.x, -texel.y)).rgb;
                vec3 sw = texture2D(uCurrTex, uv + vec2(-texel.x,  texel.y)).rgb;
                vec3 se = texture2D(uCurrTex, uv + vec2( texel.x,  texel.y)).rgb;

                // Unsharp mask: original - promedio cruz
                vec3 blur4 = (n + s + w + e) * 0.25;
                float unsharp = dot(c, lumW) - dot(blur4, lumW);

                // Laplaciano: 8*c - vecinos
                float lap = dot(8.0 * c - (n+s+w+e+nw+ne+sw+se), lumW);

                // Señal combinada × strength
                float detail = (unsharp * 4.0 + lap * 0.25) * strength;

                // Clamp final a [0,1]
                float colorLuma = dot(color, lumW);
                float newLuma = clamp(colorLuma + detail, 0.0, 1.0);
                vec3 chroma = color - vec3(colorLuma);
                return clamp(vec3(newLuma) + chroma, 0.0, 1.0);
            }

            // Light Boost estilo Splash: "iluminación inteligente y color vivo".
            // Es DINÁMICO: estima la luminancia media de la escena en cada frame
            // (muestreo en malla 3x3) y modula la fuerza. Si el video ya es muy
            // brillante reduce la elevación y la saturación, para jamás lavar
            // luces ni quemar highlights.
            vec3 lightBoost(vec3 color, vec2 uv, vec2 texel, float strength) {
                float luma = lumaOf(color);
                // Estimación del brillo global de la escena, en coords absolutas
                // (siempre dentro del frame, sin depender del texel).
                float s00 = lumaOf(texture2D(uCurrTex, vec2(0.18, 0.18)).rgb);
                float s01 = lumaOf(texture2D(uCurrTex, vec2(0.50, 0.18)).rgb);
                float s02 = lumaOf(texture2D(uCurrTex, vec2(0.82, 0.18)).rgb);
                float s10 = lumaOf(texture2D(uCurrTex, vec2(0.18, 0.50)).rgb);
                float s11 = lumaOf(texture2D(uCurrTex, vec2(0.50, 0.50)).rgb);
                float s12 = lumaOf(texture2D(uCurrTex, vec2(0.82, 0.50)).rgb);
                float s20 = lumaOf(texture2D(uCurrTex, vec2(0.18, 0.82)).rgb);
                float s21 = lumaOf(texture2D(uCurrTex, vec2(0.50, 0.82)).rgb);
                float s22 = lumaOf(texture2D(uCurrTex, vec2(0.82, 0.82)).rgb);
                float sceneLuma = (s00 + s01 + s02 + s10 + s11 + s12 + s20 + s21 + s22) / 9.0;
                // Factor dinámico: 1.0 en escenas normales/oscuras, se degrada
                // hacia ~0.4 cuando la escena ya es muy brillante (evita quemar).
                float dyn = 1.0 - smoothstep(0.5, 0.82, sceneLuma) * 0.6;
                float s = strength * dyn;
                // Elevación suave de sombras y medios, sin tocar brillos.
                float lift = (1.0 - smoothstep(0.12, 0.6, luma)) * s * 0.45;
                // Contraste real centrado en 0.5 (S-curve leve), no encendido bruto.
                float contrast = 1.0 + s * 0.18;
                vec3 result = color * (1.0 + lift) + lift * 0.05;
                result = (result - vec3(0.5)) * contrast + vec3(0.5);
                // Saturación "vibrance": mucha en tonos apagados, un toque en luces,
                // pero SIEMPRE presente para que las escenas claras no pierdan color.
                float chromaDev = length(result - vec3(lumaOf(result)));
                float vibrance = 1.0 - smoothstep(0.15, 0.7, chromaDev);
                float sat = 1.0 + s * (0.55 + 0.25 * vibrance);
                result = mix(vec3(lumaOf(result)), result, sat);
                // Protección de highlights: el brillo NO debe lavar el tono.
                float hl = smoothstep(0.75, 0.98, luma);
                result = mix(result, color, hl * strength * 0.55);
                // Proteje piel para no volverla anaranjada.
                float skinMask = smoothstep(0.15, 0.08, abs(color.r - color.g))
                               * smoothstep(0.15, 0.05, color.r - color.b);
                skinMask *= step(0.3, color.r) * step(color.r, 0.75)
                          * step(0.15, color.g) * step(color.g, 0.65);
                result = mix(result, color, skinMask * strength * 0.5);
                result = pow(clamp(result, 0.0, 1.0), vec3(1.0 - strength * 0.05));
                return clamp(result, 0.0, 1.0);
            }

            // Mejora baja calidad: reparación de artefactos de compresión de bajo bitrate.
            // Solo técnicas que ayudan a vídeos pobres: 1) anti-mosquito bilateral (quita el
            // "crawl" de ruido en bordes y planos sin desenfocar), 2) deblock dirigido (suaviza
            // a través de los nodos de rejilla de 8px detectados por periodicidad) y 3)
            // recuperación de detalle real en banda 2px sin amplificar ruido de 1px ni rejilla.
            vec3 lowBitrateBoost(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec2 txl = texel;
                vec2 t2 = txl * 2.0;
                vec3 c = texture2D(uCurrTex, uv).rgb;
                vec3 n1 = texture2D(uCurrTex, uv + vec2(0.0, -txl.y)).rgb;
                vec3 s1 = texture2D(uCurrTex, uv + vec2(0.0,  txl.y)).rgb;
                vec3 w1 = texture2D(uCurrTex, uv + vec2(-txl.x, 0.0)).rgb;
                vec3 e1 = texture2D(uCurrTex, uv + vec2( txl.x, 0.0)).rgb;
                vec3 nw = texture2D(uCurrTex, uv + vec2(-txl.x, -txl.y)).rgb;
                vec3 ne = texture2D(uCurrTex, uv + vec2( txl.x, -txl.y)).rgb;
                vec3 sw = texture2D(uCurrTex, uv + vec2(-txl.x,  txl.y)).rgb;
                vec3 se = texture2D(uCurrTex, uv + vec2( txl.x,  txl.y)).rgb;
                vec3 n2 = texture2D(uCurrTex, uv + vec2(0.0, -t2.y)).rgb;
                vec3 s2 = texture2D(uCurrTex, uv + vec2(0.0,  t2.y)).rgb;
                vec3 w2 = texture2D(uCurrTex, uv + vec2(-t2.x, 0.0)).rgb;
                vec3 e2 = texture2D(uCurrTex, uv + vec2( t2.x, 0.0)).rgb;
                float lc = lumaOf(c);
                float ln = lumaOf(n1); float ls = lumaOf(s1);
                float lw = lumaOf(w1); float le = lumaOf(e1);
                float lnw = lumaOf(nw); float lne = lumaOf(ne);
                float lsw = lumaOf(sw); float lse = lumaOf(se);

                // Bordes reales (para no dañarlos).
                float edgeMask = smoothstep(0.02, 0.14, max(abs(le - lw), abs(ln - ls)));

                // Nodos de rejilla de bloque (8px nativos) por periodicidad: el salto a 1px
                // debe repetirse a 8px para ser rejilla de compresión y no textura real.
                vec2 gm = 1.0 / uVideoRes;
                float gH1 = abs(lumaOf(texture2D(uCurrTex, uv + vec2(gm.x, 0.0)).rgb) -
                                lumaOf(texture2D(uCurrTex, uv - vec2(gm.x, 0.0)).rgb));
                float gV1 = abs(lumaOf(texture2D(uCurrTex, uv + vec2(0.0, gm.y)).rgb) -
                                lumaOf(texture2D(uCurrTex, uv - vec2(0.0, gm.y)).rgb));
                float gH8 = abs(lumaOf(texture2D(uCurrTex, uv + vec2(gm.x * 8.0, 0.0)).rgb) -
                                lumaOf(texture2D(uCurrTex, uv + vec2(gm.x * 7.0, 0.0)).rgb));
                float gV8 = abs(lumaOf(texture2D(uCurrTex, uv + vec2(0.0, gm.y * 8.0)).rgb) -
                                lumaOf(texture2D(uCurrTex, uv + vec2(0.0, gm.y * 7.0)).rgb));
                float gridH = gH1 * clamp((gH8 - gH1 * 0.4) / (gH1 * 0.5 + 0.0001), 0.0, 1.0);
                float gridV = gV1 * clamp((gV8 - gV1 * 0.4) / (gV1 * 0.5 + 0.0001), 0.0, 1.0);
                float gridMask = smoothstep(0.02, 0.08, max(gridH, gridV));

                // DEBANDING: suaviza la posterización en gradientes suaves (muy común en
                // baja calidad). Media de los 8 vecinos solo donde el contenido es plano.
                float lflat = (abs(lc - ln) + abs(lc - ls) + abs(lc - lw) + abs(lc - le)) * 0.25;
                // Dispara en zonas planas/bandas (lflat bajo), no en textura media, para
                // suavizar de verdad los escalones de cuantización sin borrar detalle.
                float flatFactor = 1.0 - smoothstep(0.0, 0.03, lflat);
                float bandAmt = flatFactor * (1.0 - edgeMask);
                vec3 deband = (n1 + s1 + w1 + e1 + nw + ne + sw + se) * 0.125;
                vec3 cc = mix(c, deband, bandAmt * strength * 0.6);

                // Anti-mosquito bilateral 1px (con diagonales): promedia solo vecinos parecidos
                // (exp de la diferencia de luma), eliminando el crawl de ruido sin desenfocar.
                float wN = exp(-abs(ln - lc) * 12.0);
                float wS = exp(-abs(ls - lc) * 12.0);
                float wW = exp(-abs(lw - lc) * 12.0);
                float wE = exp(-abs(le - lc) * 12.0);
                float wNW = exp(-abs(lnw - lc) * 12.0);
                float wNE = exp(-abs(lne - lc) * 12.0);
                float wSW = exp(-abs(lsw - lc) * 12.0);
                float wSE = exp(-abs(lse - lc) * 12.0);
                vec3 smooth1 = (cc + n1*wN + s1*wS + w1*wW + e1*wE + nw*wNW + ne*wNE + sw*wSW + se*wSE)
                              / (1.0 + wN + wS + wW + wE + wNW + wNE + wSW + wSE);
                float noiseAmt = smoothstep(0.008, 0.05, length(smooth1 - cc)) * (1.0 - edgeMask * 0.6);
                vec3 deblocked = mix(cc, smooth1, noiseAmt * strength * 0.8);

                // Deblock dirigido (mejorado con diagonales): en nodos de rejilla sobre
                // contenido plano suaviza a través del borde del bloque en vez de línea dura.
                float localVar = lflat;
                float isFlatish = 1.0 - smoothstep(0.03, 0.12, localVar);
                vec3 blendLine = (n1 + s1 + w1 + e1 + nw + ne + sw + se) * 0.125;
                deblocked = mix(deblocked, blendLine, gridMask * isFlatish * strength * 0.6);

                // DERINGING: atenúa halos de Gibbs (overshoot) alrededor de bordes nítidos,
                // comparando con el rango mín/máx local de los 8 vecinos.
                vec3 lo = min(min(min(n1, s1), min(w1, e1)), min(min(nw, ne), min(sw, se)));
                vec3 hi = max(max(max(n1, s1), max(w1, e1)), max(max(nw, ne), max(sw, se)));
                vec3 clamped = clamp(deblocked, lo, hi);
                float ring = length(deblocked - clamped) / (length(deblocked) + 0.0001);
                float ringMask = smoothstep(0.02, 0.1, ring) * edgeMask;
                deblocked = mix(deblocked, clamped, ringMask * strength * 0.7);

                // Recuperación de detalle en banda 2px (detalle real del contenido), sin
                // rejilla (8px) ni ruido (1px); clamp al rango local para no crear halos.
                vec3 hi2 = deblocked - (n2 + s2 + w2 + e2) * 0.25;
                float detailMask = smoothstep(0.004, 0.03, length(hi2));
                float mask = detailMask * edgeMask * (1.0 - gridMask * 0.9);
                vec3 result = deblocked + hi2 * strength * 1.6 * mask;
                vec3 loAll = min(min(min(n1, s1), min(w1, e1)), min(min(nw, ne), min(sw, se)));
                vec3 hiAll = max(max(max(n1, s1), max(w1, e1)), max(max(nw, ne), max(sw, se)));
                result = clamp(result, loAll, hiAll);

                // Dither antiposterización: ruido triangular subpixel (PDF triangular es óptimo
                // para borrar la escalera de 8-bit). Solo en zonas planas; el ruido temporal
                // (uGrainSeed varía por frame) hace que el ojo promedie gradientes suaves.
                float flatness = 1.0 - smoothstep(0.003, 0.04, lflat);
                float d1 = hash(uv * 591.7 + vec2(uGrainSeed * 0.17));
                float d2 = hash(uv * 811.3 + vec2(uGrainSeed * 0.37 + 1.7));
                float dither = d1 + d2 - 1.0;   // triangular en [-1, 1]
                result += dither * (0.75 / 255.0) * strength * flatness;

                return clamp(result, 0.0, 1.0);
            }

            // Denoise espacial-temporal: bilateral 3x3 + muestreo del frame anterior
            // compensado por movimiento (solo cuando hay interpolación activa).
            vec3 denoisePass(vec3 color, vec2 uv, vec2 texel, float strength) {
                vec3 n1 = texture2D(uCurrTex, uv + vec2(texel.x, 0.0)).rgb;
                vec3 n2 = texture2D(uCurrTex, uv - vec2(texel.x, 0.0)).rgb;
                vec3 n3 = texture2D(uCurrTex, uv + vec2(0.0, texel.y)).rgb;
                vec3 n4 = texture2D(uCurrTex, uv - vec2(0.0, texel.y)).rgb;
                vec3 n5 = texture2D(uCurrTex, uv + vec2(texel.x, texel.y)).rgb;
                vec3 n6 = texture2D(uCurrTex, uv - vec2(texel.x, texel.y)).rgb;
                vec3 n7 = texture2D(uCurrTex, uv + vec2(-texel.x, texel.y)).rgb;
                vec3 n8 = texture2D(uCurrTex, uv + vec2(texel.x, -texel.y)).rgb;
                float w1 = exp(-length(n1 - color) * 8.0);
                float w2 = exp(-length(n2 - color) * 8.0);
                float w3 = exp(-length(n3 - color) * 8.0);
                float w4 = exp(-length(n4 - color) * 8.0);
                float w5 = exp(-length(n5 - color) * 8.0) * 0.7;
                float w6 = exp(-length(n6 - color) * 8.0) * 0.7;
                float w7 = exp(-length(n7 - color) * 8.0) * 0.7;
                float w8 = exp(-length(n8 - color) * 8.0) * 0.7;
                float wSum = 1.0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8;
                vec3 bilateral = (color + n1*w1 + n2*w2 + n3*w3 + n4*w4 + n5*w5 + n6*w6 + n7*w7 + n8*w8) / wSum;
                vec3 result = color;
                if (uInterpEnabled > 0.5) {
                    vec4 mN = texture2D(uMotionTex, vTexCoord);
                    float mConf = clamp(mN.b * mN.a, 0.0, 1.0);
                    vec2 mvN = mix(uGlobalVec, mN.xy * 2.0 - 1.0, mConf) * 16.0 * uMotionScale;
                    vec2 nUV = clamp(vTexCoord - mvN * 0.5, vec2(0.0), vec2(1.0));
                    vec3 nPrev = texture2D(uPrevTex, nUV).rgb;
                    float diff = length(nPrev - bilateral);
                    float trust = 1.0 - smoothstep(0.03, 0.22, diff);
                    vec3 den = mix(bilateral, nPrev, 0.65);
                    result = mix(result, den, trust * strength);
                } else {
                    result = mix(result, bilateral, strength * 0.7);
                }
                return clamp(result, 0.0, 1.0);
            }

            // Deband tipo gradfun (mpv): muestreo estocástico en anillo alrededor del píxel,
            // promedia solo si la luma está dentro del umbral (zonas planas/gradiente) y
            // añade dither de grano fino para romper la posterización. No toca bordes reales.
            vec3 debandPass(vec3 color, vec2 uv, vec2 texel, float strength) {
                float range = 2.0 + 14.0 * strength;
                float threshold = 0.0025 + 0.018 * strength;
                float dirSeed = hash(uv * 137.13 + 0.123);
                float ang = dirSeed * 6.2831853;
                vec2 dir = vec2(cos(ang), sin(ang));
                float lc = lumaOf(color);
                vec3 sum = vec3(0.0);
                float wsum = 0.0;
                for (int i = 1; i <= 2; i++) {
                    float dist = float(i) * range * (0.5 + dirSeed);
                    vec2 off = dir * dist * texel;
                    vec3 s1 = texture2D(uCurrTex, uv + off).rgb;
                    vec3 s2 = texture2D(uCurrTex, uv - off).rgb;
                    float w1 = 1.0 - smoothstep(0.0, threshold * (1.0 + float(i)), abs(lumaOf(s1) - lc));
                    float w2 = 1.0 - smoothstep(0.0, threshold * (1.0 + float(i)), abs(lumaOf(s2) - lc));
                    sum += s1 * w1 + s2 * w2;
                    wsum += w1 + w2;
                }
                vec3 avg = sum / max(wsum, 1e-3);
                float bandMask = 1.0 - smoothstep(0.003, 0.03, abs(lc - lumaOf(avg)));
                float dither = (hash(uv * 811.0 + vec2(uGrainSeed * 0.3)) - 0.5);
                vec3 result = mix(color, avg, bandMask * strength);
                result += dither * (0.5 / 255.0) * (1.0 + strength * 2.0) * bandMask;
                return clamp(result, 0.0, 1.0);
            }

            void main() {
                vec4 curr = texture2D(uCurrTex, vTexCoord);
                vec3 color = curr.rgb;
                // Conversión de color real según metadata del stream (HDR->SDR / BT.2020->BT.709).
                // Sin colorInfo fiable es casi identidad (salvo expansión de rango limited->full).
                color = colorManage(color, uSrcTransfer, uSrcPrimaries, uSrcRange);
                #ifdef USE_3D
                if (u3DMode > 0) {
                    color = make3D(vTexCoord, u3DMode, u3DStrength);
                }
                #endif

                if (uDbgMode > 0.5 && uDbgMode < 7.5) {
                    if (uDbgMode > 6.5) {
                        vec2 px = vTexCoord / uTexelSize;
                        float sqx = mix(100.0, 200.0, uFactor);
                        float inX = step(abs(px.x - sqx), 15.0);
                        float inY = step(abs(px.y - 200.0), 15.0);
                        float sq = inX * inY;
                        gl_FragColor = vec4(vec3(sq), 1.0);
                        return;
                    }
                    if (uDbgMode > 5.5) {
                        vec4 m0 = texture2D(uMotionTex, vTexCoord);
                        vec2 mvdbg = (m0.xy * 2.0 - 1.0) * 16.0;
                        vec2 msD = mvdbg * uMotionScale;
                        vec2 fuD = clamp(vTexCoord - msD, vec2(0.0), vec2(1.0));
                        float resD = length(texture2D(uPrevTex, fuD).rgb - curr.rgb);
                        float trustD = 1.0 - smoothstep(0.04, 0.3, resD);
                        float selD = clamp(max(m0.b * m0.a, trustD), 0.0, 1.0);
                        float maskD = mix(0.3, 1.0, selD);
                        gl_FragColor = vec4(vec3(maskD), 1.0);
                        return;
                    }
                    if (uDbgMode > 4.5) {
                        float m = texture2D(uMotionTex, vTexCoord).a;
                        gl_FragColor = vec4(vec3(m), 1.0);
                        return;
                    }
                    if (uDbgMode > 3.5) {
                        gl_FragColor = vec4(vec3(uFactor), 1.0);
                        return;
                    }
                    if (uDbgMode > 2.5) {
                        gl_FragColor = vec4(vTexCoord, 0.0, 1.0);
                        return;
                    }
                    if (uDbgMode > 1.5) {
                        gl_FragColor = vec4(curr.rgb, 1.0);
                        return;
                    }
                    vec4 p = texture2D(uPrevTex, vTexCoord);
                    gl_FragColor = vec4(p.rgb, 1.0);
                    return;
                }

                if (uInterpEnabled > 0.5) {
                    vec3 interp;
                    float mask;
                    // Confianza del motion vector por píxel (detecta oclusiones y regiones
                    // de baja fiabilidad donde el warp generaría ghosting si confiáramos a ciegas).
                    vec4 mC = texture2D(uMotionTex, vTexCoord);
                    float motionConf = clamp(mC.b * mC.a, 0.0, 1.0);
                    if (uMode > 7.5) {
                        // Alta gama: frame-doubling con micro-blend más agresivo (hasta 30%)
                        // y suavizado temporal fino para 60fps más fluidos en equipos potentes.
                        // Estático -> 0% blend (nítido); movimiento rápido -> hasta 30% (oculta judder).
                        // La confianza por píxel reduce el blend en oclusiones para evitar ghosting.
                        vec3 pv = texture2D(uPrevTex, vTexCoord).rgb;
                        float adapt = smoothstep(0.02, 0.30, length(uGlobalVec));
                        interp = mix(curr.rgb, pv, 0.30 * adapt * mix(0.5, 1.0, motionConf));
                        mask = 1.0;
                    } else if (uMode > 3.5) {
                        // Híbrido recomendado: frame-doubling con micro-blend adaptativo al movimiento.
                        // Estático -> 0% blend (nítido); movimiento rápido -> hasta 16% (oculta judder).
                        vec3 pv = texture2D(uPrevTex, vTexCoord).rgb;
                        float adapt = smoothstep(0.03, 0.20, length(uGlobalVec));
                        interp = mix(curr.rgb, pv, 0.16 * adapt * mix(0.5, 1.0, motionConf));
                        mask = 1.0;
                    } else if (uMode > 1.5) {
                        // Blend por movimiento con clamp min/max: evita el lavado/overshoot del fundido puro.
                        vec3 pv = texture2D(uPrevTex, vTexCoord).rgb;
                        interp = clamp(mix(pv, curr.rgb, uFactor), min(pv, curr.rgb), max(pv, curr.rgb));
                        mask = 1.0;
                    } else {
                        interp = curr.rgb;
                        mask = 1.0;
                    }
                    color = mix(curr.rgb, interp, mask);
                }

                if (uEnabled > 0.5) {
                    // Limpieza primero (denoise → deband → deblock): la reparación de
                    // lowBitrateBoost debe operar sobre señal limpia, no amplificar artefactos.
                    #ifdef USE_DENOISE
                    if (uDenoise > 0.001) {
                        color = denoisePass(color, vTexCoord, uTexelSize, uDenoise);
                    }
                    #endif

                    #ifdef USE_DEBAND
                    if (uDeband > 0.001) {
                        color = debandPass(color, vTexCoord, uTexelSize, uDeband);
                    }
                    #endif

                    #ifdef USE_DEBLOCK
                    if (uDeblock > 0.001) {
                        color = deblock(color, vTexCoord, uTexelSize, uDeblock);
                    }
                    #endif

                    #ifdef USE_SUPERRES
                    if (uLowBitrateBoost > 0.001) {
                        color = lowBitrateBoost(color, vTexCoord, uTexelSize, uLowBitrateBoost);
                    }
                    #endif

                    // Nitidez del perfil de color (básica, sobre luma)
                    #ifdef USE_SHARP
                    if (uStatic < 0.5 && uSharpness > 0.001) {
                    vec2 txl = uDownTexel;
                    vec3 top    = texture2D(uDownTex, vTexCoord + vec2(0.0, txl.y)).rgb;
                    vec3 bottom = texture2D(uDownTex, vTexCoord - vec2(0.0, txl.y)).rgb;
                    vec3 left   = texture2D(uDownTex, vTexCoord - vec2(txl.x, 0.0)).rgb;
                    vec3 right  = texture2D(uDownTex, vTexCoord + vec2(txl.x, 0.0)).rgb;
                    vec3 sharpened = color + uSharpness * (4.0 * color - top - bottom - left - right);
                    color = mix(color, sharpened, uSharpness * 0.5 * contentSharpFactor());
                    }
                    #endif

                    // Nitidez adaptativa (técnica de video): afila solo bordes reales, no ruido.
                    #ifdef USE_ADAPTIVESHARP
                    if (uStatic < 0.5 && uAdaptiveSharp > 0.001) {
                    vec2 txl = uDownTexel;
                    vec3 top    = texture2D(uDownTex, vTexCoord + vec2(0.0, txl.y)).rgb;
                    vec3 bottom = texture2D(uDownTex, vTexCoord - vec2(0.0, txl.y)).rgb;
                    vec3 left   = texture2D(uDownTex, vTexCoord - vec2(txl.x, 0.0)).rgb;
                    vec3 right  = texture2D(uDownTex, vTexCoord + vec2(txl.x, 0.0)).rgb;
                    vec3 sharpened = color + uAdaptiveSharp * (2.0 * color - top - bottom);
                    float gx = lumaOf(right) - lumaOf(left);
                    float gy = lumaOf(top) - lumaOf(bottom);
                    float edge = clamp(sqrt(gx * gx + gy * gy) * 6.0, 0.0, 1.0);
                    color = mix(color, sharpened, uAdaptiveSharp * 0.5 * edge * contentSharpFactor());
                    }
                    #endif

                    #ifdef USE_LOCALCONTRAST
                    if (uLocalContrast > 0.001) {
                        color = localContrast(color, vTexCoord, uTexelSize, uLocalContrast);
                    }
                    #endif

                    // Limpieza de halos tras TODO el afilado (lowBitrateBoost, sharpness, adaptive, local).
                    #ifdef USE_DESRINGING
                    if (uDesRinging > 0.001) {
                        color = desRinging(color, vTexCoord, uTexelSize, uDesRinging);
                    }
                    #endif

                    color = applyTint(color, uTint);
                    float ct = (uContrast - 1.0) * 0.5;
                    color = clamp(color, 0.0, 1.0);
                    if (abs(uContrast - 1.0) > 0.001) {
                        color = color * color * (3.0 - 2.0 * color);
                        color = mix(color, color * color * (3.0 - 2.0 * color), ct);
                        color = mix(vec3(0.5), color, uContrast);
                    }
                    color = adjustSaturation(color, uSaturation);
                    color = gamutBoost(color, uColorBoost);
                    color += uBrightness * 0.3;

                    #ifdef USE_DEHAZE
                    if (uDehaze > 0.001) {
                        color = dehaze(color, vTexCoord, uTexelSize, uDehaze);
                    }
                    #endif

                    #ifdef USE_HDR
                    if (uSrcTransfer == 1 || uSrcTransfer == 2) {
                        // Source HDR real: la conversión correcta ya se aplicó en colorManage.
                    } else if (uHdr > 0.001) {
                        color = applyHdr(color, uHdr);
                    }
                    #endif

                    #ifdef USE_LIGHTBOOST
                    if (uLightBoost > 0.001) {
                        color = lightBoost(color, vTexCoord, uTexelSize, uLightBoost);
                        if (uLightBoostHdr > 0.5 && uSrcTransfer != 1 && uSrcTransfer != 2) {
                            color = applyHdr(color, uLightBoost);
                        }
                    }
                    #endif

                    #ifdef USE_DETAILBOOST
                    if (uDetailBoost > 0.001) {
                        color = detailBoost(color, vTexCoord, uTexelSize, uDetailBoost);
                    }
                    #endif

                    #ifdef USE_DEPTH
                    if (uDepth > 0.001) {
                        color = depthPass(color, vTexCoord, uDepth);
                    }
                    #endif

                    // Grano fílmico SIEMPRE al final: ningún lift/tone-mapping posterior lo
                    // amplifica y el afilado no lo endurece.
                    #ifdef USE_GRAIN
                    if (uGrain > 0.001) {
                        color = addGrain(color, vTexCoord, uGrain);
                    }
                    #endif
                }

                color = clamp(color, 0.0, 1.0);

                // Blue Noise Dithering: último paso antes de output.
                // Rompe banding residual de 8-bit. Temporal rotation automática por frame.
                vec2 fragPx = vTexCoord * uVideoRes;
                #ifdef USE_DITHER
                color = blueNoiseDither(color, fragPx, uDitherStrength);
                #endif

                // DEMO: split screen DESPUÉS del procesamiento
                // Ahora 'color' tiene todos los enhancements aplicados
                if (uDbgMode > 7.5) {
                    float lineDist = abs(vTexCoord.x - 0.5);
                    float lineWidth = 2.0 * uTexelSize.x;
                    if (lineDist < lineWidth) {
                        gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
                        return;
                    }
                    if (vTexCoord.x < 0.5) {
                        gl_FragColor = vec4(curr.rgb, 1.0);
                    } else {
                        gl_FragColor = vec4(color, 1.0);
                    }
                    return;
                }

                gl_FragColor = vec4(color, 1.0);
            }
        """

    val motionShader = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform samplerExternalOES uCurrTex;
            uniform sampler2D uPrevTex;
            uniform sampler2D uOldMotionTex;
            uniform sampler2D uCoarseTex;
            uniform vec2 uMotionTexel;
            uniform float uTemporalAlpha;

            float luma(vec4 c) { return dot(c.rgb, vec3(0.299, 0.587, 0.114)); }

            void main() {
                vec2 t = uMotionTexel;

                vec4 coarse = texture2D(uCoarseTex, vTexCoord);
                vec2 f0 = (coarse.xy * 2.0 - 1.0) * 16.0;

                float l0 = luma(texture2D(uCurrTex, vTexCoord));
                float lpRaw = luma(texture2D(uPrevTex, vTexCoord));
                float lp = luma(texture2D(uPrevTex, vTexCoord - f0 * t));
                float cR = luma(texture2D(uCurrTex, vTexCoord + vec2(t.x, 0.0)));
                float cL = luma(texture2D(uCurrTex, vTexCoord - vec2(t.x, 0.0)));
                float cT = luma(texture2D(uCurrTex, vTexCoord + vec2(0.0, t.y)));
                float cB = luma(texture2D(uCurrTex, vTexCoord - vec2(0.0, t.y)));
                float gx = (cR - cL) * 0.25;
                float gy = (cT - cB) * 0.25;
                float denom = gx * gx + gy * gy + 1e-4;
                float d = l0 - lp;
                vec2 corr = vec2(clamp(-d * gx / denom, -8.0, 8.0),
                                 clamp(-d * gy / denom, -8.0, 8.0));
                vec2 f = clamp(f0 + corr, -8.0, 8.0);

                float l2 = luma(texture2D(uPrevTex, vTexCoord - f * t));
                float d2 = l0 - l2;
                vec2 corr2 = vec2(clamp(-d2 * gx / denom, -8.0, 8.0),
                                  clamp(-d2 * gy / denom, -8.0, 8.0));
                vec2 f2 = clamp(f + corr2, -8.0, 8.0);

                vec2 p = vTexCoord - f2 * t;
                float pl0 = luma(texture2D(uPrevTex, p));
                float pc0 = luma(texture2D(uCurrTex, p));
                float pR = luma(texture2D(uPrevTex, p + vec2(t.x, 0.0)));
                float pL = luma(texture2D(uPrevTex, p - vec2(t.x, 0.0)));
                float pT = luma(texture2D(uPrevTex, p + vec2(0.0, t.y)));
                float pB = luma(texture2D(uPrevTex, p - vec2(0.0, t.y)));
                float pgx = (pR - pL) * 0.25;
                float pgy = (pT - pB) * 0.25;
                float pdenom = pgx * pgx + pgy * pgy + 1e-4;
                float db = pl0 - pc0;
                vec2 b = vec2(clamp(-db * pgx / pdenom, -8.0, 8.0),
                              clamp(-db * pgy / pdenom, -8.0, 8.0));

                float conf = (0.15 + 0.85 * coarse.b) * (0.35 + 0.65 * (1.0 - smoothstep(0.0, 0.6, length(b))));

                vec4 old = texture2D(uOldMotionTex, vTexCoord);
                vec2 oldF = (old.xy * 2.0 - 1.0) * 16.0;
                float a = uTemporalAlpha;
                vec2 sm = clamp(mix(oldF, f2, a), -8.0, 8.0);
                float mag = mix(old.a, smoothstep(0.0, 0.035, abs(l0 - lpRaw)), a);
                float smConf = mix(old.b, conf, a);

                vec2 enc = clamp(sm * 0.0625 + 0.5, 0.0, 1.0);
                gl_FragColor = vec4(enc.x, enc.y, smConf, mag);
            }
        """

    val motionBwdShader = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uCurrTex;
            uniform samplerExternalOES uPrevTex;
            uniform sampler2D uOldMotionTex;
            uniform sampler2D uCoarseTex;
            uniform vec2 uMotionTexel;
            uniform float uTemporalAlpha;
            uniform float uDir;

            float luma(vec4 c) { return dot(c.rgb, vec3(0.299, 0.587, 0.114)); }

            void main() {
                vec2 t = uMotionTexel;

                vec4 coarse = texture2D(uCoarseTex, vTexCoord);
                vec2 f0 = uDir * (coarse.xy * 2.0 - 1.0) * 16.0;

                float l0 = luma(texture2D(uCurrTex, vTexCoord));
                float lpRaw = luma(texture2D(uPrevTex, vTexCoord));
                float lp = luma(texture2D(uPrevTex, vTexCoord - f0 * t));
                float cR = luma(texture2D(uCurrTex, vTexCoord + vec2(t.x, 0.0)));
                float cL = luma(texture2D(uCurrTex, vTexCoord - vec2(t.x, 0.0)));
                float cT = luma(texture2D(uCurrTex, vTexCoord + vec2(0.0, t.y)));
                float cB = luma(texture2D(uCurrTex, vTexCoord - vec2(0.0, t.y)));
                float gx = (cR - cL) * 0.25;
                float gy = (cT - cB) * 0.25;
                float denom = gx * gx + gy * gy + 1e-4;
                float d = l0 - lp;
                vec2 corr = vec2(clamp(-d * gx / denom, -8.0, 8.0),
                                 clamp(-d * gy / denom, -8.0, 8.0));
                vec2 f = clamp(f0 + corr, -8.0, 8.0);

                float l2 = luma(texture2D(uPrevTex, vTexCoord - f * t));
                float d2 = l0 - l2;
                vec2 corr2 = vec2(clamp(-d2 * gx / denom, -8.0, 8.0),
                                  clamp(-d2 * gy / denom, -8.0, 8.0));
                vec2 f2 = clamp(f + corr2, -8.0, 8.0);

                vec2 p = vTexCoord - f2 * t;
                float pl0 = luma(texture2D(uPrevTex, p));
                float pc0 = luma(texture2D(uCurrTex, p));
                float pR = luma(texture2D(uPrevTex, p + vec2(t.x, 0.0)));
                float pL = luma(texture2D(uPrevTex, p - vec2(t.x, 0.0)));
                float pT = luma(texture2D(uPrevTex, p + vec2(0.0, t.y)));
                float pB = luma(texture2D(uPrevTex, p - vec2(0.0, t.y)));
                float pgx = (pR - pL) * 0.25;
                float pgy = (pT - pB) * 0.25;
                float pdenom = pgx * pgx + pgy * pgy + 1e-4;
                float db = pl0 - pc0;
                vec2 b = vec2(clamp(-db * pgx / pdenom, -8.0, 8.0),
                              clamp(-db * pgy / pdenom, -8.0, 8.0));

                float conf = (0.15 + 0.85 * coarse.b) * (0.35 + 0.65 * (1.0 - smoothstep(0.0, 0.6, length(b))));

                vec4 old = texture2D(uOldMotionTex, vTexCoord);
                vec2 oldF = (old.xy * 2.0 - 1.0) * 16.0;
                float a = uTemporalAlpha;
                vec2 sm = clamp(mix(oldF, f2, a), -8.0, 8.0);
                float mag = mix(old.a, smoothstep(0.0, 0.035, abs(l0 - lpRaw)), a);
                float smConf = mix(old.b, conf, a);

                vec2 enc = clamp(sm * 0.0625 + 0.5, 0.0, 1.0);
                gl_FragColor = vec4(enc.x, enc.y, smConf, mag);
            }
        """

    // Filtro bilateral sobre el campo de motion vectors (inspirado en la regularización
    // de FSR 3.0): suaviza el flujo en regiones de baja confianza/ojo al ruido, preservando
    // los bordes reales de movimiento. Reduce ghosting/artefactos en la interpolación.
    // Codificado igual que el motion shader: RG = (f*0.0625+0.5), B = confianza, A = magnitud.
    val motionFilterShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uMotionTex;
            uniform vec2 uMotionTexel;
            uniform float uBlurStrength;
            void main() {
                vec2 t = uMotionTexel;
                vec4 c = texture2D(uMotionTex, vTexCoord);
                vec2 cVec = (c.xy * 2.0 - 1.0) * 16.0;
                float cConf = c.b;
                // Vecinos en cruz (5-tap) con peso bilateral: parecido vectorial y de confianza.
                vec4 n[4];
                n[0] = texture2D(uMotionTex, vTexCoord + vec2( t.x, 0.0));
                n[1] = texture2D(uMotionTex, vTexCoord + vec2(-t.x, 0.0));
                n[2] = texture2D(uMotionTex, vTexCoord + vec2(0.0,  t.y));
                n[3] = texture2D(uMotionTex, vTexCoord + vec2(0.0, -t.y));
                vec2 acc = cVec * cConf;
                float wsum = cConf;
                for (int i = 0; i < 4; i++) {
                    vec2 nVec = (n[i].xy * 2.0 - 1.0) * 16.0;
                    float nd = length(nVec - cVec);
                    // Peso espacial fijo + similitud vectorial (suave) + confianza del vecino.
                    float w = (1.0 - smoothstep(0.5, 4.0, nd)) * n[i].b;
                    acc += nVec * w;
                    wsum += w;
                }
                vec2 outVec = acc / max(wsum, 1e-4);
                float outConf = max(cConf, 0.0);
                vec2 enc = clamp(outVec * 0.0625 + 0.5, 0.0, 1.0);
                gl_FragColor = vec4(enc.x, enc.y, mix(cConf, outConf, uBlurStrength), c.a);
            }
        """

    val coarseShader = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform samplerExternalOES uCurrTex;
            uniform sampler2D uPrevTex;
            uniform vec2 uCoarseTexel;
            uniform mat4 uTexMatrix;
            uniform float uVFlip;

            float luma(vec4 c) { return dot(c.rgb, vec3(0.299, 0.587, 0.114)); }

            float boxCurr(vec2 uv, vec2 hs) {
                return (luma(texture2D(uCurrTex, uv + hs)) + luma(texture2D(uCurrTex, uv + vec2(-hs.x, hs.y)))
                      + luma(texture2D(uCurrTex, uv + vec2(hs.x, -hs.y))) + luma(texture2D(uCurrTex, uv - hs))) * 0.25;
            }
            float boxPrev(vec2 uv, vec2 hs) {
                return (luma(texture2D(uPrevTex, uv + hs)) + luma(texture2D(uPrevTex, uv + vec2(-hs.x, hs.y)))
                      + luma(texture2D(uPrevTex, uv + vec2(hs.x, -hs.y))) + luma(texture2D(uPrevTex, uv - hs))) * 0.25;
            }

            void main() {
                vec2 cell = uCoarseTexel;
                vec2 hs = uCoarseTexel * 0.5;

                float l0 = boxCurr(vTexCoord, hs);
                float bestS = 1e9;
                vec2 bestD = vec2(0.0);
                float s0 = 0.0;
                float sumS = 0.0;
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dx = -3; dx <= 3; dx++) {
                        vec2 off = vec2(float(dx), float(dy)) * cell;
                        float lp = boxPrev(vTexCoord - off, hs);
                        float s = abs(lp - l0);
                        sumS += s;
                        if (dx == 0 && dy == 0) s0 = s;
                        if (s < bestS) {
                            bestS = s;
                            bestD = vec2(float(dx), float(dy));
                        }
                    }
                }
                vec2 f = (bestS < s0 * 0.8) ? bestD : vec2(0.0);
                vec2 enc = clamp(f * 0.0625 + 0.5, 0.0, 1.0);
                float valley = bestS / (sumS * 0.0204 + 1e-4);
                float q = 1.0 - smoothstep(0.3, 0.7, valley);
                gl_FragColor = vec4(enc, q, 0.0);
            }
        """

    val staticVertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

    val staticShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uMotionTex;

            void main() {
                vec2 cell = vec2(1.0 / 16.0);
                float m = 0.0;
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.375, -0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.125, -0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.125, -0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.375, -0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.375, -0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.125, -0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.125, -0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.375, -0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.375, 0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.125, 0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.125, 0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.375, 0.125) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.375, 0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(-0.125, 0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.125, 0.375) * cell).a);
                m = max(m, texture2D(uMotionTex, vTexCoord + vec2(0.375, 0.375) * cell).a);
                gl_FragColor = vec4(m);
            }
        """

    val globalShader = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform sampler2D uMotionTex;
            void main() {
                vec4 m = texture2D(uMotionTex, vTexCoord);
                gl_FragColor = vec4(m.xy, m.b, m.a);
            }
        """

}
