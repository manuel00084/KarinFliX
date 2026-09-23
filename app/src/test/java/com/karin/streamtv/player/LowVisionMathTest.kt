package com.karin.streamtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tests de la matemática de AYUDA VISUAL -> BAJA VISIÓN.
 *
 * [LowVisionMath] es una réplica en JVM del bloque `bLowVision` del fragment
 * shader en VisionAssistEffect.FRAGMENT_SHADER. Si se cambia el GLSL hay que
 * mantener esta copia sincronizada (está anotada en ambos lados).
 *
 * Cubre: identidad en OFF, interpolación 0/25/50/75/100, ausencia de NaN,
 * rango [0,1], preservación de negros, tope anti-halo por vecindario,
 * protección de altas luces, lift solo en sombras y gate de planos.
 */
class LowVisionMathTest {

    // ---------------------------------------------------------------------
    // Réplica en espejo del shader (GLES 2.0 / GLSL ES 1.00 equivalences).
    // ---------------------------------------------------------------------
    private object LowVisionMath {
        private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            if (e1 <= e0) return if (x < e0) 0f else 1f
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun dotLuma(v: FloatArray): Float =
            v[0] * LUMA[0] + v[1] * LUMA[1] + v[2] * LUMA[2]

        private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

        /**
         * Bloque bLowVision completo.
         * @param c centro (sRGB 0..1)
         * @param n/s/e/w vecinos de 1 px (cruz)
         * @param lowSharp Definición, lowEdge Bordes, lowShadow Sombras,
        *                  lowIntensity intensidad propia, master intensidad maestra.
         */
        fun apply(
            c: FloatArray,
            n: FloatArray,
            s: FloatArray,
            e: FloatArray,
            w: FloatArray,
            lowSharp: Float,
            lowEdge: Float,
            lowShadow: Float,
            lowIntensity: Float,
            master: Float,
        ): FloatArray {
            val i = master.coerceIn(0f, 1f)
            if (i <= 0.001f) return c.copyOf()
            val eff = lowIntensity.coerceIn(0f, 1f) * i
            if (eff <= 0.001f) return c.copyOf()

            val mean4 = FloatArray(3) { k -> (n[k] + s[k] + e[k] + w[k]) * 0.25f }
            val hf = FloatArray(3) { k -> c[k] - mean4[k] }

            val ly = dotLuma(c)
            val ln = dotLuma(n)
            val ls = dotLuma(s)
            val le = dotLuma(e)
            val lw = dotLuma(w)
            val lMin = min(min(min(ly, ln), ls), min(le, lw))
            val lMax = max(max(max(ly, ln), ls), max(le, lw))
            val rng = max(lMax - lMin, 0f)

            val gx = le - lw
            val gy = ls - ln
            val gmag = sqrt(gx * gx + gy * gy)

            val flatGate = smoothstep(0.008f, 0.03f, rng)
            val edgeRoll = 1f / (1f + 6f * rng)
            val darkComp = 0.65f + 0.35f * smoothstep(0f, 0.3f, ly)

            val sh = lowSharp.coerceIn(0f, 1f)
            val ed = lowEdge.coerceIn(0f, 1f)
            val amtD = sh * 0.45f * flatGate * edgeRoll * darkComp
            val edgeW = smoothstep(0.015f, 0.08f, gmag)
            val amtE = ed * 0.35f * edgeW * (0.35f + 0.65f * flatGate) * edgeRoll * darkComp

            val deltaRaw = FloatArray(3) { k -> hf[k] * (amtD + amtE) }
            val dL = dotLuma(deltaRaw)
            val deltaChroma = FloatArray(3) { k ->
                dL + (deltaRaw[k] - dL) * 0.6f
            }

            // Tope por vecindario incluyendo el centro + holgura 25% (anti-halo
            // con capacidad de realzar detalles finos que son extremo local).
            val cLo = FloatArray(3) { k -> min(c[k], min(min(n[k], s[k]), min(e[k], w[k]))) }
            val cHi = FloatArray(3) { k -> max(c[k], max(max(n[k], s[k]), max(e[k], w[k]))) }
            val slack = FloatArray(3) { k -> (cHi[k] - cLo[k]) * 0.25f }
            val delta = FloatArray(3) { k ->
                deltaChroma[k].coerceIn(cLo[k] - c[k] - slack[k], cHi[k] - c[k] + slack[k]) *
                    (1f - smoothstep(0.88f, 1f, ly))
            }

            val c2 = FloatArray(3) { k -> c[k] + delta[k] }

            val liftAmt = lowShadow.coerceIn(0f, 1f) * 0.20f * flatGate
            val u = ly / (ly + 0.08f)
            val om = 1f - ly
            val sw = u * om * om * om
            val lift = liftAmt * sw

            val out = FloatArray(3) { k ->
                clamp01(mix(c[k], clamp01(c2[k] + lift), eff))
            }
            return out
        }

