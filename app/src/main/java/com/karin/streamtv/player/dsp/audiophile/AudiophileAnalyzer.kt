package com.karin.streamtv.player.dsp.audiophile

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Analizador ligero de monitoreo (solo medición, NO modifica el audio):
 * L/R level, peak, true-peak (aprox desde output), correlación estéreo,
 * y contenedor de métricas del pipeline. CPU ~ trivial.
 */
class AudiophileAnalyzer {

    data class Metrics(
        val inputPeakDb: Float,
        val outputPeakDb: Float,
        val truePeakDbtp: Float,
        val lufs: Float,
        val dynamicRangeDb: Float,
        val correlation: Float,
        val cpuPercent: Float
    ) {
        companion object {
            val EMPTY = Metrics(
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.NaN, 0f, 0f
            )
        }
    }

    private var peakIn = 0.0
    private var sumLR = 0.0
    private var sumLL = 0.0
    private var sumRR = 0.0
    private var corrN = 0
    private var corrWindow = 2048
    @Volatile var correlation = 0f
        private set
    @Volatile var inputPeak = 0.0
        private set

    // CPU: media móvil del tiempo de proceso por bloque.
    private var cpuEma = 0.0

    fun configure(fs: Int) {
        corrWindow = (fs / 20).coerceIn(512, 8192) // ~50 ms
        peakIn = 0.0; sumLR = 0.0; sumLL = 0.0; sumRR = 0.0; corrN = 0
        correlation = 0f; inputPeak = 0.0; cpuEma = 0.0
    }

    fun tapInput(l: Double, r: Double) {
        val a = abs(l)
        if (a > peakIn) peakIn = a else peakIn *= 0.9999
        if (a > inputPeak) inputPeak = a else inputPeak *= 0.9999
        sumLR += l * r
        sumLL += l * l
        sumRR += r * r
        corrN++
        if (corrN >= corrWindow) {
            val denom = sqrt(sumLL * sumRR)
            correlation = if (denom > 1e-12) (sumLR / denom).coerceIn(-1.0, 1.0).toFloat() else 0f
            sumLR = 0.0; sumLL = 0.0; sumRR = 0.0; corrN = 0
        }
    }

    fun inputPeakDb(): Float =
        if (peakIn > 1e-9) (20.0 * log10(peakIn)).toFloat() else Float.NEGATIVE_INFINITY

    /** % de CPU estimado = (ns de proceso / ns del bloque real) × 100. */
    fun reportBlockTime(nsProcess: Long, frames: Int, fs: Int) {
        if (frames <= 0 || fs <= 0) return
        val blockNs = frames * 1_000_000_000.0 / fs
        val pct = (nsProcess / blockNs) * 100.0
        cpuEma = if (cpuEma == 0.0) pct else cpuEma * 0.9 + pct * 0.1
    }

    fun cpuPercent(): Float = cpuEma.coerceIn(0.0, 100.0).toFloat()

    fun metrics(
        output: AudiophileOutput,
        loudness: AudiophileLoudness,
        dynamics: AudiophileDynamics
    ): Metrics {
        val outPk = if (output.outputPeak > 1e-9)
            (20.0 * log10(output.outputPeak)).toFloat() else Float.NEGATIVE_INFINITY
        val tp = if (output.truePeakEst > 1e-9)
            (20.0 * log10(output.truePeakEst)).toFloat() else Float.NEGATIVE_INFINITY
        return Metrics(
            inputPeakDb = inputPeakDb(),
            outputPeakDb = outPk,
            truePeakDbtp = tp,
            lufs = loudness.shortTermLufs,
            dynamicRangeDb = dynamics.dynamicRangeDb,
            correlation = correlation,
            cpuPercent = cpuPercent()
        )
    }

    fun reset() {
        peakIn = 0.0; sumLR = 0.0; sumLL = 0.0; sumRR = 0.0; corrN = 0
        correlation = 0f; inputPeak = 0.0; cpuEma = 0.0
    }
}
