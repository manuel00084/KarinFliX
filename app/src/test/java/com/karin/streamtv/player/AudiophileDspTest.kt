package com.karin.streamtv.player.dsp

import com.karin.streamtv.player.dsp.audiophile.AudiophileConfig
import com.karin.streamtv.player.dsp.audiophile.AudiophileEQ
import com.karin.streamtv.player.dsp.audiophile.AudiophileHeadroom
import com.karin.streamtv.player.dsp.audiophile.AudiophileLoudness
import com.karin.streamtv.player.dsp.audiophile.AudiophileOutput
import com.karin.streamtv.player.dsp.audiophile.AudiophileSpeaker
import com.karin.streamtv.player.dsp.audiophile.KarinAudiophileDSP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Tests del DSP experimental Karin Audiophile (JVM puro, sin Android).
 * Cubre: EQ, headroom, true-peak, loudness, bypass/A-B, silencio,
 * mono/estéreo, NaN/Inf, cambio de parámetros, pipeline completo.
 */
class AudiophileDspTest {

    private fun sine(fs: Int, freq: Double, seconds: Double, amp: Double = 0.5): DoubleArray {
        val n = (fs * seconds).toInt()
        return DoubleArray(n) { i -> amp * sin(2.0 * PI * freq * i / fs) }
    }

    private fun rms(x: DoubleArray): Double {
        var s = 0.0
        for (v in x) s += v * v
        return kotlin.math.sqrt(s / x.size)
    }

    private fun db(v: Double): Double = 20.0 * kotlin.math.log10(v.coerceAtLeast(1e-12))

    // ── EQ ──────────────────────────────────────────────────────────────

    @Test
    fun eqPeakingBoostIncreasesEnergyAtResonance() {
        val fs = 48000
        val band = AudiophileConfig.Band(
            freqHz = 1000f, gainDb = 6f, q = 1.0f,
            kind = BiquadFilter.Kind.PEAKING, enabled = true
        )
        val eq = AudiophileEQ()
        eq.configure(fs, listOf(band))
        val input = sine(fs, 1000.0, 0.25)
        val out = DoubleArray(2)
        var acc = 0.0
        // Warm-up + medida
        for (i in input.indices) {
            eq.process(input[i], input[i], out)
            if (i > fs / 20) acc += out[0] * out[0]
        }
        val outRms = kotlin.math.sqrt(acc / (input.size - fs / 20))
        assertTrue("boost debe aumentar RMS", outRms > rms(input) * 1.4)
    }

    @Test
    fun eqDisabledBandIsBypassed() {
        val fs = 48000
        val band = AudiophileConfig.Band(
            freqHz = 1000f, gainDb = 12f, q = 1.0f,
            kind = BiquadFilter.Kind.PEAKING, enabled = false
        )
        val eq = AudiophileEQ()
        eq.configure(fs, listOf(band))
        val input = sine(fs, 1000.0, 0.05)
        val out = DoubleArray(2)
        // Tras warm-up, la señal debe ser casi idéntica (filtro reset).
        var maxDiff = 0.0
        for (i in input.indices) {
            eq.process(input[i], input[i], out)
            if (i > 100) maxDiff = maxOf(maxDiff, abs(out[0] - input[i]))
        }
        assertTrue("banda off no debe colorear", maxDiff < 1e-6)
    }

    @Test
    fun eqNotchAttenuatesTargetFrequency() {
        val fs = 48000
        val band = AudiophileConfig.Band(
            freqHz = 1000f, gainDb = 0f, q = 4f,
            kind = BiquadFilter.Kind.NOTCH, enabled = true
        )
        val eq = AudiophileEQ()
        eq.configure(fs, listOf(band))
        val input = sine(fs, 1000.0, 0.25)
        val out = DoubleArray(2)
        var acc = 0.0
        for (i in input.indices) {
            eq.process(input[i], input[i], out)
            if (i > fs / 10) acc += out[0] * out[0]
        }
        val outRms = kotlin.math.sqrt(acc / (input.size - fs / 10))
        assertTrue("notch debe atenuar", outRms < rms(input) * 0.5)
    }

    @Test
    fun biquadNotchKindExists() {
        val f = BiquadFilter()
        f.configure(BiquadFilter.Kind.NOTCH, 48000, 1000f, 0f, 2f)
        assertTrue(f.process(1.0).isFinite())
    }

