package com.karin.streamtv.player.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Valida la ruta nativa del DSP (libkarindsp) contra una convolución directa de
 * referencia. Se ejecuta en dispositivo/emulador ARM (el .so solo se empaqueta
 * para arm64-v8a y armeabi-v7a); en x86 [NativeDsp.isAvailable] es false y el
 * test se salta (ahí aplica el fallback Kotlin).
 */
class NativeDspConvolutionTest {

    private val blockSize = 512

    @Before
    fun nativeMustBeAvailable() {
        assumeTrue("libkarindsp no disponible en esta ABI", NativeDsp.isAvailable)
    }

    /** LCG determinista para generar señal y IR reproducibles. */
    private fun lcg(seed: Long, n: Int, scale: Float): FloatArray {
        var s = seed
        val out = FloatArray(n)
        for (i in 0 until n) {
            s = s * 6364136223846793005L + 1442695040888963407L
            out[i] = (s ushr 33).toInt().toFloat() / Int.MAX_VALUE.toFloat() * scale
        }
        return out
    }

    /** y[n] = (x * ir)[n], convolución directa en doble precisión (referencia). */
    private fun directConvolve(x: FloatArray, ir: FloatArray): DoubleArray {
        val n = x.size
        val m = ir.size
        val y = DoubleArray(n + m - 1)
        for (k in 0 until m) {
            val h = ir[k].toDouble()
            if (h == 0.0) continue
            for (i in 0 until n) {
                y[i + k] += x[i].toDouble() * h
            }
        }
        return y
    }

    @Test
    fun impulseIrReplicaEntradaConLatencia() {
        val conv = Convolver()
        conv.setImpulseResponse(floatArrayOf(1f))
        val n = 2048
        val x = lcg(1, n, 0.5f)
        val out = DoubleArray(n)
        for (i in 0 until n) out[i] = conv.process(x[i].toDouble())
        for (i in 0 until blockSize - 1) {
            assertEquals("antes del primer render debe ser 0", 0.0, out[i], 0.0)
        }
        for (i in blockSize - 1 until n) {
            val expected = x[i - (blockSize - 1)].toDouble()
            assertEquals("salida en $i", expected, out[i], 1e-4)
        }
    }

    @Test
    fun convolutionCoincideConReferenciaDirecta() {
        val ir = FloatArray(900) { ((it % 41) - 20) * 0.002f }
        val conv = Convolver()
        conv.setImpulseResponse(ir)
        val n = 8192
        val x = lcg(42, n, 0.8f)
        val y = directConvolve(x, ir)
        val out = DoubleArray(n)
        for (i in 0 until n) out[i] = conv.process(x[i].toDouble())
        val last = n - ir.size + blockSize
        var maxErr = 0.0
        for (i in blockSize - 1 until last) {
            val err = abs(out[i] - y[i - (blockSize - 1)])
            if (err > maxErr) maxErr = err
        }
        assertTrue("error max=$maxErr (esperado <1e-3)", maxErr < 1e-3)
    }

    @Test
    fun resetLimpiaElRing() {
        val ir = lcg(7, 600, 0.3f)
        val conv = Convolver()
        conv.setImpulseResponse(ir)
        for (i in 0 until blockSize * 3) conv.process(0.5)
        conv.reset()
        var peak = 0.0
        for (i in 0 until blockSize * 4) {
            peak = max(peak, abs(conv.process(0.0)))
        }
        assertTrue("tras reset y silencio la salida debe ser 0, peak=$peak", peak < 1e-4)
    }

    @Test
    fun irVacioEsPassthrough() {
        val conv = Convolver()
        conv.setImpulseResponse(FloatArray(0))
        val n = 1024
        val x = lcg(99, n, 0.7f)
        for (i in 0 until n) {
            val v = x[i].toDouble()
            assertEquals("passthrough en $i", v, conv.process(v), 1e-9)
        }
    }
}
