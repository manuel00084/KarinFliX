package com.karin.streamtv.player.dsp

// Dither + noise-shaping para la cuantización 24/32-bit en writeSample
// (único superviviente del DSP antiguo: el resto fue eliminado con él).

// F-weighted 4th-order noise shaper (Lipshitz/Vanderkooy/Waugh): empuja el
// error de cuantización a las bandas de menor sensibilidad. Se combina con un
// dither TPDF de ±0.5 LSB para 24/32-bit (implícitamente ya disfrazado por el
// shaping en las frecuencias altas donde la audición es más tolerante).
class NoiseShaper {
    private var e1 = 0.0
    private var e2 = 0.0
    private var e3 = 0.0
    private var e4 = 0.0

    /** Devuelve la muestra "moldeada" (en unidades LSB) lista para cuantizar. */
    fun shaped(xLsb: Double): Double =
        xLsb - 2.371 * e1 + 1.725 * e2 - 0.574 * e3 + 0.212 * e4

    /** Introduce el error de cuantización del último paso (en unidades LSB). */
    fun pushError(shaped: Double, quantized: Double) {
        e4 = e3
        e3 = e2
        e2 = e1
        e1 = shaped - quantized
    }

    fun reset() {
        e1 = 0.0
        e2 = 0.0
        e3 = 0.0
        e4 = 0.0
    }
}