    // ── Headroom ────────────────────────────────────────────────────────

    @Test
    fun headroomAutoCompensatesMaxBoost() {
        val bands = listOf(
            AudiophileConfig.Band(100f, 5f, 0.7f, BiquadFilter.Kind.LOWSHELF, true),
            AudiophileConfig.Band(1000f, 3f, 1f, BiquadFilter.Kind.PEAKING, true),
            AudiophileConfig.Band(8000f, -2f, 1f, BiquadFilter.Kind.PEAKING, true)
        )
        val p = AudiophileConfig.Params(autoHeadroom = true, eqEnabled = true, bands = bands)
        val hr = AudiophileHeadroom.compute(p)
        assertEquals(-5.0, hr.preampDb.toDouble(), 0.01)
        val g = AudiophileHeadroom.preampGain(hr)
        assertTrue("preamp < 1", g < 1.0)
        assertEquals(Math.pow(10.0, -5.0 / 20.0), g, 1e-9)
    }

    @Test
    fun headroomOffMeansZeroPreamp() {
        val bands = listOf(
            AudiophileConfig.Band(100f, 6f, 0.7f, BiquadFilter.Kind.LOWSHELF, true)
        )
        val p = AudiophileConfig.Params(autoHeadroom = false, bands = bands)
        val hr = AudiophileHeadroom.compute(p)
        assertEquals(0.0, hr.preampDb.toDouble(), 0.01)
        assertEquals(1.0, AudiophileHeadroom.preampGain(hr), 1e-9)
    }

    @Test
    fun headroomCutOnlyDoesNotBoostPreamp() {
        val bands = listOf(
            AudiophileConfig.Band(100f, -6f, 0.7f, BiquadFilter.Kind.LOWSHELF, true)
        )
        val p = AudiophileConfig.Params(autoHeadroom = true, bands = bands)
        assertEquals(0.0, AudiophileHeadroom.compute(p).preampDb.toDouble(), 0.01)
    }

    // ── True peak / output ─────────────────────────────────────────────

    @Test
    fun truePeakLimiterRespectsCeiling() {
        val out = AudiophileOutput()
        out.configure(48000, -1.0f, 0f)
        val ceil = Math.pow(10.0, -1.0 / 20.0) // ~0.891
        val buf = DoubleArray(2)
        var maxOut = 0.0
        // Señal fuerte 0.99 pico (cerca del clip)
        for (i in 0 until 48000) {
            val x = 0.99 * sin(2.0 * PI * 997.0 * i / 48000)
            out.process(x, x, buf)
            maxOut = maxOf(maxOut, abs(buf[0]), abs(buf[1]))
        }
        assertTrue("salida <= techo * margen", maxOut <= ceil * 1.05 + 1e-6)
    }

    @Test
    fun outputSanitizesNonFinite() {
        val out = AudiophileOutput()
        out.configure(48000, -1.0f, 0f)
        val buf = DoubleArray(2)
        out.process(Double.NaN, Double.POSITIVE_INFINITY, buf)
        assertTrue("L finite", buf[0].isFinite())
        assertTrue("R finite", buf[1].isFinite())
    }

    // ── Loudness ────────────────────────────────────────────────────────

    @Test
    fun loudnessOffReturnsUnityGain() {
        val lo = AudiophileLoudness()
        lo.configure(48000, false, -16f)
        assertEquals(1.0, lo.processGain(0.5, 0.5), 1e-12)
    }

    @Test
    fun loudnessSilenceDoesNotExplode() {
        val lo = AudiophileLoudness()
        lo.configure(48000, true, -16f)
        var g = 1.0
        for (i in 0 until 48000) g = lo.processGain(0.0, 0.0)
        assertTrue("gain finita en silencio", g.isFinite())
        assertTrue("gain razonable", g in 0.5..2.0)
    }

    @Test
    fun loudnessGainStaysWithinClamp() {
        val lo = AudiophileLoudness()
        lo.configure(48000, true, -14f)
        // Entrada muy silenciosa (~ -40 dBFS) durante 1 s
        var minG = Double.MAX_VALUE
        var maxG = 0.0
        for (i in 0 until 48000) {
            val x = 0.01 * sin(2.0 * PI * 440.0 * i / 48000)
            val g = lo.processGain(x, x)
            minG = minOf(minG, g); maxG = maxOf(maxG, g)
        }
        // Corrección máxima ±3 dB → 0.708..2.0
        assertTrue("min >= 0.7", minG >= 0.7 - 1e-6)
        assertTrue("max <= 2.1", maxG <= 2.1)
    }

