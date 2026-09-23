package com.karin.streamtv.player

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * KARINFLIX VISION ASSIST: ayuda de visión en un SOLO pase GL, cero LUTs.
 *
 * Selección MULTIPLE de necesidades (bitmask por uniforme, GPU-light):
 *   bit0 = Daltonismo  (matrices 3x3 suaves tipo Vienot, con subtipo Protan/Deutan/Tritan/Mono)
 *   bit1 = Baja Visión (unsharp adaptativo anti-halo + contraste local acotado +
 *          lift progresivo de sombras con negros intactos y protección de altas
 *          luces; 4 fetch px, ver bloque al final de main())
 *   bit2 = Alto Contraste (curva-S racional con rodillas suaves: separa sombras,
 *          medios tonos y luces sin negros rotos ni blancos quemados; dither
 *          anti-banding; intensidad propia 0/25/50/75/100)
 *   bit3 = Fotofobia   (confort visual: anti-glare adaptativo con rodilla suave
 *          en altas luminancias + atenuación de colores brillantes; tinte cálido
 *          opcional muy suave — NO es el filtro Luz Azul; intensidad propia;
 *          es un filtro de confort, no un tratamiento médico)
 *   bit4 = Luz Azul    (temperatura de color cálida: balance diagonal von Kries
 *          en RGB, solo atenuación; intensidad y temperatura propias)
 *   bit5 = Vista Cansada (confort: techo suave de highlights + lift anclado en
 *          el negro + desaturación moderada + calidez muy ligera opcional;
 *          modos Suave/Confort, intensidad propia; SIN blur — nitidez intacta)
 *
 * Orden interno fijo: Daltonismo → Alto Contraste → Vista Cansada → Fotofobia →
 * Luz Azul → Baja Visión (la última a propósito: su lift de sombras y su
 * protección de altas luces definen el detalle final y no deben ser re-aplastados
 * por la curva global de Alto Contraste ni apagados por Fotofobia/Luz Azul).
 * Las etapas no elegidas se saltan por bit-test; branching solo por uniforme
 * (barato), 4 fetches extra como máximo, sin texturas auxiliares ni LUTs. GLES2.
 */
class VisionAssistEffect(
    var settings: VisionAssistSettings = VisionAssistSettings(),
) : GlEffect {

    private var program: VisionAssistShaderProgram? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return VisionAssistShaderProgram(context, useHdr, settings)
            .also { program = it }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        settings.mask == 0 || settings.intensity <= 0f

    fun update(newSettings: VisionAssistSettings) {
        settings = newSettings
        program?.update(newSettings)
    }
}

