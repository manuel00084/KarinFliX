package com.karin.streamtv.player

/**
 * Shaders GLSL de los efectos de video, en texto claro.
 */
object ShaderBlobs {

    private val preamble: String = "\n            precision highp float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n        "

    val superresVertex: String = "\n            attribute vec4 aFramePosition;\n            uniform mat4 uTransformationMatrix;\n            uniform mat4 uTexTransformationMatrix;\n            varying vec2 vTexCoord;\n            void main() {\n                gl_Position = uTransformationMatrix * aFramePosition;\n                vec4 tp = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\n                vTexCoord = (uTexTransformationMatrix * tp).xy;\n            }\n        "

    val superresFsr: String = "\n            precision highp float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n        \n            uniform vec2 uInputSize;\n            uniform vec2 uTexelSize;\n            uniform vec2 uOutputTexelSize;\n            uniform float uSharpness;\n\n            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n\n            float fsrRcp(float a) { return 1.0 / a; }\n            float fsrRsq(float a) { return inversesqrt(a); }\n\n            // AnÃ¡lisis de direcciÃ³n y longitud del gradiente (EASU, modo SÃšPER SIMPLE).\n            void fsrDirLen(inout vec2 dir, inout float len, vec2 pp,\n                           float b, float c, float i, float j, float f, float e,\n                           float k, float l, float h, float g, float o, float n) {\n                vec4 w = vec4(0.0);\n                w.x = (1.0 - pp.x) * (1.0 - pp.y);\n                w.y = pp.x * (1.0 - pp.y);\n                w.z = (1.0 - pp.x) * pp.y;\n                w.w = pp.x * pp.y;\n                float lA = dot(w, vec4(b, c, f, g));\n                float lB = dot(w, vec4(e, f, i, j));\n                float lC = dot(w, vec4(f, g, j, k));\n                float lD = dot(w, vec4(g, h, k, l));\n                float lE = dot(w, vec4(j, k, n, o));\n                float dc = lD - lC;\n                float cb = lC - lB;\n                float lenX = max(abs(dc), abs(cb));\n                lenX = fsrRcp(lenX + 1.0e-4);\n                float dirX = lD - lB;\n                lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);\n                lenX *= lenX;\n                float ec = lE - lC;\n                float ca = lC - lA;\n                float lenY = max(abs(ec), abs(ca));\n                lenY = fsrRcp(lenY + 1.0e-4);\n                float dirY = lE - lA;\n                lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);\n                lenY *= lenY;\n                len = lenX + lenY;\n                dir = vec2(dirX, dirY);\n            }\n\n            // Peso anisotrÃ³pico del tap (aprox. lancos2 sin sin()/rcp()/sqrt()).\n            float fsrTapW(vec2 off, vec2 dir, vec2 len, float lob, float clp) {\n                vec2 v;\n                v.x = off.x * dir.x + off.y * dir.y;\n                v.y = off.x * (-dir.y) + off.y * dir.x;\n                v *= len;\n                float d2 = v.x * v.x + v.y * v.y;\n                d2 = min(d2, clp);\n                float wB = (2.0 / 5.0) * d2 - 1.0;\n                float wA = lob * d2 - 1.0;\n                wB *= wB;\n                wA *= wA;\n                wB = (25.0 / 16.0) * wB - (9.0 / 16.0);\n                return wB * wA;\n            }\n\n            void main() {\n                // ---- EASU: 12 taps (b c / e f g h / i j k l / n o) ----\n                vec2 pp = vTexCoord * uInputSize - vec2(0.5);\n                vec2 fp = floor(pp);\n                vec2 fr = pp - fp;\n                vec2 tc = fp * uTexelSize;\n\n                vec3 cB = texture2D(uTexSampler, tc + uTexelSize * vec2(0.5, -0.5)).rgb;\n                vec3 cC = texture2D(uTexSampler, tc + uTexelSize * vec2(1.5, -0.5)).rgb;\n                vec3 cE = texture2D(uTexSampler, tc + uTexelSize * vec2(-0.5, 0.5)).rgb;\n                vec3 cF = texture2D(uTexSampler, tc + uTexelSize * vec2(0.5, 0.5)).rgb;\n                vec3 cG = texture2D(uTexSampler, tc + uTexelSize * vec2(1.5, 0.5)).rgb;\n                vec3 cH = texture2D(uTexSampler, tc + uTexelSize * vec2(2.5, 0.5)).rgb;\n                vec3 cI = texture2D(uTexSampler, tc + uTexelSize * vec2(-0.5, 1.5)).rgb;\n                vec3 cJ = texture2D(uTexSampler, tc + uTexelSize * vec2(0.5, 1.5)).rgb;\n                vec3 cK = texture2D(uTexSampler, tc + uTexelSize * vec2(1.5, 1.5)).rgb;\n                vec3 cL = texture2D(uTexSampler, tc + uTexelSize * vec2(2.5, 1.5)).rgb;\n                vec3 cN = texture2D(uTexSampler, tc + uTexelSize * vec2(0.5, 2.5)).rgb;\n                vec3 cO = texture2D(uTexSampler, tc + uTexelSize * vec2(1.5, 2.5)).rgb;\n\n                vec2 dir = vec2(0.0);\n                float len = 0.0;\n                fsrDirLen(dir, len, fr,\n                    dot(cB, LUMA), dot(cC, LUMA), dot(cI, LUMA), dot(cJ, LUMA),\n                    dot(cF, LUMA), dot(cE, LUMA), dot(cK, LUMA), dot(cL, LUMA),\n                    dot(cH, LUMA), dot(cG, LUMA), dot(cO, LUMA), dot(cN, LUMA));\n\n                // Normalizar direcciÃ³n; zonas planas -> kernel simÃ©trico.\n                float dirR = dir.x * dir.x + dir.y * dir.y;\n                if (dirR < 0.00003052) {\n                    dir = vec2(1.0, 0.0);\n                    dirR = 1.0;\n                } else {\n                    dirR = fsrRsq(dirR);\n                    dir *= dirR;\n                }\n\n                // Longitud -> formael kernel (anisotropÃ­a y longitud rotada).\n                len = len * 0.5;\n                len *= len;\n\n                float stretch = (dir.x * dir.x + dir.y * dir.y) *\n                    fsrRcp(max(abs(dir.x), abs(dir.y)) + 1.0e-4);\n                vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n                float lob = 0.5 - 0.29 * len;\n                float clp = fsrRcp(lob + 1.0e-4);\n\n                // Acumular color con los pesos (compartidos por canal).\n                vec3 acc = vec3(0.0);\n                float aW = 0.0;\n                float w;\n                w = fsrTapW(vec2(0.0, -1.0) - fr, dir, len2, lob, clp); acc += cB * w; aW += w;\n                w = fsrTapW(vec2(1.0, -1.0) - fr, dir, len2, lob, clp); acc += cC * w; aW += w;\n                w = fsrTapW(vec2(-1.0, 1.0) - fr, dir, len2, lob, clp); acc += cI * w; aW += w;\n                w = fsrTapW(vec2(0.0, 1.0) - fr, dir, len2, lob, clp); acc += cJ * w; aW += w;\n                w = fsrTapW(vec2(0.0, 0.0) - fr, dir, len2, lob, clp); acc += cF * w; aW += w;\n                w = fsrTapW(vec2(-1.0, 0.0) - fr, dir, len2, lob, clp); acc += cE * w; aW += w;\n                w = fsrTapW(vec2(1.0, 1.0) - fr, dir, len2, lob, clp); acc += cK * w; aW += w;\n                w = fsrTapW(vec2(2.0, 1.0) - fr, dir, len2, lob, clp); acc += cL * w; aW += w;\n                w = fsrTapW(vec2(2.0, 0.0) - fr, dir, len2, lob, clp); acc += cH * w; aW += w;\n                w = fsrTapW(vec2(1.0, 0.0) - fr, dir, len2, lob, clp); acc += cG * w; aW += w;\n                w = fsrTapW(vec2(1.0, 2.0) - fr, dir, len2, lob, clp); acc += cO * w; aW += w;\n                w = fsrTapW(vec2(0.0, 2.0) - fr, dir, len2, lob, clp); acc += cN * w; aW += w;\n\n                vec3 eC = acc / max(aW, 1.0e-4);\n                // Dering: nunca salirse del rango de los 4 vecinos 2x2.\n                eC = clamp(eC, min(min(min(cF, cG), cJ), cK), max(max(max(cF, cG), cJ), cK));\n\n                // ---- RCAS: vecinos a 1px de salida (bilineal del origen) ----\n                vec2 ot = uOutputTexelSize;\n                vec3 bN = texture2D(uTexSampler, vTexCoord + ot * vec2(0.0, -1.0)).rgb;\n                vec3 dN = texture2D(uTexSampler, vTexCoord + ot * vec2(-1.0, 0.0)).rgb;\n                vec3 fN = texture2D(uTexSampler, vTexCoord + ot * vec2(1.0, 0.0)).rgb;\n                vec3 hN = texture2D(uTexSampler, vTexCoord + ot * vec2(0.0, 1.0)).rgb;\n\n                float eL = dot(eC, LUMA);\n                float bL = dot(bN, LUMA);\n                float dL = dot(dN, LUMA);\n                float fL = dot(fN, LUMA);\n                float hL = dot(hN, LUMA);\n\n                float mn1 = min(min(min(bL, dL), fL), hL);\n                float mx1 = max(max(max(bL, dL), fL), hL);\n                float hitMin = min(mn1, eL) / (4.0 * mx1 + 1.0e-4);\n                float hitMax = (1.0 - max(mx1, eL)) / (4.0 * mn1 - 4.0 - 1.0e-4);\n                float lobeL = max(-hitMin, hitMax);\n                float lobe = clamp(lobeL, -0.1875, 0.0) * exp2(-uSharpness);\n\n                // ReducciÃ³n de ruido: zonas ruidosas -> menos afilado.\n                float nz = 0.25 * (bL + dL + fL + hL) - eL;\n                float nR = max(max(max(max(bL, dL), fL), hL), eL) -\n                    min(min(min(min(bL, dL), fL), hL), eL);\n                nz = clamp(abs(nz) / (nR + 1.0e-4), 0.0, 1.0);\n                nz = -0.5 * nz + 1.0;\n                lobe *= nz;\n\n                vec3 sharp = (lobe * (bN + dN + fN + hN) + eC) / (4.0 * lobe + 1.0);\n                gl_FragColor = vec4(clamp(sharp, 0.0, 1.0), 1.0);\n            }\n        "

