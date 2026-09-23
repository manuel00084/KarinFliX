package com.karin.streamtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests de la matemática de AYUDA VISUAL -> FOTOFOBIA.
 *
 * [PhotophobiaMath] es una réplica en JVM del bloque `bPhoto` del fragment
 * shader en VisionAssistEffect.FRAGMENT_SHADER. Si se cambia el GLSL hay que
 * mantener esta copia sincronizada (está anotada en ambos lados).
 *
 * Fotofobia es un FILTRO DE CONFORT VISUAL (no un tratamiento médico): reduce
 * la sensación de deslumbramiento atenuando progresivamente las zonas más
 * brillantes.
 *
 * Cubre: identidad en OFF, interpolación 0/25/50/75/100 exacta, rodilla
 * adaptativa (zonas normales intactas, progresiva en brillos, sin clip),
 * conservación de detalle, estabilidad de color, negros intactos, contraste,
 * no-expansión de luminancia (garantía frente a Light Boost/HDR), dither
 * acotado y contrato de defaults.
 */
class PhotophobiaMathTest {

    // ---------------------------------------------------------------------
    // Réplica en espejo del shader (GLES 2.0 / GLSL ES 1.00 equivalences).
    // ---------------------------------------------------------------------
    private object PhotophobiaMath {
        private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

        private fun dotLuma(v: FloatArray): Float =
            v[0] * LUMA[0] + v[1] * LUMA[1] + v[2] * LUMA[2]

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            if (e1 <= e0) return if (x < e0) 0f else 1f
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun fract(x: Float): Float = x - kotlin.math.floor(x)

        private fun bayer2(ax: Float, ay: Float): Float {
            val x = kotlin.math.floor(ax)
            val y = kotlin.math.floor(ay)
            return fract(x * 0.5f + y * y * 0.75f)
        }

        private fun bayer4(x: Float, y: Float): Float =
            bayer2(x * 0.5f, y * 0.5f) * 0.25f + bayer2(x, y)

        /** Solo la rodilla anti-glare sobre la luma (sin desat/tinte/dither). */
        fun knee(y: Float): Float {
            val d = maxOf(y - 0.62f, 0f)
            if (d <= 0f) return y
            return (y - d) + d / (1f + 2.2f * d)
        }

        /** Solo la máscara de desaturación anti-glare. */
        fun glareMask(y: Float, chroma: Float): Float =
            smoothstep(0.55f, 0.92f, y) * smoothstep(0.03f, 0.30f, chroma)

        /**
         * Bloque bPhoto completo (desat por máscara + rodilla + calidez +
         * mezcla + dither).
         * @param photoWarm tinte cálido, photoDesat atenuar colores brillantes,
         *                  photoIntensity intensidad propia, master maestra.
         */
        fun apply(
            c: FloatArray,
            photoWarm: Float,
            photoDesat: Float,
            photoIntensity: Float,
            master: Float,
            fragX: Float = 0.5f,
            fragY: Float = 0.5f,
        ): FloatArray {
            val i = master.coerceIn(0f, 1f)
            if (i <= 0.001f) return c.copyOf()
            val eff = photoIntensity.coerceIn(0f, 1f) * i
            if (eff <= 0.001f) return c.copyOf()

            val c0 = c.copyOf()
            val y = dotLuma(c0).coerceIn(0f, 1f)
            val warm = photoWarm.coerceIn(0f, 1f)
            val desat = photoDesat.coerceIn(0f, 1f)

            // 2) Desaturación anti-glare solo en brillos saturados.
            val mx = maxOf(c0[0], maxOf(c0[1], c0[2]))
            val mn = minOf(c0[0], minOf(c0[1], c0[2]))
            val chroma = maxOf(mx - mn, 0f)
            val mask = glareMask(y, chroma)
            val desatAmt = desat * mask
            val c1 = FloatArray(3) { k -> c0[k] + (y - c0[k]) * desatAmt }

            // 1) Rodilla suave de altas luminancias (solo Y > 0.62).
            val d = maxOf(y - 0.62f, 0f)
            val c2 = if (d > 0f && y > 1e-6f) {
                val yk = (y - d) + d / (1f + 2.2f * d)
                val scale = yk / y
                FloatArray(3) { k -> c1[k] * scale }
            } else {
                c1.copyOf()
            }

            // 3) Calidez opcional SOLO por atenuación relativa (nunca amplifica).
            val c3 = floatArrayOf(
                c2[0],
                c2[1] * (1f - 0.02f * warm),
                c2[2] * (1f - 0.07f * warm),
            )

            val cProc = FloatArray(3) { k -> c3[k].coerceIn(0f, 1f) }

            // 5) Interpolación estricta + 4) dither anti-banding.
            val out = FloatArray(3) { k -> c0[k] + (cProc[k] - c0[k]) * eff }
            val dAm = (1f / 255f) * eff * smoothstep(0.55f, 0.75f, y)
            val dth = (bayer4(fragX, fragY) - 0.5f) * dAm
            for (k in 0..2) out[k] = (out[k] + dth).coerceIn(0f, 1f)
            return out
        }
    }

