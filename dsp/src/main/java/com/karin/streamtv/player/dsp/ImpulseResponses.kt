package com.karin.streamtv.player.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Genera respuestas al impulso (IRs) de forma procedural, a la frecuencia de
 * muestreo real, para el Convolver. Son el equivalente "integrado" de los IRS
 * de ViPER4Android/JamesDSP/Wavelet.
 */
object ImpulseResponses {
    private val rng = Random(1337)

    fun pair(type: AudioEnhanceConfig.IrPreset, fs: Int): Pair<FloatArray, FloatArray> = when (type) {
        AudioEnhanceConfig.IrPreset.NONE -> FloatArray(0) to FloatArray(0)
        AudioEnhanceConfig.IrPreset.ROOM -> room(fs)
        AudioEnhanceConfig.IrPreset.HALL -> hall(fs)
        AudioEnhanceConfig.IrPreset.CROSSFEED -> crossfeed(fs)
        AudioEnhanceConfig.IrPreset.SPEAKER_CAB -> speakerCab(fs)
        AudioEnhanceConfig.IrPreset.USER -> FloatArray(0) to FloatArray(0) // se carga aparte (WavIr)
    }

    /**
     * Remuestreo lineal: ajusta una IR a la frecuencia de muestreo del procesador
     * cuando el WAV de usuario no coincide con ella.
     */
    fun resample(src: FloatArray, srcFs: Int, dstFs: Int): FloatArray {
        if (srcFs <= 0 || dstFs <= 0 || srcFs == dstFs) return src
        val ratio = dstFs.toDouble() / srcFs
        val out = FloatArray((src.size.toDouble() * ratio).toInt().coerceAtLeast(1))
        val last = src.size - 1
        for (i in out.indices) {
            val pos = i / ratio
            var i0 = pos.toInt()
            if (i0 > last) i0 = last
            val i1 = if (i0 < last) i0 + 1 else last
            val frac = (pos - i0).coerceIn(0.0, 1.0)
            out[i] = (src[i0] * (1.0 - frac) + src[i1] * frac).toFloat()
        }
        return out
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
}
