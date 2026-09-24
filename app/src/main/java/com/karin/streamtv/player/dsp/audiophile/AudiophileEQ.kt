package com.karin.streamtv.player.dsp.audiophile

import com.karin.streamtv.player.dsp.BiquadFilter

/**
 * EQ paramétrica audiophile: bandas independientes con enable, headroom-aware.
 * Reutiliza [BiquadFilter] (peaking/shelf/corte/notch). Por muestra, sin alloc.
 */
class AudiophileEQ {
    private var filtersL = Array(0) { BiquadFilter() }
    private var filtersR = Array(0) { BiquadFilter() }
    private var lastFs = 0
    private var lastKey = ""

    /** Preamp recomendado (dB, negativo) por las bandas con boost. */
    fun maxBoostDb(bands: List<AudiophileConfig.Band>): Float {
        var m = 0f
        for (b in bands) if (b.enabled && b.gainDb > m) m = b.gainDb
        return m
    }

    fun configure(fs: Int, bands: List<AudiophileConfig.Band>) {
        val key = bands.joinToString("|") { "${it.freqHz}:${it.gainDb}:${it.q}:${it.kind}:${it.enabled}" }
        if (fs == lastFs && key == lastKey) return
        lastFs = fs
        lastKey = key
        if (filtersL.size != bands.size) {
            filtersL = Array(bands.size) { BiquadFilter() }
            filtersR = Array(bands.size) { BiquadFilter() }
        }
        val maxF = 0.45f * fs
        for (i in bands.indices) {
            val b = bands[i]
            if (!b.enabled) {
                filtersL[i].reset()
                filtersR[i].reset()
                continue
            }
            val f = b.freqHz.coerceIn(20f, maxF)
            filtersL[i].configure(b.kind, fs, f, b.gainDb, b.q.coerceAtLeast(0.1f))
            filtersR[i].configure(b.kind, fs, f, b.gainDb, b.q.coerceAtLeast(0.1f))
        }
    }

    fun process(l: Double, r: Double, out: DoubleArray) {
        var a = l
        var c = r
        for (i in filtersL.indices) {
            a = filtersL[i].process(a)
            c = filtersR[i].process(c)
        }
        out[0] = a
        out[1] = c
    }

    fun reset() {
        for (f in filtersL) f.reset()
        for (f in filtersR) f.reset()
    }
}
