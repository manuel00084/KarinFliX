package com.karin.streamtv.player.dsp

/**
 * Wrapper JNI para libkarindsp.so - Convolución FIR por bloques con FFT (overlap-save) NEON.
 * Los métodos nativos se registran en JNI_OnLoad (karindsp_jni.c).
 */
class NativeDsp {

    companion object {
        @Volatile private var loaded = false

        fun load() {
            if (!loaded) {
                synchronized(this) {
                    if (!loaded) {
                        System.loadLibrary("karindsp")
                        loaded = true
                    }
                }
            }
        }

        fun isAvailable(): Boolean {
            try {
                load()
                return true
            } catch (e: UnsatisfiedLinkError) {
                return false
            }
        }
    }

    private var nativeHandle: Long = 0

    /**
     * Crea una nueva instancia de convolución.
     * @param blockSize Tamaño de bloque en muestras (debe ser potencia de 2, ej: 256, 512, 1024)
     */
    fun create(blockSize: Int): Boolean {
        load()
        nativeHandle = nativeCreate(blockSize)
        return nativeHandle != 0L
    }

    /** Asigna la respuesta al impulso (IR) para convolución. */
    fun setIr(ir: FloatArray) {
        require(nativeHandle != 0L) { "DSP no inicializado. Llama create() primero." }
        nativeSetIr(nativeHandle, ir)
    }

    /**
     * Procesa un bloque de audio.
     * @param window Buffer de entrada de tamaño fftSize (2 * blockSize)
     * @param out Buffer de salida de tamaño blockSize
     */
    fun render(window: FloatArray, out: FloatArray) {
        require(nativeHandle != 0L) { "DSP no inicializado. Llama create() primero." }
        require(window.size == out.size * 2) { "window debe ser 2x out.size (fftSize = 2 * blockSize)" }
        nativeRender(nativeHandle, window, out)
    }

    /** Resetea el estado interno (colas de overlap-save). */
    fun reset() {
        if (nativeHandle != 0L) {
            nativeReset(nativeHandle)
        }
    }

    /** Libera recursos nativos. */
    fun free() {
        if (nativeHandle != 0L) {
            nativeFree(nativeHandle)
            nativeHandle = 0
        }
    }

    // Métodos nativos - registrados en JNI_OnLoad (karindsp_jni.c)
    private external fun nativeCreate(blockSize: Int): Long
    private external fun nativeSetIr(handle: Long, ir: FloatArray)
    private external fun nativeRender(handle: Long, window: FloatArray, out: FloatArray)
    private external fun nativeReset(handle: Long)
    private external fun nativeFree(handle: Long)

    protected fun finalize() {
        free()
    }
}