package com.karin.streamtv.player.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Convolución FIR particionada (uniform partitioned overlap-save).
 *
 * Procesa por bloques de [blockSize] muestras usando FFT compleja de tamaño
 * [fftSize] = 2*[blockSize]. El IR se divide en particiones de [blockSize]
 * muestras; cada partición se FFT'ea una sola vez al cargarla. Por bloque de
 * entrada se hace 1 FFT, la acumulación espectral (partición * historial de
 * espectros) y 1 IFFT.
 *
 * Expone una interfaz muestra a muestra ([process]) para integrarse fácil en el
 * bucle del AudioProcessor: internamente acumula entradas y rinde un bloque de
 * salida cada [blockSize] muestras (latencia de un bloque de FFT).
 */
class Convolver {
    private var blockSize = 512
    private var fftSize = 1024

    private var numPartitions = 0
    private var irRe = emptyArray<FloatArray>()
    private var irIm = emptyArray<FloatArray>()
    private var ringRe = emptyArray<FloatArray>()
    private var ringIm = emptyArray<FloatArray>()
    private var writePos = 0

    private var window = FloatArray(fftSize)
    private var pending = FloatArray(blockSize)
    private var pendingCount = 0

    private var outBuf = FloatArray(blockSize)
    private var outLen = 0
    private var outPos = 0

    private var fr = FloatArray(fftSize)
    private var fi = FloatArray(fftSize)
    private var tr = FloatArray(fftSize)
    private var ti = FloatArray(fftSize)

    fun setImpulseResponse(ir: FloatArray) {
        numPartitions = 0
        irRe = emptyArray()
        irIm = emptyArray()
        ringRe = emptyArray()
        ringIm = emptyArray()
        if (ir.isNotEmpty()) {
            numPartitions = (ir.size + blockSize - 1) / blockSize
            irRe = Array(numPartitions) { FloatArray(fftSize) }
            irIm = Array(numPartitions) { FloatArray(fftSize) }
            val pad = FloatArray(fftSize)
            for (k in 0 until numPartitions) {
                pad.fill(0f)
                val off = k * blockSize
                val n = min(blockSize, ir.size - off)
                for (i in 0 until n) pad[i] = ir[off + i]
                System.arraycopy(pad, 0, irRe[k], 0, fftSize)
                irIm[k].fill(0f)
                fft(irRe[k], irIm[k], forward = true)
            }
            ringRe = Array(numPartitions + 1) { FloatArray(fftSize) }
            ringIm = Array(numPartitions + 1) { FloatArray(fftSize) }
        }
        reset()
    }

    fun reset() {
        writePos = 0
        pendingCount = 0
        outLen = 0
        outPos = 0
        window.fill(0f)
        pending.fill(0f)
        outBuf.fill(0f)
        for (a in ringRe) a.fill(0f)
        for (a in ringIm) a.fill(0f)
    }

    fun process(x: Double): Double {
        if (numPartitions == 0) return x
        pending[pendingCount++] = x.toFloat()
        if (pendingCount >= blockSize) renderBlock()
        return if (outPos < outLen) outBuf[outPos++].toDouble() else 0.0
    }

    // Latencia de la convolución por bloques (en muestras): el wet sale con este
    // retraso respecto a la entrada. El camino seco debe alinearse con este valor.
    fun latencySamples(): Int = blockSize

    private fun renderBlock() {
        System.arraycopy(window, blockSize, window, 0, blockSize)
        System.arraycopy(pending, 0, window, blockSize, blockSize)
        pendingCount = 0

        System.arraycopy(window, 0, fr, 0, fftSize)
        fi.fill(0f)
        fft(fr, fi, forward = true)
        System.arraycopy(fr, 0, ringRe[writePos], 0, fftSize)
        System.arraycopy(fi, 0, ringIm[writePos], 0, fftSize)

        tr.fill(0f)
        ti.fill(0f)
        val ringSize = ringRe.size
        var idx = writePos
        for (k in 0 until numPartitions) {
            val xr = ringRe[idx]
            val xi = ringIm[idx]
            val hr = irRe[k]
            val hi = irIm[k]
            for (b in 0 until fftSize) {
                val rr = xr[b] * hr[b] - xi[b] * hi[b]
                val ii = xr[b] * hi[b] + xi[b] * hr[b]
                tr[b] += rr
                ti[b] += ii
            }
            idx--
            if (idx < 0) idx = ringSize - 1
        }
        writePos++
        if (writePos >= ringSize) writePos = 0

        fft(tr, ti, forward = false)
        System.arraycopy(tr, blockSize, outBuf, 0, blockSize)
        outLen = blockSize
        outPos = 0
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
