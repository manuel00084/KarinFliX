package com.karin.streamtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Espejo CPU (JVM, sin GL) del bloque 2d "coherencia CRT" de
 * RestoreBoostEffect.kt. No ejecuta el shader; replica su matematica
 * (smoothstep/gate/horizEdge/vertEdge/thinGuard/w) para fijar 4 escenas:
 *
 *  1) cable claro 1px (centro brillante): el bloque nuevo debe saltarlo
 *     (w=0); el viejo (0.35, sin guarda clara, mezcla fija) lo tocaba.
 *  2) vecino al borde del cable: la deriva debe ser < 0.025 (el viejo
 *     dabas ~0.044: engordaba el cable).
 *  3) plano con ruido: gate=0 -> w=0 (no toca lo que ya limpio Depixel).
 *  4) techo anti-halo: w <= 0.08 con K=0.15 (el viejo llegaba a 0.175).
 *
 * Si alguien vuelve a subir K o a mezclar ambos pares sin direccion,
 * estos tests fallan antes de que se note "a ojo".
 */
class RestoreBoostMathTest {

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun clamp(x: Float, lo: Float, hi: Float) = min(max(x, lo), hi)

    data class Cross(
        val lL: Float, val lR: Float, val lU: Float, val lD: Float, val lOut: Float,
        val lc: Float = lOut, // centro ORIGINAL (para maxC del gate perceptual)
    )

    /** Gate VIEJO (absoluto) para comparar contra el nuevo en los tests 5-6. */
    private fun gateOld(edge: Float): Float = smoothstep(0.02f, 0.09f, edge)

    private fun edges(c: Cross): Triple<Float, Float, Float> {
        val gH = abs(c.lL - c.lR)
        val gV = abs(c.lU - c.lD)
        val edge = max(gH, gV)
        // Espejo del gate perceptual estilo FXAA (RestoreBoostEffect 2b):
        // 1/16 + suelo, 1/8 + suelo, relativo al luma local maxC.
        val maxC = max(max(max(c.lL, c.lR), max(c.lU, c.lD)), c.lc)
        val thrLo = max(0.008f, maxC * 0.0625f)
        val thrHi = max(0.030f, maxC * 0.125f)
        val gate = smoothstep(thrLo, thrHi, edge)
        val horizEdge = smoothstep(0.15f, 0.6f, (gV - gH) / (edge + 1e-4f))
        val vertEdge = smoothstep(0.15f, 0.6f, (gH - gV) / (edge + 1e-4f))
        return Triple(gate, horizEdge, vertEdge)
    }

    /** Peso w del bloque NUEVO (K=0.15, guarda clara, sin flatCap). */
    private fun weightNew(c: Cross, sA: Float = 1f): Float {
        val (gate, _, _) = edges(c)
        val minN = min(min(c.lL, c.lR), min(c.lU, c.lD))
        val maxN = max(max(c.lL, c.lR), max(c.lU, c.lD))
        val isDark = 1f - smoothstep(0.18f, 0.38f, c.lOut)
        val isThin = smoothstep(0.03f, 0.10f, maxN - c.lOut)
        val isBright = smoothstep(0.62f, 0.82f, c.lOut)
        val isThinB = smoothstep(0.03f, 0.10f, c.lOut - minN)
        val thinGuard = max(isDark * isThin, isBright * isThinB)
        val crtGate = gate * (1f - thinGuard)
        val crtK = sA * 0.15f
        if (crtK <= 0.001f || crtGate <= 0.001f) return 0f
        return clamp(crtGate * crtK * 0.5f, 0f, 1f)
    }

    /** Peso w del bloque VIEJO (K=0.35, solo guarda oscura con flatCap). */
    private fun weightOld(c: Cross, sA: Float = 1f): Float {
        val (gate, _, _) = edges(c)
        val minN = min(min(c.lL, c.lR), min(c.lU, c.lD))
        val maxN = max(max(c.lL, c.lR), max(c.lU, c.lD))
        val isDark = 1f - smoothstep(0.18f, 0.38f, c.lOut)
        val isThin = smoothstep(0.03f, 0.10f, maxN - c.lOut)
        val flatCap = 1f - smoothstep(0.20f, 0.35f, maxN - minN)
        val crtGate = gate * (1f - isDark * isThin * flatCap)
        val crtK = sA * 0.35f
        if (crtK <= 0.001f || crtGate <= 0.001f) return 0f
        return clamp(crtGate * crtK * 0.5f, 0f, 1f)
    }

