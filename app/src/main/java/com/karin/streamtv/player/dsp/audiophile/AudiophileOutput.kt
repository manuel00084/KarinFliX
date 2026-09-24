package com.karin.streamtv.player.dsp.audiophile

import com.karin.streamtv.player.dsp.BiquadFilter
import com.karin.streamtv.player.dsp.LookaheadLimiterPair
import kotlin.math.abs
import kotlin.math.max

/**
 * Output stage: Output Gain → True Peak Protection (limitador lookahead con
 * detección inter-sample) → (dither lo aplica el cuantizador del procesador
 * principal solo si hace falta). El limiter es PROTECCIÓN, no efecto de
 * loudness agresivo: umbral configurable −0.5/−1.0/−1.5/−2.0 dBTP.
 */
class AudiophileOutput {
    private val limiter = LookaheadLimiterPair()
    private val dcBlockL = BiquadFilter()
    private val dcBlockR = BiquadFilter()
    private var gainLin = 1.0
    private var ceilingLin = 0.891 // -1.0 dBTP
    private var ready = false

    @Volatile var outputPeak = 0.0
        private set
    @Volatile var truePeakEst = 0.0
        private set

    // Estado para estimación true-peak (misma cúbica Catmull-Rom que el DSP actual).
    private var p0L = 0.0; private var p1L = 0.0; private var p2L = 0.0
    private var p0R = 0.0; private var p1R = 0.0; private var p2R = 0.0

    fun configure(fs: Int, ceilingDb: Float, outputGainDb: Float) {
        ceilingLin = AudiophileConfig.dbToLin(ceilingDb)
        gainLin = AudiophileConfig.dbToLin(outputGainDb)
        limiter.configure(fs, 2f, 120f, ceilingLin)
        dcBlockL.configure(BiquadFilter.Kind.HIGHPASS, fs, 12f, 0f, 0.707f)
        dcBlockR.configure(BiquadFilter.Kind.HIGHPASS, fs, 12f, 0f, 0.707f)
        ready = true
        outputPeak = 0.0
        truePeakEst = 0.0
    }

    private val scratch = DoubleArray(2)

    fun process(l: Double, r: Double, out: DoubleArray) {
        if (!ready) {
            out[0] = l; out[1] = r
            return
        }
        var a = dcBlockL.process(l) * gainLin
        var b = dcBlockR.process(r) * gainLin
        // Sanidad NaN/Inf → 0 (nunca propagar al DAC).
        if (!a.isFinite()) a = 0.0
        if (!b.isFinite()) b = 0.0
        limiter.process(a, b, scratch)
        a = scratch[0]
        b = scratch[1]
        // Estimación true-peak (inter-sample) para el monitor.
        val tpL = truePeakOf(p0L, p1L, a, /*future*/ a)
        val tpR = truePeakOf(p0R, p1R, b, b)
        p0L = p1L; p1L = a
        p0R = p1R; p1R = b
        val tp = max(tpL, tpR)
        if (tp > truePeakEst) truePeakEst = tp
        else truePeakEst *= 0.9995
        val pk = max(abs(a), abs(b))
        if (pk > outputPeak) outputPeak = pk else outputPeak *= 0.9995
        out[0] = a
        out[1] = b
    }

    private fun truePeakOf(m0: Double, m1: Double, m2: Double, m3: Double): Double {
        var p = max(abs(m1), abs(m2))
        val a0 = 2.0 * m1
        val a1 = -m0 + m2
        val a2 = 2.0 * m0 - 5.0 * m1 + 4.0 * m2 - m3
        val a3 = -m0 + 3.0 * m1 - 3.0 * m2 + m3
        for (t in doubleArrayOf(0.25, 0.5, 0.75)) {
            val v = 0.5 * (a0 + a1 * t + a2 * t * t + a3 * t * t * t)
            val av = abs(v)
            if (av > p) p = av
        }
        return p
    }

    fun reset() {
        limiter.reset()
        dcBlockL.reset(); dcBlockR.reset()
        p0L = 0.0; p1L = 0.0; p0R = 0.0; p1R = 0.0
        outputPeak = 0.0; truePeakEst = 0.0
    }
}