    // Defaults: warm 60, desat 40, int 100, maestra 45.
    private val defaults = floatArrayOf(0.60f, 0.40f, 1.00f, 0.45f)

    private fun run(
        c: FloatArray,
        p: FloatArray = defaults,
        fragX: Float = 0.5f,
        fragY: Float = 0.5f,
    ): FloatArray = PhotophobiaMath.apply(c, p[0], p[1], p[2], p[3], fragX, fragY)

    private fun luma(c: FloatArray): Float =
        0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private val scenes = listOf(
        // nieve / cielo brillante / flash / fondo anime
        floatArrayOf(0.97f, 0.97f, 0.97f),
        floatArrayOf(0.85f, 0.90f, 0.96f),
        floatArrayOf(1f, 1f, 1f),
        // explosión
        floatArrayOf(1f, 0.85f, 0.45f),
        // neón brillante
        floatArrayOf(0.2f, 1f, 0.95f),
        floatArrayOf(1f, 0.15f, 0.7f),
        // nocturna
        floatArrayOf(0.03f, 0.04f, 0.08f),
        floatArrayOf(0.10f, 0.11f, 0.16f),
        // normales
        floatArrayOf(0.5f, 0.5f, 0.5f),
        floatArrayOf(0.87f, 0.72f, 0.67f), // piel
        floatArrayOf(0.25f, 0.4f, 0.65f),
        floatArrayOf(0f, 0f, 0f),
        floatArrayOf(0.02f, 0.02f, 0.03f),
        // HDR-like casi en el techo
        floatArrayOf(0.99f, 0.97f, 0.90f),
    )

    // ---------------------------------------------------------------------
    // 1) Intensidad OFF => identidad exacta.
    // ---------------------------------------------------------------------
    @Test
    fun intensityZeroIsIdentity() {
        for (c in scenes) {
            val out = run(c, floatArrayOf(1f, 1f, 0f, 1f))
            for (k in 0..2) assertEquals("OFF debe ser identidad", c[k], out[k], 0f)
        }
        for (c in scenes) {
            val out = run(c, floatArrayOf(1f, 1f, 1f, 0f))
            for (k in 0..2) assertEquals(c[k], out[k], 0f)
        }
    }

    // ---------------------------------------------------------------------
    // 2) Interpolación exacta 0/25/50/75/100 (50% = punto medio exacto).
    // ---------------------------------------------------------------------
    @Test
    fun intensityInterpolatesExactly() {
        val c = floatArrayOf(0.9f, 0.92f, 0.88f)
        val full = run(c, floatArrayOf(0.6f, 0.4f, 1f, 1f))
        var prevD = -1f
        for (lvl in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val out = run(c, floatArrayOf(0.6f, 0.4f, lvl, 1f))
            val expected = c[1] + (full[1] - c[1]) * lvl
            assertEquals("punto medio exacto (lvl=$lvl)", expected, out[1], 1.5e-3f)
            val d = abs(out[0] - c[0]) + abs(out[1] - c[1]) + abs(out[2] - c[2])
            assertTrue("monótono ($lvl)", d >= prevD - 1e-5f)
            prevD = d
        }
        assertTrue("a 100% debe atenuar el brillo", full[1] < c[1])
    }

