package com.karin.streamtv.player.dsp.audiophile

import com.karin.streamtv.player.dsp.BiquadFilter
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Compresor estéreo suave de 2 bandas (LP 250 / HP 3000) para preservar
 * dinámica sin pumping agresivo. Solo activo si el usuario lo enciende.
 * Mide de forma independiente el dynamic range (crest approx) del flujo
 * para el monitor informativo (no bloquea el DSP).
 */
class AudiophileDynamics {
    private val lpL = BiquadFilter()
    private val lpR = BiquadFilter()
    private val hpL = BiquadFilter()
    private val hpR = BiquadFilter()
    private var envLowL = 0.0
    private var envLowR = 0.0
    private var envHighL = 0.0
    private var envHighR = 0.0
    private var atk = 0.0
    private var rel = 0.0
    private var active = false

    // Ratio suave: reducción máx ~3 dB bajo umbral alto (crest-preserving).
    private val threshold = 0.5 // ~ -6 dBFS
    private val ratio = 2.0

    private var peakHold = 0.0
    private var rmsAcc = 0.0
    private var rmsN = 0
    private var rmsWindow = 4800

    @Volatile
    var dynamicRangeDb = Float.NaN
        private set

    fun configure(fs: Int, enabled: Boolean) {
        active = enabled && fs > 0
        lpL.configure(BiquadFilter.Kind.LOWPASS, fs, 250f, 0f, 0.707f)
        lpR.configure(BiquadFilter.Kind.LOWPASS, fs, 250f, 0f, 0.707f)
        hpL.configure(BiquadFilter.Kind.HIGHPASS, fs, 3000f, 0f, 0.707f)
        hpR.configure(BiquadFilter.Kind.HIGHPASS, fs, 3000f, 0f, 0.707f)
        atk = Math.exp(-1.0 / (0.030 * fs))
        rel = Math.exp(-1.0 / (0.300 * fs))
        rmsWindow = (fs / 10).coerceAtLeast(1024)
        envLowL = 0.0; envLowR = 0.0; envHighL = 0.0; envHighR = 0.0
        peakHold = 0.0; rmsAcc = 0.0; rmsN = 0
    }

    fun process(l: Double, r: Double, out: DoubleArray) {
        // Medición DR siempre (muy barata): peak-hold + RMS de ventana 100 ms.
        val mono = (l + r) * 0.5
        val a = abs(mono)
        peakHold = max(peakHold * 0.9995, a)
        rmsAcc += mono * mono
        rmsN++
        if (rmsN >= rmsWindow) {
            val rms = sqrt(rmsAcc / rmsN)
            if (rms > 1e-9 && peakHold > 1e-9) {
                dynamicRangeDb = (20.0 * log10(peakHold / rms)).toFloat()
            }
            rmsAcc = 0.0
            rmsN = 0
            peakHold *= 0.5
        }

        if (!active) {
            out[0] = l
            out[1] = r
            return
        }

        // Banda low y high por canal (filtros separados L/R).
        val lowL = lpL.process(l)
        val lowR = lpR.process(r)
        val highL = hpL.process(l)
        val highR = hpR.process(r)

        envLowL = follow(envLowL, abs(lowL))
        envLowR = follow(envLowR, abs(lowR))
        envHighL = follow(envHighL, abs(highL))
        envHighR = follow(envHighR, abs(highR))

        val gLowL = bandGain(envLowL)
        val gLowR = bandGain(envLowR)
        val gHighL = bandGain(envHighL)
        val gHighR = bandGain(envHighR)

        // Suma de bandas ganadas ≈ señal procesada (crossovers no perfectos,
        // pero suficiente para un compresor de preservación suave).
        out[0] = lowL * gLowL + highL * gHighL
        out[1] = lowR * gLowR + highR * gHighR
    }

    private fun follow(env: Double, x: Double): Double =
        if (x > env) env + (x - env) * (1.0 - atk) else env + (x - env) * (1.0 - rel)

    private fun bandGain(env: Double): Double {
        if (env <= threshold) return 1.0
        // over = env/threshold; gain = over^(1/ratio - 1) → reduce hacia 1/ratio.
        val over = env / threshold
        val gain = Math.pow(over, 1.0 / ratio - 1.0)
        return gain.coerceIn(1.0 / ratio, 1.0)
    }

    fun reset() {
        lpL.reset(); lpR.reset(); hpL.reset(); hpR.reset()
        envLowL = 0.0; envLowR = 0.0; envHighL = 0.0; envHighR = 0.0
        peakHold = 0.0; rmsAcc = 0.0; rmsN = 0
        dynamicRangeDb = Float.NaN
    }
}