    val superresFsrEasu: String = "\n            precision highp float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n        \n            uniform vec2 uInputSize;\n            uniform vec2 uTexelSize;\n\n            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n\n            float fsrRcp(float a) { return 1.0 / a; }\n            float fsrRsq(float a) { return inversesqrt(a); }\n\n            void fsrDirLen(inout vec2 dir, inout float len, vec2 pp,\n                           float b, float c, float i, float j, float f, float e,\n                           float k, float l, float h, float g, float o, float n) {\n                vec4 w = vec4(0.0);\n                w.x = (1.0 - pp.x) * (1.0 - pp.y);\n                w.y = pp.x * (1.0 - pp.y);\n                w.z = (1.0 - pp.x) * pp.y;\n                w.w = pp.x * pp.y;\n                float lA = dot(w, vec4(b, c, f, g));\n                float lB = dot(w, vec4(e, f, i, j));\n                float lC = dot(w, vec4(f, g, j, k));\n                float lD = dot(w, vec4(g, h, k, l));\n                float lE = dot(w, vec4(j, k, n, o));\n                float dc = lD - lC;\n                float cb = lC - lB;\n                float lenX = max(abs(dc), abs(cb));\n                lenX = fsrRcp(lenX + 1.0e-4);\n                float dirX = lD - lB;\n                lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);\n                lenX *= lenX;\n                float ec = lE - lC;\n                float ca = lC - lA;\n                float lenY = max(abs(ec), abs(ca));\n                lenY = fsrRcp(lenY + 1.0e-4);\n                float dirY = lE - lA;\n                lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);\n                lenY *= lenY;\n                len = lenX + lenY;\n                dir = vec2(dirX, dirY);\n            }\n\n            float fsrTapW(vec2 off, vec2 dir, vec2 len, float lob, float clp) {\n                vec2 v;\n                v.x = off.x * dir.x + off.y * dir.y;\n                v.y = off.x * (-dir.y) + off.y * dir.x;\n                v *= len;\n                float d2 = v.x * v.x + v.y * v.y;\n                d2 = min(d2, clp);\n                float wB = (2.0 / 5.0) * d2 - 1.0;\n                float wA = lob * d2 - 1.0;\n                wB *= wB;\n                wA *= wA;\n                wB = (25.0 / 16.0) * wB - (9.0 / 16.0);\n                return wB * wA;\n            }\n\n            void main() {\n                vec2 pp = vTexCoord * uInputSize - vec2(0.5);\n                vec2 fp = floor(pp);\n                vec2 fr = pp - fp;\n                vec2 tc = fp * uTexelSize;\n\n                vec3 cB = texture2D(uTexSampler, tc + uTexelSize * vec2(0.5, -0.5)).rgb;\n                vec3 cC = texture2D(uTexSampler, tc + uTexelSize * vec2(1.5, -0.5)).rgb;\n                vec3 cE = texture2D(uTexSampler, tc + uTexelSize * vec2(-0.5, 0.5)).rgb;\n                vec3 cF = texture2D(uTexSampler, tc + uTexelSize * vec2(0.5, 0.5)).rgb;\n                vec3 cG = texture2D(uTexSampler, tc + uTexelSize * vec2(1.5, 0.5)).rgb;\n                vec3 cH = texture2D(uTexSampler, tc + uTexelSize * vec2(2.5, 0.5)).rgb;\n                vec3 cI = texture2D(uTexSampler, tc + uTexelSize * vec2(-0.5, 1.5)).rgb;\n                vec3 cJ = texture2D(uTexSampler, tc + uTexelSize * vec2(0.5, 1.5)).rgb;\n                vec3 cK = texture2D(uTexSampler, tc + uTexelSize * vec2(1.5, 1.5)).rgb;\n                vec3 cL = texture2D(uTexSampler, tc + uTexelSize * vec2(2.5, 1.5)).rgb;\n                vec3 cN = texture2D(uTexSampler, tc + uTexelSize * vec2(0.5, 2.5)).rgb;\n                vec3 cO = texture2D(uTexSampler, tc + uTexelSize * vec2(1.5, 2.5)).rgb;\n\n                vec2 dir = vec2(0.0);\n                float len = 0.0;\n                fsrDirLen(dir, len, fr,\n                    dot(cB, LUMA), dot(cC, LUMA), dot(cI, LUMA), dot(cJ, LUMA),\n                    dot(cF, LUMA), dot(cE, LUMA), dot(cK, LUMA), dot(cL, LUMA),\n                    dot(cH, LUMA), dot(cG, LUMA), dot(cO, LUMA), dot(cN, LUMA));\n\n                float dirR = dir.x * dir.x + dir.y * dir.y;\n                if (dirR < 0.00003052) {\n                    dir = vec2(1.0, 0.0);\n                    dirR = 1.0;\n                } else {\n                    dirR = fsrRsq(dirR);\n                    dir *= dirR;\n                }\n\n                len = len * 0.5;\n                len *= len;\n\n                float stretch = (dir.x * dir.x + dir.y * dir.y) *\n                    fsrRcp(max(abs(dir.x), abs(dir.y)) + 1.0e-4);\n                vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n                float lob = 0.5 - 0.29 * len;\n                float clp = fsrRcp(lob + 1.0e-4);\n\n                vec3 acc = vec3(0.0);\n                float aW = 0.0;\n                float w;\n                w = fsrTapW(vec2(0.0, -1.0) - fr, dir, len2, lob, clp); acc += cB * w; aW += w;\n                w = fsrTapW(vec2(1.0, -1.0) - fr, dir, len2, lob, clp); acc += cC * w; aW += w;\n                w = fsrTapW(vec2(-1.0, 1.0) - fr, dir, len2, lob, clp); acc += cI * w; aW += w;\n                w = fsrTapW(vec2(0.0, 1.0) - fr, dir, len2, lob, clp); acc += cJ * w; aW += w;\n                w = fsrTapW(vec2(0.0, 0.0) - fr, dir, len2, lob, clp); acc += cF * w; aW += w;\n                w = fsrTapW(vec2(-1.0, 0.0) - fr, dir, len2, lob, clp); acc += cE * w; aW += w;\n                w = fsrTapW(vec2(1.0, 1.0) - fr, dir, len2, lob, clp); acc += cK * w; aW += w;\n                w = fsrTapW(vec2(2.0, 1.0) - fr, dir, len2, lob, clp); acc += cL * w; aW += w;\n                w = fsrTapW(vec2(2.0, 0.0) - fr, dir, len2, lob, clp); acc += cH * w; aW += w;\n                w = fsrTapW(vec2(1.0, 0.0) - fr, dir, len2, lob, clp); acc += cG * w; aW += w;\n                w = fsrTapW(vec2(1.0, 2.0) - fr, dir, len2, lob, clp); acc += cO * w; aW += w;\n                w = fsrTapW(vec2(0.0, 2.0) - fr, dir, len2, lob, clp); acc += cN * w; aW += w;\n\n                vec3 eC = acc / max(aW, 1.0e-4);\n                eC = clamp(eC, min(min(min(cF, cG), cJ), cK), max(max(max(cF, cG), cJ), cK));\n                gl_FragColor = vec4(clamp(eC, 0.0, 1.0), 1.0);\n            }\n        "

