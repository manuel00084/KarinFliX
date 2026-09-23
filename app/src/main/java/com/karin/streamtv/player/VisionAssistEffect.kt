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
 *   bit1 = Baja Visión (microclaridad: Laplaciano 4-vecinos + contraste, 4 fetch px)
 *   bit2 = Alto Contraste (curva S + lift de sombras preservando cromaticidad + soft-knee)
 *   bit3 = Fotofobia   (atenuación no lineal de brillo + tinte cálido + desaturación + roll-off)
 *   bit4 = Luz Azul    (protección de luz azul: recorte espectral, calidez, atenuación)
 *   bit5 = Vista Cansada (relajación: baja el contraste duro, levanta sombras, suaviza saturación)
 *
 * Orden interno fijo: Daltonismo → Claridad → Alto Contraste → Vista Cansada →
 * Fotofobia → Luz Azul (el final espectral azul va último para dominar el tinte).
 * Las etapas no elegidas se saltan por bit-test; branching solo por uniforme
 * (barato), máx. 4 fetches extra, sin texturas auxiliares ni LUTs. GLES2.
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
        glProgram.setFloatUniform("uPhotoWarm", s.photoWarm)
        glProgram.setFloatUniform("uPhotoDesat", s.photoDesat)
        glProgram.setFloatUniform("uHcLift", s.hcLift)
        glProgram.setFloatUniform("uHcSoft", s.hcSoft)
        glProgram.setFloatUniform("uStrainRelax", s.strainRelax)
        glProgram.setFloatUniform("uBlueLight", s.blueLight)
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
            uniform float uPhotoWarm;
            uniform float uPhotoDesat;
            uniform float uHcLift;
            uniform float uHcSoft;
            uniform float uStrainRelax; // Vista cansada: nivel de relajación
            uniform float uBlueLight;   // Luz azul: fuerza de protección

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

                if (bLowVision && i > 0.001) {
                    // --- Baja Visión: microclaridad sin LUTs, 4 fetch ---
                    vec2 texel = 1.0 / uResolution;
                    vec3 n = texture2D(uTexSampler, vTexCoord + vec2(0.0, -texel.y)).rgb;
                    vec3 s = texture2D(uTexSampler, vTexCoord + vec2(0.0,  texel.y)).rgb;
                    vec3 e = texture2D(uTexSampler, vTexCoord + vec2( texel.x, 0.0)).rgb;
                    vec3 w = texture2D(uTexSampler, vTexCoord + vec2(-texel.x, 0.0)).rgb;
                    vec3 edge = (n + s + e + w - 4.0 * c);
                    float sharp = clamp(uLowSharp, 0.0, 1.0);
                    float edgeB = clamp(uLowEdge, 0.0, 1.0);
                    // Unsharp ligero + realce de bordes sutil (sin halos).
                    vec3 clarity = c + edge * (0.25 * sharp * i);
                    clarity += edge * (0.15 * edgeB * i);
                    // Contraste de medios + levantado leve de sombras.
                    float con = 1.0 + 0.15 * i;
                    clarity = (clarity - 0.5) * con + 0.5;
                    clarity *= (1.0 + 0.08 * i);
                    c = clamp(mix(c, clarity, clamp(i, 0.0, 0.85)), 0.0, 1.0);
                }

                if (bHighContr && i > 0.001) {
                    // --- Alto Contraste: S-curva + lift de sombras sin quemados ---
                    // Calibrado con la luminancia relativa WCAG (Rec.709, LUMA):
                    // el piso de negros (>=0.03) mantiene el ratio de contraste
                    // acotado y sin clipping, y la curva S empuja los medios hacia
                    // separaciones tipo 4.5:1 sin reventar altas luces.
                    float lift = clamp(uHcLift, 0.0, 1.0);
                    float soft = clamp(uHcSoft, 0.0, 1.0);
                    float lum = dot(c, LUMA);
                    float con = 1.0 + 0.40 * i;
                    float l2 = clamp((lum - 0.5) * con + 0.5, 0.0, 1.0);
                    float gamma = clamp(1.0 - 0.15 * lift * i, 0.40, 1.0);
                    l2 = pow(l2, gamma);
                    // Piso de negros WCAG acotado: evita negros rotos y parpadeo.
                    l2 = mix(l2, max(l2, 0.03), clamp(i, 0.0, 1.0));
                    // Aplicar manteniendo la cromaticidad (escala RGB por luminancia).
                    vec3 hc = (lum > 0.001) ? c * (l2 / lum) : vec3(l2);
                    // Soft-knee para no clipear en negros/blancos.
                    float knee = smoothstep(0.0, 0.20, l2) * (1.0 - smoothstep(0.80, 1.0, l2));
                    float softMix = mix(1.0, 0.95, soft * i);
                    hc = mix(c, hc, softMix * (1.0 - knee * 0.15));
                    c = clamp(mix(c, hc, clamp(i, 0.0, 0.95)), 0.0, 1.0);
                }

                if (bEyeStrain && i > 0.001) {
                    // --- Vista cansada: relajación (menos contraste duro, sombras levantadas) ---
                    float relax = clamp(uStrainRelax, 0.0, 1.0);
                    float lum = dot(c, LUMA);
                    // Reduce el contraste de la imagen (menos esfuerzo de enfoque).
                    float con = 1.0 - 0.08 * relax * i;
                    float lr = (lum - 0.5) * con + 0.5;
                    // Levanta apenas las sombras para aliviar el parpadeo en negros.
                    float lift = 0.05 * relax * i;
                    lr = lr * (1.0 - lift) + lift;
                    vec3 rlx = (lum > 0.001) ? c * (lr / lum) : vec3(lr);
                    // Suaviza la saturación extrema (menos fricción visual).
                    rlx = mix(rlx, vec3(dot(rlx, LUMA)), 0.18 * relax * i);
                    c = clamp(mix(c, rlx, clamp(i, 0.0, 0.8)), 0.0, 1.0);
                }

                if (bPhoto && i > 0.001) {
                    // --- Fotofobia: menos deslumbramiento, legibilidad intacta ---
                    float warm = clamp(uPhotoWarm, 0.0, 1.0);
                    float desat = clamp(uPhotoDesat, 0.0, 1.0);
                    float lum = dot(c, LUMA);
                    // Atenuación no lineal: protege altas luces sin apagar sombras.
                    if (lum > 0.001) {
                        float curve = pow(lum, 1.0 + 0.25 * warm * i);
                        float atten = mix(1.0, curve / lum, clamp(warm * i, 0.0, 0.5));
                        c *= atten;
                    }
                    // Tinte cálido respetando cromaticidad.
                    vec3 warmCol = vec3(
                        c.r * (1.0 + 0.08 * i),
                        c.g * (1.0 + 0.03 * i),
                        c.b * (1.0 - 0.12 * i)
                    );
                    c = mix(c, warmCol, clamp(warm * i, 0.0, 0.6));
                    // Desaturación protectora.
                    float l2 = dot(c, LUMA);
                    c = mix(c, vec3(l2), clamp(desat * i, 0.0, 0.5));
                    // Roll-off suave de las altas luces (anti-glare).
                    float l3 = dot(c, LUMA);
                    float hiMask = max(0.0, l3 - 0.70);
                    c *= (1.0 - hiMask * (0.40 * i));
                    // Reducción muy leve de azul sin virar a morado.
                    c.b *= (1.0 - 0.10 * i);
                    c = clamp(c, 0.0, 1.0);
                }

                if (bBlueLight && i > 0.001) {
                    // --- Luz azul: protección espectral, calidez y atenuación final ---
                    float blue = clamp(uBlueLight, 0.0, 1.0);
                    // Recorte de azul (protección circadiana), mayor que el de fotofobia.
                    c.b *= (1.0 - 0.30 * blue * i);
                    // Calidez suave que compensa la pérdida azul sin teñir a naranja.
                    vec3 warm = vec3(
                        c.r * (1.0 + 0.04 * i),
                        c.g * (1.0 + 0.015 * i),
                        c.b
                    );
                    c = mix(c, clamp(warm, 0.0, 1.0), clamp(blue * i, 0.0, 0.55));
                    // Atenuación discreta del conjunto (menos luz hacia la noches).
                    c *= (1.0 - 0.07 * blue * i);
                    c = clamp(c, 0.0, 1.0);
                }

                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}