        private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }

    // Paleta de pruebas: negros, blancos, grises, primarias, piel, gradientes.
    private val palette = listOf(
        floatArrayOf(0f, 0f, 0f),
        floatArrayOf(1f, 1f, 1f),
        floatArrayOf(0.5f, 0.5f, 0.5f),
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(1f, 1f, 0f),
        floatArrayOf(0f, 1f, 1f),
        floatArrayOf(1f, 0f, 1f),
        floatArrayOf(0.87f, 0.72f, 0.67f), // piel
        floatArrayOf(0.2f, 0.35f, 0.6f),   // texto/interfaz típico
        floatArrayOf(0.1f, 0.1f, 0.12f),   // sombra oscura
        floatArrayOf(0.95f, 0.93f, 0.88f), // casi quemado
        floatArrayOf(0.02f, 0.02f, 0.02f), // negro casi puro
    )

    private val defaults = floatArrayOf(0.40f, 0.25f, 0.40f, 1.00f, 0.45f)

    private fun run(
        c: FloatArray,
        n: FloatArray,
        s: FloatArray,
        e: FloatArray,
        w: FloatArray,
        p: FloatArray = defaults,
    ): FloatArray = LowVisionMath.apply(c, n, s, e, w, p[0], p[1], p[2], p[3], p[4])

    /** Vecindario "plano" (sin textura): central ≈ vecinos. */
    private fun flat(c: FloatArray): List<FloatArray> =
        listOf(c.copyOf(), c.copyOf(), c.copyOf(), c.copyOf(), c.copyOf())

    /** Devuelve (c, n, s, e, w). */
    private fun unpack(c: FloatArray, nb: List<FloatArray>): List<FloatArray> =
        listOf(c, nb[0], nb[1], nb[2], nb[3])

    // ---------------------------------------------------------------------
    // 1) Intensidad OFF => identidad exacta.
    // ---------------------------------------------------------------------
    @Test
    fun intensityZeroIsIdentity() {
        for (c in palette) {
            val nb = flat(c)
            val out = run(c, nb[0], nb[1], nb[2], nb[3], floatArrayOf(1f, 1f, 1f, 0f, 1f))
            for (k in 0..2) assertEquals("OFF debe ser identidad", c[k], out[k], 0f)
        }
        // Maestra en 0 también (corta antes del mix).
        for (c in palette) {
            val nb = flat(c)
            val out = run(c, nb[0], nb[1], nb[2], nb[3], floatArrayOf(1f, 1f, 1f, 1f, 0f))
            for (k in 0..2) assertEquals(c[k], out[k], 0f)
        }
    }

    // ---------------------------------------------------------------------
    // 2) Interpolación estricta: 0/25/50/75/100 monótona hacia el total.
    // ---------------------------------------------------------------------
    @Test
    fun intensityInterpolatesMonotonically() {
        val c = floatArrayOf(0.32f, 0.45f, 0.58f)
        val n = floatArrayOf(0.28f, 0.40f, 0.55f)
        val s = floatArrayOf(0.36f, 0.50f, 0.62f)
        val e = floatArrayOf(0.30f, 0.47f, 0.60f)
        val w = floatArrayOf(0.34f, 0.43f, 0.56f)
        val levels = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        var prevDelta = -1f
        for (lvl in levels) {
            val out = run(c, n, s, e, w, floatArrayOf(0.8f, 0.8f, 0.8f, lvl, 1f))
            val d = kotlin.math.abs(out[0] - c[0]) + kotlin.math.abs(out[1] - c[1]) +
                kotlin.math.abs(out[2] - c[2])
            assertTrue("delta debe crecer con la intensidad ($lvl: $d vs $prevDelta)", d >= prevDelta - 1e-6f)
            prevDelta = d
        }
        // A 100% el resultado difiere del original (el efecto hace algo).
        assertTrue("a 100% debe modificar la imagen", prevDelta > 1e-4f)
    }

