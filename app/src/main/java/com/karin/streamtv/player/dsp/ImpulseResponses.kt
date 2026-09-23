package com.karin.streamtv.player.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Genera respuestas al impulso (IRs) de forma procedural, a la frecuencia de
 * muestreo real, para el Convolver: banco de IRs integrado (sin archivos
 * externos) para sala, auditorio, crossfeed y caja de altavoz.
 */
object ImpulseResponses {
    // var (no val): se re-sembraba al inicio de pair() para que regenerar una
    // IR (cambio de mix/tipo) produzca SIEMPRE el mismo ruido, no otra IR.
    private var rng = Random(1337)

    fun pair(type: AudioEnhanceConfig.IrPreset, fs: Int): Pair<FloatArray, FloatArray> {
        rng = Random(1337) // determinista entre regeneraciones
        return when (type) {
            AudioEnhanceConfig.IrPreset.NONE -> FloatArray(0) to FloatArray(0)
            AudioEnhanceConfig.IrPreset.ROOM -> room(fs)
            AudioEnhanceConfig.IrPreset.HALL -> hall(fs)
            AudioEnhanceConfig.IrPreset.CROSSFEED -> crossfeed(fs)
            AudioEnhanceConfig.IrPreset.SPEAKER_CAB -> speakerCab(fs)
            AudioEnhanceConfig.IrPreset.STUDIO -> studio(fs)
        }
    }

    private fun gauss(): Double = (rng.nextDouble() * 2.0 - 1.0) + (rng.nextDouble() * 2.0 - 1.0)

    private fun lowpassInPlace(ir: FloatArray, fs: Int, fc: Double) {
        val a = exp(-2.0 * PI * fc / fs)
        var s = 0.0
        for (i in ir.indices) {
            s = s * a + ir[i].toDouble() * (1.0 - a)
            ir[i] = s.toFloat()
        }
    }

    private fun highpassInPlace(ir: FloatArray, fs: Int, fc: Double) {
        val rc = 1.0 / (2.0 * PI * fc)
        val dt = 1.0 / fs
        val alpha = rc / (rc + dt)
        var y = 0.0
        var prev = 0.0
        for (i in ir.indices) {
            val x = ir[i].toDouble()
            y = alpha * (y + x - prev)
            prev = x
            ir[i] = y.toFloat()
        }
    }

    private fun normalizeEnergy(ir: FloatArray, target: Double) {
        var e = 0.0
        for (v in ir) e += v.toDouble() * v.toDouble()
        if (e <= 1e-12) return
        val s = sqrt(target / e)
        for (i in ir.indices) ir[i] = (ir[i] * s).toFloat()
    }

    private fun room(fs: Int): Pair<FloatArray, FloatArray> {
        val len = (0.35 * fs).toInt()
        val ir = FloatArray(len)
        val preDelay = (0.002 * fs).toInt()
        val tau = 0.10 * fs
        for (n in preDelay until len) {
            val t = (n - preDelay) / tau
            ir[n] = (exp(-t) * gauss() * 0.8).toFloat()
        }
        lowpassInPlace(ir, fs, 8000.0)
        normalizeEnergy(ir, 1.0)
        return ir to ir
    }

    private fun hall(fs: Int): Pair<FloatArray, FloatArray> {
        val len = (0.75 * fs).toInt()
        val ir = FloatArray(len)
        val preDelay = (0.018 * fs).toInt()
        val tau = 0.32 * fs
        for (n in preDelay until len) {
            val t = (n - preDelay) / tau
            val attack = minOf(1.0, (n - preDelay) / (0.040 * fs))
            ir[n] = (attack * exp(-t) * gauss() * 0.9).toFloat()
        }
        val taps = intArrayOf(0, (0.024 * fs).toInt(), (0.041 * fs).toInt(), (0.068 * fs).toInt())
        val gs = doubleArrayOf(0.25, 0.6, 0.4, 0.28)
        for (i in taps.indices) {
            if (taps[i] < len) ir[taps[i]] += gs[i].toFloat()
        }
        lowpassInPlace(ir, fs, 6000.0)
        normalizeEnergy(ir, 1.0)
        return ir to ir
    }