    val superresBicubic: String = "\n            precision highp float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n        \n            uniform vec2 uInputSize;\n            uniform vec2 uTexelSize;\n\n            float catmull(float t) {\n                if (t < 1.0) {\n                    return (1.5 * t - 2.5) * t * t + 1.0;\n                }\n                return ((-0.5 * t + 2.5) * t - 4.0) * t + 2.0;\n            }\n\n            void main() {\n                vec2 p = vTexCoord * uInputSize - vec2(0.5);\n                vec2 fp = floor(p);\n                vec2 fr = p - fp;\n                vec2 tc = fp * uTexelSize;\n\n                float wx0 = catmull(fr.x + 1.0);\n                float wx1 = catmull(fr.x);\n                float wx2 = catmull(1.0 - fr.x);\n                float wx3 = catmull(2.0 - fr.x);\n                float wy0 = catmull(fr.y + 1.0);\n                float wy1 = catmull(fr.y);\n                float wy2 = catmull(1.0 - fr.y);\n                float wy3 = catmull(2.0 - fr.y);\n\n                vec3 r0 = texture2D(uTexSampler, tc + uTexelSize * vec2(-1.0, -1.0)).rgb * wx0 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 0.0, -1.0)).rgb * wx1 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 1.0, -1.0)).rgb * wx2 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 2.0, -1.0)).rgb * wx3;\n                vec3 r1 = texture2D(uTexSampler, tc + uTexelSize * vec2(-1.0, 0.0)).rgb * wx0 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 0.0, 0.0)).rgb * wx1 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 1.0, 0.0)).rgb * wx2 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 2.0, 0.0)).rgb * wx3;\n                vec3 r2 = texture2D(uTexSampler, tc + uTexelSize * vec2(-1.0, 1.0)).rgb * wx0 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 0.0, 1.0)).rgb * wx1 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 1.0, 1.0)).rgb * wx2 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 2.0, 1.0)).rgb * wx3;\n                vec3 r3 = texture2D(uTexSampler, tc + uTexelSize * vec2(-1.0, 2.0)).rgb * wx0 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 0.0, 2.0)).rgb * wx1 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 1.0, 2.0)).rgb * wx2 +\n                    texture2D(uTexSampler, tc + uTexelSize * vec2( 2.0, 2.0)).rgb * wx3;\n\n                vec3 acc = r0 * wy0 + r1 * wy1 + r2 * wy2 + r3 * wy3;\n                gl_FragColor = vec4(clamp(acc, 0.0, 1.0), 1.0);\n            }\n        "