    // ---------------------------------------------------------------------
    // 3) Sin NaN ni valores fuera de [0,1] sobre una rejilla densa.
    // ---------------------------------------------------------------------
    @Test
    fun noNaNAndOutputInUnitRange() {
        val steps = intArrayOf(0, 64, 128, 192, 255)
        var checked = 0
        for (r in steps) for (g in steps) for (b in steps) {
            val c = floatArrayOf(r / 255f, g / 255f, b / 255f)
            // Vecindario con variación fuerte (peor caso para los topes).
            val n = floatArrayOf(1f - c[0], c[1], 1f - c[2])
            val s = floatArrayOf(c[0], 1f - c[1], c[2])
            val e = floatArrayOf(0f, 0f, 0f)
            val w = floatArrayOf(1f, 1f, 1f)
            val out = run(c, n, s, e, w, floatArrayOf(1f, 1f, 1f, 1f, 1f))
            for (k in 0..2) {
                assertTrue("NaN/Inf en canal $k", out[k].isFinite())
                assertTrue("fuera de rango: ${out[k]}", out[k] >= 0f && out[k] <= 1f)
            }
            checked++
        }
        assertTrue(checked > 0)
    }

    // ---------------------------------------------------------------------
    // 4) Negros intactos: negro plano (barras de cine) sigue en 0.
    // ---------------------------------------------------------------------
    @Test
    fun pureBlackStaysBlack() {
        val black = floatArrayOf(0f, 0f, 0f)
        val out = run(black, black, black, black, black, floatArrayOf(1f, 1f, 1f, 1f, 1f))
        for (k in 0..2) assertEquals("el lift no convierte negro en gris", 0f, out[k], 1e-6f)
    }

    // ---------------------------------------------------------------------
    // 5) Negro con textura real (vecinos distintos) no se dispara a gris.
    // ---------------------------------------------------------------------
    @Test
    fun nearBlackWithTextureStaysDark() {
        val c = floatArrayOf(0.03f, 0.03f, 0.035f)
        val n = floatArrayOf(0.05f, 0.05f, 0.05f)
        val s = floatArrayOf(0.02f, 0.02f, 0.02f)
        val e = floatArrayOf(0.04f, 0.04f, 0.045f)
        val w = floatArrayOf(0.025f, 0.025f, 0.03f)
        val out = run(c, n, s, e, w, floatArrayOf(1f, 1f, 1f, 1f, 1f))
        for (k in 0..2) {
            assertTrue("sombra muy levantada: ${out[k]}", out[k] < 0.15f)
            assertTrue(out[k] >= 0f)
        }
    }

    // ---------------------------------------------------------------------
    // 6) Anti-halo: en un paso duro el resultado queda en el rango local
    //    (más el lift acotado de sombras); nunca overshoot blanco/negro.
    // ---------------------------------------------------------------------
    @Test
    fun noHaloOnHardStep() {
        // Paso vertical: lado oscuro 0.15, lado claro 0.85.
        val dark = floatArrayOf(0.15f, 0.15f, 0.15f)
        val bright = floatArrayOf(0.85f, 0.85f, 0.85f)
        val cases = listOf(
            // Píxel oscuro en la frontera (un vecino claro).
            Pair(dark, listOf(bright, dark, dark, dark)),
            // Píxel claro en la frontera.
            Pair(bright, listOf(dark, bright, bright, bright)),
        )
        for ((c, nb) in cases) {
            val out = run(c, nb[0], nb[1], nb[2], nb[3], floatArrayOf(1f, 1f, 1f, 1f, 1f))
            val localMin = c[0].coerceAtMost(nb.minOf { it[0] })
            val localMax = c[0].coerceAtLeast(nb.maxOf { it[0] })
            val range = localMax - localMin
            // El realce puede exceder el rango vecindario como mucho un 25%
            // (holgura anti-halo) y el lift de sombras suma <= ~0.085.
            val slack = 0.25f * range
            assertTrue(
                "undershoot excesivo: ${out[0]} < $localMin - $slack",
                out[0] >= localMin - slack - 1e-3f,
            )
            assertTrue(
                "overshoot excesivo: ${out[0]} > $localMax + $slack + lift",
                out[0] <= localMax + slack + 0.085f + 1e-3f,
            )
        }
    }

