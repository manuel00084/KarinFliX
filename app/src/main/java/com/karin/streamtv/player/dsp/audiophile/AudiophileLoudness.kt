package com.karin.streamtv.player.dsp.audiophile

import com.karin.streamtv.player.dsp.BiquadFilter
import kotlin.math.log10
import kotlin.math.pow

/**
 * Medición + corrección suave de loudness (BS.1770 / K-weighting).
 * Objetivo configurable (−14 / −16 / −18 LUFS). NO es un AGC agresivo:
 * corrige como máximo ±3 dB con ramps lentos para conservar dinámica.
 * Mide short-term (ventana ~3 s) y publica valores para el monitor.
 */
class AudiophileLoudness {
    private var hpL = BiquadFilter()
    private var hpR = BiquadFilter()
    private var shL = BiquadFilter()
    private var shR = BiquadFilter()
    private var acc = 0.0
    private var leak = 0.0
    private var smooth = 0.0
    private var cnt = 0
    private var updateEvery = 4096
    private var gainDb = 0.0
    private var active = false
    private var target = -16.0

    @Volatile var shortTermLufs = Float.NEGATIVE_INFINITY
        private set

    fun configure(fs: Int, enabled: Boolean, targetLufs: Float) {
        target = targetLufs.toDouble()
        active = enabled && fs > 0
        if (!active) {
            gainDb = 0.0
            acc = 0.0
            shortTermLufs = Float.NEGATIVE_INFINITY
            return
        }
        hpL.configure(BiquadFilter.Kind.HIGHPASS, fs, 38f, 0f, 0.707f)
        hpR.configure(BiquadFilter.Kind.HIGHPASS, fs, 38f, 0f, 0.707f)
        shL.configure(BiquadFilter.Kind.HIGHSHELF, fs, 1681.97f, 4f, 0.707f)
        shR.configure(BiquadFilter.Kind.HIGHSHELF, fs, 1681.97f, 4f, 0.707f)
        leak = Math.exp(-1.0 / (3.0 * fs))
        smooth = Math.exp(-1.0 / (1.5 * fs))
        updateEvery = (fs / 4).coerceAtLeast(2048)
        acc = 0.0
        gainDb = 0.0
        cnt = 0
    }

    /**
     * Alimenta el medidor con la señal de ENTRADA (post-preamp, pre-efectos)
     * y devuelve la ganancia lineal de corrección (1.0 si está off).
     */
    fun processGain(l: Double, r: Double): Double {
        if (!active) return 1.0
        val al = shL.process(hpL.process(l))
        val ar = shR.process(hpR.process(r))
        acc = acc * leak + (al * al + ar * ar)
        cnt++
        if (cnt >= updateEvery) {
            cnt = 0
            val lufs = -0.691 + 10.0 * log10((acc / 2.0).coerceAtLeast(1e-12))
            shortTermLufs = lufs.toFloat()
            if (lufs > -70.0) {
                val need = (target - lufs).coerceIn(-3.0, 3.0)
                gainDb += (need - gainDb) * (1.0 - smooth)
            }
        }
        return 10.0.pow(gainDb / 20.0)
    }

    fun reset() {
        hpL.reset(); hpR.reset(); shL.reset(); shR.reset()
        acc = 0.0; gainDb = 0.0; cnt = 0
        shortTermLufs = Float.NEGATIVE_INFINITY
    }
}