    val superresAnime4k: String = "\n            precision highp float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n        \n            uniform vec2 uOutputTexelSize;\n            uniform float uSharpness;\n\n            void main() {\n                vec2 ot = uOutputTexelSize;\n                vec3 center = texture2D(uTexSampler, vTexCoord).rgb;\n\n                vec3 up   = texture2D(uTexSampler, vTexCoord + ot * vec2( 0.0, -1.0)).rgb;\n                vec3 down = texture2D(uTexSampler, vTexCoord + ot * vec2( 0.0,  1.0)).rgb;\n                vec3 left = texture2D(uTexSampler, vTexCoord + ot * vec2(-1.0,  0.0)).rgb;\n                vec3 right = texture2D(uTexSampler, vTexCoord + ot * vec2( 1.0,  0.0)).rgb;\n\n                vec3 up2   = texture2D(uTexSampler, vTexCoord + ot * vec2( 0.0, -2.0)).rgb;\n                vec3 down2 = texture2D(uTexSampler, vTexCoord + ot * vec2( 0.0,  2.0)).rgb;\n                vec3 left2 = texture2D(uTexSampler, vTexCoord + ot * vec2(-2.0,  0.0)).rgb;\n                vec3 right2 = texture2D(uTexSampler, vTexCoord + ot * vec2( 2.0,  0.0)).rgb;\n\n                // doG: gaussiana fina (cruz 1px) menos gaussiana amplia (con anillo 2px).\n                vec3 small = (center * 4.0 + up + down + left + right) * (1.0 / 8.0);\n                vec3 large = (center * 2.0 + up + down + left + right +\n                              up2 + down2 + left2 + right2) * (1.0 / 10.0);\n                vec3 dog = small - large;\n\n                // Ganancia guiada por luma: menos en extremos y zonas lisas.\n                float luma = dot(center, vec3(0.2126, 0.7152, 0.0722));\n                float gain = uSharpness * (0.35 + 0.65 * (1.0 - abs(luma - 0.5) * 2.0));\n\n                vec3 outC = center + dog * gain;\n                gl_FragColor = vec4(clamp(outC, 0.0, 1.0), 1.0);\n            }\n        "





