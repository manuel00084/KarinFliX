package com.karin.streamtv.player.dsp.smartlite

import com.karin.streamtv.player.dsp.BiquadFilter

/**
 * Crossfeed para auriculares (estilo Meier/bs2b simplificado): reduce la
 * separación extrema L/R mezclando una versión LP+retardada del canal
 * contrario, con un sangrado menor de agudos para no apagar el aire estéreo.
 * NO añade reverberación ni lo convierte en surround.
 * Modos: OFF / LOW / MEDIUM / HIGH.
 */
class SmartLiteCrossfeed {
    private val lpL = BiquadFilter()
    private val lpR = BiquadFilter()
    private val hpL = BiquadFilter()
    private val hpR = BiquadFilter()
    private var delayL = DoubleArray(1)
    private var delayR = DoubleArray(1)
    private var idxL = 0
    private var idxR = 0
    private var mix = 0.0
    private var direct = 1.0

    /** Sangrado de agudos relativo al mix: los agudos cruzan menos que los graves. */
    private val hfBleed = 0.3
    private var active = false

    fun configure(fs: Int, mode: SmartLiteConfig.CrossfeedMode) {
        active = mode != SmartLiteConfig.CrossfeedMode.OFF && fs > 0
        mix = when (mode) {
            SmartLiteConfig.CrossfeedMode.OFF -> 0.0
            SmartLiteConfig.CrossfeedMode.LOW -> 0.12
            SmartLiteConfig.CrossfeedMode.MEDIUM -> 0.22
            SmartLiteConfig.CrossfeedMode.HIGH -> 0.35
        }
        // COMPENSACIÓN DE NIVEL: la suma era `l + mix·cL`, o sea ×(1+mix) en
        // graves (hasta +3.5 dB en HIGH) y sin límite en DC. Se notaba como
        // "el bajo se infla" al activar el crossfeed. Con `direct = 1 − mix` el
        // contenido correlacionado —que es el bajo casi siempre— suma
        // exactamente 1.0, así que el crossfeed solo cambia la imagen estéreo y
        // no el nivel. Es el mismo compromiso de bs2b: se recorta el canal
        // ipsilateral para mantener la potencia total.
        direct = 1.0 - mix
        // Filtro "head-related": LP ~700 Hz (la energía contralateral de alta
        // frecuencia se atenúa naturalmente por la cabeza) más un pasa-altos a
        // 2 kHz con mezcla reducida: los agudos cruzan ~1/3 que los graves para
        // conservar aire estéreo sin endurecer el centro.
        lpL.configure(BiquadFilter.Kind.LOWPASS, fs, 700f, 0f, 0.707f)
        lpR.configure(BiquadFilter.Kind.LOWPASS, fs, 700f, 0f, 0.707f)
        hpL.configure(BiquadFilter.Kind.HIGHPASS, fs, 2000f, 0f, 0.707f)
        hpR.configure(BiquadFilter.Kind.HIGHPASS, fs, 2000f, 0f, 0.707f)
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
        // Energía de R hacia L (retardada): graves casi íntegros + agudos
        // atenuados, como la sombra acústica de la cabeza.
        val cL = lpL.process(dR) + hfBleed * hpL.process(dR)
        val cR = lpR.process(dL) + hfBleed * hpR.process(dL)
        out[0] = l * direct + mix * cL
        out[1] = r * direct + mix * cR
    }

    fun reset() {
        lpL.reset(); lpR.reset(); hpL.reset(); hpR.reset()
        delayL.fill(0.0); delayR.fill(0.0)
        idxL = 0; idxR = 0
    }
}
