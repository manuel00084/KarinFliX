package com.karin.streamtv.player.dsp.audiophile

/**
 * Snapshot de métricas del Audiophile Monitor (hilo de audio → UI).
 * Solo valores realmente medidos; NaN/−Inf cuando no hay señal aún.
 */
data class AudiophileMetrics(
    val inputPeakDb: Float = Float.NEGATIVE_INFINITY,
    val outputPeakDb: Float = Float.NEGATIVE_INFINITY,
    val truePeakDbtp: Float = Float.NEGATIVE_INFINITY,
    val lufs: Float = Float.NEGATIVE_INFINITY,
    val dynamicRangeDb: Float = Float.NaN,
    val correlation: Float = 0f,
    val cpuPercent: Float = 0f
) {
    companion object {
        @Volatile
        var current = AudiophileMetrics()
            internal set
    }
}