    val rcasVertex: String = "\n            attribute vec4 aFramePosition;\n            uniform mat4 uTransformationMatrix;\n            uniform mat4 uTexTransformationMatrix;\n            varying vec2 vTexCoord;\n            void main() {\n                gl_Position = uTransformationMatrix * aFramePosition;\n                vec4 tp = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\n                vTexCoord = (uTexTransformationMatrix * tp).xy;\n            }\n        "

    val rcasFragment: String = "\n            precision mediump float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n            uniform int uDemoSplit; // 1 = demo: mitad izquierda sin afilar\n            uniform vec2 uTexelSize;\n            uniform float uSharpness;\n\n            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n\n            void main() {\n                vec2 ot = uTexelSize;\n                vec3 center = texture2D(uTexSampler, vTexCoord).rgb;\n                vec3 bN = texture2D(uTexSampler, vTexCoord + ot * vec2(0.0, -1.0)).rgb;\n                vec3 dN = texture2D(uTexSampler, vTexCoord + ot * vec2(-1.0, 0.0)).rgb;\n                vec3 fN = texture2D(uTexSampler, vTexCoord + ot * vec2(1.0, 0.0)).rgb;\n                vec3 hN = texture2D(uTexSampler, vTexCoord + ot * vec2(0.0, 1.0)).rgb;\n\n                float eL = dot(center, LUMA);\n                float bL = dot(bN, LUMA);\n                float dL = dot(dN, LUMA);\n                float fL = dot(fN, LUMA);\n                float hL = dot(hN, LUMA);\n\n                float mn1 = min(min(min(bL, dL), fL), hL);\n                float mx1 = max(max(max(bL, dL), fL), hL);\n                float hitMin = min(mn1, eL) / (4.0 * mx1 + 1.0e-4);\n                float hitMax = (1.0 - max(mx1, eL)) / (4.0 * mn1 - 4.0 - 1.0e-4);\n                float lobeL = max(-hitMin, hitMax);\n                float lobe = clamp(lobeL, -0.1875, 0.0) * exp2(-uSharpness);\n\n                // ReducciÃ³n de ruido: zonas ruidosas -> menos afilado.\n                float nz = 0.25 * (bL + dL + fL + hL) - eL;\n                float nR = max(max(max(max(bL, dL), fL), hL), eL) -\n                    min(min(min(min(bL, dL), fL), hL), eL);\n                nz = clamp(abs(nz) / (nR + 1.0e-4), 0.0, 1.0);\n                nz = -0.5 * nz + 1.0;\n                lobe *= nz;\n\n                vec3 sharp = (lobe * (bN + dN + fN + hN) + center) / (4.0 * lobe + 1.0);\n                vec3 outC = (uDemoSplit == 1 && vTexCoord.x < 0.5) ? center : sharp;\n                gl_FragColor = vec4(clamp(outC, 0.0, 1.0), 1.0);\n            }\n        "

    val motionx2Vertex: String = "\r\n            attribute vec4 aFramePosition;\r\n            uniform mat4 uTransformationMatrix;\r\n            uniform mat4 uTexTransformationMatrix;\r\n            varying vec2 vTexCoord;\r\n            void main() {\r\n                gl_Position = uTransformationMatrix * aFramePosition;\r\n                vec4 tp = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\r\n                vTexCoord = (uTexTransformationMatrix * tp).xy;\r\n            }\r\n        "

    val motionx2CopyFragment: String = "\n            precision mediump float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n            void main() {\n                gl_FragColor = texture2D(uTexSampler, vTexCoord);\n            }\n        "

    val motionx2Fragment: String = "\n            precision highp float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n            uniform sampler2D uPrevFrame;\n            uniform float uStrength;\n            uniform int uMode; // 0=HYBRID, 1=DOUBLING, 2=BLEND\n            uniform int uFirstFrame;\n            uniform int uDemoSplit; // 1 = demo: mitad izquierda intacta\n            void main() {\n                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;\n                if (uMode == 1 || uFirstFrame == 1 || uStrength <= 0.0) {\n                    // DOUBLING: cada cuadro nÃ­tido tal cual (la repeticiÃ³n la hace el panel).\n                    gl_FragColor = vec4(c, 1.0);\n                    return;\n                }\n                vec3 p = texture2D(uPrevFrame, vTexCoord).rgb;\n                float mot = length(c - p);\n                float m = smoothstep(0.03, 0.20, mot);\n                // HYBRID = micro-mezcla (25%), BLEND = mezcla completa (50%).\n                float micro = (uMode == 0) ? 0.25 : 0.5;\n                float k = clamp(m * uStrength, 0.0, 1.0) * micro;\n                vec3 demoRgb = mix(c, p, k);\n                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { demoRgb = texture2D(uTexSampler, vTexCoord).rgb; }\n                gl_FragColor = vec4(demoRgb, 1.0);\n            }\n        "