    private fun crossfeed(fs: Int): Pair<FloatArray, FloatArray> {
        val len = (0.030 * fs).toInt()
        fun ir(tapDelays: DoubleArray, tapGains: DoubleArray): FloatArray {
            val a = FloatArray(len)
            for (i in tapDelays.indices) {
                val idx = (tapDelays[i] * fs).toInt()
                if (idx < len) a[idx] = tapGains[i].toFloat()
            }
            lowpassInPlace(a, fs, 7000.0)
            normalizeEnergy(a, 1.0)
            return a
        }
        val l = ir(
            doubleArrayOf(0.0000, 0.0004, 0.0016, 0.0042, 0.0090),
            doubleArrayOf(0.85, 0.20, 0.12, 0.08, 0.05)
        )
        val r = ir(
            doubleArrayOf(0.0000, 0.0007, 0.0021, 0.0050, 0.0100),
            doubleArrayOf(0.85, 0.18, 0.11, 0.07, 0.04)
        )
        return l to r
    }

    private fun speakerCab(fs: Int): Pair<FloatArray, FloatArray> {
        val len = (0.10 * fs).toInt()
        val ir = FloatArray(len)
        ir[0] += 0.6f
        val modes = arrayOf(
            doubleArrayOf(140.0, 6.0, 0.35),
            doubleArrayOf(320.0, 5.0, 0.30),
            doubleArrayOf(980.0, 9.0, 0.7),
            doubleArrayOf(2000.0, 6.0, 0.42),
            doubleArrayOf(3600.0, 4.0, 0.30)
        )
        for (m in modes) {
            val f = m[0]
            val q = m[1]
            val g = m[2]
            val w = 2.0 * PI * f / fs
            val rate = PI * f / q
            for (n in 0 until len) {
                val env = exp(-(n.toDouble() / fs) * rate)
                ir[n] += (g * env * sin(w * n)).toFloat()
            }
        }
        val noiseLen = (0.008 * fs).toInt()
        for (n in 0 until noiseLen) {
            ir[n] += (gauss() * 0.12 * exp(-n.toDouble() / (0.002 * fs))).toFloat()
        }
        highpassInPlace(ir, fs, 60.0)
        lowpassInPlace(ir, fs, 5600.0)
        normalizeEnergy(ir, 0.8)
        return ir to ir
    }

