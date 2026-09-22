package com.karin.streamtv.player.dsp

/**
 * Convolver que delega el FFT al binario nativo (karindsp / [NativeDsp]).
 *
 * Misma API pública que [Convolver] y mismo algoritmo por bloques
 * (overlap-save particionado, latencia = blockSize): internamente acumula la
 * entrada, llama a nativeRender cada [blockSize] muestras y expone el bloque
 * de salida muestra a muestra.
 *
 * Si la librería nativa no carga (tests JVM, ABI sin soporte) delega en el
 * [Convolver] puro Kotlin, que produce exactamente la misma salida.
 */
class NativeConvolver {

    private val fallback = Convolver()

    private var handle = 0L
    private var nativeOk = false

    private val blockSize = 512
    private val fftSize = blockSize * 2

    private var hasIr = false

    private val pending = FloatArray(blockSize)
    private var pendingCount = 0
    private val window = FloatArray(fftSize)

    private val outBuf = FloatArray(blockSize)
    private var outLen = 0
    private var outPos = 0

    init {
        if (NativeDsp.available) {
            val h = NativeDsp.create(blockSize)
            if (h != 0L) {
                handle = h
                nativeOk = true
            }
        }
    }

    fun setImpulseResponse(ir: FloatArray) {
        hasIr = ir.isNotEmpty()
        if (nativeOk) {
            NativeDsp.setIr(handle, ir)
            reset()
            return
        }
        fallback.setImpulseResponse(ir)
    }

    fun reset() {
        pendingCount = 0
        pending.fill(0f)
        window.fill(0f)
        outLen = 0
        outPos = 0
        if (nativeOk) NativeDsp.reset(handle) else fallback.reset()
    }

    fun process(x: Double): Double {
        if (!hasIr) return x
        if (nativeOk) {
            pending[pendingCount++] = x.toFloat()
            if (pendingCount >= blockSize) renderBlock()
            return if (outPos < outLen) outBuf[outPos++].toDouble() else 0.0
        }
        return fallback.process(x)
    }

    // Latencia de la convolución por bloques (en muestras): el wet sale con
    // este retraso respecto a la entrada (el camino seco lo alinea el caller).
    fun latencySamples(): Int = blockSize

    private fun renderBlock() {
        System.arraycopy(window, blockSize, window, 0, blockSize)
        System.arraycopy(pending, 0, window, blockSize, blockSize)
        pendingCount = 0
        NativeDsp.render(handle, window, outBuf)
        outLen = blockSize
        outPos = 0
    }

    // Red muerta: libera la memoria nativa si el procesador se descarta sin
    // pasar por un reset limpio (fallback por si algún día cambia el ciclo de
    // vida del AudioProcessor).
    protected fun finalize() {
        if (nativeOk) {
            NativeDsp.free(handle)
            nativeOk = false
            handle = 0L
        }
    }
}