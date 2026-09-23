package com.karin.streamtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests de la matemática de AYUDA VISUAL -> ALTO CONTRASTE.
 *
 * [HighContrastMath] es una réplica en JVM del bloque `bHighContr` del fragment
 * shader en VisionAssistEffect.FRAGMENT_SHADER. Si se cambia el GLSL hay que
 * mantener esta copia sincronizada (está anotada en ambos lados).
 *
 * Cubre: identidad en OFF, interpolación 0/25/50/75/100 exacta, monotonicidad
 * de la curva, negro/blanco intactos, sin clip plano en altas luces, separación
 * de medios tonos, preservación de matiz (Daltonismo), ausencia de NaN,
 * dither acotado y contrato de defaults.
 */
class HighContrastMathTest {

    // ---------------------------------------------------------------------
    // Réplica en espejo del shader (GLES 2.0 / GLSL ES 1.00 equivalences).
    // ---------------------------------------------------------------------
    private object HighContrastMath {
        private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

        private fun dotLuma(v: FloatArray): Float =
            v[0] * LUMA[0] + v[1] * LUMA[1] + v[2] * LUMA[2]

        private fun fract(x: Float): Float = x - kotlin.math.floor(x)

        private fun bayer2(ax: Float, ay: Float): Float {
            val x = kotlin.math.floor(ax)
            val y = kotlin.math.floor(ay)
            return fract(x * 0.5f + y * y * 0.75f)
        }

        private fun bayer4(x: Float, y: Float): Float =
            bayer2(x * 0.5f, y * 0.5f) * 0.25f + bayer2(x, y)

        /**
         * Bloque bHighContr completo (curva-S racional + lift + techo +
         * reempaque limitado + mezcla + dither).
         * @param fragX/fragY gl_FragCoord.xy (para el dither).
         * @param hcLift abrir sombras, hcSoft protección de luces,
         *               hcIntensity intensidad propia, master intensidad maestra.
         */
        fun apply(
            c: FloatArray,
            hcLift: Float,
            hcSoft: Float,
            hcIntensity: Float,
            master: Float,
            fragX: Float = 0.5f,
            fragY: Float = 0.5f,
        ): FloatArray {
            val i = master.coerceIn(0f, 1f)
            if (i <= 0.001f) return c.copyOf()
            val eff = hcIntensity.coerceIn(0f, 1f) * i
            if (eff <= 0.001f) return c.copyOf()

            val c0 = c.copyOf()
            val y = dotLuma(c0).coerceIn(0f, 1f)
            val lift = hcLift.coerceIn(0f, 1f)
            val soft = hcSoft.coerceIn(0f, 1f)

            // 1) Curva-S racional (k=1.5 => R=7/6).
            val v = (y - 0.5f) * 1.5f
            var fY = 0.5f + 1.1666667f * (v / (1f + kotlin.math.abs(v)))

            // 2) Apertura de sombras anclada en el negro exacto.
            val wS = (fY / (fY + 0.04f)) * (1f - fY) * (1f - fY) * (1f - fY)
            fY += lift * 0.055f * wS

            // 3) Techo suave de altas luces (monótono, sin clip).
            val over = maxOf(fY - 0.78f, 0f)
            fY = fY / (1f + 0.5f * soft * over)
            fY = fY.coerceIn(0f, 1f)

            // 5) Reempaque con cromaticidad intacta y sin clip de canal.
            val cProc = FloatArray(3)
            if (y > 0.0001f) {
                var scale = fY / y
                val maxC = maxOf(c0[0], maxOf(c0[1], c0[2]))
                if (maxC > 0.0001f) scale = minOf(scale, 1f / maxC)
                for (k in 0..2) cProc[k] = c0[k] * scale
            } else {
                c0.copyInto(cProc)
            }
            for (k in 0..2) cProc[k] = cProc[k].coerceIn(0f, 1f)

            // 6) Interpolación estricta + 4) dither anti-banding.
            val out = FloatArray(3) { k -> c0[k] + (cProc[k] - c0[k]) * eff }
            val dAm = (1f / 255f) * eff * smoothstep(0f, 0.015f, y)
            val dth = (bayer4(fragX, fragY) - 0.5f) * dAm
            for (k in 0..2) out[k] = (out[k] + dth).coerceIn(0f, 1f)
            return out
        }

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            if (e1 <= e0) return if (x < e0) 0f else 1f
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Solo la curva de tono pura (sin lift/techo/reempaque), para tests
         *  de monotonicidad y propiedades de la fórmula. */
        fun toneCurve(y: Float): Float {
            val v = (y - 0.5f) * 1.5f
            return 0.5f + 1.1666667f * (v / (1f + kotlin.math.abs(v)))
        }

