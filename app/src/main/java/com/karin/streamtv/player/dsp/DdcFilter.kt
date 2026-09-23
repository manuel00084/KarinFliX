package com.karin.streamtv.player.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * DDC — Digital Domain Correction (DRC por medida del altavoz).
 *
 * Entra la respuesta al impulso medida del altavoz en la sala (micrófono
 * real, o una medida procedimental del driver). Aquí se calcula el filtro
 * inverso regularizado en el dominio FFT (inversión de Tikhonov con
 * regularización dependiente de frecuencia) contra una curva objetivo suave
 * (house curve) que no extiende debajo de ~28 Hz ni empuja agudos
 * >11 kHz. El filtro resultante se aplica con convolución particionada
 * (reusa [Convolver], la misma máquina que las IRs de sala).
 */
class DdcFilter {
    private val engine = Convolver()
    private var taps = 0

    val isReady: Boolean get() = taps > 0

    fun latencySamples(): Int = engine.latencySamples()

    /** [measurement] = IR medida del altavoz; [fs] = tasa; [strength] 0..1 = profundidad. */
    fun setMeasurement(measurement: FloatArray, fs: Int, strength: Float) {
        val corr = if (measurement.size >= 32) computeCorrection(measurement, fs, strength) else FloatArray(0)
        engine.setImpulseResponse(corr)
        taps = corr.size
    }

    fun clear() {
        engine.setImpulseResponse(FloatArray(0))
        taps = 0
    }

    fun reset() { engine.reset() }

    /** Salida corregida (wet). Mientras el pipeline llena, devuelve la entrada. */
    fun process(x: Double): Double = if (taps > 0) engine.process(x) else x

    companion object {
        private fun nextPow2(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }

        /**
         * Inversión regularizada de la medida:
         *   H(f) = conj(M(f)) * T(f) / (|M(f)|² + λ(f))
         * con λ(f) = lam * (1 + (f/0.45·fs)²) · meanPower → el boost queda atado en
         * huecos y no explota en notchs profundos; T(f) es la curva objetivo
         * (plana 35 Hz…8 kHz, rodillas suaves abajo/arriba). El filtro se recorta a
         * ~5,3 ms de FIR y se normaliza el ganancia DC = 1.
         */
        fun computeCorrection(measurement: FloatArray, fs: Int, strength: Float): FloatArray {
            val n = nextPow2(maxOf(measurement.size, 4096))
            val re = FloatArray(n)
            val im = FloatArray(n)
            for (i in 0 until minOf(measurement.size, n)) re[i] = measurement[i]
            fft(re, im, forward = true)

            val meanP = DoubleArray(n)
            var mean = 0.0
            for (i in 0 until n) {
                val m2 = re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
                meanP[i] = m2
                mean += m2
            }
            mean /= n
            if (mean <= 1e-15) return FloatArray(0)

            val s = strength.toDouble().coerceIn(0.0, 1.0)
            val lam = 0.02 * mean
            val depth = 6.0 + 9.0 * s // tope de boost (dB)
            for (i in 0 until n) {
                val f = i * fs.toDouble() / n
                // Curva objetivo: plana en el núcleo, sin pelear por sub-graves
                // inexistentes ni por agudos que nadie escucha.
                val hp = 1.0 / (1.0 + (28.0 / (f + 1e-9)) * (28.0 / (f + 1e-9)))
                val lp = 1.0 / (1.0 + (f / 11000.0) * (f / 11000.0) * (f / 11000.0))
                val t = hp * lp
                val reg = lam * (1.0 + (f / (0.45 * fs)) * (f / (0.45 * fs)))
                val m2 = meanP[i]
                val denom = m2 + reg
                if (denom <= 1e-12) {
                    re[i] = 0f
                    im[i] = 0f
                    continue
                }
                // H = conj(M) * T / denom
                var hr = re[i].toDouble() / denom * t
                var hi = -im[i].toDouble() / denom * t
                // Tope de ganancia para no "ascender" huecos profundos.
                var gain = kotlin.math.sqrt(hr * hr + hi * hi)
                val maxGain = 10.0.pow(depth / 20.0)
                if (gain > maxGain) {
                    val k = maxGain / gain
                    hr *= k
                    hi *= k
                    gain = maxGain
                }
                re[i] = hr.toFloat()
                im[i] = hi.toFloat()
            }

            fft(re, im, forward = false)

            // Recorte a FIR corta (~5,3 ms) con ventana coseno para suavizar en
            // tiempo (control del pre/post-ringing de la inversión).
            val firLen = ((0.0053 * fs).toInt()).coerceIn(64, n / 4)
            val h = FloatArray(firLen)
            val fade = firLen / 5
            var peak = 0.0
            for (i in 0 until firLen) {
                var v = re[i].toDouble()
                if (i >= firLen - fade) {
                    val k = (firLen - i).toDouble() / fade
                    v *= k * k
                }
                if (i == 0) v *= 0.5 // reduce el borde para evitar clic directo
                h[i] = v.toFloat()
                peak = maxOf(peak, abs(v))
            }
            var sum = 0.0
            for (v in h) sum += v
            if (sum == 0.0 || peak <= 1e-12) return FloatArray(0)

            // Profundidad: escala solo la parte correctiva (fuera del delta directo)
            // para que strength=0 deje la señal intacta y 1 aplique la corrección.
            for (i in 1 until firLen) h[i] = (h[i] * s).toFloat()
            // Re-normalizar DC = 1.
            sum = 0.0
            for (v in h) sum += v
            if (sum == 0.0) return FloatArray(0)
            val k = 1.0 / sum
            for (i in h.indices) h[i] = (h[i] * k).toFloat()
            return h
        }

        private fun fft(re: FloatArray, im: FloatArray, forward: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while ((j and bit) != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val ang = (if (forward) -1.0 else 1.0) * 2.0 * PI / len
                val wlenR = cos(ang).toFloat()
                val wlenI = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var wR = 1.0f
                    var wI = 0.0f
                    val half = len / 2
                    for (k in 0 until half) {
                        val ur = re[i + k]
                        val ui = im[i + k]
                        val vr = re[i + k + half] * wR - im[i + k + half] * wI
                        val vi = re[i + k + half] * wI + im[i + k + half] * wR
                        re[i + k] = ur + vr
                        im[i + k] = ui + vi
                        re[i + k + half] = ur - vr
                        im[i + k + half] = ui - vi
                        val twR = wR * wlenR - wI * wlenI
                        wI = wR * wlenI + wI * wlenR
                        wR = twR
                    }
                    i += len
                }
                len = len shl 1
            }
            if (!forward) {
                val inv = 1.0f / n
                for (i in 0 until n) {
                    re[i] *= inv
                    im[i] *= inv
                }
            }
        }
    }
}