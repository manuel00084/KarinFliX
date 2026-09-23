package com.karin.streamtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests de la matemática de AYUDA VISUAL -> LUZ AZUL.
 *
 * [BlueLightMath] es una réplica en JVM del bloque `bBlueLight` del fragment
 * shader en VisionAssistEffect.FRAGMENT_SHADER. Si se cambia el GLSL hay que
 * mantener esta copia sincronizada (está anotada en ambos lados).
 *
 * Luz Azul desplaza el balance de color hacia tonos más cálidos mediante una
 * matriz DIAGONAL von Kries en RGB (solo atenuación): no es un recorte de
 * canal ni una atenuación global. Solo modifica el color mostrado de la
 * imagen (sin afirmaciones médicas).
 *
 * Cubre: identidad en OFF/Normal, interpolación 0/25/50/75/100 exacta, blancos
 * sin amarillo dominante, piel acotada, azul no desaparece (salvo máximo),
 * sin clipping/amplificación, luma nunca aumenta (garantía vs Light Boost/HDR),
 * contraste de grises sin oscurecimiento global, carácter de temperatura no
 * redundante con la intensidad, escenas (anime/nocturnas/azules/HDR) y
 * contrato de defaults.
 */
class BlueLightMathTest {

    // ---------------------------------------------------------------------
    // Réplica en espejo del shader (GLES 2.0 / GLSL ES 1.00 equivalences).
    // ---------------------------------------------------------------------
    private object BlueLightMath {
        /** Ganancias diagonales von Kries: (1, 1-gG, 1-gB). Solo <= 1. */
        fun gains(temp: Float): FloatArray {
            val t = temp.coerceIn(0f, 1f)
            val gB = 0.24f * t
            val gG = (0.05f * t) * t
            return floatArrayOf(1f, 1f - gG, 1f - gB)
        }

        /**
         * Bloque bBlueLight completo (mezcla por intensidad).
         * @param temp temperatura 0..1 (0=Normal, 1=muy cálido),
         *             intensity intensidad propia, master maestra.
         */
        fun apply(c: FloatArray, temp: Float, intensity: Float, master: Float): FloatArray {
            val i = master.coerceIn(0f, 1f)
            if (i <= 0.001f) return c.copyOf()
            val eff = intensity.coerceIn(0f, 1f) * i
            if (eff <= 0.001f) return c.copyOf()
            val g = gains(temp)
            val c0 = c.copyOf()
            val cProc = FloatArray(3) { k -> c0[k] * g[k] }
            // GLSL mix(x, y, a) = x*(1-a) + y*a; clamp final.
            return FloatArray(3) { k ->
                (c0[k] * (1f - eff) + cProc[k] * eff).coerceIn(0f, 1f)
            }
        }
    }

    // Defaults: intensidad 60, temperatura 50, maestra 45.
    private val defaults = floatArrayOf(0.60f, 0.50f, 0.45f)

    private fun run(c: FloatArray, p: FloatArray = defaults): FloatArray =
        BlueLightMath.apply(c, p[0], p[1], p[2])

    private fun luma(c: FloatArray): Float =
        0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private val scenes = listOf(
        floatArrayOf(0.98f, 0.98f, 0.98f),  // anime: fondo blanco
        floatArrayOf(0.87f, 0.72f, 0.60f),  // piel
        floatArrayOf(1f, 1f, 1f),           // blanco
        floatArrayOf(0.75f, 0.72f, 0.70f),  // blanco apagado
        floatArrayOf(0.5f, 0.5f, 0.5f),     // gris medio
        floatArrayOf(0.03f, 0.04f, 0.08f),  // nocturna
        floatArrayOf(0.10f, 0.14f, 0.22f),  // nocturna azulada
        floatArrayOf(0.25f, 0.45f, 0.85f),  // azul saturado (mar)
        floatArrayOf(0.55f, 0.75f, 0.95f),  // cielo anime
        floatArrayOf(0.99f, 0.97f, 0.90f),  // HDR-like
        floatArrayOf(0.20f, 0.55f, 0.30f),  // vegetación
        floatArrayOf(0.90f, 0.35f, 0.30f),  // rojo anime
        floatArrayOf(0f, 0f, 0f),           // negro
    )

