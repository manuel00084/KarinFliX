package com.karin.streamtv.player.dsp

import com.karin.streamtv.player.dsp.audiophile.AudiophileConfig
import com.karin.streamtv.player.dsp.audiophile.KarinAudiophileDSP
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Micro-benchmark del pipeline Karin Audiophile vs un bucle de referencia.
 * Mide ns/frame a 44.1 kHz y 48 kHz con bloques estéreo realistas.
 * Imprime resultados (no falla salvo error real). NO afirma "low CPU"
 * sin revisar la salida en el dispositivo objetivo.
 */
class AudiophileBenchmarkTest {

    private fun runPipeline(fs: Int, preset: AudiophileConfig.ApPreset, frames: Int): Double {
        val dsp = KarinAudiophileDSP()
        dsp.configure(fs, 2, AudiophileConfig.presetParams(preset))
        dsp.setEngaged(true)
        val buf = DoubleArray(2)
        // Warm-up JIT
        for (i in 0 until frames / 4) dsp.processFrame(0.0, 0.0, buf)
        val t0 = System.nanoTime()
        var phase = 0.0
        val w = 2.0 * PI * 440.0 / fs
        for (i in 0 until frames) {
            phase += w
            val x = 0.5 * sin(phase)
            dsp.processFrame(x, x * 0.9, buf)
        }
        val elapsed = System.nanoTime() - t0
        return elapsed.toDouble() / frames // ns/frame
    }

    private fun report(label: String, nsPerFrame: Double, fs: Int) {
        val pct = (nsPerFrame / (1e9 / fs)) * 100.0
        println(
            "BENCH $label fs=$fs → %.1f ns/frame · %.2f%% CPU por hilo".format(
                java.util.Locale.US, nsPerFrame, pct
            )
        )
    }

    @Test
    fun benchmark44kAnd48k() {
        val frames = 48000 * 2 // 2 s
        for (fs in intArrayOf(44100, 48000)) {
            for (preset in AudiophileConfig.ApPreset.entries) {
                val ns = runPipeline(fs, preset, frames)
                report(preset.label, ns, fs)
            }
        }
        // Comparativa conceptual con el DSP actual: el pipeline audiophile
        // tiene ~8 módulos ligeros vs ~22 etapas del actual. El número real
        // de "Current DSP" debe medirse en dispositivo con el mismo método.
        println("BENCH nota: medir AudioEnhanceProcessor en dispositivo para comparar.")
    }
}
