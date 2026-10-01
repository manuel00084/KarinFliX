package com.karin.streamtv.player.dsp.smartlite

import com.karin.streamtv.player.dsp.BiquadFilter

/**
 * Harmonic Enhancement (nombre técnico, sin marketing): añade armónicos
 * superiores muy sutiles vía waveshaper suave en la banda alta.
 * Preferencia: transparencia. Defaults mínimos; el usuario puede apagarlo.
 *
 * Anti-aliasing sin sobremuestreo: el generador es cúbico (x³), así que una
 * entrada a `f` produce contenido hasta `3f`. La entrada se limita con un
 * pasa-bajos de 4º orden a 5 kHz (o 0.12·fs si fs es bajo): el tercer
 * armónico queda por debajo de Nyquist con margen, y lo que pudiera quedar
 * por encima cae atenuado decenas de dB. Barato: 2 biquads extra por canal.
 */
class SmartLiteHarmonic {
    private val hpL = BiquadFilter()
    private val hpR = BiquadFilter()
    private val lpShapeL = BiquadFilter()
    private val lpShapeR = BiquadFilter()
    private val lpShape2L = BiquadFilter()
    private val lpShape2R = BiquadFilter()
    private var active = false
    private var amount = 0.1
    private var drive = 1.5
    private var drive2 = 2.25
    private var mix = 0.4

    fun configure(fs: Int, enabled: Boolean, freqHz: Float, amt: Float, drv: Float, mx: Float) {
        active = enabled && fs > 0
        amount = amt.coerceIn(0f, 1f).toDouble()
        drive = (1.0 + drv.coerceIn(0f, 1f) * 3.0) // 1..4
        drive2 = drive * drive
        mix = mx.coerceIn(0f, 1f).toDouble()
        // Corte del generador: 5 kHz, o 0.12·fs en frecuencias bajas, para que
        // 3·fc quede por debajo de Nyquist (fs/2) con margen. Un solo biquad de
        // 2º orden no basta: su falda deja pasar fundamentales cuyo tercer
        // armónico se pliega; en cascada (4º orden) la atenuación al cubo deja
        // el alias decenas de dB abajo.
        val shapeF = minOf(5000f, 0.12f * fs)
        // El pasa-altos no puede pedir banda por encima del limitador.
        val f = freqHz.coerceIn(500f, shapeF)
        hpL.configure(BiquadFilter.Kind.HIGHPASS, fs, f, 0f, 0.707f)
        hpR.configure(BiquadFilter.Kind.HIGHPASS, fs, f, 0f, 0.707f)
        lpShapeL.configure(BiquadFilter.Kind.LOWPASS, fs, shapeF, 0f, 0.707f)
        lpShapeR.configure(BiquadFilter.Kind.LOWPASS, fs, shapeF, 0f, 0.707f)
        lpShape2L.configure(BiquadFilter.Kind.LOWPASS, fs, shapeF, 0f, 0.707f)
        lpShape2R.configure(BiquadFilter.Kind.LOWPASS, fs, shapeF, 0f, 0.707f)
    }

    fun process(l: Double, r: Double, out: DoubleArray) {
        if (!active || amount <= 0.0) {
            out[0] = l; out[1] = r
            return
        }
        val bandL = lpShape2L.process(lpShapeL.process(l))
        val bandR = lpShape2R.process(lpShapeR.process(r))
        val hiL = hpL.process(bandL)
        val hiR = hpR.process(bandR)
        // 3.er armónico puro (k·x³) sobre la banda limitada.
        //
        // Antes era `tanh(x·drive)/drive − x`: como tanh tiende a 1/drive, en
        // cuanto |x| > 1/drive devolvía MENOS que la entrada, así que el término
        // "armónico" se volvía NEGATIVO y el módulo restaba agudos en lugar de
        // añadirlos. x³ es monótono y transparente para señales pequeñas, y
        // `drive` pasa a controlar la profundidad del 3.er (∝ drive²).
        // El techo solo acota el término AÑADIDO; la señal original nunca pasa
        // por un shaper, así que no se satura ni se genera ruido en la banda.
        val addL = (0.3 * drive2 * hiL * hiL * hiL).coerceIn(-1.0, 1.0)
        val addR = (0.3 * drive2 * hiR * hiR * hiR).coerceIn(-1.0, 1.0)
        val g = amount * mix
        out[0] = l + g * addL
        out[1] = r + g * addR
    }

    fun reset() {
        hpL.reset(); hpR.reset()
        lpShapeL.reset(); lpShapeR.reset()
        lpShape2L.reset(); lpShape2R.reset()
    }
}