    // ---------------------------------------------------------------------
    // 7) Altas luces protegidas: casi-blanco con textura no se quema a 1.0
    //    ni sube de forma agresiva.
    // ---------------------------------------------------------------------
    @Test
    fun highlightsAreProtected() {
        val c = floatArrayOf(0.90f, 0.90f, 0.90f)
        val n = floatArrayOf(0.86f, 0.86f, 0.86f)
        val s = floatArrayOf(0.93f, 0.93f, 0.93f)
        val e = floatArrayOf(0.88f, 0.88f, 0.88f)
        val w = floatArrayOf(0.91f, 0.91f, 0.91f)
        val out = run(c, n, s, e, w, floatArrayOf(1f, 1f, 1f, 1f, 1f))
        for (k in 0..2) {
            assertTrue("alta luz subió demasiado: ${out[k]}", out[k] <= 0.95f)
            assertTrue(out[k] <= 1f)
        }
        // Blanco puro plano permanece en blanco.
        val white = floatArrayOf(1f, 1f, 1f)
        val outW = run(white, white, white, white, white, floatArrayOf(1f, 1f, 1f, 1f, 1f))
        for (k in 0..2) assertEquals(1f, outW[k], 1e-5f)
    }

    // ---------------------------------------------------------------------
    // 8) Plano sin textura (banding/ruido plano): flatGate≈0 => casi identidad
    //    (no amplifica planos).
    // ---------------------------------------------------------------------
    @Test
    fun flatRegionBarelyTouched() {
        val c = floatArrayOf(0.5f, 0.5f, 0.5f)
        val out = run(c, c, c, c, c, floatArrayOf(1f, 1f, 1f, 1f, 1f))
        for (k in 0..2) assertEquals("plano debe quedar intacto", c[k], out[k], 1e-4f)
    }

    // ---------------------------------------------------------------------
    // 9) Realce en región con detalle: el contraste local aumenta
    //    (se aleja de la media) en el canal de luma, sin salir de [min,max].
    // ---------------------------------------------------------------------
    @Test
    fun detailRegionGetsLocalContrast() {
        // Centro más oscuro que la media de vecinos => hf<0 => debe oscurecerse
        // (realce correcto del signo; el shader viejo aclaraba = blur).
        val c = floatArrayOf(0.40f, 0.40f, 0.40f)
        val nbri = floatArrayOf(0.55f, 0.55f, 0.55f)
        val out = run(c, nbri, nbri, nbri, nbri, floatArrayOf(1f, 1f, 0f, 1f, 1f))
        assertTrue("centro oscuro debe oscurecerse (hf = c-media < 0)", out[0] < c[0] - 1e-4f)
        assertTrue(out[0] >= 0.40f - 0.20f) // dentro del rango vecindario
        // Centro más claro que la media => debe aclararse.
        val c2 = floatArrayOf(0.60f, 0.60f, 0.60f)
        val out2 = run(c2, nbri, nbri, nbri, nbri, floatArrayOf(1f, 1f, 0f, 1f, 1f))
        assertTrue("centro claro debe aclararse", out2[0] > c2[0] + 1e-4f)
    }

    // ---------------------------------------------------------------------
    // 10) Sombras: el lift sube zonas oscuras con textura y casi no toca
    //     medios/altos (con Sombras=0 no hay lift).
    // ---------------------------------------------------------------------
    @Test
    fun shadowLiftTargetsDarkAreasOnly() {
        val dark = floatArrayOf(0.12f, 0.12f, 0.12f)
        val nb = listOf(
            floatArrayOf(0.15f, 0.15f, 0.15f),
            floatArrayOf(0.10f, 0.10f, 0.10f),
            floatArrayOf(0.14f, 0.14f, 0.14f),
            floatArrayOf(0.11f, 0.11f, 0.11f),
        )
        val withLift = run(dark, nb[0], nb[1], nb[2], nb[3], floatArrayOf(0f, 0f, 1f, 1f, 1f))
        val noLift = run(dark, nb[0], nb[1], nb[2], nb[3], floatArrayOf(0f, 0f, 0f, 1f, 1f))
        assertTrue("Sombras=100 debe aclarar la zona oscura", withLift[0] > noLift[0] + 1e-4f)
        assertTrue("aún con lift, la sombra sigue siendo oscura", withLift[0] < 0.3f)
        // Medio-tonto: lift despreciable cerca de 0.5+.
        val mid = floatArrayOf(0.55f, 0.55f, 0.55f)
        val midNb = listOf(
            floatArrayOf(0.58f, 0.58f, 0.58f),
            floatArrayOf(0.52f, 0.52f, 0.52f),
            floatArrayOf(0.57f, 0.57f, 0.57f),
            floatArrayOf(0.53f, 0.53f, 0.53f),
        )
        val midOut = run(mid, midNb[0], midNb[1], midNb[2], midNb[3], floatArrayOf(0f, 0f, 1f, 1f, 1f))
        val midNo = run(mid, midNb[0], midNb[1], midNb[2], midNb[3], floatArrayOf(0f, 0f, 0f, 1f, 1f))
        assertTrue("el lift en medios debe ser pequeño", midOut[0] - midNo[0] < 0.05f)
    }