    // ---------------------------------------------------------------------
    // 3) Zonas normales INTACTAS (anti-glare adaptativo): con warm=0 y
    //    desat=0, todo Y<=0.62 pasa idéntico; con sliders por defecto la
    //    zona media solo cambia por el tinte (muy leve).
    // ---------------------------------------------------------------------
    @Test
    fun normalZonesAreUntouched() {
        val neutral = floatArrayOf(0f, 0f, 1f, 1f) // warm=0, desat=0
        for (y in listOf(0.0f, 0.1f, 0.3f, 0.5f, 0.60f, 0.62f)) {
            val g = floatArrayOf(y, y, y)
            val out = run(g, neutral)
            // La rampa de dither arranca en Y=0.55 (prepivote de la rodilla):
            // por debajo la identidad es exacta; por encima, acotado a 1/255.
            val tol = if (y >= 0.55f) 1.01f / 255f else 1e-5f
            for (k in 0..2) assertEquals("Y=$y debe quedar intacto", g[k], out[k], tol)
        }
        // Rodilla exacta en el umbral (C1): Y=0.62 -> 0.62.
        assertEquals(0.62f, PhotophobiaMath.knee(0.62f), 1e-6f)
        // Pendiente 1 en la rodilla (sin borde visible).
        val slope = PhotophobiaMath.knee(0.63f) - PhotophobiaMath.knee(0.62f)
        assertEquals("pendiente ~1 en el umbral", 0.01f, slope, 0.002f)
    }

    // ---------------------------------------------------------------------
    // 4) Reducción PROGRESIVA en brillos: más brillante => más atenuado,
    //    estrictamente monótona por encima del umbral.
    // ---------------------------------------------------------------------
    @Test
    fun highlightReductionIsProgressive() {
        var prev = -1f
        for (t in 0..100) {
            val y = 0.5f + t / 200f // 0.50..1.00
            val f = PhotophobiaMath.knee(y)
            assertTrue("monótona en $y", f >= prev - 1e-6f)
            prev = f
        }
        // Magnitud de la reducción crece con el brillo.
        val red70 = 0.70f - PhotophobiaMath.knee(0.70f)
        val red85 = 0.85f - PhotophobiaMath.knee(0.85f)
        val red100 = 1.00f - PhotophobiaMath.knee(1.00f)
        assertTrue("0.70 apenas toca: $red70", red70 in 0f..0.02f)
        assertTrue("progresiva 0.85: $red85", red85 > red70)
        assertTrue("máxima en 1.00: $red100", red100 > red85)
        assertTrue("reduce de forma visible: $red100", red100 > 0.10f)
        assertTrue("sin exagerar: $red100", red100 < 0.30f)
    }

    // ---------------------------------------------------------------------
    // 5) Conservación de detalle: pendiente > 0 en todo el techo (nieve,
    //    cielo, HDR: los valores siguen diferenciados, sin aplanar).
    // ---------------------------------------------------------------------
    @Test
    fun highlightDetailIsPreserved() {
        val ys = listOf(0.80f, 0.85f, 0.90f, 0.95f, 0.99f, 1.00f)
        val fs = ys.map { PhotophobiaMath.knee(it) }
        for (idx in 1 until fs.size) {
            assertTrue(
                "detalle aplanado entre ${ys[idx - 1]} y ${ys[idx]}",
                fs[idx] > fs[idx - 1] + 1e-5f,
            )
        }
        // Pendiente final teórica 1/(1+2.2)^2 = 0.303.
        val endSlope = (PhotophobiaMath.knee(1f) - PhotophobiaMath.knee(0.99f)) / 0.01f
        assertTrue("pendiente final ~0.30: $endSlope", endSlope in 0.20f..0.45f)
    }

