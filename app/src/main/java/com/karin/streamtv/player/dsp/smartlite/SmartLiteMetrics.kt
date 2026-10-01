package com.karin.streamtv.player.dsp.smartlite

/**
 * Snapshot de métricas del SmartLite Monitor (hilo de audio → UI).
 * Solo valores realmente medidos; NaN/−Inf cuando no hay señal aún.
 */
data class SmartLiteMetrics(
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
        var current = SmartLiteMetrics()
            internal set
    }
}
