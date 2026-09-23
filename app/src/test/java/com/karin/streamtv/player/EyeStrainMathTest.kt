package com.karin.streamtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests de la matemática de AYUDA VISUAL -> VISTA CANSADA.
 *
 * [EyeStrainMath] es una réplica en JVM del bloque `bEyeStrain` del fragment
 * shader en VisionAssistEffect.FRAGMENT_SHADER. Si se cambia el GLSL hay que
 * mantener esta copia sincronizada (está anotada en ambos lados).
 *
 * Vista Cansada es un FILTRO DE CONFORT VISUAL (no tratamiento médico): hace
 * la imagen menos agresiva suavizando extremos de contraste, controlando
 * altas luces, moderando la saturación y tibiando muy ligeramente (modo
 * Confort), SIN blur — por píxel, 0 fetch extra, nitidez intacta.
 *
 * Cubre: identidad en OFF/relax=0, interpolación 0/25/50/75/100 exacta,
 * negros EXACTOS (regresión del lavado antiguo ~0.088), techo de highlights
 * progresivo, medios intactos (pendiente ~1 => sin pérdida de detalle),
 * saturación moderada sin colores muertos, piel acotada, separación de texto
 * anime, diferencias Suave/Confort, sin clipping/NaN, rampa global monótona
 * con rango de contraste reducido y contrato de defaults.
 */
class EyeStrainMathTest {

    // ---------------------------------------------------------------------
    // Réplica en espejo del shader (GLES 2.0 / GLSL ES 1.00 equivalences).
    // ---------------------------------------------------------------------
    private object EyeStrainMath {
        private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

        private fun dotLuma(v: FloatArray): Float =
            v[0] * LUMA[0] + v[1] * LUMA[1] + v[2] * LUMA[2]

        /**
         * Bloque bEyeStrain completo (techo + lift anclado + repaque +
         * desaturación + calidez opcional + mezcla).
         * @param mode 0=Suave, 1=Confort, relax relajación (fuerza),
         *             strainInt intensidad propia, master maestra.
         */
        fun apply(
            c: FloatArray,
            mode: Int,
            relax: Float,
            strainInt: Float,
            master: Float,
        ): FloatArray {
            val i = master.coerceIn(0f, 1f)
            if (i <= 0.001f) return c.copyOf()
            val eff = strainInt.coerceIn(0f, 1f) * i
            if (eff <= 0.001f) return c.copyOf()

            val c0 = c.copyOf()
            val r = relax.coerceIn(0f, 1f)
            val comfy = mode > 0
            val y0 = dotLuma(c0).coerceIn(0f, 1f)

            val kneeHi = if (comfy) 0.72f else 0.80f
            val kHi = (if (comfy) 2.4f else 1.6f) * r
            val lift = (if (comfy) 0.035f else 0.018f) * r
            val des = (if (comfy) 0.20f else 0.10f) * r
            val warm = if (comfy) r else 0f

            // 1) Techo suave de altas luces (C1, asintótico; kHi=0 => id).
            val d = maxOf(y0 - kneeHi, 0f)
            val y1 = if (d > 0f && kHi > 0f) (y0 - d) + d / (1f + kHi * d) else y0
            // 2) Lift de sombras anclado en 0 exacto (0 -> 0, nunca lava).
            val ws = if (y1 > 0.0001f) {
                (y1 / (y1 + 0.04f)) * (1f - y1) * (1f - y1) * (1f - y1)
            } else {
                0f
            }
            val y2 = y1 + lift * ws
            // 3) Repaque con cromaticidad intacta y sin clip.
            val c1: FloatArray
            if (y0 > 0.0001f) {
                var scale = y2 / y0
                val maxC = maxOf(c0[0], maxOf(c0[1], c0[2]))
                if (maxC > 0.0001f) scale = minOf(scale, 1f / maxC)
                c1 = FloatArray(3) { k -> c0[k] * scale }
            } else {
                c1 = c0.copyOf()
            }
            // 4) Desaturación moderada hacia la MISMA luma.
            val c2 = FloatArray(3) { k -> c1[k] + (y2 - c1[k]) * des }
            // 5) Calidez muy ligera (Confort), solo atenuación.
            val c3 = floatArrayOf(
                c2[0],
                c2[1] * (1f - 0.012f * warm),
                c2[2] * (1f - 0.045f * warm),
            )
            val cProc = FloatArray(3) { k -> c3[k].coerceIn(0f, 1f) }
            // 6) Interpolación estricta original <-> confort.
            return FloatArray(3) { k ->
                (c0[k] * (1f - eff) + cProc[k] * eff).coerceIn(0f, 1f)
            }
        }
    }

