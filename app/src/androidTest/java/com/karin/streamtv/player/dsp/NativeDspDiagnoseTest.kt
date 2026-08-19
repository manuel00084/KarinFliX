package com.karin.streamtv.player.dsp

import android.util.Log
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/** Diagnóstico temporal: compara cada camino (nativo y Kotlin) contra la referencia directa. */
class NativeDspDiagnoseTest {

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

    private fun collect(conv: Convolver, x: FloatArray): FloatArray {
        val out = FloatArray(x.size)
        for (i in x.indices) out[i] = conv.process(x[i].toDouble()).toFloat()
        return out
    }

    private fun refAt(ir: FloatArray, x: FloatArray, i: Int): Double {
        var acc = 0.0
        val max = min(ir.size, i + 1)
        for (k in 0 until max) acc += ir[k].toDouble() * x[i - k].toDouble()
        return acc
    }

    private fun maxErrVsRef(ir: FloatArray, x: FloatArray, got: FloatArray, label: String) {
        var max = 0.0
        var at = -1
        for (i in blockSize - 1 until x.size) {
            val r = refAt(ir, x, i - (blockSize - 1))
            val d = abs(got[i].toDouble() - r)
            if (d > max) { max = d; at = i }
        }
        Log.i(TAG, "$label maxErrVsRef=$max at=$at")
    }

    @Test
    fun diagnose() {
        val ir = lcg(1, 512, 0.3f)
        val blocks = 400
        val n = blocks * blockSize
        val x = lcg(4, n, 0.5f)

        val native = Convolver(); native.setImpulseResponse(ir)
        val kotlin = Convolver(forceKotlin = true); kotlin.setImpulseResponse(ir)

        var firstDiff = -1
        var diffs = 0
        var nativeWrong = 0
        var kotlinWrong = 0
        var maxD = 0.0
        var at = -1
        for (i in 0 until n) {
            val a = native.process(x[i].toDouble())
            val b = kotlin.process(x[i].toDouble())
            if (a != b) {
                if (firstDiff < 0) firstDiff = i
                diffs++
                if (i >= blockSize - 1) {
                    val r = refAt(ir, x, i - (blockSize - 1))
                    val da = abs(a - r)
                    val db = abs(b - r)
                    if (da > db) kotlinWrong++ else if (db > da) nativeWrong++
                }
            }
            val d = abs(a - b)
            if (d > maxD) { maxD = d; at = i }
        }
        Log.i(TAG, "INTERLEAVED maxDiff=$maxD at=$at firstDiff=$firstDiff diffs=$diffs " +
            "nativeWrong=$nativeWrong kotlinWrong=$kotlinWrong")
    }

    companion object {
        private const val TAG = "KARINDSP_DIAG"
    }
}