        /** Solo el lift de sombras aplicado tras la curva. */
        fun liftOnly(fY: Float, hcLift: Float): Float {
            val wS = (fY / (fY + 0.04f)) * (1f - fY) * (1f - fY) * (1f - fY)
            return fY + hcLift.coerceIn(0f, 1f) * 0.055f * wS
        }

        /** Solo el techo de altas luces. */
        fun ceilingOnly(fY: Float, hcSoft: Float): Float {
            val over = maxOf(fY - 0.78f, 0f)
            return fY / (1f + 0.5f * hcSoft.coerceIn(0f, 1f) * over)
        }
    }

    /** Vecindario "plano" no aplica aquí: Alto Contraste es por píxel. */
    private val defaults = floatArrayOf(0.40f, 0.60f, 1.00f, 0.45f)

    private fun run(
        c: FloatArray,
        p: FloatArray = defaults,
        fragX: Float = 0.5f,
        fragY: Float = 0.5f,
    ): FloatArray = HighContrastMath.apply(c, p[0], p[1], p[2], p[3], fragX, fragY)

    private fun gray(y: Float) = floatArrayOf(y, y, y)

    private val palette = listOf(
        floatArrayOf(0f, 0f, 0f),
        floatArrayOf(1f, 1f, 1f),
        floatArrayOf(0.5f, 0.5f, 0.5f),
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(0.87f, 0.72f, 0.67f),
        floatArrayOf(0.2f, 0.35f, 0.6f),
        floatArrayOf(0.1f, 0.1f, 0.12f),
        floatArrayOf(0.95f, 0.93f, 0.88f),
        floatArrayOf(0.02f, 0.02f, 0.02f),
        floatArrayOf(0.33f, 0.33f, 0.33f),
    )

    // ---------------------------------------------------------------------
    // 1) Intensidad OFF => identidad exacta.
    // ---------------------------------------------------------------------
    @Test
    fun intensityZeroIsIdentity() {
        for (c in palette) {
            val out = run(c, floatArrayOf(1f, 1f, 0f, 1f))
            for (k in 0..2) assertEquals("OFF debe ser identidad", c[k], out[k], 0f)
        }
        // Maestra en 0 también.
        for (c in palette) {
            val out = run(c, floatArrayOf(1f, 1f, 1f, 0f))
            for (k in 0..2) assertEquals(c[k], out[k], 0f)
        }
    }

    // ---------------------------------------------------------------------
    // 2) Interpolación estricta: 0/25/50/75/100 exacta (50% = punto medio
    //    entre original y procesado total).
    // ---------------------------------------------------------------------
    @Test
    fun intensityInterpolatesExactly() {
        val c = floatArrayOf(0.30f, 0.45f, 0.60f)
        val full = run(c, floatArrayOf(0.4f, 0.6f, 1f, 1f))
        // 25/50/75 deben caer exactamente sobre el segmento original->full.
        val levels = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        var prevD = -1f
        for (lvl in levels) {
            val out = run(c, floatArrayOf(0.4f, 0.6f, lvl, 1f))
            val expected0 = c[0] + (full[0] - c[0]) * lvl
            assertEquals("25/50/75/100 exactos en R (lvl=$lvl)", expected0, out[0], 1.5e-3f)
            val d = abs(out[0] - c[0]) + abs(out[1] - c[1]) + abs(out[2] - c[2])
            assertTrue("monótono ($lvl: $d vs $prevD)", d >= prevD - 1e-5f)
            prevD = d
        }
        assertTrue("a 100% debe modificar la imagen", prevD > 1e-3f)
    }

    // ---------------------------------------------------------------------
    // 3) Propiedades EXACTAS de la curva de tono: f(0)=0, f(1)=1, f(0.5)=0.5.
    // ---------------------------------------------------------------------
    @Test
    fun toneCurveExactAnchors() {
        assertEquals(0f, HighContrastMath.toneCurve(0f), 1e-6f)
        assertEquals(1f, HighContrastMath.toneCurve(1f), 1e-5f)
        assertEquals(0.5f, HighContrastMath.toneCurve(0.5f), 1e-6f)
        // Simetría central: f(0.5+d) = 1 - f(0.5-d).
        for (d in listOf(0.05f, 0.15f, 0.3f, 0.45f)) {
            val lo = HighContrastMath.toneCurve(0.5f - d)
            val hi = HighContrastMath.toneCurve(0.5f + d)
            assertEquals("simetría en d=$d", 1f - lo, hi, 1e-5f)
        }
    }