    // ── Pipeline completo ───────────────────────────────────────────────

    private fun processSignal(
        dsp: KarinAudiophileDSP,
        l: DoubleArray,
        r: DoubleArray = l
    ): Pair<DoubleArray, DoubleArray> {
        val outL = DoubleArray(l.size)
        val outR = DoubleArray(r.size)
        val buf = DoubleArray(2)
        for (i in l.indices) {
            dsp.processFrame(l[i], r[i], buf)
            outL[i] = buf[0]; outR[i] = buf[1]
        }
        return outL to outR
    }

    @Test
    fun purePresetPassesApproximatelyTransparent() {
        val fs = 48000
        val dsp = KarinAudiophileDSP()
        val p = AudiophileConfig.presetParams(AudiophileConfig.ApPreset.PURE)
        dsp.configure(fs, 2, p)
        dsp.setEngaged(true)
        // Dejar que el crossfade llegue a wet=1
        val warm = DoubleArray(fs / 10)
        val buf = DoubleArray(2)
        for (i in warm.indices) dsp.processFrame(0.0, 0.0, buf)
        val input = sine(fs, 440.0, 0.1, 0.3)
        val (outL, _) = processSignal(dsp, input)
        // Tras warm-up, Pure no debe alterar mucho el RMS
        val ratio = rms(outL) / rms(input)
        assertTrue("ratio razonable (got $ratio)", ratio in 0.7..1.3)
    }

    @Test
    fun bypassAbMatchesInputAfterFade() {
        val fs = 48000
        val dsp = KarinAudiophileDSP()
        val p = AudiophileConfig.presetParams(AudiophileConfig.ApPreset.REFERENCE)
        dsp.configure(fs, 2, p)
        dsp.setEngaged(true)
        dsp.updateIfChanged(p, abBypassNow = true) // A/B → dry
        // Warm-up hasta que mix llegue a 0
        val buf = DoubleArray(2)
        for (i in 0 until fs / 5) dsp.processFrame(0.0, 0.0, buf)
        assertFalse("no fading tras warm-up", dsp.isFading())
        val input = sine(fs, 1000.0, 0.05, 0.4)
        val (outL, _) = processSignal(dsp, input)
        var maxDiff = 0.0
        for (i in input.indices) maxDiff = maxOf(maxDiff, abs(outL[i] - input[i]))
        assertTrue("bypass ≈ input (diff=$maxDiff)", maxDiff < 1e-3)
    }

    @Test
    fun silenceStaysSilence() {
        val dsp = KarinAudiophileDSP()
        dsp.configure(48000, 2, AudiophileConfig.presetParams(AudiophileConfig.ApPreset.HIFI))
        dsp.setEngaged(true)
        val buf = DoubleArray(2)
        for (i in 0 until 48000) {
            dsp.processFrame(0.0, 0.0, buf)
            assertEquals(0.0, buf[0], 1e-9)
            assertEquals(0.0, buf[1], 1e-9)
        }
    }

    @Test
    fun monoInputProducesFiniteStereo() {
        val dsp = KarinAudiophileDSP()
        dsp.configure(48000, 1, AudiophileConfig.presetParams(AudiophileConfig.ApPreset.MOBILE_SPEAKER))
        dsp.setEngaged(true)
        val buf = DoubleArray(2)
        for (i in 0 until 4800) {
            val x = 0.5 * sin(2.0 * PI * 220.0 * i / 48000)
            dsp.processFrame(x, x, buf)
            assertTrue(buf[0].isFinite())
            assertTrue(buf[1].isFinite())
        }
    }

    @Test
    fun channelImbalanceIsFinite() {
        val dsp = KarinAudiophileDSP()
        dsp.configure(48000, 2, AudiophileConfig.presetParams(AudiophileConfig.ApPreset.HIFI))
        dsp.setEngaged(true)
        val buf = DoubleArray(2)
        for (i in 0 until 4800) {
            dsp.processFrame(0.9, -0.1, buf)
            assertTrue(buf[0].isFinite())
            assertTrue(buf[1].isFinite())
        }
    }