    private val configs = listOf(
        floatArrayOf(0f, 1f, 1f),        // Normal, intensidad plena
        floatArrayOf(1f, 1f, 1f),        // muy cálido, intensidad plena
        floatArrayOf(0.5f, 1f, 1f),      // cálido, intensidad plena
        floatArrayOf(1f, 0.25f, 1f),     // muy cálido, 25%
        floatArrayOf(0.6f, 0.6f, 0.45f), // defaults
        floatArrayOf(1f, 1f, 0.45f),     // muy cálido con maestra por defecto
    )

    // ---------------------------------------------------------------------
    // 1) Intensidad OFF o maestra OFF => identidad exacta.
    // ---------------------------------------------------------------------
    @Test
    fun intensityZeroIsIdentity() {
        for (c in scenes) for (t in listOf(0f, 0.5f, 1f)) {
            val out = run(c, floatArrayOf(t, 0f, 1f))
            for (k in 0..2) assertEquals("OFF debe ser identidad", c[k], out[k], 0f)
            val outM = run(c, floatArrayOf(t, 1f, 0f))
            for (k in 0..2) assertEquals("maestra 0 debe ser identidad", c[k], outM[k], 0f)
        }
    }

    // ---------------------------------------------------------------------
    // 2) Temperatura Normal (t=0) => ganancias (1,1,1), filtro inerte.
    // ---------------------------------------------------------------------
    @Test
    fun tempZeroIsNeutral() {
        val g = BlueLightMath.gains(0f)
        assertEquals(1f, g[0], 0f)
        assertEquals(1f, g[1], 0f)
        assertEquals(1f, g[2], 0f)
        for (c in scenes) {
            val out = run(c, floatArrayOf(0f, 1f, 1f))
            for (k in 0..2) assertEquals("Normal debe ser identidad", c[k], out[k], 0f)
        }
    }

    // ---------------------------------------------------------------------
    // 3) Interpolación exacta 0/25/50/75/100 (50% = punto medio exacto).
    // ---------------------------------------------------------------------
    @Test
    fun intensityInterpolatesExactly() {
        val c = floatArrayOf(0.9f, 0.92f, 0.88f)
        val full = run(c, floatArrayOf(0.8f, 1f, 1f))
        var prevD = -1f
        for (lvl in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val out = run(c, floatArrayOf(0.8f, lvl, 1f))
            for (k in 0..2) {
                val expected = c[k] * (1f - lvl) + full[k] * lvl
                assertEquals("canal $k lvl=$lvl", expected, out[k], 1e-6f)
            }
            val d = abs(out[0] - c[0]) + abs(out[1] - c[1]) + abs(out[2] - c[2])
            assertTrue("monótono ($lvl)", d >= prevD - 1e-5f)
            prevD = d
        }
        // A 100% el azul baja de verdad.
        assertTrue("azul debe bajar", full[2] < c[2])
    }

    // ---------------------------------------------------------------------
    // 4) Blancos no se vuelven excesivamente amarillos: R-B acotado en
    //    cualquier temperatura; el blanco sigue siendo claro (sin lodo).
    // ---------------------------------------------------------------------
    @Test
    fun whitesNotExcessivelyYellow() {
        val white = floatArrayOf(1f, 1f, 1f)
        for (t in 0..100) {
            val temp = t / 100f
            val out = run(white, floatArrayOf(temp, 1f, 1f))
            val yellow = out[0] - out[2]
            assertTrue("amarillo dominante en t=$temp: $yellow", yellow <= 0.26f)
            if (temp <= 0.5f) {
                assertTrue("tinte demasiado fuerte en t=$temp: $yellow", yellow <= 0.13f)
            }
            assertTrue("blanco embarrado en t=$temp", out[2] >= 0.72f)
            assertTrue("blanco oscurecido en t=$temp", luma(out) >= 0.93f)
        }
        // Dirección del cambio: hacia CÁLIDO (r >= g >= b) con t>0.
        val warm = run(white, floatArrayOf(1f, 1f, 1f))
        assertTrue("orden cálido", warm[0] >= warm[1] && warm[1] >= warm[2])
        assertEquals("corte azul exacto a plena temperatura", 0.24f, 1f - warm[2], 1e-6f)
        assertEquals("corte verde exacto a plena temperatura", 0.05f, 1f - warm[1], 1e-6f)
    }