    val motionx2InterpFragment: String = "\n            precision highp float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n            uniform sampler2D uPrevFrame;\n            uniform float uFactor; // 0..1: progreso entre anterior y actual\n            uniform int uDemoSplit;\n            void main() {\n                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;\n                vec3 p = texture2D(uPrevFrame, vTexCoord).rgb;\n                float f = clamp(uFactor, 0.0, 1.0);\n                float mot = length(c - p);\n                float m = smoothstep(0.03, 0.20, mot);\n                float k = clamp(m * f, 0.0, 1.0);\n                vec3 rgb = mix(c, p, k);\n                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { rgb = c; }\n                gl_FragColor = vec4(rgb, 1.0);\n            }\n        "

    val colorsVertex: String = "\r\n            attribute vec4 aFramePosition;\r\n            uniform mat4 uTransformationMatrix;\r\n            uniform mat4 uTexTransformationMatrix;\r\n            varying vec2 vTexCoord;\r\n            void main() {\r\n                gl_Position = uTransformationMatrix * aFramePosition;\r\n                vec4 tp = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\r\n                vTexCoord = (uTexTransformationMatrix * tp).xy;\r\n            }\r\n        "

    val colorsFragment: String = "\n            precision highp float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n            uniform float uStrength; // 0..1\n            uniform int uDemoSplit; // 1 = demo: mitad izquierda intacta\n\n            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }\n\n            void main() {\n                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;\n                if (uStrength <= 0.0) {\n                    gl_FragColor = vec4(c, 1.0);\n                    return;\n                }\n                float l0 = luma(c);\n                float mx0 = max(c.r, max(c.g, c.b));\n                float mn0 = min(c.r, min(c.g, c.b));\n                // Mascara de piel sobre el original (deteccion estable).\n                float skin = smoothstep(0.02, 0.1, c.r - c.g) * smoothstep(0.01, 0.08, c.r - c.b);\n                skin *= smoothstep(0.25, 0.45, c.r) * (1.0 - smoothstep(0.7, 0.85, c.r));\n                skin = clamp(skin, 0.0, 1.0);\r\n                // Adaptativo: lo apagado pide mas, lo vivido casi nada.\r\n                float satDeficit = 1.0 - clamp((mx0 - mn0) * 2.0, 0.0, 1.0);\r\n                // ...cuidando sombras (ruido) y blancos (clipping).\r\n                float toneW = smoothstep(0.02, 0.18, l0) * (1.0 - smoothstep(0.75, 0.98, l0));\r\n                float drive = clamp(satDeficit * (0.35 + 0.65 * toneW), 0.0, 1.0);\r\n                drive *= 1.0 - skin * 0.85;\r\n                // 1) Saturacion adaptativa.\r\n                vec3 outc = mix(vec3(l0), c, 1.0 + uStrength * (0.25 + 1.0 * drive));\r\n                // 2) Vibrance de remate, tambien adaptativa.\r\n                float mx = max(outc.r, max(outc.g, outc.b));\r\n                float mn = min(outc.r, min(outc.g, outc.b));\r\n                float vib = 0.35 * uStrength * drive * (1.0 - clamp((mx - mn) * 1.5, 0.0, 1.0));\r\n                outc = mix(vec3(luma(outc)), outc, 1.0 + vib);\r\n                vec3 demoRgb = clamp(outc, 0.0, 1.0);\r\n                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { demoRgb = texture2D(uTexSampler, vTexCoord).rgb; }\r\n                gl_FragColor = vec4(demoRgb, 1.0);\r\n            }\r\n        "



