package com.karin.streamtv.player.dsp.audiophile

import com.karin.streamtv.player.dsp.BiquadFilter

/**
 * Crossfeed para auriculares (estilo Meier/bs2b simplificado): reduce la
 * separación extrema L/R mezclando una versión LP+retardada del canal
 * contrario. NO añade reverberación ni lo convierte en surround.
 * Modos: OFF / LOW / MEDIUM / HIGH.
 */
class AudiophileCrossfeed {
    private val lpL = BiquadFilter()
    private val lpR = BiquadFilter()
    private var delayL = DoubleArray(1)
    private var delayR = DoubleArray(1)
    private var idxL = 0
    private var idxR = 0
    private var mix = 0.0
    private var active = false

    fun configure(fs: Int, mode: AudiophileConfig.CrossfeedMode) {
        active = mode != AudiophileConfig.CrossfeedMode.OFF && fs > 0
        mix = when (mode) {
            AudiophileConfig.CrossfeedMode.OFF -> 0.0
            AudiophileConfig.CrossfeedMode.LOW -> 0.12
            AudiophileConfig.CrossfeedMode.MEDIUM -> 0.22
            AudiophileConfig.CrossfeedMode.HIGH -> 0.35
        }
        // Filtro "head-related": LP ~700 Hz (la energía contralateral de alta
        // frecuencia se atenúa naturalmente por la cabeza).
        lpL.configure(BiquadFilter.Kind.LOWPASS, fs, 700f, 0f, 0.707f)
        lpR.configure(BiquadFilter.Kind.LOWPASS, fs, 700f, 0f, 0.707f)
        // Delay mínimo (~0.3 ms) para ITD sutil.
        val n = (0.0003 * fs).toInt().coerceAtLeast(1)
        if (delayL.size != n) {
            delayL = DoubleArray(n)
            delayR = DoubleArray(n)
            idxL = 0; idxR = 0
        }
    }

    fun process(l: Double, r: Double, out: DoubleArray) {
        if (!active) {
            out[0] = l; out[1] = r
            return
        }
        val dL = delayL[idxL]
        val dR = delayR[idxR]
        delayL[idxL] = l
        delayR[idxR] = r
        idxL = (idxL + 1) % delayL.size
        idxR = (idxR + 1) % delayR.size
        val cL = lpL.process(dR) // energía de R hacia L (retardada)
        val cR = lpR.process(dL)
        out[0] = l + mix * cL
        out[1] = r + mix * cR
    }

    fun reset() {
        lpL.reset(); lpR.reset()
        delayL.fill(0.0); delayR.fill(0.0)
        idxL = 0; idxR = 0
    }
}