class VisionAssistShaderProgram(
    context: Context,
    useHdr: Boolean,
    private var settings: VisionAssistSettings,
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

    override fun release() {
        try {
            glProgram.delete()
        } catch (_: Exception) {
        }
        super.release()
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        glProgram.setFloatsUniform("uResolution", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            pushUniforms()
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            Log.w(TAG, "VisionAssist: drawFrame GL error (mask=${settings.mask}, daltonType=${settings.daltonType})", e)
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun pushUniforms() {
        val s = settings
        glProgram.setIntUniform("uProfileMask", s.mask)
        glProgram.setFloatUniform("uIntensity", s.intensity)
        glProgram.setIntUniform("uDaltonType", s.daltonType)
        glProgram.setFloatUniform("uLowSharp", s.lowSharp)
        glProgram.setFloatUniform("uLowEdge", s.lowEdge)
        glProgram.setFloatUniform("uLowShadow", s.lowShadow)
        glProgram.setFloatUniform("uLowInt", s.lowIntensity)
        glProgram.setFloatUniform("uPhotoWarm", s.photoWarm)
        glProgram.setFloatUniform("uPhotoDesat", s.photoDesat)
        glProgram.setFloatUniform("uPhotoInt", s.photoIntensity)
        glProgram.setFloatUniform("uHcLift", s.hcLift)
        glProgram.setFloatUniform("uHcSoft", s.hcSoft)
        glProgram.setFloatUniform("uHcInt", s.hcIntensity)
        glProgram.setFloatUniform("uStrainRelax", s.strainRelax)
        glProgram.setFloatUniform("uStrainInt", s.strainInt)
        glProgram.setIntUniform("uStrainMode", s.strainMode)
        glProgram.setFloatUniform("uBlueLight", s.blueLight)
        glProgram.setFloatUniform("uBlueTemp", s.blueTemp)
    }

    fun update(newSettings: VisionAssistSettings) {
        settings = newSettings
    }

    companion object {
        private const val TAG = "VisionAssistEffect"
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
            uniform vec2 uResolution;
            uniform int uProfileMask;   // bit0=dalton, 1=claridad, 2=alto contraste, 3=fotofobia, 4=luz azul, 5=vista cansada
            uniform float uIntensity;   // 0.0 - 1.0
            uniform int uDaltonType;    // 0=Protan, 1=Deutan, 2=Tritan, 3=Monocromo
            uniform float uLowSharp;
            uniform float uLowEdge;
            uniform float uLowShadow; // Baja Visión: elevación de sombras 0..1
            uniform float uLowInt;    // Baja Visión: intensidad propia 0..1 (0=OFF)
            uniform float uPhotoWarm;
            uniform float uPhotoDesat;
            uniform float uPhotoInt;  // Fotofobia: intensidad propia 0..1 (0=OFF)
            uniform float uHcLift;
            uniform float uHcSoft;
            uniform float uHcInt;    // Alto Contraste: intensidad propia 0..1 (0=OFF)
            uniform float uStrainRelax; // Vista cansada: relajación (fuerza) 0..1
            uniform float uStrainInt;   // Vista cansada: intensidad propia 0..1 (0=OFF)
            uniform int uStrainMode;    // Vista cansada: 0=Suave, 1=Confort
            uniform float uBlueLight;   // Luz azul: intensidad propia 0..1 (0=OFF)
            uniform float uBlueTemp;    // Luz azul: temperatura 0..1 (0=Normal, 1=muy cálido)

            // --- Matrices de corrección fisiológica de daltonismo (Viénot 1999 + AOSP),
            // en forma ESCALAR explícita: 100% compatible GLES 2.0 y con ningún driver.
            // RGB2LMS (filas L, M, S): de sRGB-lineal a espacio de conos.
            const float C_L1 = 0.390405; const float C_L2 = 0.549941; const float C_L3 = 0.008926;
            const float C_M1 = 0.070842; const float C_M2 = 0.963172; const float C_M3 = 0.001358;
            const float C_S1 = 0.023108; const float C_S2 = 0.128021; const float C_S3 = 0.936245;
            // LMS2RGB (filas R, G, B): su inversa.
            const float C_R1 = 2.858468; const float C_R2 = -1.628788; const float C_R3 = -0.024891;
            const float C_G1 = -0.210182; const float C_G2 = 1.158201; const float C_G3 = 0.000324;
            const float C_B1 = -0.041812; const float C_B2 = -0.118169; const float C_B3 = 1.068666;
            // Luminancia relativa WCAG (Rec.709): usada en todas las etapas.
            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

            // Bayer 4x4 (dither anti-banding de Alto Contraste; si el bloque
            // no se usa el compilador lo elimina, coste cero para el resto).
            float bayer2(vec2 a) {
                a = floor(a);
                return fract(a.x * 0.5 + a.y * a.y * 0.75);
            }
            float bayer4(vec2 a) { return bayer2(a * 0.5) * 0.25 + bayer2(a); }

            void main() {
                vec3 c = texture2D(uTexSampler, vTexCoord).rgb;
                float i = clamp(uIntensity, 0.0, 1.0);
                // Bits del mask leídos con mod() en FLOAT: GLES2 no soporta
                // operadores bitwise (& | ~) y el % entero falla en varios
                // drivers; mod/floor float compila en todos.
                float mf = float(uProfileMask);
                bool bDalton    = mod(mf, 2.0) > 0.5;
                bool bLowVision = mod(floor(mf / 2.0), 2.0) > 0.5;
                bool bHighContr = mod(floor(mf / 4.0), 2.0) > 0.5;
                bool bPhoto     = mod(floor(mf / 8.0), 2.0) > 0.5;
                bool bBlueLight = mod(floor(mf / 16.0), 2.0) > 0.5;
                bool bEyeStrain = mod(floor(mf / 32.0), 2.0) > 0.5;

                if (bDalton && i > 0.001) {
                    // --- Daltonismo: corrección perceptiva (Viénot 1999 + AOSP).
                    // Se linealiza sRGB (pow 2.2) para que la simulación actúe sobre
                    // luminancia perceptiva, se proyecta el eje del cono ausente al
                    // plano(blanco, azul/rojo) y el error se reparte sobre los conos
                    // sanos. Todo en aritmética escalar explícita (GLES 2.0 puro).
                    int dt = uDaltonType;
                    if (dt < 0) dt = 0;
                    else if (dt > 3) dt = 3;
                    // Linealización sRGB.
                    float rr = pow(max(c.r, 0.0), 2.2);
                    float gg = pow(max(c.g, 0.0), 2.2);
                    float bb = pow(max(c.b, 0.0), 2.2);
                    vec3 corr;
                    if (dt == 3) {
                        // Monocromo: luminancia lineal (mejor percepción de claro/oscuro).
                        float gray = clamp(dot(vec3(rr, gg, bb), LUMA), 0.0, 1.0);
                        corr = clamp(pow(vec3(gray, gray, gray), vec3(0.4545)), 0.0, 1.0);
                    } else {
                        // RGB-lineal -> LMS (conos).
                        float L = C_L1 * rr + C_L2 * gg + C_L3 * bb;
                        float M = C_M1 * rr + C_M2 * gg + C_M3 * bb;
                        float S = C_S1 * rr + C_S2 * gg + C_S3 * bb;
                        if (dt == 0) {
                            // Protan: proyecta el eje L hacia el plano(blanco,azul).
                            float simL = 0.908212 * M + 0.008216 * S;
                            float err = L - simL;
                            M = M + 0.7 * err;
                            S = S + 0.7 * err;
                        } else if (dt == 1) {
                            // Deutan: proyecta el eje M hacia el plano(blanco,azul).
                            float simM = 1.101064 * L - 0.009047 * S;
                            float err = M - simM;
                            L = L + 0.7 * err;
                            S = S + 0.7 * err;
                        } else {
                            // Tritan: proyecta el eje S hacia el plano(blanco,rojo).
                            float simS = -0.157602 * L + 1.194721 * M;
                            float err = S - simS;
                            L = L + 0.7 * err;
                            M = M + 0.7 * err;
                        }
                        // LMS -> RGB-lineal -> re-encode gamma 1/2.2, con clamp final.
                        float or_ = C_R1 * L + C_R2 * M + C_R3 * S;
                        float ogr = C_G1 * L + C_G2 * M + C_G3 * S;
                        float ob_ = C_B1 * L + C_B2 * M + C_B3 * S;
                        corr = clamp(pow(max(vec3(or_, ogr, ob_), 0.0), vec3(0.4545)), 0.0, 1.0);
                    }
                    c = mix(c, corr, i);
                }

                if (bHighContr && i > 0.001) {
                    // --- Alto Contraste: curva-S racional con rodillas suaves ---
                    // Referencia en espejo: HighContrastMath (test unitario;
                    // mantener sincronizado este GLSL con la copia de pruebas).
                    //
                    // ANTES (bug): clamp((Y-0.5)*(1+0.40*i)+0.5, 0, 1) achataba
                    // ~14% del rango en cada extremo (negros rotos Y<0.14 y
                    // blancos quemados Y>0.86 a intensidad plena), el "knee"
                    // actuaba en el centro y no en los extremos, y pow(l2,gamma)
                    // con gamma<1 aclaraba también las altas luces.
                    //
                    // AHORA, sobre la luma Y (Rec.709, espacio gamma codificado):
                    //  1) Tono: f(Y) = 0.5 + R * v/(1+|v|), v=(Y-0.5)*k, k=1.5,
                    //     R=(1+0.5k)/k=7/6. Propiedades EXACTAS: f(0)=0 (negro
                    //     intacto), f(1)=1, f(0.5)=0.5 (sin cambio de brillo
                    //     medio), monótona; ganancia en el pivote 1+0.5k=1.75
                    //     (separa medios tonos) y pendiente en extremos
                    //     1/(1+0.5k)=0.571>0 => el detalle de sombras/luces
                    //     NUNCA se aplasta; comportamiento asintótico => sin
                    //     clamp duro y sin clip plano en HDR ni en SDR.
                    //  2) hcLift: abre el pie de sombras con peso
                    //     (x/(x+0.04))*(1-x)^3 => exactamente 0 en negro puro
                    //     (sin velo gris) y ~0 en medios/altos.
                    //  3) hcSoft: techo racional monótono f/(1+a*over) con
                    //     a*0.78<1 siempre => blancos acercados a <1 con
                    //     pendiente viva (mantiene gradiente, sin quemado).
                    //  4) Dither Bayer 4x4 de 1/255 anclado a 0 en negro puro
                    //     => sin banding al comprimir los extremos.
                    //  5) Reempaque: escala uniforme c*fY/Y LIMITADA a que
                    //     ningún canal supere 1 => ratios R/G/B intactos
                    //     (clave para Daltonismo+Alto contraste: la
                    //     diferenciación cromática no se destruye) y sin
                    //     tinte por recorte de canal.
                    //  6) Mezcla final original<->procesado con
                    //     eff = uHcInt * i => 0/25/50/75/100% exactos.
                    float eff = clamp(uHcInt, 0.0, 1.0) * clamp(i, 0.0, 1.0);
                    if (eff > 0.001) {
                        vec3 c0 = c;
                        float Y = clamp(dot(c0, LUMA), 0.0, 1.0);
                        float lift = clamp(uHcLift, 0.0, 1.0);
                        float soft = clamp(uHcSoft, 0.0, 1.0);

                        // 1) Curva-S racional (k=1.5 => R=7/6). Sin pow.
                        float v = (Y - 0.5) * 1.5;
                        float fY = 0.5 + 1.1666667 * (v / (1.0 + abs(v)));

                        // 2) Apertura de sombras anclada en el negro exacto.
                        float wS = (fY / (fY + 0.04)) *
                            (1.0 - fY) * (1.0 - fY) * (1.0 - fY);
                        fY += lift * 0.055 * wS;

                        // 3) Techo suave de altas luces (monótono, sin clip).
                        float over = max(fY - 0.78, 0.0);
                        fY = fY / (1.0 + 0.5 * soft * over);
                        fY = clamp(fY, 0.0, 1.0);

                        // 5) Reempaque con cromaticidad intacta y sin clip.
                        vec3 cProc = c0;
                        if (Y > 0.0001) {
                            float scale = fY / Y;
                            float maxC = max(c0.r, max(c0.g, c0.b));
                            if (maxC > 0.0001) scale = min(scale, 1.0 / maxC);
                            cProc = c0 * scale;
                        }
                        cProc = clamp(cProc, 0.0, 1.0);

                        // 6) Interpolación estricta + 4) dither anti-banding.
                        c = mix(c0, cProc, eff);
                        float dAm = (1.0 / 255.0) * eff * smoothstep(0.0, 0.015, Y);
                        float dth = (bayer4(gl_FragCoord.xy) - 0.5) * dAm;
                        c = clamp(c + vec3(dth), 0.0, 1.0);
                    }
                }

                if (bEyeStrain && i > 0.001) {
                    // --- Vista Cansada: FILTRO DE CONFORT (no médico, SIN blur) ---
                    // Referencia en espejo: EyeStrainMath (test unitario;
                    // mantener sincronizado este GLSL con la copia de pruebas).
                    //
                    // ANTES (defectos auditados): la pendiente global con pivot
                    // 0.5 (con = 1-0.08·relax·i) APLANABA medios y extremos por
                    // igual (sin control real de highlights: el blanco solo
                    // bajaba a ~0.96); el lift lr*(1-lift)+lift junto al branch
                    // `vec3(lr)` en negros LAVABA el negro puro hasta ~0.088
                    // (22/255) con relajación plena; la maestra i aparecía dos
                    // veces (efecto cuadrático) con techo ad-hoc 0.8 => la
                    // interpolación original→confort no era lineal ni exacta;
                    // sin modos, sin calidez, sin intensidad propia, sin tests.
                    //
                    // AHORA (por píxel, 0 fetch extra => NITIDEZ INTACTA — no
                    // hay blur: la curva es punto a punto y los gradientes
                    // locales medios se conservan con pendiente 1):
                    //  1) Techo de highlights asintótico C1 (solo Y>kneeHi):
                    //       d = max(Y-kneeHi,0); Y1 = Y-d + d/(1+kHi·d)
                    //     Suave: knee 0.80 / base 1.6 ; Confort: 0.72 / 2.4 ;
                    //     kHi = base·relax (relax=0 => kHi=0 => sin techo,
                    //     identidad exacta). Monótono, pendiente final >0 =>
                    //     nieve/HDR SIN aplanar; blanco a plena Confort ≈0.89.                    //  2) Lift de sombras ANCLADO en el negro EXACTO (0->0,
                    //     nunca lava): wS = (Y/(Y+0.04))·(1-Y)³ (estilo Alto
                    //     Contraste); lift Suave 0.018 / Confort 0.035 ×relax.
                    //  3) Reempaque de cromaticidad con min(scale, 1/maxC):
                    //     ratios intactos y NINGÚN canal sale de [0,1].
                    //  4) Desaturación moderada hacia la MISMA luma (no cambia
                    //     brillo): croma escala exacto ×(1-des); Suave ≤0.10,
                    //     Confort ≤0.20 ×relax => colores SIEMPRE vivos.
                    //  5) Calidez MUY ligera SOLO en Confort, solo atenuación
                    //     (g -1.2%, b -4.5% ×relax): < Fotofobia (b -7%) y
                    //     < Luz Azul (b -24%); sin exceso de amarillo.
                    //  6) Mezcla por eff = uStrainInt·i => OFF/25/50/75/100%
                    //     exactos (la intensidad máxima no destruye la imagen:
                    //     medios con pendiente 1, negros exactos, blanco ≥0.89).
                    float eff = clamp(uStrainInt, 0.0, 1.0) * clamp(i, 0.0, 1.0);
                    if (eff > 0.001) {
                        vec3 c0 = c;
                        float r = clamp(uStrainRelax, 0.0, 1.0);
                        bool comfy = uStrainMode > 0;
                        float Y0 = clamp(dot(c0, LUMA), 0.0, 1.0);

                        float kneeHi = comfy ? 0.72 : 0.80;
                        float kHi = (comfy ? 2.4 : 1.6) * r;
                        float lift = (comfy ? 0.035 : 0.018) * r;
                        float des = (comfy ? 0.20 : 0.10) * r;
                        float warm = comfy ? r : 0.0;

                        // 1) Techo suave de altas luces (C1, asintótico;
                        //    kHi=0 => identidad exacta, sin redondeo).
                        float d = max(Y0 - kneeHi, 0.0);
                        float Y1 = (d > 0.0 && kHi > 0.0)
                            ? (Y0 - d) + d / (1.0 + kHi * d)
                            : Y0;
                        // 2) Lift de sombras anclado en 0 exacto (0 -> 0).
                        float wS = (Y1 > 0.0001)
                            ? (Y1 / (Y1 + 0.04)) * (1.0 - Y1) * (1.0 - Y1) * (1.0 - Y1)
                            : 0.0;
                        float Y2 = Y1 + lift * wS;
                        // 3) Repaque con cromaticidad intacta y sin clip.
                        vec3 c1 = c0;
                        if (Y0 > 0.0001) {
                            float scale = Y2 / Y0;
                            float maxC = max(c0.r, max(c0.g, c0.b));
                            if (maxC > 0.0001) scale = min(scale, 1.0 / maxC);
                            c1 = c0 * scale;
                        }
                        // 4) Desaturación moderada hacia la MISMA luma.
                        vec3 c2 = mix(c1, vec3(Y2), des);
                        // 5) Calidez muy ligera (Confort), solo atenuación.
                        vec3 c3 = c2 * vec3(
                            1.0,
                            1.0 - 0.012 * warm,
                            1.0 - 0.045 * warm
                        );
                        vec3 cProc = clamp(c3, 0.0, 1.0);
                        // 6) Interpolación estricta original <-> confort.
                        c = clamp(mix(c0, cProc, eff), 0.0, 1.0);
                    }
                }

                if (bPhoto && i > 0.001) {
                    // --- Fotofobia: FILTRO DE CONFORT VISUAL (no médico) ---
                    // Reduce la sensación de deslumbramiento atenuando
                    // PROGRESIVAMENTE las zonas excesivamente brillantes.
                    // Referencia en espejo: PhotophobiaMath (test unitario;
                    // mantener sincronizado este GLSL con la copia de pruebas).
                    //
                    // ANTES (defectos auditados): la atenuación adaptativa
                    // dependía del slider de tinte cálido (warm=0 => sin
                    // anti-glare), el tinte AMPLIFICABA el rojo (*1.08 =>
                    // clip de canal), había un corte azul incondicional del
                    // 10% (duplicaba Luz Azul), la desaturación era GLOBAL
                    // (colores muertos fuera de los brillos) y el roll-off
                    // tenía esquina dura en 0.70 (derivada discontinua).
                    //
                    // AHORA (luma Rec.709, espacio gamma codificado):
                    //  1) Anti-glare adaptativo: zonas normales (Y<=0.62)
                    //     INTACTAS; rodilla racional C1 por encima:
                    //       d = max(Y-0.62, 0); Y' = Y-d + d/(1+2.2d)
                    //     pendiente 1 en la rodilla (sin borde visible),
                    //     progresiva (0.70=>-0.012, 0.85=>-0.077, 1.0=>-0.173)
                    //     y pendiente final 0.30 > 0 => detalle de nieve/cielo/
                    //     explosiones/flashes SIN aplanar; asintótica < 1 =>
                    //     sin clip, respeta el orden dinámico (monótona) en
                    //     SDR y HDR; sin pow (más barato que antes).
                    //  2) Anti-glare de color: desaturación SOLO donde hay
                    //     brillo+chroma (máscara smoothstep sobre Y y mx-mn),
                    //     hacia la MISMA luma (invariante => sin lavado),
                    //     máx. el slider => nunca colores muertos en sombras/
                    //     medios.
                    // 3) Opción nocturna (tinte cálido) SOLO por atenuación
                    //     relativa (g -2%, b -7% al máximo): ningún canal
                    //     supera 1 => sin clip; mucho más suave que el tope
                    //     de Luz Azul (b -24% solo al máximo de temperatura)
                    //     y con slider a 0 => Fotofobia pura.
                    //  4) Dither Bayer 4x4 (reutiliza el helper) SOLO en la
                    //     zona comprimida => sin banding en cielos/gradientes.
                    //  5) Mezcla final por eff = uPhotoInt * i =>
                    //     OFF/25/50/75/100% exactos.
                    float eff = clamp(uPhotoInt, 0.0, 1.0) * clamp(i, 0.0, 1.0);
                    if (eff > 0.001) {
                        vec3 c0 = c;
                        float Y = clamp(dot(c0, LUMA), 0.0, 1.0);
                        float warm = clamp(uPhotoWarm, 0.0, 1.0);
                        float desat = clamp(uPhotoDesat, 0.0, 1.0);

                        // 2) Desaturación anti-glare SOLO en brillos saturados.
                        float mx = max(c0.r, max(c0.g, c0.b));
                        float mn = min(c0.r, min(c0.g, c0.b));
                        float chroma = max(mx - mn, 0.0);
                        float glareMask = smoothstep(0.55, 0.92, Y) *
                            smoothstep(0.03, 0.30, chroma);
                        vec3 c1 = mix(c0, vec3(Y), desat * glareMask);

                        // 1) Rodilla suave adaptativa de altas luminancias
                        //    (solo Y > 0.62; pendiente C1 = 1 en el umbral).
                        vec3 c2 = c1;
                        float d = max(Y - 0.62, 0.0);
                        if (d > 0.0) {
                            float Yk = (Y - d) + d / (1.0 + 2.2 * d);
                            c2 = c1 * (Yk / Y); // escala <=1 => sin clip,
                        }                        // cromaticidad intacta
                        // 3) Calidez opcional por atenuación relativa (nunca
                        //    amplifica => ningún canal sale de [0,1]).
                        vec3 c3 = c2 * vec3(
                            1.0,
                            1.0 - 0.02 * warm,
                            1.0 - 0.07 * warm
                        );
                        vec3 cProc = clamp(c3, 0.0, 1.0);

                        // 5) Interpolación estricta + 4) dither anti-banding.
                        c = mix(c0, cProc, eff);
                        float dAm = (1.0 / 255.0) * eff * smoothstep(0.55, 0.75, Y);
                        float dth = (bayer4(gl_FragCoord.xy) - 0.5) * dAm;
                        c = clamp(c + vec3(dth), 0.0, 1.0);
                    }
                }

                if (bBlueLight && i > 0.001) {
                    // --- Luz azul: TEMPERATURA DE COLOR CÁLIDA (von Kries RGB) ---
                    // Referencia en espejo: BlueLightMath (test unitario;
                    // mantener sincronizado este GLSL con la copia de pruebas).
                    //
                    // ANTES (defectos auditados): c.b *= (1-0.30·blue·i) era un
                    // recorte de canal a secas (tipo B *= 0.5 parametrizado); la
                    // "calidez" AMPLIFICABA r (*1.04) y g (*1.015) con clamp =>
                    // clip de canal en rojos/verdes brillantes; la mezcla de esa
                    // compensación iba truncada al 55% mientras el corte azul
                    // se aplicaba a escala; una atenuación global *(1-0.07·blue·i)
                    // oscurecía TODA la imagen (pérdida de brillo/contraste
                    // innecesaria); sin intensidad propia ni temperatura.
                    //
                    // AHORA: matriz DIAGONAL von Kries en RGB (balance de
                    // temperatura de color; equivalente barato a LMS: sin pow,
                    // sin linealizar, sin matrices 3x3):
                    //   gains(t) = (1, 1-gG, 1-gB), gB = 0.24·t, gG = 0.05·t²
                    // SOLO ATENUACIÓN (gains <= 1) => amplificación y clipping
                    // IMPOSIBLES; la relación G/B decide el carácter del tinte
                    // (t bajo => amarillo suave, t alto => ámbar): t=0 Normal
                    // (gains 1,1,1, filtro inerte), t=1 muy cálido
                    // (blanco 1,1,1 => 1,0.95,0.76: cálido, sin amarillo
                    // dominante). La temperatura NO es redundante con la
                    // intensidad: G crece con t² y B con t, así (t=1,int=50%)
                    // y (t=50%,int=100%) dan imágenes distintas.
                    // Mezcla estricta por eff = uBlueLight·i =>
                    // 0/25/50/75/100% exactos; SIN atenuación global.
                    float eff = clamp(uBlueLight, 0.0, 1.0) * clamp(i, 0.0, 1.0);
                    if (eff > 0.001) {
                        vec3 c0 = c;
                        float t = clamp(uBlueTemp, 0.0, 1.0);
                        float gB = 0.24 * t;
                        float gG = 0.05 * t * t;
                        vec3 cProc = c0 * vec3(1.0, 1.0 - gG, 1.0 - gB);
                        c = clamp(mix(c0, cProc, eff), 0.0, 1.0);
                    }
                }

                if (bLowVision && i > 0.001) {
                    // --- Baja Visión (va la ÚLTIMA del pase) -------------------
                    // Sharpening adaptativo + contraste local acotado + lift de
                    // sombras + protección de altas luces. 4 fetch (cruz 1px),
                    // math pura, GLES 2.0. Referencia en espejo: LowVisionMath
                    // (test unitario; mantener sincronizado con este GLSL).
                    //
                    // Garantías:
                    //  - sin halos/ringing: el delta se acota al rango del
                    //    vecindario (centro + 4 vecinos) por canal con holgura
                    //    máxima del 25% (suficiente para detalles finos, corta
                    //    el overshoot) y edgeRoll reduce gain en bordes duros;
                    //  - sin quemado de altas luces: delta * (1 - smoothstep(0.88,1,lY))
                    //    y el lift de sombras decae con (1-lY)^3 ~ 0 en blancos;
                    //  - negros intactos: lift ∝ lY/(lY+0.08) vale 0 en lY=0, y el
                    //    gate `flatGate` impide levantar negros planos (barras);
                    //  - adaptativo: gain baja donde el rango local es grande (bordes
                    //    fuertes) y se corta bajo umbral de ruido (planos/banding);
                    //  - intensidad propia 0..1 (0=OFF) interpolada con la maestra
                    //    vía mix(original, procesado, eff) => 0/25/50/75/100% exactos.
                    float eff = clamp(uLowInt, 0.0, 1.0) * clamp(i, 0.0, 1.0);
                    if (eff > 0.001) {
                        vec3 c0 = c;
                        vec2 tx = 1.0 / uResolution;
                        vec3 nT = texture2D(uTexSampler, vTexCoord + vec2(0.0, -tx.y)).rgb;
                        vec3 sT = texture2D(uTexSampler, vTexCoord + vec2(0.0,  tx.y)).rgb;
                        vec3 eT = texture2D(uTexSampler, vTexCoord + vec2( tx.x, 0.0)).rgb;
                        vec3 wT = texture2D(uTexSampler, vTexCoord + vec2(-tx.x, 0.0)).rgb;
                        vec3 mean4 = (nT + sT + eT + wT) * 0.25;
                        // Unsharp con signo CORRECTO: realza c vs media (el código
                        // anterior usaba (media-c)*gain = blur, no realce).
                        vec3 hf = c0 - mean4;

                        // Estadísticos de vecindario en luma (Rec.709) para la
                        // adaptación y los topes.
                        float ly = dot(c0, LUMA);
                        float ln = dot(nT, LUMA);
                        float ls = dot(sT, LUMA);
                        float le = dot(eT, LUMA);
                        float lw = dot(wT, LUMA);
                        float lMin = min(min(min(ly, ln), ls), min(le, lw));
                        float lMax = max(max(max(ly, ln), ls), max(le, lw));
                        float rng = max(lMax - lMin, 0.0);

                        // Dirección del gradiente (cruz) para ponderar "Bordes".
                        float gx = le - lw;
                        float gy = ls - ln;
                        float gmag = sqrt(gx * gx + gy * gy);

                        // Puertas adaptativas (escalares, coherentes por píxel).
                        float flatGate = smoothstep(0.008, 0.03, rng); // corta ruido/banding en planos
                        float edgeRoll = 1.0 / (1.0 + 6.0 * rng);       // menos gain en bordes fuertes
                        float darkComp = 0.65 + 0.35 * smoothstep(0.0, 0.3, ly); // un pelín menos gain en negros

                        float sh = clamp(uLowSharp, 0.0, 1.0);
                        float ed = clamp(uLowEdge, 0.0, 1.0);
                        float amtD = sh * 0.45 * flatGate * edgeRoll * darkComp;
                        float edgeW = smoothstep(0.015, 0.08, gmag);
                        float amtE = ed * 0.35 * edgeW * (0.35 + 0.65 * flatGate) * edgeRoll * darkComp;

                        vec3 delta = hf * (amtD + amtE);
                        // Damping de croma: la componente de luma del realce se
                        // conserva; el resto de color se atenúa (menos ringing
                        // cromático en bordes con compresión).
                        float dL = dot(delta, LUMA);
                        delta = vec3(dL) + (delta - vec3(dL)) * 0.6;
                        // Tope por vecindario (incluye el centro) con holgura del
                        // 25% del rango local: los detalles finos (texto, 1px) son
                        // extremo local y necesitan poder apartarse un poco para
                        // ganar acutancia; la holgura acota el ringing y edgeRoll
                        // ya reduce el gain en bordes duros => sin halos visibles.
                        vec3 cLo = min(c0, min(min(nT, sT), min(eT, wT)));
                        vec3 cHi = max(c0, max(max(nT, sT), max(eT, wT)));
                        vec3 slack = (cHi - cLo) * 0.25;
                        delta = clamp(delta, cLo - c0 - slack, cHi - c0 + slack);
                        // Protección de altas luces: sin empuje donde puede quemar.
                        delta *= 1.0 - smoothstep(0.88, 1.0, ly);
                        vec3 c2 = c0 + delta;

                        // Lift progresivo de sombras (visible oscuro, neutro):
                        //  - 0 en negros puros (lY=0) y en negros planos (flatGate),
                        //  - decae con (1-lY)^3 => casi nulo en medios y altas luces,
                        //  - mezcla (original, procesado, eff) abajo da OFF/25/50/75/100.
                        float liftAmt = clamp(uLowShadow, 0.0, 1.0) * 0.20 * flatGate;
                        float sw = (ly / (ly + 0.08)) * (1.0 - ly) * (1.0 - ly) * (1.0 - ly);
                        vec3 cProc = clamp(c2 + vec3(liftAmt * sw), 0.0, 1.0);
                        // Interpolación estricta original <-> procesada.
                        c = mix(c0, cProc, eff);
                    }
                }

                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}