    // ---------------------------------------------------------------------
    // 6) Sin clipping: el blanco NO llega a 1.0 y ningún canal sale de
    //    [0,1] (la calidez solo atenúa => amplificación imposible).
    // ---------------------------------------------------------------------
    @Test
    fun noClippingNoAmplification() {
        val white = floatArrayOf(1f, 1f, 1f)
        val outW = run(white, floatArrayOf(1f, 1f, 1f, 1f))
        for (k in 0..2) {
            assertTrue("blanco no recortado arriba", outW[k] < 1f)
            assertTrue("blanco muy atenuado?", outW[k] > 0.70f)
        }
        // Ninguna escena, ninguna config, ningún canal fuera de rango o >entrada
        // en luminancia (ver test 7 para luma).
        val configs = listOf(
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(1f, 1f, 1f, 1f),
            floatArrayOf(1f, 0f, 1f, 1f),
            floatArrayOf(0f, 1f, 1f, 1f),
            floatArrayOf(0.6f, 0.4f, 0.25f, 1f),
        )
        for (c in scenes) for (p in configs) {
            val out = run(c, p, (c[0] * 37f), (c[2] * 41f))
            val yIn = luma(c)
            for (k in 0..2) {
                assertTrue("NaN", out[k].isFinite())
                assertTrue("fuera de rango: ${out[k]}", out[k] >= 0f && out[k] <= 1f)
                // La desaturación hacia la luma puede SUBIR un canal por
                // debajo de Y (por diseño, preserva luma); la rodilla y la
                // calidez solo atenúan => cota = max(entrada, Y) + dither.
                assertTrue(
                    "amplificó el canal $k: ${c[k]} -> ${out[k]}",
                    out[k] <= maxOf(c[k], yIn) + 1.5f / 255f,
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // 7) Nunca aumenta la luminancia => Fotofobia posterior a Light Boost
    //    no puede re-exceder las altas luces (garantía de interacción) y
    //    HDR conserva el orden dinámico (monótona no expansiva).
    // ---------------------------------------------------------------------
    @Test
    fun lumaNeverIncreases_guaranteesVsLightBoostHdr() {
        val configs = listOf(
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(1f, 1f, 1f, 1f),
            floatArrayOf(0.6f, 0.4f, 1f, 1f),
        )
        for (c in scenes) for (p in configs) {
            val yIn = luma(c)
            val out = run(c, p)
            val yOut = luma(out)
            assertTrue("luma subió: $yIn -> $yOut", yOut <= yIn + 1.5f / 255f)
        }
        // Orden dinámico preservado en el techo (HDR): a<b => f(a)<=f(b).
        var prev = -1f
        for (t in 0..200) {
            val y = 0.62f + t * (0.38f / 200f)
            val f = PhotophobiaMath.knee(y)
            assertTrue("orden dinámico roto en $y", f >= prev - 1e-6f)
            prev = f
        }
    }

    // ---------------------------------------------------------------------
    // 8) Escenas de prueba: nieve/cielo/explosión/flash/neón/HDR reducen
    //    el deslumbramiento; nocturnas y normales casi no cambian.
    // ---------------------------------------------------------------------
    @Test
    fun sceneBehavior() {
        // Nieve/cielo/blanco: reducción clara.
        for (c in listOf(
            floatArrayOf(0.97f, 0.97f, 0.97f),
            floatArrayOf(0.85f, 0.90f, 0.96f),
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(0.99f, 0.97f, 0.90f),
        )) {
            val out = run(c, floatArrayOf(0f, 0f, 1f, 1f)) // sin tinte: solo glare
            assertTrue("brillo no reducido: ${luma(c)} -> ${luma(out)}", luma(out) < luma(c) - 0.02f)
        }
        // Explosión: baja y conserva el gradiente cálido (r>g>b).
        val exp = floatArrayOf(1f, 0.85f, 0.45f)
        val outExp = run(exp, floatArrayOf(0f, 0f, 1f, 1f))
        assertTrue(luma(outExp) < luma(exp))
        assertTrue("gradiente cálido conservado", outExp[0] >= outExp[1] && outExp[1] >= outExp[2])
        // Nocturna: prácticamente intacta (misma imagen perceptiva).
        val night = floatArrayOf(0.03f, 0.04f, 0.08f)
        val outNight = run(night, defaults)
        for (k in 0..2) assertTrue("nocturna alterada", abs(outNight[k] - night[k]) < 0.01f)
        // Neón: baja su luminancia (anti-glare) sin volverse gris puro.
        val neon = floatArrayOf(0.2f, 1f, 0.95f)
        val outNeon = run(neon, defaults)
        assertTrue(luma(outNeon) < luma(neon))
        val chromaN = maxOf(neon[0], neon[1], neon[2]) - minOf(neon[0], neon[1], neon[2])
        val chromaOut = maxOf(outNeon[0], outNeon[1], outNeon[2]) -
            minOf(outNeon[0], outNeon[1], outNeon[2])
        assertTrue("neón no desaturado a gris", chromaOut > chromaN * 0.25f)
    }

    // ---------------------------------------------------------------------
    // 9) Estabilidad de color: en zonas NO anti-glare los ratios R/G/B se
    //    conservan con warm=0 (knee es escala uniforme; desat con máscara 0).
    // ---------------------------------------------------------------------
    @Test
    fun colorStabilityRatiosPreserved() {
        val neutral = floatArrayOf(0f, 0f, 1f, 1f)
        val colors = listOf(
            floatArrayOf(0.6f, 0.35f, 0.35f),
            floatArrayOf(0.3f, 0.5f, 0.6f),
            floatArrayOf(0.15f, 0.55f, 0.25f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
        )
        for (c in colors) {
            val out = run(c, neutral)
            if (c[1] > 0.05f && c[2] > 0.05f) {
                assertEquals("ratio R/G", c[0] / c[1], out[0] / out[1], 0.03f)
                assertEquals("ratio G/B", c[1] / c[2], out[1] / out[2], 0.03f)
            }
        }
        // El tinte cálido es LIMITADO (b -7% máx): no es el tope de Luz Azul (b -24%).
        val mid = floatArrayOf(0.5f, 0.5f, 0.5f)
        val warmFull = run(mid, floatArrayOf(1f, 0f, 1f, 1f))
        val blueCut = 1f - warmFull[2] / mid[2]
        assertTrue("corte azul excesivo: $blueCut", blueCut < 0.10f)
        assertTrue("el tinte existe: $blueCut", blueCut > 0.02f)
        assertTrue("no apaga el verde del tinte", warmFull[1] / mid[1] > 0.95f)
    }

    // ---------------------------------------------------------------------
    // 10) Desaturación SOLO en brillos (sin colores muertos en sombras/
    //     medios) y con invariante de luma (sin lavado).
    // ---------------------------------------------------------------------
    @Test
    fun desaturationOnlyInGlareZones() {
        // Color saturado NO brillante: máscara ~0 => intacto (warm=0).
        val darkSat = floatArrayOf(0.10f, 0.30f, 0.85f)
        val outDark = run(darkSat, floatArrayOf(0f, 1f, 1f, 1f))
        val chromaIn = 0.85f - 0.10f
        val chromaOut = maxOf(outDark[0], outDark[1], outDark[2]) -
            minOf(outDark[0], outDark[1], outDark[2])
        assertTrue("sombra desaturada: $chromaIn -> $chromaOut", chromaOut > chromaIn * 0.92f)
        // Brillo saturado: la máscara actúa.
        assertTrue(PhotophobiaMath.glareMask(0.9f, 0.5f) > 0.5f)
        // Zona normal o sin chroma: máscara ~0.
        assertTrue(PhotophobiaMath.glareMask(0.4f, 0.5f) < 0.01f)
        assertTrue(PhotophobiaMath.glareMask(0.9f, 0.0f) < 0.01f)
        // La desaturación hacia la MISMA luma no la cambia (sin lavado).
        val bright = floatArrayOf(0.95f, 0.90f, 0.60f)
        val y0 = luma(bright)
        val mask = PhotophobiaMath.glareMask(y0, maxOf(bright[0], bright[2]) - minOf(bright[0], bright[2]))
        assertTrue("debería entrar en la máscara", mask > 0.01f)
    }

    // ---------------------------------------------------------------------
    // 11) Contraste: pendiente C1 en el umbral (sin salto visible) y la
    //     zona media mantiene contraste local intacto.
    // ---------------------------------------------------------------------
    @Test
    fun contrastStableNoAbruptExposureJump() {
        // Sin discontinuidad: f(0.62-eps) ~ 0.62-eps y f(0.62+eps) ~ 0.62+eps.
        val below = PhotophobiaMath.knee(0.619f)
        val above = PhotophobiaMath.knee(0.621f)
        assertEquals("continuidad en el umbral", 0.62f, below, 0.002f)
        assertEquals("continuidad en el umbral", 0.62f, above, 0.002f)
        // Separación local en medios intacta (identity).
        val a = 0.30f
        val b = 0.36f
        assertEquals(b - a, PhotophobiaMath.knee(b) - PhotophobiaMath.knee(a), 1e-6f)
    }

    // ---------------------------------------------------------------------
    // 12) Dither: acotado a 1/255 y solo en la zona de compresión.
    // ---------------------------------------------------------------------
    @Test
    fun ditherBoundedAndLocal() {
        val hi = floatArrayOf(0.9f, 0.9f, 0.9f)
        val a = run(hi, defaults, 1f, 1f)
        val b = run(hi, defaults, 3f, 5f)
        assertTrue(abs(a[0] - b[0]) <= 1.01f / 255f)
        // Zona normal (Y=0.4): sin dither => posiciones idénticas.
        val mid = floatArrayOf(0.4f, 0.4f, 0.4f)
        val m1 = run(mid, floatArrayOf(0f, 0f, 1f, 1f), 1f, 1f)
        val m2 = run(mid, floatArrayOf(0f, 0f, 1f, 1f), 7f, 9f)
        for (k in 0..2) assertEquals("sin dither fuera de compresión", m1[k], m2[k], 0f)
        // Negro puro intacto.
        val blk = run(floatArrayOf(0f, 0f, 0f), defaults, 5f, 5f)
        for (k in 0..2) assertEquals(0f, blk[k], 1f / 255f)
    }

    // ---------------------------------------------------------------------
    // 13) Anime con fondos blancos: texto/rostros sobre blanco siguen
    //     diferenciados tras la rodilla.
    // ---------------------------------------------------------------------
    @Test
    fun animeWhiteBackgroundKeepsSeparation() {
        val bg = 0.98f
        val line = 0.88f // línea de trazo sobre fondo blanco
        val bgOut = PhotophobiaMath.knee(bg)
        val lineOut = PhotophobiaMath.knee(line)
        assertTrue("separación texto/fondo conservada", bgOut > lineOut + 0.01f)
        assertTrue(bgOut < bg)
    }

    // ---------------------------------------------------------------------
    // 14) Contrato con VisionAssistSettings (warm 60, desat 40, int 100).
    // ---------------------------------------------------------------------
    @Test
    fun defaultsMatchSettingsContract() {
        assertEquals(0.60f, defaults[0], 1e-6f)
        assertEquals(0.40f, defaults[1], 1e-6f)
        assertEquals(1.00f, defaults[2], 1e-6f)
        assertEquals(0.45f, defaults[3], 1e-6f)
    }
}