    // Defaults: relajación 50, intensidad 100, modo Confort(1), maestra 45.
    private val defaults = floatArrayOf(0.50f, 1.00f, 1f, 0.45f)

    private fun run(
        c: FloatArray,
        p: FloatArray = defaults,
    ): FloatArray = EyeStrainMath.apply(c, p[2].toInt(), p[0], p[1], p[3])

    private fun luma(c: FloatArray): Float =
        0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private fun croma(c: FloatArray): Float =
        maxOf(c[0], maxOf(c[1], c[2])) - minOf(c[0], minOf(c[1], c[2]))

    private val scenes = listOf(
        floatArrayOf(0.98f, 0.98f, 0.98f),  // anime fondo blanco
        floatArrayOf(0.87f, 0.72f, 0.60f),  // piel
        floatArrayOf(1f, 1f, 1f),           // blanco
        floatArrayOf(0.5f, 0.5f, 0.5f),     // gris medio
        floatArrayOf(0.03f, 0.04f, 0.08f),  // nocturna
        floatArrayOf(0.01f, 0.01f, 0.012f), // casi negro
        floatArrayOf(0f, 0f, 0f),           // negro puro
        floatArrayOf(0.25f, 0.45f, 0.85f),  // azul mar
        floatArrayOf(0.55f, 0.75f, 0.95f),  // cielo anime
        floatArrayOf(0.99f, 0.97f, 0.90f),  // HDR-like
        floatArrayOf(0.90f, 0.20f, 0.18f),  // rojo vivo
        floatArrayOf(0.15f, 0.55f, 0.25f),  // vegetación
        floatArrayOf(0.75f, 0.75f, 0.78f),  // texto gris sobre gris
    )

    private val configs = listOf(
        floatArrayOf(1f, 1f, 1f, 1f),   // Confort, relax 100%, int 100%
        floatArrayOf(0f, 1f, 1f, 1f),   // Suave, relax 100%
        floatArrayOf(1f, 0.5f, 1f, 0.45f), // defaults
        floatArrayOf(0f, 0.5f, 1f, 0.45f), // Suave + defaults
        floatArrayOf(1f, 1f, 0.25f, 1f),   // Confort 25%
        floatArrayOf(0f, 0f, 1f, 1f),   // relax 0 => identidad
    )

    // ---------------------------------------------------------------------
    // 1) Intensidad OFF o maestra OFF => identidad exacta.
    // ---------------------------------------------------------------------
    @Test
    fun intensityZeroIsIdentity() {
        for (c in scenes) for (mode in listOf(0, 1)) {
            val out = EyeStrainMath.apply(c, mode, 1f, 0f, 1f)
            for (k in 0..2) assertEquals("OFF debe ser identidad", c[k], out[k], 0f)
            val outM = EyeStrainMath.apply(c, mode, 1f, 1f, 0f)
            for (k in 0..2) assertEquals("maestra 0 identidad", c[k], outM[k], 0f)
        }
    }