    /**
     * KarinSuperRes ECO (~9 taps, gama baja): bilineal + DoG luma-only con
     * puerta por contraste + detector de bordes por gradiente + dering
     * adaptativo. Barato y sin halos ni deriva de color: solo afila luma
     * donde hay borde real.
     * Uniforms: uTexelSize + uInputSize (ambos alimentan el paso de texel),
     * uSharpness (ganancia), uScaleFactor (DRS-aware: menos fuerza si el
     * re-escalado real es menor a 2x). GLES2 (GLSL ES 1.00).
     */
    val superresKarinEco: String = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform vec2 uInputSize;
            uniform float uSharpness;
            uniform float uScaleFactor;
            void main() {
                vec2 tx = (uTexelSize + vec2(1.0) / uInputSize) * 0.5;
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                vec3 n = texture2D(uTexSampler, vTexCoord + tx * vec2(0.0, -1.0)).rgb;
                vec3 s = texture2D(uTexSampler, vTexCoord + tx * vec2(0.0, 1.0)).rgb;
                vec3 e = texture2D(uTexSampler, vTexCoord + tx * vec2(1.0, 0.0)).rgb;
                vec3 w = texture2D(uTexSampler, vTexCoord + tx * vec2(-1.0, 0.0)).rgb;
                vec3 n2 = texture2D(uTexSampler, vTexCoord + tx * vec2(0.0, -2.0)).rgb;
                vec3 s2 = texture2D(uTexSampler, vTexCoord + tx * vec2(0.0, 2.0)).rgb;
                vec3 e2 = texture2D(uTexSampler, vTexCoord + tx * vec2(2.0, 0.0)).rgb;
                vec3 w2 = texture2D(uTexSampler, vTexCoord + tx * vec2(-2.0, 0.0)).rgb;
                vec3 small = (c * 4.0 + n + s + e + w) * 0.125;
                vec3 wide = (c * 2.0 + n2 + s2 + e2 + w2) * 0.1666667;
                vec3 dog = small - wide;
                vec3 mx = max(max(n, s), max(e, w));
                vec3 mn = min(min(n, s), min(e, w));
                float contrast = clamp((mx.g - mn.g) * 4.0 + (mx.r - mn.r + mx.b - mn.b) * 2.0, 0.0, 1.0);
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float nL = dot(n, vec3(0.2126, 0.7152, 0.0722));
                float sL = dot(s, vec3(0.2126, 0.7152, 0.0722));
                float eL = dot(e, vec3(0.2126, 0.7152, 0.0722));
                float wL = dot(w, vec3(0.2126, 0.7152, 0.0722));
                vec2 gg = vec2(eL - wL, sL - nL);
                float edge = clamp(length(gg) * 2.0, 0.0, 1.0);
                float gate = max(contrast, edge * 0.7);
                float tone = 0.35 + 0.65 * (1.0 - abs(luma - 0.5) * 2.0);
                float scaleW = clamp(uScaleFactor - 1.0, 0.25, 1.0);
                float dogL = dot(dog, vec3(0.2126, 0.7152, 0.0722));
                vec3 outc = c + vec3(dogL) * (uSharpness * 1.5 * gate * tone * scaleW);
                float ringEps = mix(0.03, 0.006, gate);
                outc = clamp(outc, mn - vec3(ringEps), mx + vec3(ringEps));
                gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            }
        """

    /**
     * KarinSuperRes CRISP (~9 taps + hash, gama media, un solo pase):
     * gradiente sobre 3x3 + orientacion diagonal, direccion de borde,
     * remate direccional en lineas, CAS luma-only de contraste adaptativo
     * sobre el vecindario de salida, puerta de ruido + mascara de grano,
     * DRS-aware y dering adaptativo. Mismos uniforms que ECO.
     */
    val superresKarin: String = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform vec2 uInputSize;
            uniform float uSharpness;
            uniform float uScaleFactor;
            float karinLuma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            void main() {
                vec2 tx = (uTexelSize + vec2(1.0) / uInputSize) * 0.5;
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                vec3 n = texture2D(uTexSampler, vTexCoord + tx * vec2(0.0, -1.0)).rgb;
                vec3 s = texture2D(uTexSampler, vTexCoord + tx * vec2(0.0, 1.0)).rgb;
                vec3 e = texture2D(uTexSampler, vTexCoord + tx * vec2(1.0, 0.0)).rgb;
                vec3 w = texture2D(uTexSampler, vTexCoord + tx * vec2(-1.0, 0.0)).rgb;
                vec3 nw = texture2D(uTexSampler, vTexCoord + tx * vec2(-1.0, -1.0)).rgb;
                vec3 ne = texture2D(uTexSampler, vTexCoord + tx * vec2(1.0, -1.0)).rgb;
                vec3 sw = texture2D(uTexSampler, vTexCoord + tx * vec2(-1.0, 1.0)).rgb;
                vec3 se = texture2D(uTexSampler, vTexCoord + tx * vec2(1.0, 1.0)).rgb;
                float cL = karinLuma(c);
                float nL = karinLuma(n);
                float sL = karinLuma(s);
                float eL = karinLuma(e);
                float wL = karinLuma(w);
                float nwL = karinLuma(nw);
                float neL = karinLuma(ne);
                float swL = karinLuma(sw);
                float seL = karinLuma(se);
                vec2 g = vec2((eL + neL + seL) - (wL + nwL + swL), (sL + swL + seL) - (nL + nwL + neL));
                float glen = length(g) + 0.0001;
                float edgeMag = clamp(glen * 2.0, 0.0, 1.0);
                float d1 = abs((neL + swL) * 0.5 - cL);
                float d2 = abs((nwL + seL) * 0.5 - cL);
                float orient = clamp(abs(d1 - d2) * 6.0, 0.0, 1.0);
                vec3 lap = (n + s + e + w) * 0.25 - c;
                float dirBoost = 0.6 + 0.9 * max(edgeMag, orient);
                vec3 detail = -lap * dirBoost;
                float detL = dot(detail, vec3(0.2126, 0.7152, 0.0722));
                vec3 crossMin = min(min(n, s), min(e, w));
                vec3 crossMax = max(max(n, s), max(e, w));
                vec3 diagMin = min(min(nw, ne), min(sw, se));
                vec3 diagMax = max(max(nw, ne), max(sw, se));
                vec3 mn = min(crossMin, diagMin);
                vec3 mx = max(crossMax, diagMax);
                float range = max(karinLuma(mx) - karinLuma(mn), 0.0001);
                float gate = clamp((range - 0.008) * 24.0, 0.0, 1.0);
                vec2 gcell = floor(vTexCoord * uInputSize);
                float hash = fract(sin(dot(gcell, vec2(12.9898, 78.233))) * 43758.5453);
                float grain = clamp(abs(hash - 0.5) * 2.0, 0.0, 1.0);
                float grainMask = mix(1.0, grain, clamp((0.03 - range) * 20.0, 0.0, 1.0) * 0.7);
                float scaleW = clamp(uScaleFactor - 1.0, 0.2, 1.0);
                float tone = 0.4 + 0.6 * (1.0 - abs(cL - 0.5) * 2.0);
                float k = uSharpness * 1.6 * gate * grainMask * scaleW * tone;
                vec3 outc = c + vec3(detL) * k;
                float ringEps = mix(0.025, 0.005, edgeMag);
                outc = clamp(outc, mn - vec3(ringEps), mx + vec3(ringEps));
                gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            }
        """