    // 1) Cable claro 1px: centro 0.90 rodeado de 0.30 (borde fuerte).
    @Test
    fun cableClaroCentroSeSalta() {
        // Centro sobre el cable: L/R oscuros, U/D a lo largo (claros).
        // El detector de borde ve gH=0/gV=0 -> gate=0, pero el pixel vecino
        // al borde si tiene gate=1; aqui se prueba la guarda de polaridad:
        // con contraste alto el viejo no protegia (flatCap=0) y el nuevo si.
        val centro = Cross(0.30f, 0.30f, 0.90f, 0.90f, 0.90f)
        // Caso que si activa gate: pixel justo al filo del cable claro.
        val filo = Cross(0.30f, 0.90f, 0.90f, 0.90f, 0.90f)
        val wNew = weightNew(filo)
        val wOld = weightOld(filo)
        println("cable claro filo: wNew=$wNew wOld=$wOld")
        assertEquals(0f, wNew, 1e-4f) // guarda clara lo salta
        assertTrue("el viejo tocaba el cable claro (wOld=$wOld)", wOld > 0.05f)
        // El centro, sin gradiente, tampoco se toca en ninguno.
        assertEquals(0f, weightNew(centro), 1e-4f)
    }

    // 2) Vecino al borde del cable: deriva pequena (no engorda la linea).
    @Test
    fun vecinoDeCableNoSeEngorda() {
        // Pixel de fondo (0.35) pegado al cable (R=0.85): borde vertical.
        val v = Cross(lL = 0.35f, lR = 0.85f, lU = 0.35f, lD = 0.35f, lOut = 0.35f)
        val (_, _, vertEdge) = edges(v)
        assertTrue("escena debe ser borde vertical (vertEdge=$vertEdge)", vertEdge > 0.9f)
        // Deriva = w * |crtL - curL|; con direccion 2b vertEdge->avgH:
        // avgH=(0.35+0.85)/2=0.60, crtL~=0.60, curL=0.35.
        val avgH = (v.lL + v.lR) * 0.5f
        val driftNew = weightNew(v) * abs(avgH - v.lOut)
        val driftOld = weightOld(v) * abs(avgH - v.lOut)
        println("vecino cable: driftNew=$driftNew driftOld=$driftOld")
        assertTrue("el filo debe seguir siendo filo (deriva<0.025, fue $driftNew)", driftNew < 0.025f)
        assertTrue("el viejo engordaba mas que el nuevo", driftOld > driftNew)
    }

    // 3) Plano con ruido: sin estructura -> w=0.
    @Test
    fun planoConRuidoNoSeToca() {
        val p = Cross(0.500f, 0.508f, 0.495f, 0.503f, 0.500f)
        assertEquals(0f, weightNew(p), 1e-4f)
        assertEquals(0f, weightOld(p), 1e-4f)
    }

    // 4) Techo anti-halo: ni en el peor caso direccional w supera 0.08.
    @Test
    fun techoAntiHalo() {
        val e = Cross(lL = 0.20f, lR = 0.80f, lU = 0.80f, lD = 0.80f, lOut = 0.80f)
        val wNew = weightNew(e, sA = 1f)
        val wOld = weightOld(e, sA = 1f)
        println("techo: wNew=$wNew wOld=$wOld")
        assertTrue("techo nuevo w<=0.08 (fue $wNew)", wNew <= 0.08f)
        assertTrue("el viejo superaba el techo (wOld=$wOld)", wOld > 0.08f)
    }

    // 5) Gate perceptual en sombra: el viejo (absoluto) no ve el borde
    //    tenue oscuro; el nuevo abre gracias al umbral relativo + suelo.
    @Test
    fun sombraAbreGate() {
        val s = Cross(lL = 0.080f, lR = 0.095f, lU = 0.080f, lD = 0.080f, lOut = 0.085f)
        val edge = abs(s.lL - s.lR)
        val (gateNew, _, _) = edges(s)
        val gateViejo = gateOld(edge)
        println("sombra: edge=$edge gateNew=$gateNew gateViejo=$gateViejo")
        assertEquals(0f, gateViejo, 1e-4f)
        assertTrue("en sombra el gate nuevo debe abrir (fue $gateNew)", gateNew > 0.15f)
    }

    // 6) Gate perceptual en luz: el viejo satura a 1 con cualquier escalon;
    //    el nuevo modera (sigue detectando, pero sin disparo total).
    @Test
    fun luzModeraGate() {
        val s = Cross(lL = 0.85f, lR = 0.95f, lU = 0.90f, lD = 0.90f, lOut = 0.90f)
        val edge = abs(s.lL - s.lR)
        val (gateNew, _, _) = edges(s)
        val gateViejo = gateOld(edge)
        println("luz: edge=$edge gateNew=$gateNew gateViejo=$gateViejo")
        assertEquals(1f, gateViejo, 1e-4f)
        assertTrue("en luz el gate nuevo no debe saturar (fue $gateNew)", gateNew < 1f)
        assertTrue("pero debe seguir detectando (fue $gateNew)", gateNew > 0.5f)
    }
}