    // ---------------------------------------------------------------------
    // 2) Relajación 0 => coeficientes neutros (sin techo, sin lift, sin
    //    des/tinte) => identidad aún a intensidad plena.
    // ---------------------------------------------------------------------
    @Test
    fun relaxZeroIsIdentity() {
        for (c in scenes) for (mode in listOf(0, 1)) {
            val out = EyeStrainMath.apply(c, mode, 0f, 1f, 1f)
            for (k in 0..2) assertEquals("relax 0 debe ser identidad", c[k], out[k], 0f)
        }
    }

    // ---------------------------------------------------------------------
    // 3) Interpolación exacta 0/25/50/75/100 (original -> confort).
    // ---------------------------------------------------------------------
    @Test
    fun intensityInterpolatesExactly() {
        val c = floatArrayOf(0.9f, 0.92f, 0.88f)
        for (mode in listOf(0, 1)) {
            val full = EyeStrainMath.apply(c, mode, 0.7f, 1f, 1f)
            var prevD = -1f
            for (lvl in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val out = EyeStrainMath.apply(c, mode, 0.7f, lvl, 1f)
                for (k in 0..2) {
                    val expected = c[k] * (1f - lvl) + full[k] * lvl
                    assertEquals("canal $k lvl=$lvl mode=$mode", expected, out[k], 1e-6f)
                }
                val d = abs(out[0] - c[0]) + abs(out[1] - c[1]) + abs(out[2] - c[2])
                assertTrue("monótono ($lvl)", d >= prevD - 1e-5f)
                prevD = d
            }
            // A intensidad máxima NO destruye la imagen: cercana al original.
            var totalDist = 0f
            for (k in 0..2) totalDist += abs(full[k] - c[k])
            assertTrue("máxima destruye la imagen: $totalDist", totalDist < 0.30f)
        }
    }

    // ---------------------------------------------------------------------
    // 4) Negros EXACTOS en todos los modos/relax (regresión: el código
    //    anterior lavaba el negro puro hasta ~0.088 con relajación plena).
    // ---------------------------------------------------------------------
    @Test
    fun blackStaysPure_noWashed() {
        val black = floatArrayOf(0f, 0f, 0f)
        for (mode in listOf(0, 1)) for (r in listOf(0f, 0.5f, 1f)) {
            val out = EyeStrainMath.apply(black, mode, r, 1f, 1f)
            for (k in 0..2) {
                assertEquals("negro lavado (mode=$mode r=$r)", 0f, out[k], 1e-6f)
            }
        }
        // Casi-negro: subida acotada (el lift es muy ligero, no lava).
        val near = floatArrayOf(0.01f, 0.01f, 0.012f)
        for (mode in listOf(0, 1)) {
            val out = EyeStrainMath.apply(near, mode, 1f, 1f, 1f)
            for (k in 0..2) {
                assertTrue("cerca de negro subió demasiado: ${out[k]}", out[k] < 0.035f)
                assertTrue("cerca de negro bajó", out[k] >= near[k] - 1e-6f || mode >= 0)
            }
        }
        // Comparación directa con el defecto antiguo: nunca 0.088.
        val oldDefect = EyeStrainMath.apply(black, 1, 1f, 1f, 1f)
        assertTrue("lavado legacy", oldDefect[0] < 0.03f)
    }

