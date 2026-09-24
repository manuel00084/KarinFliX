package com.karin.streamtv.player.dsp.audiophile

import com.karin.streamtv.player.dsp.BiquadFilter
import kotlin.math.tanh

/**
 * Harmonic Enhancement (nombre técnico, sin marketing): añade armónicos
 * superiores muy sutiles vía waveshaper suave en la banda alta.
 * Preferencia: transparencia. Defaults mínimos; el usuario puede apagarlo.
 */
class AudiophileHarmonic {
    private val hpL = BiquadFilter()
    private val hpR = BiquadFilter()
    private val lpShapeL = BiquadFilter()
    private val lpShapeR = BiquadFilter()
    private var active = false
    private var amount = 0.1
    private var drive = 1.5
    private var mix = 0.4

    fun configure(fs: Int, enabled: Boolean, freqHz: Float, amt: Float, drv: Float, mx: Float) {
        active = enabled && fs > 0
        amount = amt.coerceIn(0f, 1f).toDouble()
        drive = (1.0 + drv.coerceIn(0f, 1f) * 3.0) // 1..4
        mix = mx.coerceIn(0f, 1f).toDouble()
        val f = freqHz.coerceIn(500f, 0.4f * fs)
        hpL.configure(BiquadFilter.Kind.HIGHPASS, fs, f, 0f, 0.707f)
        hpR.configure(BiquadFilter.Kind.HIGHPASS, fs, f, 0f, 0.707f)
        lpShapeL.configure(BiquadFilter.Kind.LOWPASS, fs, f, 0f, 0.707f)
        lpShapeR.configure(BiquadFilter.Kind.LOWPASS, fs, f, 0f, 0.707f)
    }

    fun process(l: Double, r: Double, out: DoubleArray) {
        if (!active || amount <= 0.0) {
            out[0] = l; out[1] = r
            return
        }
        val hiL = hpL.process(l)
        val hiR = hpR.process(r)
        // tanh suave (odd harmonics) sobre la banda alta; mezcla controlada.
        val sL = tanh(hiL * drive) / drive
        val sR = tanh(hiR * drive) / drive
        out[0] = l + amount * mix * (sL - hiL)
        out[1] = r + amount * mix * (sR - hiR)
    }

    fun reset() {
        hpL.reset(); hpR.reset(); lpShapeL.reset(); lpShapeR.reset()
    }
}
