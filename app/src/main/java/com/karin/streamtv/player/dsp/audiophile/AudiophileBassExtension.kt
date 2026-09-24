package com.karin.streamtv.player.dsp.audiophile

import com.karin.streamtv.player.dsp.BiquadFilter
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Bass Extension ligero: genera 2º/3º armónico controlado por debajo de una
 * frecuencia de corte para mejorar la percepción de graves en dispositivos
 * con limitación de reproducción (NO es un simple Bass Boost +6 dB).
 * Muy barato en CPU: 1 LP + waveshaper suave + mezcla.
 */
class AudiophileBassExtension {
    private val lpL = BiquadFilter()
    private val lpR = BiquadFilter()
    private val hpReinjectL = BiquadFilter()
    private val hpReinjectR = BiquadFilter()
    private var active = false
    private var amount = 0.0
    private var harmonicMix = 0.5

    fun configure(fs: Int, enabled: Boolean, freqHz: Float, amt: Float, hmix: Float) {
        active = enabled && fs > 0
        amount = amt.coerceIn(0f, 1f).toDouble()
        harmonicMix = hmix.coerceIn(0f, 1f).toDouble()
        val f = freqHz.coerceIn(40f, 0.2f * fs)
        lpL.configure(BiquadFilter.Kind.LOWPASS, fs, f, 0f, 0.707f)
        lpR.configure(BiquadFilter.Kind.LOWPASS, fs, f, 0f, 0.707f)
        // Reinyectar solo la parte de graves generada (no el fundamental completo).
        hpReinjectL.configure(BiquadFilter.Kind.HIGHPASS, fs, f, 0f, 0.707f)
        hpReinjectR.configure(BiquadFilter.Kind.HIGHPASS, fs, f, 0f, 0.707f)
    }

    fun process(l: Double, r: Double, out: DoubleArray) {
        if (!active || amount <= 0.0) {
            out[0] = l; out[1] = r
            return
        }
        val bL = lpL.process(l)
        val bR = lpR.process(r)
        // 2º armónico controlado: x*|x| (asimetría suave) → tono "más grave audible".
        val hL = bL * abs(bL)
        val hR = bR * abs(bR)
        // Mezcla: fundamental + amount * (armónico mezclado con la banda LP).
        val g = amount
        val m = harmonicMix
        out[0] = l + g * (m * hL + (1.0 - m) * bL * 0.35)
        out[1] = r + g * (m * hR + (1.0 - m) * bR * 0.35)
        // Limitar el peak generado para no empujar el TP limiter al máximo.
        out[0] = tanh(out[0])
        out[1] = tanh(out[1])
    }

    fun reset() {
        lpL.reset(); lpR.reset(); hpReinjectL.reset(); hpReinjectR.reset()
    }
}
