package com.karin.streamtv.player.dsp.audiophile

import com.karin.streamtv.player.dsp.BiquadFilter

/**
 * Speaker Voicing — tonalidad para bocinas pequeñas (TV/parlantes sencillos).
 *
 * Reproduce, con 4 biquads por canal (sin convolución ni reverb), lo que hace
 * que un sistema dedicado se perciba "grande" desde un televisor económico:
 *   • Bass: low-shelf ~110 Hz (cuerpo / peso percibido).
 *   • Body: peaking ~350 Hz (calidez, evita "caja pequeña").
 *   • Presence: peaking ~2.7 kHz (claridad de diálogo y detalle).
 *   • Smooth: peaking ~8.5 kHz negativo (suaviza resonancias duras del driver).
 *
 * TRANSPARENCIA por defecto (OFF). Es una etapa puramente tonal y opcional,
 * controlada por niveles LIGHT/MEDIUM/STRONG. Combinada con los módulos
 * existentes (BassExtension para sub-armónicos, Harmonic para brillo) en el
 * preset TV Speaker logra el efecto "sistema de sonido dedicado" sin colorear
 * en los presets de referencia.
 */
class AudiophileSpeaker {
    private val bassL = BiquadFilter()
    private val bassR = BiquadFilter()
    private val bodyL = BiquadFilter()
    private val bodyR = BiquadFilter()
    private val presL = BiquadFilter()
    private val presR = BiquadFilter()
    private val smthL = BiquadFilter()
    private val smthR = BiquadFilter()
    private var active = false

    fun configure(fs: Int, mode: AudiophileConfig.SpeakerMode) {
        active = mode != AudiophileConfig.SpeakerMode.OFF && fs > 0
        if (!active) return
        val c = mode.curve()
        bassL.configure(BiquadFilter.Kind.LOWSHELF, fs, 110f, c.bassDb, 0.707f)
        bassR.configure(BiquadFilter.Kind.LOWSHELF, fs, 110f, c.bassDb, 0.707f)
        bodyL.configure(BiquadFilter.Kind.PEAKING, fs, 350f, c.bodyDb, 0.9f)
        bodyR.configure(BiquadFilter.Kind.PEAKING, fs, 350f, c.bodyDb, 0.9f)
        presL.configure(BiquadFilter.Kind.PEAKING, fs, 2700f, c.presenceDb, 1.1f)
        presR.configure(BiquadFilter.Kind.PEAKING, fs, 2700f, c.presenceDb, 1.1f)
        smthL.configure(BiquadFilter.Kind.PEAKING, fs, 8500f, c.smoothDb, 1.2f)
        smthR.configure(BiquadFilter.Kind.PEAKING, fs, 8500f, c.smoothDb, 1.2f)
    }

    fun process(l: Double, r: Double, out: DoubleArray) {
        if (!active) {
            out[0] = l; out[1] = r
            return
        }
        var a = bassL.process(l)
        a = bodyL.process(a)
        a = presL.process(a)
        a = smthL.process(a)
        var b = bassR.process(r)
        b = bodyR.process(b)
        b = presR.process(b)
        b = smthR.process(b)
        out[0] = a
        out[1] = b
    }

    fun reset() {
        bassL.reset(); bassR.reset()
        bodyL.reset(); bodyR.reset()
        presL.reset(); presR.reset()
        smthL.reset(); smthR.reset()
    }
}