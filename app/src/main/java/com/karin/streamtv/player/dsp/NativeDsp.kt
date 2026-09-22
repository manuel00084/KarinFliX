package com.karin.streamtv.player.dsp

/**
 * Puente JNI a la librería nativa karindsp (C).
 *
 * Los métodos nativos se registran en JNI_OnLoad vía RegisterNatives
 * (karindsp_jni.c), de modo que la clase y sus métodos deben conservar su
 * nombre: regla -keep en proguard-rules.pro.
 *
 * Si la librería no carga (tests en JVM, ABI sin soporte) [available] es false
 * y los llamadores usan el Convolver puro Kotlin como respaldo.
 */
object NativeDsp {

    val available: Boolean by lazy {
        try {
            System.loadLibrary("karindsp")
            true
        } catch (t: Throwable) {
            false
        }
    }

    private external fun nativeCreate(blockSize: Int): Long
    private external fun nativeSetIr(handle: Long, ir: FloatArray)
    private external fun nativeRender(handle: Long, window: FloatArray, out: FloatArray)
    private external fun nativeReset(handle: Long)
    private external fun nativeFree(handle: Long)

    fun create(blockSize: Int): Long = nativeCreate(blockSize)

    fun setIr(handle: Long, ir: FloatArray) {
        if (handle != 0L) nativeSetIr(handle, ir)
    }

    fun render(handle: Long, window: FloatArray, out: FloatArray) {
        if (handle != 0L) nativeRender(handle, window, out)
    }

    fun reset(handle: Long) {
        if (handle != 0L) nativeReset(handle)
    }

    fun free(handle: Long) {
        if (handle != 0L) nativeFree(handle)
    }
}