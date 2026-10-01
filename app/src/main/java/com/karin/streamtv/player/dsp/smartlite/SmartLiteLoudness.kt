package com.karin.streamtv.player.dsp.smartlite

import com.karin.streamtv.player.dsp.BiquadFilter
import kotlin.math.log10
import kotlin.math.pow

/**
 * Medición + corrección suave de loudness (BS.1770 / K-weighting).
 * Objetivo configurable (−14 / −16 / −18 LUFS). NO es un AGC agresivo:
 * corrige como máximo ±3 dB con ramps lentos para conservar dinámica.
 * Mide short-term (ventana ~3 s) y publica valores para el monitor.
 */
class SmartLiteLoudness {
    private var hpL = BiquadFilter()
    private var hpR = BiquadFilter()
    private var shL = BiquadFilter()
    private var shR = BiquadFilter()
    private var acc = 0.0
    private var leak = 0.0
    private var accNorm = 0.0
    private var smooth = 0.0
    private var sampleSmooth = 0.0
    private var cnt = 0
    private var updateEvery = 4096
    private var gainDb = 0.0
    private var targetGain = 1.0
    private var appliedGain = 1.0
    private var active = false
    private var target = -16.0

    @Volatile var shortTermLufs = Float.NEGATIVE_INFINITY
        private set

    fun configure(fs: Int, enabled: Boolean, targetLufs: Float) {
        target = targetLufs.toDouble()
        active = enabled && fs > 0
        if (!active) {
            gainDb = 0.0
            targetGain = 1.0
            appliedGain = 1.0
            acc = 0.0
            shortTermLufs = Float.NEGATIVE_INFINITY
            return
        }
        hpL.configure(BiquadFilter.Kind.HIGHPASS, fs, 38f, 0f, 0.707f)
        hpR.configure(BiquadFilter.Kind.HIGHPASS, fs, 38f, 0f, 0.707f)
        shL.configure(BiquadFilter.Kind.HIGHSHELF, fs, 1681.97f, 4f, 0.707f)
        shR.configure(BiquadFilter.Kind.HIGHSHELF, fs, 1681.97f, 4f, 0.707f)
        leak = Math.exp(-1.0 / (3.0 * fs))
        updateEvery = (fs / 4).coerceAtLeast(2048)
        // El integrador con fuga `acc = acc*leak + p` tiene ganancia DC
        // 1/(1-leak), o sea que en régimen permanente `acc` vale la potencia
        // media multiplicada por (1-leak)⁻¹. BS.1770 quiere la potencia media,
        // así que hay que deshacer esa ganancia. Sin esto el logaritmo marca
        // 10*log10(3*fs) ≈ 45 dB por encima y el LUFS sale siempre inflado.
        accNorm = 1.0 - leak
        // El tau de 1.5 s se mide en el INTERVALO DE ACTUALIZACIÓN, no por
        // muestra: este factor solo se aplica cada `updateEvery` muestras.
        // Usar el factor por muestra (exp(-1/(1.5*fs)) ≈ 1.4e-5) aquí daba un
        // tau de horas, y la corrección no llegaba a moverse en minutos.
        smooth = 1.0 - Math.exp(-updateEvery / (1.5 * fs))
        // La ganancia APLICADA se interpola muestra a muestra hacia el objetivo
        // del bloque (tau 50 ms): sin esto, cada actualización de 0.25 s era un
        // escalón audible (zipper/pumping) en cambios de nivel.
        sampleSmooth = 1.0 - Math.exp(-1.0 / (0.050 * fs))
        acc = 0.0
        gainDb = 0.0
        targetGain = 1.0
        appliedGain = 1.0
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
            // z es la suma de potencias medias por canal (G = 1 para L/R),
            // ya normalizada por la ganancia DC del integrador.
            val lufs = -0.691 + 10.0 * log10((acc * accNorm).coerceAtLeast(1e-12))
            shortTermLufs = lufs.toFloat()
            if (lufs > -70.0) {
                val need = (target - lufs).coerceIn(-3.0, 3.0)
                gainDb += (need - gainDb) * smooth
                targetGain = 10.0.pow(gainDb / 20.0)
            }
        }
        // Interpolación por muestra hacia el objetivo del bloque: la misma
        // corrección lenta, sin escalones cada 0.25 s.
        appliedGain += (targetGain - appliedGain) * sampleSmooth
        return appliedGain
    }

    fun reset() {
        hpL.reset(); hpR.reset(); shL.reset(); shR.reset()
        acc = 0.0; gainDb = 0.0; targetGain = 1.0; appliedGain = 1.0; cnt = 0
        shortTermLufs = Float.NEGATIVE_INFINITY
    }
}