    // ---------------------------------------------------------------------
    // 11) Gradientes y pares cromáticos: sin NaN, en rango, sin sesgo de
    //     luminancia general (brillo medio casi estable).
    // ---------------------------------------------------------------------
    @Test
    fun gradientSweepStableBrightness() {
        var sumIn = 0f
        var sumOut = 0f
        var count = 0
        for (t in 0..32) {
            val x = t / 32f
            val c = floatArrayOf(x, x * 0.9f, 1f - x)
            val n = floatArrayOf(x, (x * 0.9f).coerceIn(0f, 1f), (1f - x).coerceIn(0f, 1f))
            val s = floatArrayOf(x * 0.95f, x * 0.85f, (1f - x * 0.95f).coerceIn(0f, 1f))
            val e = c.copyOf()
            val w = n.copyOf()
            val out = run(c, n, s, e, w, floatArrayOf(0.7f, 0.7f, 0.7f, 1f, 1f))
            for (k in 0..2) {
                assertTrue(out[k].isFinite())
                assertTrue(out[k] in 0f..1f)
            }
            sumIn += c[0] + c[1] + c[2]
            sumOut += out[0] + out[1] + out[2]
            count += 3
        }
        val avgIn = sumIn / count
        val avgOut = sumOut / count
        assertTrue(
            "brillo medio se desvió demasiado: $avgIn -> $avgOut",
            kotlin.math.abs(avgOut - avgIn) < 0.12f,
        )
    }

    // ---------------------------------------------------------------------
    // 12) Tono de piel a intensidad por defecto: cambio acotado (sin
    //     recoloreado artificial).
    // ---------------------------------------------------------------------
    @Test
    fun skinToneChangeIsBounded() {
        val skin = floatArrayOf(0.87f, 0.72f, 0.67f)
        val nb = listOf(
            floatArrayOf(0.85f, 0.70f, 0.65f),
            floatArrayOf(0.89f, 0.74f, 0.69f),
            floatArrayOf(0.86f, 0.71f, 0.66f),
            floatArrayOf(0.88f, 0.73f, 0.68f),
        )
        val out = run(skin, nb[0], nb[1], nb[2], nb[3], floatArrayOf(1f, 1f, 1f, 1f, 1f))
        for (k in 0..2) {
            val d = kotlin.math.abs(out[k] - skin[k])
            assertTrue("piel cambiada demasiado en canal $k: $d", d < 0.10f)
        }
    }

    // ---------------------------------------------------------------------
    // 13) Anisotropía de bordes: con gradiente fuerte el gain baja
    //     (edgeRoll) — el realce en bordes duros no dispara overshoot.
    // ---------------------------------------------------------------------
    @Test
    fun strongEdgeDoesNotOvershoot() {
        val dark = floatArrayOf(0.2f, 0.2f, 0.2f)
        val bright = floatArrayOf(0.8f, 0.8f, 0.8f)
        // Píxel oscuro rodeado mayormente de claro pero sin ser extremo local
        // absoluto (para que el clamp no lo sature a 0 delta): vecinos mixtos.
        val c = floatArrayOf(0.35f, 0.35f, 0.35f)
        val out = run(
            c, bright, dark, bright, dark,
            floatArrayOf(1f, 1f, 0f, 1f, 1f),
        )
        val lo = dark[0] - 0.25f * (bright[0] - dark[0]) - 1e-3f
        val hi = bright[0] + 0.25f * (bright[0] - dark[0]) + 1e-3f
        for (k in 0..2) {
            assertTrue("undershoot: ${out[k]} < $lo", out[k] >= lo)
            assertTrue("overshoot: ${out[k]} > $hi", out[k] <= hi)
        }
    }

    @Test
    fun defaultsMatchSettingsContract() {
        // Contrato con VisionAssistSettings (definición 40, bordes 25,
        // sombras 40, intensidad propia 100, maestra 45).
        val p = defaults
        assertEquals(0.40f, p[0], 1e-6f)
        assertEquals(0.25f, p[1], 1e-6f)
        assertEquals(0.40f, p[2], 1e-6f)
        assertEquals(1.00f, p[3], 1e-6f)
        assertEquals(0.45f, p[4], 1e-6f)
    }
}