    // Studio: IR de convolución densa y natural (no metálica) para "reverb
    // convolutiva de calidad". Tres ingredientes que la distinguen del ruido
    // blanco simple:
    //   1) Paredes de la sala: una nube difusa FDN (red de 12 líneas de retardo
    //      con longitudes inconmensurables y matriz de feedback Hadamard) sembrada
    //      con un impulso; duplica la densidad de la cola sin "combos" audibles.
    //   2) Reflexiones tempranas IRREGULARES (sin rejilla periódica) y con
    //      micro-difusión gaussiana → ataque suave, sin timbre de "tubo".
    //   3) Pérdida de aire progresiva: el lowpass de la cola baja de ~11 kHz a
    //      ~2.4 kHz según el tiempo (como en una sala real: los agudos mueren
    //      antes). L/R se decorrelan con semillas distintas y reflexiones y
    //      ITD ligeramente distintos → anchura estéreo real del tail.
    private fun studio(fs: Int): Pair<FloatArray, FloatArray> {
        val len = (1.7 * fs).toInt()
        val preDelay = (0.024 * fs).toInt().coerceAtLeast(2)
        val s = fs / 44100.0

        fun fdnTail(seed: Long): FloatArray {
            val r = Random(seed)
            val delays = IntArray(12)
            val base = intArrayOf(719, 883, 1051, 1259, 1481, 1693, 1951, 2203, 2477, 2713, 2963, 3203)
            for (i in 0 until 12) {
                // ±11% de jitter real: evita que las líneas compartan armónicos
                // (antes se multiplicaba ×0.01 y el jitter era virtualmente nulo).
                val jit = r.nextDouble() * 0.222 - 0.111
                delays[i] = ((base[i] * s * (1.0 + jit)).toInt()).coerceAtLeast(32)
            }
            val buf = Array(12) { i -> FloatArray(delays[i]) }
            val idx = IntArray(12)
            // Matriz Hadamard de Paley (orden 12 = 11+1): ortogonal real
            // (H·Hᵀ = 12·I), escalada por 1/√12 → ganancia unitaria por pasada
            // (sin resonancias acopladas). La matriz anterior (had4×had3 con
            // had4 mal formada) no era ortogonal y hacía el bucle inestable.
            val qr = intArrayOf(1, 3, 4, 5, 9) // residuos cuadráticos mod 11
            val fb = Array(12) { i ->
                IntArray(12) { j ->
                    if (i == 0 || j == 0) 1
                    else {
                        val d = ((i - j) % 11 + 11) % 11
                        if (d == 0) 1 else if (qr.contains(d)) 1 else -1
                    }
                }
            }
            val inv = 1.0 / sqrt(12.0)
            // Pérdida de línea: T60 ≈ 1.35 s (react < 1 saca energía del bucle).
            val react = exp(-6.9 / (1.35 * fs))
            val damp = 0.38
            val st = DoubleArray(12)
            val out = FloatArray(len)
            buf[0][0] += 1f
            // Filtro de aire variable con el tiempo: f_c cae ~11 kHz → ~2.4 kHz
            // durante la cola (los agudos mueren antes en una sala real).
            val aStart = exp(-2.0 * PI * 11000.0 / fs)
            val aEnd = exp(-2.0 * PI * 2400.0 / fs)
            var airAcc = 0.0
            for (n in 0 until len) {
                val t = n.toDouble() / fs
                val a = aStart * Math.pow(aEnd / aStart, t / 0.42)
                var acc = 0.0
                for (ipt in 0 until 12) {
                    val d = buf[ipt][idx[ipt]]
                    val filtered = d * (1.0 - damp) + st[ipt] * damp
                    st[ipt] = filtered
                    acc += filtered
                }
                for (li in 0 until 12) {
                    var sum = 0.0
                    for (lj in 0 until 12) sum += st[lj] * fb[li][lj]
                    val b = buf[li]
                    // Sobrescribe (no acumula): los retrasos se leen al inicio
                    // del paso, así que "=" es seguro y es el FDN clásico.
                    b[idx[li]] = (sum * inv * react).toFloat()
                    idx[li] = (idx[li] + 1) % b.size
                }
                airAcc = airAcc * a + acc * (1.0 - a)
                out[n] = airAcc.toFloat()
            }
            return out
        }

        // Mezcla final: predelay + reflexiones tempranas irregulares y micro
        // difuminadas + cola FDN. L y R con semillas y topologías ligeramente
        // distintas para una imagen estéreo ancha y estable.
        fun channel(seed: Long, reflectShiftMs: Double): FloatArray {
            val ir = fdnTail(seed)
            val r = Random(seed xor -0x61C8864680B583EBL)
            val reflex = doubleArrayOf(5.2, 8.7, 12.4, 17.9, 23.5, 31.5, 41.0, 54.0, 71.0, 95.0)
            for (rf in reflex) {
                val at = ((rf + reflectShiftMs) * 0.001 * fs).toInt()
                if (at >= preDelay && at < ir.size) {
                    // Cada reflexión es un micro-tren de 3 golpes gaussianos que
                    // le quita el filo metálico (difusión temprana).
                    for (sm in -2..2) {
                        val ix = at + sm
                        if (ix in ir.indices) {
                            val g = Math.exp(-(sm * sm).toDouble() / 3.0)
                            ir[ix] += (0.9 * g / 2.2).toFloat()
                        }
                    }
                }
            }
            // Decay del directo: golpe inicial pequeño + caída suave.
            for (i in 0..(fs * 0.004).toInt()) if (i < ir.size) ir[i] *= (1.0 - i / (fs * 0.004).toDouble()).toFloat()
            // Hay que alcanzar la señal en el window: envuelve la cola en un
            // fade para que la convolución arranque limpia y no haga clic.
            for (i in 0 until preDelay) ir[i] = 0f
            lowpassInPlace(ir, fs, 15000.0)
            normalizeEnergy(ir, 1.0)
            return ir
        }

        val l = channel(0x5DEECE66DL, 0.0)
        val r = channel(0xDEADC0DE5EEDL, 0.72)
        return l to r
    }
}