    /**
     * KarinSuperRes EASU (4 taps, pase 1 de HiRes en gama alta): re-escalado
     * 2x limpio, sin afilado dentro. El afilado lo pone el pase 2 sobre la
     * salida real. uSharpness solo modula un micro-dering para que el uniform
     * siga vivo (Media3 hace NPE si se setea un uniform que el compilador
     * elimino por no usarse).
     */
    val superresKarinEasu: String = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform vec2 uInputSize;
            uniform float uSharpness;
            uniform float uScaleFactor;
            void main() {
                vec2 tx = (uTexelSize + vec2(1.0) / uInputSize) * 0.5;
                vec2 p = vTexCoord * uInputSize - vec2(0.5);
                vec2 fp = floor(p);
                vec2 fr = p - fp;
                vec2 base = (fp + vec2(0.5)) * tx;
                vec3 a = texture2D(uTexSampler, base).rgb;
                vec3 b = texture2D(uTexSampler, base + tx * vec2(1.0, 0.0)).rgb;
                vec3 c2 = texture2D(uTexSampler, base + tx * vec2(0.0, 1.0)).rgb;
                vec3 d = texture2D(uTexSampler, base + tx * vec2(1.0, 1.0)).rgb;
                vec3 outc = mix(mix(a, b, fr.x), mix(c2, d, fr.x), fr.y);
                vec3 mn = min(min(a, b), min(c2, d));
                vec3 mx = max(max(a, b), max(c2, d));
                float dk = uSharpness * 0.15 * clamp(uScaleFactor - 1.0, 0.0, 1.0);
                outc = mix(outc, clamp(outc, mn, mx), dk);
                gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            }
        """

    /**
     * KarinSharp (5 taps, pase 2 de HiRes): CAS luma-only sobre pixeles YA
     * escalados + mascara de grano + DRS-aware (uScaleFactor = lambda
     * outW/inW del pase 1) + dering adaptativo + demo split. Lo compone
     * SuperResRcasEffect con casMode=true.
     */
    val karinSharpenFragment: String = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uSharpness;
            uniform float uScaleFactor;
            uniform int uDemoSplit;
            void main() {
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                if (uDemoSplit == 1 && vTexCoord.x < 0.5) { gl_FragColor = vec4(c, 1.0); return; }
                vec3 n = texture2D(uTexSampler, vTexCoord + uTexelSize * vec2(0.0, -1.0)).rgb;
                vec3 s = texture2D(uTexSampler, vTexCoord + uTexelSize * vec2(0.0, 1.0)).rgb;
                vec3 e = texture2D(uTexSampler, vTexCoord + uTexelSize * vec2(1.0, 0.0)).rgb;
                vec3 w = texture2D(uTexSampler, vTexCoord + uTexelSize * vec2(-1.0, 0.0)).rgb;
                vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
                float cL = dot(c, LUMA);
                float nL = dot(n, LUMA);
                float sL = dot(s, LUMA);
                float eL = dot(e, LUMA);
                float wL = dot(w, LUMA);
                float mnL = min(min(nL, sL), min(eL, wL));
                float mxL = max(max(nL, sL), max(eL, wL));
                float range = max(mxL - mnL, 0.0001);
                float hitMin = min(mnL, cL) / (4.0 * mxL + 0.0001);
                float hitMax = (1.0 - max(mxL, cL)) / (4.0 * (1.0 - mnL) + 0.0001);
                float lobe = clamp(max(-hitMin, hitMax), -0.25, 0.0);
                float lambda = clamp(uScaleFactor - 1.0, 0.0, 1.0);
                float k = uSharpness * (0.35 + 0.65 * lambda);
                vec3 sharp = (lobe * k * (n + s + e + w) + c) / (4.0 * lobe * k + 1.0);
                vec2 gcell = floor(vTexCoord / uTexelSize);
                float hash = fract(sin(dot(gcell, vec2(12.9898, 78.233))) * 43758.5453);
                float gate = clamp((range - 0.006) * 30.0, 0.0, 1.0);
                gate *= mix(1.0, clamp(abs(hash - 0.5) * 2.0, 0.0, 1.0), 0.6);
                float sharpL = dot(sharp, LUMA);
                float blend = clamp(gate * (0.4 + 0.6 * k), 0.0, 1.0);
                vec3 outc = c + vec3(sharpL - cL) * blend;
                vec3 mn = min(min(n, s), min(e, w));
                vec3 mx = max(max(n, s), max(e, w));
                float edgeMag2 = clamp(range * 3.0, 0.0, 1.0);
                float ringEps = mix(0.02, 0.005, edgeMag2);
                outc = clamp(outc, mn - vec3(ringEps), mx + vec3(ringEps));
                gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            }
        """

    val demoSplitVertex: String = "\n            attribute vec4 aFramePosition;\n            uniform mat4 uTransformationMatrix;\n            uniform mat4 uTexTransformationMatrix;\n            varying vec2 vTexCoord;\n            void main() {\n                gl_Position = uTransformationMatrix * aFramePosition;\n                vec4 tp = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\n                vTexCoord = (uTexTransformationMatrix * tp).xy;\n            }\n        "

    val demoSplitFragment: String = "\n            precision mediump float;\n            varying vec2 vTexCoord;\n            uniform sampler2D uTexSampler;\n            void main() {\n                vec3 rgb = texture2D(uTexSampler, vTexCoord).rgb;\n                if (abs(vTexCoord.x - 0.5) < 0.001) {\n                    rgb = vec3(1.0);\n                }\n                gl_FragColor = vec4(rgb, 1.0);\n            }\n        "



}

