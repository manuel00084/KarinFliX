package com.karin.streamtv.player.dsp.smartlite

import com.karin.streamtv.player.dsp.BiquadFilter
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Bass Extension ligero: genera 2º/3º armónico controlado por debajo de una
 * frecuencia de corte para mejorar la percepción de graves en dispositivos
 * con limitación de reproducción (NO es un simple Bass Boost +6 dB).
 * Muy barato en CPU: 1 LP + waveshaper suave + mezcla.
 */
class SmartLiteBassExtension {
    private val lpL = BiquadFilter()
    private val lpR = BiquadFilter()
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
        // Limitar SOLO el pico generado, antes de sumarlo a la señal original.
        // Aplicar tanh a la señal compuesta satura todo el rango medio y hace
        // distorsionar las voces: tanh(x) ≈ x solo para x pequeño, y a x = 0.9
        // devuelve 0.76, o sea una compresión permanente del programa entero.
        // Aquí el término es pequeño (~0.1), así que tanh casi no lo toca en
        // normal y solo actúa cuando el generado se dispara.
        val addL = g * (m * hL + (1.0 - m) * bL * 0.35)
        val addR = g * (m * hR + (1.0 - m) * bR * 0.35)
        out[0] = l + tanh(addL)
        out[1] = r + tanh(addR)
    }

    fun reset() {
        lpL.reset(); lpR.reset()
    }
}
