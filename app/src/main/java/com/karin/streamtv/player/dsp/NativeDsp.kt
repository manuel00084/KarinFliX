package com.karin.streamtv.player.dsp

import android.util.Log

/**
 * Puente al DSP nativo (librería `karindsp`, FFT/convolución en C con NEON).
 *
 * Se carga de forma segura: si el dispositivo no tiene la ABI correcta o la
 * carga falla, [isAvailable] es `false` y [Convolver] usa su implementación
 * Kotlin como respaldo. Nunca debe tumbarse el hilo de audio por JNI.
 */
object NativeDsp {

    private const val TAG = "NativeDsp"

    @Volatile
    private var tried = false

    @Volatile
    private var loaded = false

    val isAvailable: Boolean
        get() {
            if (!tried) {
                tried = true
                loaded = try {
                    System.loadLibrary("karindsp")
                    Log.i(TAG, "karindsp cargada (FFT NEON activo)")
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "karindsp no disponible, usando DSP Kotlin: ${t.message}")
                    false
                }
            }
            return loaded
        }

    external fun create(blockSize: Int): Long
    external fun setIr(handle: Long, ir: FloatArray)
    external fun render(handle: Long, window: FloatArray, out: FloatArray)
    external fun reset(handle: Long)
    external fun free(handle: Long)
}