    @Test
    fun nanInputNeverPropagates() {
        val dsp = KarinAudiophileDSP()
        dsp.configure(48000, 2, AudiophileConfig.presetParams(AudiophileConfig.ApPreset.REFERENCE))
        dsp.setEngaged(true)
        val buf = DoubleArray(2)
        dsp.processFrame(Double.NaN, Double.POSITIVE_INFINITY, buf)
        assertTrue(buf[0].isFinite())
        assertTrue(buf[1].isFinite())
        assertEquals(0.0, buf[0], 0.0)
        assertEquals(0.0, buf[1], 0.0)
    }

    @Test
    fun sampleRateChangeReconfiguresCleanly() {
        val dsp = KarinAudiophileDSP()
        val p = AudiophileConfig.presetParams(AudiophileConfig.ApPreset.HIFI)
        dsp.configure(44100, 2, p)
        dsp.setEngaged(true)
        val buf = DoubleArray(2)
        for (i in 0 until 1000) dsp.processFrame(0.2, 0.2, buf)
        dsp.configure(48000, 2, p) // cambio de fs
        for (i in 0 until 1000) {
            dsp.processFrame(0.2, 0.2, buf)
            assertTrue(buf[0].isFinite())
        }
    }

    @Test
    fun paramsChangeAppliesWithoutCrash() {
        val dsp = KarinAudiophileDSP()
        val p1 = AudiophileConfig.presetParams(AudiophileConfig.ApPreset.PURE)
        dsp.configure(48000, 2, p1)
        dsp.setEngaged(true)
        val p2 = AudiophileConfig.presetParams(AudiophileConfig.ApPreset.MOBILE_SPEAKER)
            .copy(bassExtEnabled = true, harmonicEnabled = true, transientEnabled = true)
        dsp.updateIfChanged(p2, abBypassNow = false)
        val buf = DoubleArray(2)
        for (i in 0 until 4800) {
            dsp.processFrame(0.5 * sin(2.0 * PI * 80.0 * i / 48000), 0.5, buf)
            assertTrue(buf[0].isFinite())
            assertTrue(buf[1].isFinite())
        }
    }

    @Test
    fun engineExclusivityContract() {
        // El dispatcher solo debe mostrar una ruta activa por vez.
        // Aquí validamos que los tres estados existen y son distinguibles.
        val engines = AudiophileConfig.Engine.entries
        assertEquals(3, engines.size)
        assertTrue(engines.any { it.name == "OFF" })
        assertTrue(engines.any { it.name == "CURRENT" })
        assertTrue(engines.any { it.name == "AUDIOPHILE" })
    }

    @Test
    fun presetsAreSixAndCoverSpeaker() {
        assertEquals(6, AudiophileConfig.ApPreset.entries.size)
        assertTrue(AudiophileConfig.ApPreset.entries.any { it.name == "TV_SPEAKER" })
        for (p in AudiophileConfig.ApPreset.entries) {
            val params = AudiophileConfig.presetParams(p)
            assertTrue("pure headroom on", params.autoHeadroom)
            assertTrue("tp ceiling razonable", params.truePeakCeilingDb in -3f..-0.1f)
        }
        val tv = AudiophileConfig.presetParams(AudiophileConfig.ApPreset.TV_SPEAKER)
        assertTrue("TV activa speaker STRONG", tv.speakerMode == AudiophileConfig.SpeakerMode.STRONG)
        val mobile = AudiophileConfig.presetParams(AudiophileConfig.ApPreset.MOBILE_SPEAKER)
        assertTrue("Mobile activa speaker MEDIUM", mobile.speakerMode == AudiophileConfig.SpeakerMode.MEDIUM)
    }

    @Test
    fun engageFadeStartsFromDryAndReachesWet() {
        val dsp = KarinAudiophileDSP()
        val p = AudiophileConfig.presetParams(AudiophileConfig.ApPreset.REFERENCE)
            .copy(eqEnabled = false, loudnessEnabled = false)
        dsp.configure(48000, 2, p)
        dsp.setEngaged(true)
        assertTrue(dsp.isFading()) // arranca en dry
        val buf = DoubleArray(2)
        for (i in 0 until 48000 / 5) dsp.processFrame(0.0, 0.0, buf) // >15 ms
        assertFalse("fade completo", dsp.isFading())
    }