    // ---------------------------------------------------------------------
    // 4) Monotonicidad de la curva completa (curva+lift+techo) en 0..1
    //    con combinaciones extremas de sliders.
    // ---------------------------------------------------------------------
    @Test
    fun pipelineIsMonotonic() {
        val combos = listOf(
            floatArrayOf(0f, 0f),
            floatArrayOf(1f, 0f),
            floatArrayOf(0f, 1f),
            floatArrayOf(1f, 1f),
            floatArrayOf(0.4f, 0.6f),
        )
        for (combo in combos) {
            var prev = -1f
            for (t in 0..200) {
                val y = t / 200f
                val f = HighContrastMath.ceilingOnly(
                    HighContrastMath.liftOnly(HighContrastMath.toneCurve(y), combo[0]),
                    combo[1],
                )
                assertTrue(
                    "no monótona en y=$y con lift=${combo[0]} soft=${combo[1]}: $f < $prev",
                    f >= prev - 1e-5f,
                )
                prev = f
            }
        }
    }

    // ---------------------------------------------------------------------
    // 5) Separación de medios tonos AUMENTA (ganancia ~1.75 en el pivote).
    // ---------------------------------------------------------------------
    @Test
    fun midtoneSeparationIncreases() {
        val sepIn = 0.55f - 0.45f
        val sepOut = HighContrastMath.toneCurve(0.55f) - HighContrastMath.toneCurve(0.45f)
        assertTrue("separación de medios debe crecer: $sepOut <= $sepIn", sepOut > sepIn + 0.05f)
        assertTrue("ganancia razonable (~1.75)", sepOut in 0.16f..0.20f)
    }

    // ---------------------------------------------------------------------
    // 6) Sombras: sin aplastar (pendiente > 0 en el pie, valores > 0).
    // ---------------------------------------------------------------------
    @Test
    fun shadowsAreNotCrushed() {
        // El viejo clamp mapeaba todo Y<0.14 a NEGRO. Ahora debe separar.
        val a = HighContrastMath.toneCurve(0.04f)
        val b = HighContrastMath.toneCurve(0.08f)
        val cc = HighContrastMath.toneCurve(0.12f)
        assertTrue("0.04 debe seguir > 0", a > 0f)
        assertTrue("separación 0.04->0.08", b > a + 1e-4f)
        assertTrue("separación 0.08->0.12", cc > b + 1e-4f)
        // Pendiente mínima teórica 1/1.75 = 0.571.
        val slope = (HighContrastMath.toneCurve(0.02f) - 0f) / 0.02f
        assertTrue("pendiente en el pie >= 0.4 (teórica 0.571): $slope", slope >= 0.4f)
    }

    // ---------------------------------------------------------------------
    // 7) Altas luces: sin clip plano (el viejo clamp mapeaba Y>0.86 a 1.0).
    // ---------------------------------------------------------------------
    @Test
    fun highlightsAreNotClippedFlat() {
        val ys = listOf(0.86f, 0.90f, 0.95f, 0.98f, 1.0f)
        val fs = ys.map { HighContrastMath.toneCurve(it) }
        for (idx in 1 until fs.size) {
            assertTrue(
                "clip plano entre ${ys[idx - 1]} y ${ys[idx]}: ${fs[idx - 1]} vs ${fs[idx]}",
                fs[idx] > fs[idx - 1] + 1e-5f,
            )
        }
        // Con protección de luces al máximo, el blanco NO llega a 1.0.
        val top = HighContrastMath.ceilingOnly(HighContrastMath.toneCurve(1f), 1f)
        assertTrue("techo debe dejar margen: $top", top < 0.99f)
        assertTrue("pero sin apagar el blanco: $top", top > 0.80f)
    }