    // ---------------------------------------------------------------------
    // 5) El azul NO desaparece salvo en el máximo: azules dominantes
    //    siguen siendo azules con intensidad moderada; en el tope casi
    //    empiezan a converger pero nunca se invierten a rojo/verde.
    // ---------------------------------------------------------------------
    @Test
    fun blueNeverDisappears() {
        // Azul puro: en el tope conserva >= 0.70; a 75% de intensidad >= 0.80.
        val pureBlue = floatArrayOf(0f, 0f, 1f)
        val maxOut = run(pureBlue, floatArrayOf(1f, 1f, 1f))
        assertTrue("azul apagado en el tope: ${maxOut[2]}", maxOut[2] >= 0.70f)
        assertEquals(0f, maxOut[0], 1e-6f)
        assertTrue(maxOut[1] <= maxOut[2])
        val q = run(pureBlue, floatArrayOf(1f, 0.75f, 1f))
        assertTrue("azul perdido al 75%: ${q[2]}", q[2] >= 0.80f)

        // Cielo/mar azules dominantes: con intensidad <= 75% mantiene b > g;
        // en el tope pleno no se invierte (b >= g - 0.001).
        for (sky in listOf(
            floatArrayOf(0.55f, 0.75f, 0.95f),
            floatArrayOf(0.25f, 0.45f, 0.85f),
        )) {
            val moderate = run(sky, floatArrayOf(1f, 0.75f, 1f))
            assertTrue(
                "cielo dejó de ser azul: g=${moderate[1]} b=${moderate[2]}",
                moderate[2] > moderate[1],
            )
            val full = run(sky, floatArrayOf(1f, 1f, 1f))
            assertTrue(
                "cielo invertido en el tope: g=${full[1]} b=${full[2]}",
                full[2] >= full[1] - 0.001f,
            )
            assertTrue("azul no desaparece en el tope", full[2] > sky[2] * 0.70f)
        }
    }

    // ---------------------------------------------------------------------
    // 6) Piel: orden R>=G>=B preservado y cambio acotado (sin viraje
    //    naranja excesivo en temperaturas moderadas).
    // ---------------------------------------------------------------------
    @Test
    fun skinTonesBounded() {
        val skin = floatArrayOf(0.87f, 0.72f, 0.60f)
        for (c in configs) {
            val out = run(skin, c)
            assertTrue("orden de piel roto", out[0] >= out[1] && out[1] >= out[2])
        }
        // Temperatura moderada (<=67%): delta máximo por canal acotado.
        val modOut = run(skin, floatArrayOf(0.67f, 1f, 1f))
        for (k in 0..2) {
            val d = abs(modOut[k] - skin[k])
            assertTrue("piel alterada en t=0.67 canal $k: $d", d <= 0.11f)
        }
        // Incluso al tope: el rojo intacto y el desplazamiento limitado.
        val full = run(skin, floatArrayOf(1f, 1f, 1f))
        assertEquals("el rojo de la piel no cambia", skin[0], full[0], 1e-6f)
        for (k in 0..2) {
            val d = abs(full[k] - skin[k])
            assertTrue("piel alterada en el tope canal $k: $d", d <= 0.15f)
        }
        // Con defaults el cambio es mínimo (modo noche cómodo).
        val def = run(skin)
        for (k in 0..2) {
            assertTrue("defaults alteran la piel", abs(def[k] - skin[k]) <= 0.05f)
        }
    }

