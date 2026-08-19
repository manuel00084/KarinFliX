package com.karin.streamtv.player.dsp

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Benchmark: ruta nativa (libkarindsp) vs fallback Kotlin (FFT puro) con el
 * mismo IR e input. Mide µs por bloque de [NativeDspConvolutionTest.blockSize]
 * y el speedup real. Solo significativo en ABI donde el .so esté empaquetado.
 */
class NativeDspBenchmarkTest {

    private val blockSize = 512

    @Before
    fun nativeMustBeAvailable() {
        assumeTrue("libkarindsp no disponible en esta ABI", NativeDsp.isAvailable)
    }

    private fun lcg(seed: Long, n: Int, scale: Float): FloatArray {
        var s = seed
        val out = FloatArray(n)
        for (i in 0 until n) {
            s = s * 6364136223846793005L + 1442695040888963407L
            out[i] = (s ushr 33).toInt().toFloat() / Int.MAX_VALUE.toFloat() * scale
        }
        return out
    }

    private fun run(conv: Convolver, x: FloatArray): Double {
        var acc = 0.0
        for (i in x.indices) acc += conv.process(x[i].toDouble())
        return acc
    }

    /** Warmup extenso + mínimo de [rounds] pasadas de [n] bloques (evita JIT/GC). */
    private fun bestMsPerBlock(conv: Convolver, x: FloatArray, blocks: Int, rounds: Int): Double {
        repeat(5) { run(conv, x) } // warmup JIT/estado estable
        var best = Double.MAX_VALUE
        repeat(rounds) {
            val t0 = System.nanoTime()
            run(conv, x)
            val dt = (System.nanoTime() - t0) / 1e6
            if (dt < best) best = dt
        }
        return best * 1000.0 / blocks // µs por bloque
    }

    @Test
    fun benchmarkNativeVsKotlin() {
        val irs = linkedMapOf(
            "IR 512  (1 particion)" to lcg(1, 512, 0.3f),
            "IR 2048 (4 particiones)" to lcg(2, 2048, 0.3f),
            "IR 8192 (16 particiones)" to lcg(3, 8192, 0.3f)
        )
        val blocks = 400
        val n = blocks * blockSize
        val x = lcg(4, n, 0.5f)

        for ((name, ir) in irs) {
            val native = Convolver()
            native.setImpulseResponse(ir)
            val kotlin = Convolver(forceKotlin = true)
            kotlin.setImpulseResponse(ir)

            // Sanity: ambos caminos producen la misma salida (con la latencia del bloque)
            var maxDiff = 0.0
            var firstDiff = -1
            var aAtFirst = 0.0
            var bAtFirst = 0.0
            var maxAt = -1
            for (i in 0 until n) {
                val a = native.process(x[i].toDouble())
                val b = kotlin.process(x[i].toDouble())
                val d = abs(a - b)
                if (d > maxDiff) { maxDiff = d; maxAt = i }
                if (d > 1e-3 && firstDiff < 0) {
                    firstDiff = i
                    aAtFirst = a
                    bAtFirst = b
                }
            }
            Log.i(TAG, "SANITY $name maxDiff=$maxDiff at=$maxAt firstDiff=$firstDiff a=$aAtFirst b=$bAtFirst")
            assertEquals("salidas nativa vs Kotlin deben coincidir ($name)", 0.0, maxDiff, 1e-3)

            val tNative = bestMsPerBlock(native, x, blocks, 8)
            val tKotlin = bestMsPerBlock(kotlin, x, blocks, 8)
            val speedup = tKotlin / tNative
            Log.i(TAG, "%-20s nativo=%8.2f us/bloque  kotlin=%8.2f us/bloque  speedup=%5.2fx"
                .format(name, tNative, tKotlin, speedup))
        }
    }

    companion object {
        private const val TAG = "KARINDSP_BENCH"
    }
}