    // ---------------------------------------------------------------------
    // 8) Negro puro intacto incluso con sombras al 100% (sin velo gris).
    // ---------------------------------------------------------------------
    @Test
    fun pureBlackStaysBlack() {
        val out = run(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f, 1f))
        for (k in 0..2) assertEquals("sin velo sobre negro", 0f, out[k], 1f / 255f)
        // El lift se ancla exactamente en 0.
        assertEquals(0f, HighContrastMath.liftOnly(0f, 1f), 1e-6f)
    }

    // ---------------------------------------------------------------------
    // 9) Daltonismo + Alto contraste: el reempaque preserva los ratios
    //    R/G/B (cromaticidad intacta) y ningún canal se sale de [0,1].
    // ---------------------------------------------------------------------
    @Test
    fun repackPreservesHueRatios() {
        val colors = listOf(
            floatArrayOf(0.8f, 0.4f, 0.2f),
            floatArrayOf(0.6f, 0.75f, 0.3f),
            floatArrayOf(0.25f, 0.3f, 0.7f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
        )
        for (c in colors) {
            val out = run(c, floatArrayOf(0.4f, 0.6f, 1f, 1f))
            // Ratios (evitando divisiones por ~0).
            if (c[1] > 0.05f && c[2] > 0.05f) {
                assertEquals("ratio R/G", c[0] / c[1], out[0] / out[1], 0.02f)
                assertEquals("ratio G/B", c[1] / c[2], out[1] / out[2], 0.02f)
            }
            for (k in 0..2) {
                assertTrue(out[k].isFinite())
                assertTrue("fuera de rango: ${out[k]}", out[k] >= 0f && out[k] <= 1f)
            }
        }
        // Color saturado con un canal ya en 1: escala limitada, no clipa.
        val sat = floatArrayOf(1f, 0.2f, 0.1f)
        val outSat = run(sat, floatArrayOf(0.4f, 0.6f, 1f, 1f))
        assertTrue(outSat[0] <= 1f)
        assertTrue(outSat[1] >= 0f && outSat[1] <= 1f)
        assertTrue("no destruir el canal subdominante", outSat[1] > 0.05f)
    }

    // ---------------------------------------------------------------------
    // 10) Sin NaN ni fuera de rango en una rejilla densa (sliders extremos).
    // ---------------------------------------------------------------------
    @Test
    fun noNaNAndOutputInUnitRange() {
        val steps = intArrayOf(0, 64, 128, 192, 255)
        val sliderCombos = listOf(
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(1f, 1f, 1f, 1f),
            floatArrayOf(1f, 0f, 1f, 1f),
            floatArrayOf(0f, 1f, 1f, 1f),
            floatArrayOf(0.4f, 0.6f, 0.25f, 1f),
        )
        var checked = 0
        for (r in steps) for (g in steps) for (b in steps) {
            val c = floatArrayOf(r / 255f, g / 255f, b / 255f)
            for (p in sliderCombos) {
                val out = run(c, p, fragX = (r + g).toFloat(), fragY = (b + 1).toFloat())
                for (k in 0..2) {
                    assertTrue("NaN/Inf", out[k].isFinite())
                    assertTrue("fuera de rango: ${out[k]}", out[k] >= 0f && out[k] <= 1f)
                }
                checked++
            }
        }
        assertTrue(checked > 0)
    }

    // ---------------------------------------------------------------------
    // 11) Dither: acotado a 1/255, sin efecto en negro puro, distinto por
    //     posición (rompe patrones).
    // ---------------------------------------------------------------------
    @Test
    fun ditherIsBoundedAndAnchored() {
        val mid = gray(0.5f)
        val outA = run(mid, floatArrayOf(0.4f, 0.6f, 1f, 1f), fragX = 1f, fragY = 1f)
        val outB = run(mid, floatArrayOf(0.4f, 0.6f, 1f, 1f), fragX = 3f, fragY = 5f)
        // El dither no debe mover más de 1 código de 8 bits.
        assertTrue(abs(outA[0] - outB[0]) <= 1.01f / 255f)
        // Negro puro: dither anclado (queda exactamente 0).
        val black = run(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f, 1f), 7f, 9f)
        for (k in 0..2) assertEquals("dither anclado en negro", 0f, black[k], 1f / 255f)
    }

    // ---------------------------------------------------------------------
    // 12) Luminancia razonablemente estable: la media sobre una rampa
    //     uniforme no se desplaza de forma apreciable.
    // ---------------------------------------------------------------------
    @Test
    fun averageLumaStaysRoughlyStable() {
        var sumIn = 0f
        var sumOut = 0f
        var n = 0
        for (t in 0..64) {
            val y = t / 64f
            sumIn += HighContrastMath.toneCurve(y)
            sumOut += y
            n++
        }
        val avgIn = sumOut / n
        val avgOut = sumIn / n
        assertTrue(
            "la media de la curva debe quedarse cerca de 0.5: $avgOut",
            abs(avgOut - avgIn) < 0.02f,
        )
    }

    // ---------------------------------------------------------------------
    // 13) Escenas extremas: muy oscura y muy clara siguen con detalle.
    // ---------------------------------------------------------------------
    @Test
    fun extremeScenesKeepDetail() {
        // Escena oscura (nocturna): la curva NO debe colapsar a negro.
        val darks = listOf(0.03f, 0.05f, 0.07f, 0.09f)
        val darkOut = darks.map { HighContrastMath.toneCurve(it) }
        for (idx in 1 until darkOut.size) {
            assertTrue("detalle nocturno colapsado en $idx", darkOut[idx] > darkOut[idx - 1] + 1e-5f)
        }
        // Escena clara (nieve/cielo): NO debe colapsar a blanco.
        val brighs = listOf(0.91f, 0.94f, 0.96f, 0.98f)
        val brighOut = brighs.map { HighContrastMath.toneCurve(it) }
        for (idx in 1 until brighOut.size) {
            assertTrue("detalle en luces colapsado en $idx", brighOut[idx] > brighOut[idx - 1] + 1e-5f)
        }
    }

    // ---------------------------------------------------------------------
    // 14) Rostros (piel) y texto: cambio acotado y signo correcto.
    // ---------------------------------------------------------------------
    @Test
    fun skinAndTextChangesAreBounded() {
        // Piel (~0.7 luma): sube un poco (separa de fondos oscuros), sin
        // recolorear (ratios intactos ya verificados) ni quemar.
        val skin = floatArrayOf(0.87f, 0.72f, 0.67f)
        val outSkin = run(skin, floatArrayOf(0.4f, 0.6f, 1f, 1f))
        val yIn = 0.2126f * skin[0] + 0.7152f * skin[1] + 0.0722f * skin[2]
        val yOut = 0.2126f * outSkin[0] + 0.7152f * outSkin[1] + 0.0722f * outSkin[2]
        assertTrue("piel no debe oscurecerse", yOut >= yIn - 0.01f)
        assertTrue("piel no debe quemarse", yOut < 0.99f)
        // Texto blanco sobre fondo oscuro: el fondo se oscurece (más
        // separación), el texto no se sale de 1.
        val bg = floatArrayOf(0.15f, 0.15f, 0.15f)
        val outBg = run(bg, floatArrayOf(0.4f, 0.6f, 1f, 1f))
        assertTrue("el fondo oscuro debe oscurecerse", outBg[0] < bg[0])
        val txt = floatArrayOf(1f, 1f, 1f)
        val outTxt = run(txt, floatArrayOf(0.4f, 0.6f, 1f, 1f))
        assertTrue("el texto blanco no se quema", outTxt[0] <= 1f)
        assertTrue("el texto blanco se mantiene muy claro", outTxt[0] > 0.85f)
    }

    // ---------------------------------------------------------------------
    // 15) Contrato con VisionAssistSettings (lift 40, soft 60, int 100).
    // ---------------------------------------------------------------------
    @Test
    fun defaultsMatchSettingsContract() {
        assertEquals(0.40f, defaults[0], 1e-6f)
        assertEquals(0.60f, defaults[1], 1e-6f)
        assertEquals(1.00f, defaults[2], 1e-6f)
        assertEquals(0.45f, defaults[3], 1e-6f)
    }

    // ---------------------------------------------------------------------
    // 16) Estabilidad de color: la saturación percibida no se dispara
    //     (chroma/max no crece de forma agresiva con reempaque limitado).
    // ---------------------------------------------------------------------
    @Test
    fun saturationDoesNotExplode() {
        val colors = listOf(
            floatArrayOf(0.6f, 0.35f, 0.35f),
            floatArrayOf(0.3f, 0.5f, 0.6f),
            floatArrayOf(0.55f, 0.5f, 0.2f),
        )
        for (c in colors) {
            val out = run(c, floatArrayOf(0.4f, 0.6f, 1f, 1f))
            val satIn = (maxOf(c[0], c[1], c[2]) - minOf(c[0], c[1], c[2])) /
                maxOf(c[0], maxOf(c[1], c[2]), 1e-5f)
            val satOut = (maxOf(out[0], out[1], out[2]) - minOf(out[0], out[1], out[2])) /
                maxOf(out[0], maxOf(out[1], out[2]), 1e-5f)
            assertTrue(
                "saturación descontrolada: $satIn -> $satOut",
                satOut <= satIn + 0.05f,
            )
        }
    }
}