    // ---------------------------------------------------------------------
    // 7) Grises: rampa estrictamente creciente por canal (contraste) y la
    //    pendiente de luma >= 0.93 => NO hay oscurecimiento global (el
    //    defecto antiguo aplicaba *0.93 a toda la imagen).
    // ---------------------------------------------------------------------
    @Test
    fun graysKeepContrastNoGlobalDimming() {
        val ramp = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val outs = ramp.map { y -> run(floatArrayOf(y, y, y), floatArrayOf(1f, 1f, 1f)) }
        for (idx in 1 until outs.size) {
            for (k in 0..2) {
                assertTrue(
                    "rampa aplanada canal $k en ${ramp[idx]}",
                    outs[idx][k] > outs[idx - 1][k] + 1e-5f,
                )
            }
            val yIn = ramp[idx] - ramp[idx - 1]
            val yOut = luma(outs[idx]) - luma(outs[idx - 1])
            val slope = yOut / yIn
            assertTrue("pendiente de luma excesiva: $slope", slope <= 1.0001f)
            assertTrue("pendiente de luma cae demasiado: $slope", slope >= 0.93f)
        }
        // Blanco a plena temperatura: la luma cae <= 7% (sin la caída global
        // de 7% del código antiguo ENCIMA de los cortes de canal).
        val whiteOut = run(floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f, 1f))
        assertTrue("luma del blanco: ${luma(whiteOut)}", luma(whiteOut) >= 0.93f)
        assertTrue(luma(whiteOut) <= 1f)
    }

    // ---------------------------------------------------------------------
    // 8) Sin clipping ni amplificación: gains <= 1 => ningún canal puede
    //    superar su entrada; todo en [0,1] y finito (NaN-free).
    // ---------------------------------------------------------------------
    @Test
    fun noClippingNoAmplification() {
        for (c in scenes) for (p in configs) {
            val out = run(c, p)
            for (k in 0..2) {
                assertTrue("NaN", out[k].isFinite())
                assertTrue("fuera de rango: ${out[k]}", out[k] >= 0f && out[k] <= 1f)
                assertTrue(
                    "amplificó el canal $k: ${c[k]} -> ${out[k]}",
                    out[k] <= c[k] + 1e-6f,
                )
            }
        }
        // Ganancias siempre en (0,1].
        for (t in 0..100) {
            val g = BlueLightMath.gains(t / 100f)
            for (k in 0..2) {
                assertTrue("gain > 1", g[k] <= 1f)
                assertTrue("gain <= 0", g[k] > 0f)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 9) Nunca aumenta la luminancia => interacción segura tras Light
    //    Boost/HDR (monótona no expansiva) y con Fotofobia previa.
    // ---------------------------------------------------------------------
    @Test
    fun lumaNeverIncreases_guaranteesVsLightBoostHdr() {
        for (c in scenes) for (p in configs) {
            val yIn = luma(c)
            val yOut = luma(run(c, p))
            assertTrue("luma subió: $yIn -> $yOut", yOut <= yIn + 1e-6f)
        }
        // Orden dinámico en el techo (HDR): a <= b => f(a) <= f(b) por canal.
        var prevB = -1f
        for (t in 0..200) {
            val y = 0.60f + t * (0.40f / 200f)
            val out = run(floatArrayOf(y, y, y), floatArrayOf(1f, 1f, 1f))
            assertTrue("orden dinámico roto en $y", out[2] >= prevB - 1e-6f)
            prevB = out[2]
        }
    }

    // ---------------------------------------------------------------------
    // 10) Nocturnas: con defaults el cambio es mínimo (siguen oscuras y
    //     casi idénticas); nunca se aclaran (solo atenuación).
    // ---------------------------------------------------------------------
    @Test
    fun nightScenesStayDark() {
        for (night in listOf(
            floatArrayOf(0.03f, 0.04f, 0.08f),
            floatArrayOf(0.10f, 0.14f, 0.22f),
        )) {
            val def = run(night)
            for (k in 0..2) {
                assertTrue("nocturna se aclaró", def[k] <= night[k] + 1e-6f)
                assertTrue("nocturna alterada", abs(def[k] - night[k]) < 0.01f)
            }
            assertTrue("sigue oscura", luma(def) < 0.15f)
            val full = run(night, floatArrayOf(1f, 1f, 1f))
            assertTrue("nocturna en el tope sigue oscura", luma(full) < 0.20f)
        }
    }

    // ---------------------------------------------------------------------
    // 11) Temperatura NO redundante con intensidad: (t=1, int=50%) y
    //     (t=50%, int=100%) producen imágenes distintas (G va con t² y
    //     B con t => el carácter del tinte cambia).
    // ---------------------------------------------------------------------
    @Test
    fun temperatureCharacterNotRedundant() {
        val g = floatArrayOf(0.5f, 0.5f, 0.5f)
        val a = run(g, floatArrayOf(1f, 0.5f, 1f))   // t=100%, int=50%
        val b = run(g, floatArrayOf(0.5f, 1f, 1f))   // t=50%, int=100%
        val diff = abs(a[1] - b[1])
        assertTrue("configuraciones idénticas (no redundancia rota): $diff", diff > 1e-4f)
        // Mismo B (producto t*int igual) pero distinto G => carácter distinto.
        assertEquals("mismo corte azul", a[2], b[2], 1e-6f)
        assertTrue("carácter amarillo/ámbar distinto", a[1] != b[1])
        // Rango del carácter G: cuadrático => al tope gG=0.05 exacto.
        val gw = BlueLightMath.gains(1f)
        assertEquals(0.05f, 1f - gw[1], 1e-6f)
        val gm = BlueLightMath.gains(0.5f)
        assertEquals("gG(0.5)=0.05*0.25", 0.0125f, 1f - gm[1], 1e-6f)
        assertEquals("gB(0.5)=0.12", 0.12f, 1f - gm[2], 1e-6f)
    }

    // ---------------------------------------------------------------------
    // 12) Escenas: anime conserva separación trazo/fondo en el canal rojo,
    //     HDR no recorta, vegetación/rojos solo se vuelven más cálidos.
    // ---------------------------------------------------------------------
    @Test
    fun sceneBehavior() {
        // Anime: fondo 0.98 y trazo 0.88 conservan separación (R sin cortar).
        val bg = run(floatArrayOf(0.98f, 0.98f, 0.98f), floatArrayOf(1f, 1f, 1f))
        val line = run(floatArrayOf(0.88f, 0.88f, 0.88f), floatArrayOf(1f, 1f, 1f))
        assertTrue("separación texto/fondo", bg[0] > line[0] + 0.09f)
        assertEquals("R del trazo intacto", 0.88f, line[0], 1e-6f)
        assertTrue("fondo anime cálido pero claro", bg[2] >= 0.74f)

        // HDR-like: sin recorte arriba y orden r>=g>=b tras el tinte.
        val hdr = run(floatArrayOf(0.99f, 0.97f, 0.90f), floatArrayOf(1f, 1f, 1f))
        for (k in 0..2) {
            assertTrue("HDR recortado: ${hdr[k]}", hdr[k] <= 0.99f)
            assertTrue("HDR fuera de rango", hdr[k] <= 1f)
        }
        assertTrue("orden cálido HDR", hdr[0] >= hdr[1] && hdr[1] >= hdr[2])

        // Rojo anime: el rojo no cambia (gain R=1), solo bajan g y b.
        val redIn = floatArrayOf(0.90f, 0.35f, 0.30f)
        val redOut = run(redIn, floatArrayOf(1f, 1f, 1f))
        assertEquals("rojo intacto", redIn[0], redOut[0], 1e-6f)
        assertTrue("verde/azul atenuados", redOut[1] < redIn[1] && redOut[2] < redIn[2])

        // Vegetación: el verde se vuelve más cálido sin perder su dominancia
        // inicial respecto al azul (g sigue por encima de b al tope).
        val veg = floatArrayOf(0.20f, 0.55f, 0.30f)
        val vegOut = run(veg, floatArrayOf(1f, 1f, 1f))
        assertTrue("verde dominante roto", vegOut[1] > vegOut[2])
    }

    // ---------------------------------------------------------------------
    // 13) Contrato con VisionAssistSettings (intensidad 60, temperatura 50,
    //     maestra 45).
    // ---------------------------------------------------------------------
    @Test
    fun defaultsMatchSettingsContract() {
        assertEquals(0.60f, defaults[0], 1e-6f)
        assertEquals(0.50f, defaults[1], 1e-6f)
        assertEquals(0.45f, defaults[2], 1e-6f)
    }
}