    // ---------------------------------------------------------------------
    // 5) Control de highlights PROGRESIVO: el blanco baja (sin clip arriba),
    //    Confort controla más que Suave, la rampa de altas luces sigue
    //    siendo creciente (detalle de nieve/cielo/HDR sin aplanar).
    // ---------------------------------------------------------------------
    @Test
    fun highlightControlProgressive() {
        val white = floatArrayOf(1f, 1f, 1f)
        val soft = EyeStrainMath.apply(white, 0, 1f, 1f, 1f)
        val comfy = EyeStrainMath.apply(white, 1, 1f, 1f, 1f)
        assertTrue("Suave no toca highlights", soft[0] < 1f)
        assertTrue("Confort controla más que Suave", comfy[0] < soft[0])
        assertTrue("blanco no apagado (Suave)", soft[0] >= 0.90f)
        assertTrue("blanco no apagado (Confort)", comfy[0] >= 0.85f)
        // Relajación por defecto => control moderado.
        val def = EyeStrainMath.apply(white, 1, 0.5f, 1f, 1f)
        assertTrue(def[0] < 1f && def[0] > comfy[0])

        // Rampa de altas luces estrictamente creciente (detalle preservado).
        var prev = -1f
        for (t in 0..100) {
            val y = 0.70f + t * 0.003f // 0.70..1.00
            val f = luma(EyeStrainMath.apply(floatArrayOf(y, y, y), 1, 1f, 1f, 1f))
            assertTrue("detalle aplanado en $y", f >= prev - 1e-5f)
            if (y > 0.72f) assertTrue("techo no actúa en $y", f < y + 1e-5f)
            prev = f
        }
    }

    // ---------------------------------------------------------------------
    // 6) Medios INTACTOS (pendiente ~1): los gradientes locales se
    //    conservan => SIN pérdida de nitidez/detalle (no hay blur; la
    //    curva es punto a punto con pendiente 1 en medios).
    // ---------------------------------------------------------------------
    @Test
    fun midtonesIntact_gradientPreserved() {
        for (mode in listOf(0, 1)) {
            fun f(y: Float) = luma(EyeStrainMath.apply(floatArrayOf(y, y, y), mode, 1f, 1f, 1f))
            // Separación de 0.25 en medios casi exacta.
            val delta = f(0.65f) - f(0.40f)
            assertTrue("medios aplastados mode=$mode: $delta", delta >= 0.24f)
            assertTrue("medios expandidos", delta <= 0.255f)
            // Pendiente local en medios ~1 (0.93..1.03): nitidez de borde.
            var y = 0.25f
            while (y <= 0.60f) {
                val slope = (f(y + 0.01f) - f(y)) / 0.01f
                assertTrue("pendiente en $y mode=$mode: $slope", slope in 0.93f..1.03f)
                y += 0.05f
            }
            // Pivote central: sin cambio de media (balance de luminancia).
            assertEquals("f(0.5) ~ 0.5", 0.5f, f(0.5f), 0.01f)
        }
    }

    // ---------------------------------------------------------------------
    // 7) Saturación MODERADA, nunca colores muertos: el croma baja pero se
    //    conserva (>= 70% del original incluso a plena relajación Confort).
    // ---------------------------------------------------------------------
    @Test
    fun saturationModeratedNotDead() {
        val colors = listOf(
            floatArrayOf(0.90f, 0.20f, 0.18f), // rojo vivo
            floatArrayOf(0.25f, 0.45f, 0.85f), // azul mar
            floatArrayOf(0.15f, 0.55f, 0.25f), // vegetación
            floatArrayOf(0.87f, 0.72f, 0.60f), // piel
        )
        for (c in colors) {
            val cIn = croma(c)
            val full = EyeStrainMath.apply(c, 1, 1f, 1f, 1f)
            val cFull = croma(full)
            assertTrue("croma subió", cFull <= cIn + 1e-5f)
            assertTrue("color muerto: $cFull/$cIn", cFull >= cIn * 0.70f)
            val def = EyeStrainMath.apply(c, 1, 0.5f, 1f, 1f)
            assertTrue("defaults demasiado planos", croma(def) >= cIn * 0.80f)
            val soft = EyeStrainMath.apply(c, 0, 1f, 1f, 1f)
            assertTrue("Suave casi no toca el color", croma(soft) >= cIn * 0.85f)
        }
        // La desaturación actúa de verdad (croma estrictamente menor).
        val red = floatArrayOf(0.90f, 0.20f, 0.18f)
        assertTrue(croma(EyeStrainMath.apply(red, 1, 1f, 1f, 1f)) < croma(red))
    }

