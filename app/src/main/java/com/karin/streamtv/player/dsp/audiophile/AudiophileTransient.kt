package com.karin.streamtv.player.dsp.audiophile

import com.karin.streamtv.player.dsp.BiquadFilter
import kotlin.math.abs

/**
 * Transient Control sutil: realza ligeramente el ataque de percusión/guitarra
 * comparando envolvente rápida vs lenta. Por defecto muy discreto (amount bajo).
 * Evita procesamiento agresivo: solo un boost multiplicativo corto.
 */
class AudiophileTransient {
    private val hpL = BiquadFilter()
    private val hpR = BiquadFilter()
    private var envFastL = 0.0
    private var envFastR = 0.0
    private var envSlowL = 0.0
    private var envSlowR = 0.0
    private var atkFast = 0.0
    private var relFast = 0.0
    private var atkSlow = 0.0
    private var relSlow = 0.0
    private var active = false
    private var amount = 0.25

    fun configure(fs: Int, enabled: Boolean, amt: Float, attack: Float, release: Float) {
        active = enabled && fs > 0
        amount = amt.coerceIn(0f, 1f).toDouble()
        // Banda de medios-altos donde vive el "ataque" percusivo.
        hpL.configure(BiquadFilter.Kind.HIGHPASS, fs, 1200f, 0f, 0.707f)
        hpR.configure(BiquadFilter.Kind.HIGHPASS, fs, 1200f, 0f, 0.707f)
        val atkMs = (1.0 + attack * 9.0) // 1..10 ms
        val relMs = (20.0 + release * 180.0) // 20..200 ms
        atkFast = Math.exp(-1.0 / (atkMs * 0.001 * fs))
        relFast = Math.exp(-1.0 / (relMs * 0.001 * fs))
        atkSlow = Math.exp(-1.0 / (0.050 * fs))
        relSlow = Math.exp(-1.0 / (0.300 * fs))
        envFastL = 0.0; envFastR = 0.0; envSlowL = 0.0; envSlowR = 0.0
    }

    fun process(l: Double, r: Double, out: DoubleArray) {
        if (!active || amount <= 0.0) {
            out[0] = l; out[1] = r
            return
        }
        val bL = hpL.process(l)
        val bR = hpR.process(r)
        envFastL = follow(envFastL, abs(bL), atkFast, relFast)
        envFastR = follow(envFastR, abs(bR), atkFast, relFast)
        envSlowL = follow(envSlowL, abs(bL), atkSlow, relSlow)
        envSlowR = follow(envSlowR, abs(bR), atkSlow, relSlow)
        // Diferencia de envolventes → boost proporcional al "ataque".
        val dL = (envFastL - envSlowL).coerceAtLeast(0.0)
        val dR = (envFastR - envSlowR).coerceAtLeast(0.0)
        val gL = 1.0 + amount * 4.0 * dL / (envSlowL + 1e-6)
        val gR = 1.0 + amount * 4.0 * dR / (envSlowR + 1e-6)
        // Aplicar solo sobre la banda HP (dry + boosted band) para no distorsionar graves.
        out[0] = l + bL * (gL - 1.0)
        out[1] = r + bR * (gR - 1.0)
    }

    private fun follow(env: Double, x: Double, atk: Double, rel: Double): Double =
        if (x > env) env + (x - env) * (1.0 - atk) else env + (x - env) * (1.0 - rel)

    fun reset() {
        hpL.reset(); hpR.reset()
        envFastL = 0.0; envFastR = 0.0; envSlowL = 0.0; envSlowR = 0.0
    }
}