    @Test
    fun resetDoesNotThrow() {
        val dsp = KarinAudiophileDSP()
        dsp.configure(48000, 2, AudiophileConfig.presetParams(AudiophileConfig.ApPreset.HIFI))
        dsp.reset()
        dsp.resetFade()
        val buf = DoubleArray(2)
        dsp.processFrame(0.1, 0.1, buf)
        assertTrue(buf[0].isFinite())
    }

    // ── Speaker voicing ─────────────────────────────────────────────────

    @Test
    fun speakerOffIsTransparent() {
        val sp = AudiophileSpeaker()
        sp.configure(48000, AudiophileConfig.SpeakerMode.OFF)
        val out = DoubleArray(2)
        sp.process(0.3, -0.2, out)
        assertEquals(0.3, out[0], 1e-12)
        assertEquals(-0.2, out[1], 1e-12)
    }

    @Test
    fun speakerStrongBoostsLowFrequencyEnergy() {
        val fs = 48000
        val sp = AudiophileSpeaker()
        sp.configure(fs, AudiophileConfig.SpeakerMode.STRONG)
        val input = sine(fs, 100.0, 0.25, 0.2)
        val out = DoubleArray(2)
        var acc = 0.0
        for (i in input.indices) {
            sp.process(input[i], input[i], out)
            if (i > fs / 20) acc += out[0] * out[0]
        }
        val outRms = kotlin.math.sqrt(acc / (input.size - fs / 20))
        assertTrue("bass shelf debe sumar energía (got ${"%.3f".format(outRms)})", outRms > rms(input) * 1.5)
    }

    @Test
    fun speakerStrongPresenceBandGetsSmoothing() {
        val fs = 48000
        val sp = AudiophileSpeaker()
        sp.configure(fs, AudiophileConfig.SpeakerMode.STRONG)
        val input = sine(fs, 8500.0, 0.15, 0.3) // banda del peaking "smooth"
        val out = DoubleArray(2)
        var accIn = 0.0
        var accOut = 0.0
        var n = 0
        for (i in input.indices) {
            sp.process(input[i], input[i], out)
            if (i > fs / 10) {
                accIn += input[i] * input[i]
                accOut += out[0] * out[0]
                n++
            }
        }
        val rIn = kotlin.math.sqrt(accIn / n)
        val rOut = kotlin.math.sqrt(accOut / n)
        assertTrue("smooth debe cortar 8.5k (in=${"%.4f".format(rIn)} out=${"%.4f".format(rOut)})", rOut < rIn)
    }

    @Test
    fun speakerProducesNoNanAndStaysBounded() {
        val fs = 48000
        for (mode in AudiophileConfig.SpeakerMode.entries) {
            val sp = AudiophileSpeaker()
            sp.configure(fs, mode)
            val out = DoubleArray(2)
            var maxOut = 0.0
            for (i in 0 until 8000) {
                val x = 0.9 * sin(2.0 * PI * 500.0 * i / fs)
                sp.process(x, x, out)
                assertTrue("${mode.name} L finite", out[0].isFinite())
                assertTrue("${mode.name} R finite", out[1].isFinite())
                maxOut = maxOf(maxOut, abs(out[0]))
            }
            // Las curvas maximizan +7 dB (bass) → límite amplio de sanidad < 3.
            assertTrue("${mode.name} acotado (${"%.2f".format(maxOut)})", maxOut < 3.0)
        }
    }

    @Test
    fun tvSpeakerPresetPipelineRunsClean() {
        val dsp = KarinAudiophileDSP()
        dsp.configure(48000, 2, AudiophileConfig.presetParams(AudiophileConfig.ApPreset.TV_SPEAKER))
        dsp.setEngaged(true)
        val buf = DoubleArray(2)
        var maxOut = 0.0
        for (i in 0 until 48000) {
            val x = 0.5 * sin(2.0 * PI * 90.0 * i / 48000) + 0.5 * sin(2.0 * PI * 2000.0 * i / 48000)
            dsp.processFrame(x, 0.9 * x, buf)
            assertTrue(buf[0].isFinite())
            assertTrue(buf[1].isFinite())
            maxOut = maxOf(maxOut, abs(buf[0]))
        }
        assertTrue("TV con true-peak -1.5 dBTP acotado (${"%.2f".format(maxOut)})", maxOut <= 1.0)
    }
}