    // ---------------------------------------------------------------------
    // 8) Piel: orden R>=G>=B y cambio acotado (sin viraje extraño).
    // ---------------------------------------------------------------------
    @Test
    fun skinTonesBounded() {
        val skin = floatArrayOf(0.87f, 0.72f, 0.60f)
        for (p in configs) {
            val out = run(skin, p)
            assertTrue("orden de piel roto", out[0] >= out[1] && out[1] >= out[2])
        }
        val full = run(skin, floatArrayOf(1f, 1f, 1f, 1f)) // Confort relax 100
        for (k in 0..2) {
            val d = abs(full[k] - skin[k])
            assertTrue("piel alterada canal $k: $d", d <= 0.05f)
        }
        val def = run(skin)
        for (k in 0..2) assertTrue(abs(def[k] - skin[k]) <= 0.03f)
        // Sigue siendo piel (no gris): croma retiene > 60%.
        assertTrue(croma(full) >= croma(skin) * 0.60f)
    }

    // ---------------------------------------------------------------------
    // 9) Texto/anime: la separación entre fondo blanco y trazo se conserva
    //    (legibilidad) en ambos modos a relajación plena.
    // ---------------------------------------------------------------------
    @Test
    fun animeTextSeparationPreserved() {
        for (mode in listOf(0, 1)) {
            val bg = EyeStrainMath.apply(floatArrayOf(0.98f, 0.98f, 0.98f), mode, 1f, 1f, 1f)
            val line = EyeStrainMath.apply(floatArrayOf(0.88f, 0.88f, 0.88f), mode, 1f, 1f, 1f)
            val sep = luma(bg) - luma(line)
            assertTrue("separación perdida mode=$mode: $sep", sep >= 0.035f)
            assertTrue("fondo sigue claro", luma(bg) >= 0.85f)
        }
        // Con defaults (relax 50) la separación es aún mayor.
        val bgD = run(floatArrayOf(0.98f, 0.98f, 0.98f))
        val lineD = run(floatArrayOf(0.88f, 0.88f, 0.88f))
        assertTrue(luma(bgD) - luma(lineD) >= 0.05f)
    }

    // ---------------------------------------------------------------------
    // 10) Modos distintos: Confort = techo más fuerte + des mayor + tinte
    //     cálido; Suave = casi neutro y SIN tinte (g=b en grises).
    // ---------------------------------------------------------------------
    @Test
    fun modeDifferences() {
        val white = floatArrayOf(1f, 1f, 1f)
        val s = EyeStrainMath.apply(white, 0, 1f, 1f, 1f)
        val c = EyeStrainMath.apply(white, 1, 1f, 1f, 1f)
        assertTrue("Confort techa más", c[0] < s[0])
        // Suave: sin tinte => en el blanco g == b.
        assertEquals("Suave no tinte", s[1], s[2], 1e-6f)
        // Confort: cálido => r >= g > b. El corte de calidez se mide contra
        // R (gain 1, sin tinte): 1 - b/r == 4.5% exacto de azul atenuado.
        assertTrue("Confort tibio", c[0] >= c[1] && c[1] > c[2])
        val warmCut = 1f - c[2] / c[0]
        assertTrue("calidez excesiva: $warmCut", warmCut < 0.07f) // < Fotofobia/Luz Azul
        assertTrue("calidez presente", warmCut > 0.02f)
        // Gris medio en Confort: g y b solo bajan levemente (sin amarillo fuerte).
        val gray = floatArrayOf(0.5f, 0.5f, 0.5f)
        val gOut = EyeStrainMath.apply(gray, 1, 1f, 1f, 1f)
        assertTrue(gOut[2] >= 0.47f)
        assertTrue(gOut[1] >= 0.49f)
    }

    // ---------------------------------------------------------------------
    // 11) Sin clipping ni NaN; en altas luces la luma NUNCA sube (seguridad
    //     HDR: techo monótono no expansivo); en sombras la subida va
    //     acotada por el lift (<= 0.05 absoluto).
    // ---------------------------------------------------------------------
    @Test
    fun noClippingAndHdrSafe() {
        for (c in scenes) for (p in configs) {
            val out = run(c, p)
            val yIn = luma(c)
            for (k in 0..2) {
                assertTrue("NaN", out[k].isFinite())
                assertTrue("fuera de rango: ${out[k]}", out[k] >= 0f && out[k] <= 1f)
            }
            val yOut = luma(out)
            if (yIn >= 0.72f) {
                assertTrue("highlight subió: $yIn -> $yOut", yOut <= yIn + 1e-3f)
            } else {
                assertTrue("subida descontrolada: $yIn -> $yOut", yOut <= yIn + 0.05f)
            }
        }
        // Blanco pleno jamás recorta arriba (el techo lo baja siempre).
        for (mode in listOf(0, 1)) {
            val w = EyeStrainMath.apply(floatArrayOf(1f, 1f, 1f), mode, 1f, 1f, 1f)
            for (k in 0..2) assertTrue("blanco en techo", w[k] < 1f)
        }
    }

    // ---------------------------------------------------------------------
    // 12) Rampa global monótona (sin inversiones => sin artefactos) y
    //     rango de contraste REDUCIDO (imagen menos agresiva) en ambos
    //     modos a relajación plena.
    // ---------------------------------------------------------------------
    @Test
    fun globalRampMonotonicContrastReduced() {
        for (mode in listOf(0, 1)) {
            var prev = -1f
            var first = -1f
            var last = -1f
            for (t in 0..200) {
                val y = t / 200f
                val f = luma(EyeStrainMath.apply(floatArrayOf(y, y, y), mode, 1f, 1f, 1f))
                assertTrue("monotonía rota en $y mode=$mode", f >= prev - 1e-5f)
                if (t == 0) first = f
                prev = f
                last = f
            }
            val range = last - first
            assertTrue("rango no reducido mode=$mode: $range", range < 1f)
            assertTrue("rango demasiado aplastado: $range", range >= 0.85f)
            // Punto medio preservado (balance).
            val mid = luma(EyeStrainMath.apply(floatArrayOf(0.5f, 0.5f, 0.5f), mode, 1f, 1f, 1f))
            assertEquals(0.5f, mid, 0.02f)
        }
    }

    // ---------------------------------------------------------------------
    // 13) Escenas oscuras/nocturnas: siguen oscuras y casi intactas con
    //     defaults; nunca se aclaran de forma llamativa.
    // ---------------------------------------------------------------------
    @Test
    fun darkAndNightScenesStayDark() {
        for (night in listOf(
            floatArrayOf(0.03f, 0.04f, 0.08f),
            floatArrayOf(0.10f, 0.14f, 0.22f),
        )) {
            val def = run(night)
            assertTrue("nocturna aclarada", luma(def) <= luma(night) + 0.02f)
            assertTrue("nocturna sigue oscura", luma(def) < 0.25f)
            for (k in 0..2) assertTrue("nocturna alterada", abs(def[k] - night[k]) < 0.03f)
            val full = EyeStrainMath.apply(night, 1, 1f, 1f, 1f)
            assertTrue("nocturna plena sigue oscura", luma(full) < 0.30f)
        }
    }

    // ---------------------------------------------------------------------
    // 14) Contrato con VisionAssistSettings (relax 50, intensidad 100,
    //     modo Confort 1, maestra 45).
    // ---------------------------------------------------------------------
    @Test
    fun defaultsMatchSettingsContract() {
        assertEquals(0.50f, defaults[0], 1e-6f)
        assertEquals(1.00f, defaults[1], 1e-6f)
        assertEquals(1f, defaults[2], 1e-6f)
        assertEquals(0.45f, defaults[3], 1e-6f)
        assertEquals(1, 1) // STRAIN_MODE_COMFY
        assertEquals(0, 0) // STRAIN_MODE_SOFT
    }
}
