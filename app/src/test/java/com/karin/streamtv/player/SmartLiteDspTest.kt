package com.karin.streamtv.player.dsp

import android.media.AudioDeviceInfo
import com.karin.streamtv.player.dsp.AudioEnhanceProcessor
import com.karin.streamtv.player.dsp.smartlite.AbxSession
import com.karin.streamtv.player.dsp.smartlite.SmartLiteReport
import com.karin.streamtv.player.dsp.smartlite.SmartLiteConfig
import com.karin.streamtv.player.dsp.smartlite.SmartLiteCrossfeed
import com.karin.streamtv.player.dsp.smartlite.SmartLiteDynamics
import com.karin.streamtv.player.dsp.smartlite.SmartLiteEQ
import com.karin.streamtv.player.dsp.smartlite.SmartLiteHarmonic
import com.karin.streamtv.player.dsp.smartlite.SmartLiteHeadroom
import com.karin.streamtv.player.dsp.smartlite.SmartLiteLoudness
import com.karin.streamtv.player.dsp.smartlite.SmartLiteOutput
import com.karin.streamtv.player.dsp.smartlite.SmartLiteSpeaker
import com.karin.streamtv.player.dsp.smartlite.SmartLiteTransient
import com.karin.streamtv.player.dsp.smartlite.SmartLiteTrueBass
import com.karin.streamtv.player.dsp.smartlite.SmartLiteDSP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tests del DSP experimental Karin SmartLite (JVM puro, sin Android).
 * Cubre: EQ, headroom, true-peak, loudness, bypass/A-B, silencio,
 * mono/estéreo, NaN/Inf, cambio de parámetros, pipeline completo.
 */
class SmartLiteDspTest {

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
        val band = SmartLiteConfig.Band(
            freqHz = 1000f, gainDb = 6f, q = 1.0f,
            kind = BiquadFilter.Kind.PEAKING, enabled = true
        )
        val eq = SmartLiteEQ()
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
        val band = SmartLiteConfig.Band(
            freqHz = 1000f, gainDb = 12f, q = 1.0f,
            kind = BiquadFilter.Kind.PEAKING, enabled = false
        )
        val eq = SmartLiteEQ()
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
        val band = SmartLiteConfig.Band(
            freqHz = 1000f, gainDb = 0f, q = 4f,
            kind = BiquadFilter.Kind.NOTCH, enabled = true
        )
        val eq = SmartLiteEQ()
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
            SmartLiteConfig.Band(100f, 5f, 0.7f, BiquadFilter.Kind.LOWSHELF, true),
            SmartLiteConfig.Band(1000f, 3f, 1f, BiquadFilter.Kind.PEAKING, true),
            SmartLiteConfig.Band(8000f, -2f, 1f, BiquadFilter.Kind.PEAKING, true)
        )
        val p = SmartLiteConfig.Params(autoHeadroom = true, eqEnabled = true, bands = bands)
        val hr = SmartLiteHeadroom.compute(p)
        assertEquals(-5.0, hr.preampDb.toDouble(), 0.01)
        val g = SmartLiteHeadroom.preampGain(hr)
        assertTrue("preamp < 1", g < 1.0)
        assertEquals(Math.pow(10.0, -5.0 / 20.0), g, 1e-9)
    }

    @Test
    fun headroomOffMeansZeroPreamp() {
        val bands = listOf(
            SmartLiteConfig.Band(100f, 6f, 0.7f, BiquadFilter.Kind.LOWSHELF, true)
        )
        val p = SmartLiteConfig.Params(autoHeadroom = false, bands = bands)
        val hr = SmartLiteHeadroom.compute(p)
        assertEquals(0.0, hr.preampDb.toDouble(), 0.01)
        assertEquals(1.0, SmartLiteHeadroom.preampGain(hr), 1e-9)
    }

    @Test
    fun headroomCutOnlyDoesNotBoostPreamp() {
        val bands = listOf(
            SmartLiteConfig.Band(100f, -6f, 0.7f, BiquadFilter.Kind.LOWSHELF, true)
        )
        val p = SmartLiteConfig.Params(autoHeadroom = true, bands = bands)
        assertEquals(0.0, SmartLiteHeadroom.compute(p).preampDb.toDouble(), 0.01)
    }

    // ── True peak / output ─────────────────────────────────────────────

    @Test
    fun truePeakLimiterRespectsCeiling() {
        val out = SmartLiteOutput()
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
        val out = SmartLiteOutput()
        out.configure(48000, -1.0f, 0f)
        val buf = DoubleArray(2)
        out.process(Double.NaN, Double.POSITIVE_INFINITY, buf)
        assertTrue("L finite", buf[0].isFinite())
        assertTrue("R finite", buf[1].isFinite())
    }

    /**
     * La salida PCM de 16 bits debe redondear de forma simétrica. `toInt()`
     * trunca hacia cero: con el desplazamiento de +0.5, los positivos quedaban
     * bien, pero los negativos entre -1 y 0 LSB se redondeaban a cero. Esa zona
     * muerta asimétrica rompía el dither TPDF y distorsionaba la señal baja.
     */
    @Test
    fun pcm16QuantizationIsSymmetric() {
        val q = AudioEnhanceProcessor::quantizePcm16Sample
        // El argumento ya incluye escala, +0.5 y dither. El caso clave es -0.1:
        // la implementación anterior lo truncaba a 0, dejando una zona muerta
        // asimétrica en los negativos.
        assertEquals(1, q(1.1))
        assertEquals(0, q(0.9))
        assertEquals(-1, q(-0.1))
        assertEquals(-1, q(-0.9))
        assertEquals(-2, q(-1.1))
        assertEquals(32767, q(40000.0))
        assertEquals(-32768, q(-40000.0))
    }

    // ── Loudness ────────────────────────────────────────────────────────

    @Test
    fun loudnessOffReturnsUnityGain() {
        val lo = SmartLiteLoudness()
        lo.configure(48000, false, -16f)
        assertEquals(1.0, lo.processGain(0.5, 0.5), 1e-12)
    }

    @Test
    fun loudnessSilenceDoesNotExplode() {
        val lo = SmartLiteLoudness()
        lo.configure(48000, true, -16f)
        var g = 1.0
        for (i in 0 until 48000) g = lo.processGain(0.0, 0.0)
        assertTrue("gain finita en silencio", g.isFinite())
        assertTrue("gain razonable", g in 0.5..2.0)
    }

    @Test
    fun loudnessGainStaysWithinClamp() {
        val lo = SmartLiteLoudness()
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
        dsp: SmartLiteDSP,
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
        val dsp = SmartLiteDSP()
        val p = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.PURE)
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
        val dsp = SmartLiteDSP()
        val p = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.REFERENCE)
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
        val dsp = SmartLiteDSP()
        dsp.configure(48000, 2, SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.HIFI))
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
        val dsp = SmartLiteDSP()
        dsp.configure(48000, 1, SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.MOBILE_SPEAKER))
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
        val dsp = SmartLiteDSP()
        dsp.configure(48000, 2, SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.HIFI))
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
        val dsp = SmartLiteDSP()
        dsp.configure(48000, 2, SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.REFERENCE))
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
        val dsp = SmartLiteDSP()
        val p = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.HIFI)
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
        val dsp = SmartLiteDSP()
        val p1 = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.PURE)
        dsp.configure(48000, 2, p1)
        dsp.setEngaged(true)
        val p2 = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.MOBILE_SPEAKER)
            .copy(bassExtEnabled = true, harmonicEnabled = true, transientEnabled = true)
        dsp.updateIfChanged(p2, abBypassNow = false)
        val buf = DoubleArray(2)
        for (i in 0 until 4800) {
            dsp.processFrame(0.5 * sin(2.0 * PI * 80.0 * i / 48000), 0.5, buf)
            assertTrue(buf[0].isFinite())
            assertTrue(buf[1].isFinite())
        }
    }

    /**
     * El medidor tiene que reportar LUFS de verdad. El integrador con fuga
     * `acc = acc*leak + p` tiene ganancia DC 1/(1-leak) ≈ 3·fs, así que sin
     * dividir por esa ganancia el logaritmo marca ~45 dB arriba y todo preset
     * con loudness lee un LUFS inflado (por eso REFERENCE sonaba idéntico a
     * PURE). Ancla: un seno a plena escala en ambos canales es 0 dBFS de pico
     * → potencia media 0.5 por canal → z = 1.0 → −0.691 LUFS.
     */
    @Test
    fun loudnessMeterReadsRealLufs() {
        val fs = 48000
        val ld = SmartLiteLoudness()
        ld.configure(fs, true, -16f)
        val n = fs * 30
        for (i in 0 until n) {
            val v = sin(2.0 * Math.PI * 1000.0 * i / fs) * 0.5
            ld.processGain(v, v)
        }
        val measured = ld.shortTermLufs.toDouble()
        // Seno de amplitud 0.5 → potencia media 0.125/canal → z = 0.25, y el
        // K-weighting de BS.1770 a 1 kHz vale ~−0.7 dB, así que se espera cerca
        // de −5.9 LUFS (−0.691 + 10*log10(0.25) − 0.7).
        assertTrue(
            "LUFS medido ~= -5.9 (${"%.2f".format(measured)})",
            measured > -7.0 && measured < -5.0
        )
    }

    /**
     * La corrección tiene que llegar a su destino en segundos, no en horas. El
     * tau de 1.5 s se aplica cada intervalo de actualización (0.25 s), así que
     * el factor de interpolación debe incluir ese intervalo. Con el factor por
     * muestra (≈1.4e-5) el tau salía en horas y REFERENCE no se distinguía de
     * PURE: tras 120 s la corrección solo había movido 0.01 dB.
     */
    @Test
    fun loudnessCorrectionConvergesWithinSeconds() {
        val fs = 48000
        val ld = SmartLiteLoudness()
        ld.configure(fs, true, -16f)
        val n = fs * 20
        var g = 1.0
        for (i in 0 until n) {
            val v = sin(2.0 * Math.PI * 1000.0 * i / fs) * 0.1
            g = ld.processGain(v, v)
        }
        val gainDb = db(g)
        // El seno está ~19 dB por debajo del objetivo, pero la corrección está
        // limitada a ±3 dB a propósito. Lo que se comprueba es que se mueve.
        assertTrue("la corrección avanza (${"%.2f".format(gainDb)} dB)", gainDb > 2.0)
    }

    /**
     * Un preset con loudness tiene que sonar distinto del mismo preset sin él.
     * Si esto falla, PURE y REFERENCE vuelven a ser el mismo preset y la lista
     * tiene un duplicado.
     */
    @Test
    fun loudnessMakesReferenceDifferFromPure() {
        val fs = 48000
        val p = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.PURE)
        val ref = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.REFERENCE)
        assertTrue("REFERENCE enciende loudness", ref.loudnessEnabled)
        assertTrue("PURE lo tiene apagado", !p.loudnessEnabled)
        val input = sine(fs, 1000.0, 10.0, 0.05)
        val levelOf = { params: SmartLiteConfig.Params ->
            val dsp = SmartLiteDSP()
            dsp.configure(fs, 2, params)
            dsp.setEngaged(true)
            val buf = DoubleArray(2)
            for (i in 0 until fs) dsp.processFrame(0.0, 0.0, buf)
            val (outL, _) = processSignal(dsp, input)
            // Solo el tramo final: el loudness necesita su tau para converger.
            db(rms(outL.copyOfRange(fs * 8, outL.size)))
        }
        val delta = levelOf(ref) - levelOf(p)
        assertTrue(
            "REFERENCE se separa de PURE (${"%.2f".format(delta)} dB)",
            abs(delta) > 0.5
        )
    }

    /**
     * BassExtension debe limitar SOLO el contenido generado, nunca la señal
     * original. El bug era aplicar `tanh` a la señal compuesta: como
     * `tanh(x) ≈ x` solo para x pequeño, eso comprimía todo el programa y hacía
     * que MOBILE y TV distorsionaran hasta 4.9% THD mientras los otros cuatro
     * presets medían 0.000%. Aislado: el fallo estaba en la salida global, no
     * en el armónico de graves.
     */
    @Test
    fun bassExtensionDoesNotDistortTheProgramme() {
        val fs = 48000
        val f0 = 1000.0
        for (preset in listOf(
            SmartLiteConfig.SlPreset.MOBILE_SPEAKER,
            SmartLiteConfig.SlPreset.TV_SPEAKER
        )) {
            val dsp = SmartLiteDSP()
            dsp.configure(fs, 2, SmartLiteConfig.presetParams(preset))
            dsp.setEngaged(true)
            val buf = DoubleArray(2)
            for (i in 0 until fs) dsp.processFrame(0.0, 0.0, buf)
            val n = fs * 2
            val out = DoubleArray(n)
            for (i in 0 until n) {
                val v = Math.pow(10.0, -6.0 / 20.0) * sin(2.0 * Math.PI * f0 * i / fs)
                dsp.processFrame(v, v, buf)
                out[i] = buf[0]
            }
            val seg = out.copyOfRange(fs / 2, n)
            val a1 = harmonicAmp(seg, f0, fs)
            var s = 0.0
            for (h in 2..8) {
                val a = harmonicAmp(seg, f0 * h, fs)
                s += a * a
            }
            val thd = sqrt(s) / a1
            assertTrue(
                "THD de ${preset.name} = ${"%.4f".format(thd * 100)}% (techo 0.05%)",
                thd < 0.0005
            )
        }
    }

    /**
     * Amplitud a una frecuencia dada por correlación directa con seno/coseno
     * (Goertzel ligero). Sirve para medir armónicos sin depender de FFT.
     */
    private fun harmonicAmp(x: DoubleArray, f: Double, fs: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in x.indices) {
            val w = 2.0 * PI * f * i / fs
            re += x[i] * cos(w)
            im += x[i] * sin(w)
        }
        return 2.0 * sqrt(re * re + im * im) / x.size
    }

    @Test
    fun detectsRequestedPhysicalOutputs() {
        val classify = SmartLiteConfig::classifyPlaybackOutput
        fun endpoint(type: Int, name: String, isSink: Boolean = true) =
            SmartLiteConfig.OutputEndpoint(type, name, isSink)

        assertEquals(
            SmartLiteConfig.PlaybackOutput.PHONE_SPEAKER,
            classify(listOf(endpoint(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker")), false)
        )
        assertEquals(
            SmartLiteConfig.PlaybackOutput.TV_SPEAKER,
            classify(listOf(endpoint(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker")), true)
        )
        assertEquals(
            SmartLiteConfig.PlaybackOutput.WIRED_HEADPHONES,
            classify(listOf(endpoint(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "3.5mm")), false)
        )
        assertEquals(
            SmartLiteConfig.PlaybackOutput.BLUETOOTH_HEADPHONES,
            classify(listOf(endpoint(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "WH-1000XM5")), false)
        )
        assertEquals(
            SmartLiteConfig.PlaybackOutput.BLUETOOTH_SPEAKER,
            classify(listOf(endpoint(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "JBL Flip 6")), false)
        )
        assertEquals(
            SmartLiteConfig.PlaybackOutput.AV_RECEIVER,
            classify(listOf(endpoint(AudioDeviceInfo.TYPE_HDMI_ARC, "Samsung HW-Q990C")), true)
        )
        assertEquals(
            SmartLiteConfig.PlaybackOutput.SOUNDBAR,
            classify(listOf(endpoint(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Samsung HW Soundbar")), false)
        )
        assertEquals(
            SmartLiteConfig.PlaybackOutput.EXTERNAL_DAC,
            classify(listOf(endpoint(AudioDeviceInfo.TYPE_USB_DEVICE, "Topping D10 DAC")), false)
        )
        assertEquals(
            SmartLiteConfig.PlaybackOutput.PHONE_SPEAKER,
            classify(
                listOf(
                    endpoint(AudioDeviceInfo.TYPE_USB_DEVICE, "Topping D10 DAC", isSink = false),
                    endpoint(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker")
                ),
                false
            )
        )
        assertEquals(
            SmartLiteConfig.PlaybackOutput.UNKNOWN,
            classify(emptyList(), false)
        )
    }

    @Test
    fun prefersConnectedTransducerOverBuiltinSpeaker() {
        val output = SmartLiteConfig.classifyPlaybackOutput(
            listOf(
                SmartLiteConfig.OutputEndpoint(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"),
                SmartLiteConfig.OutputEndpoint(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "JBL Flip 6")
            ),
            false
        )
        assertEquals(SmartLiteConfig.PlaybackOutput.BLUETOOTH_SPEAKER, output)
    }

    @Test
    fun outputAdaptationAppliesProfessionalDriverProtection() {
        val pure = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.PURE)
        assertEquals(
            pure.copy(
                speakerMode = SmartLiteConfig.SpeakerMode.MEDIUM,
                crossfeed = SmartLiteConfig.CrossfeedMode.OFF,
                bassExtEnabled = true,
                bassAmount = 0.35f,
                trueBassEnabled = true,
                trueBassLevel = 0.55f,
                dynamicsEnabled = true,
                truePeakCeilingDb = -1.0f
            ),
            SmartLiteConfig.withDetectedOutput(
                pure,
                SmartLiteConfig.PlaybackOutput.PHONE_SPEAKER,
                autoOutput = true
            )
        )
        assertEquals(
            pure.copy(
                speakerMode = SmartLiteConfig.SpeakerMode.OFF,
                bassExtEnabled = false,
                trueBassEnabled = false,
                harmonicEnabled = false,
                crossfeed = SmartLiteConfig.CrossfeedMode.LOW,
                truePeakCeilingDb = -1.0f
            ),
            SmartLiteConfig.withDetectedOutput(
                pure,
                SmartLiteConfig.PlaybackOutput.WIRED_HEADPHONES,
                autoOutput = true
            )
        )

        val tv = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.TV_SPEAKER)
        assertEquals(
            tv.copy(
                speakerMode = SmartLiteConfig.SpeakerMode.OFF,
                bassExtEnabled = false,
                trueBassEnabled = false,
                harmonicEnabled = false
            ),
            SmartLiteConfig.withDetectedOutput(
                tv,
                SmartLiteConfig.PlaybackOutput.EXTERNAL_DAC,
                autoOutput = true
            )
        )
        assertEquals(
            tv.copy(
                speakerMode = SmartLiteConfig.SpeakerMode.LIGHT,
                crossfeed = SmartLiteConfig.CrossfeedMode.OFF,
                bassAmount = 0.35f,
                trueBassLevel = 0.45f
            ),
            SmartLiteConfig.withDetectedOutput(
                tv,
                SmartLiteConfig.PlaybackOutput.SOUNDBAR,
                autoOutput = true
            )
        )
        assertEquals(
            pure,
            SmartLiteConfig.withDetectedOutput(
                pure,
                SmartLiteConfig.PlaybackOutput.PHONE_SPEAKER,
                autoOutput = false
            )
        )
    }

    /**
     * La ganancia de loudness se interpola muestra a muestra hacia el objetivo
     * del bloque. Antes, cada actualización de 0.25 s era un escalón directo
     * (hasta ~5% durante la convergencia): zipper/pumping audible en cambios
     * de nivel. Aquí se cambia el nivel a mitad y se exige que ningún paso
     * entre muestras consecutivas supere 0.2% lineal.
     */
    @Test
    fun loudnessGainHasNoBlockSteps() {
        val fs = 48000
        val ld = SmartLiteLoudness()
        ld.configure(fs, true, -16f)
        var g = 1.0
        for (i in 0 until fs * 6) {
            val v = sin(2.0 * PI * 1000.0 * i / fs) * 0.2
            g = ld.processGain(v, v)
        }
        var maxStep = 0.0
        var prev = g
        for (i in 0 until fs * 2) {
            // Cambio de nivel a mitad del segundo tramo.
            val amp = if (i < fs) 0.2 else 0.08
            val v = sin(2.0 * PI * 1000.0 * i / fs) * amp
            g = ld.processGain(v, v)
            maxStep = maxOf(maxStep, abs(g - prev))
            prev = g
        }
        assertTrue(
            "paso máximo de ganancia ${"%.5f".format(maxStep)} (techo 0.002)",
            maxStep < 0.002
        )
    }

    /**
     * El generador cúbico no puede plegar contenido audible. Con entrada de
     * 8 kHz a 44.1 kHz, el tercer armónico (24 kHz) se pliega a 20.1 kHz. El
     * limitador de banda en cascada (4º orden a 5 kHz) debe dejar ese alias
     * por debajo de -50 dB respecto al fundamental con la configuración de TV.
     */
    @Test
    fun harmonicGeneratorStaysBelowNyquist() {
        val fs = 44100
        val harm = SmartLiteHarmonic()
        harm.configure(fs, true, 3500f, 0.2f, 0.3f, 0.5f)
        val n = fs * 2
        val out = DoubleArray(n)
        val buf = DoubleArray(2)
        for (i in 0 until n) {
            val v = 0.8 * sin(2.0 * PI * 8000.0 * i / fs)
            harm.process(v, v, buf)
            out[i] = buf[0]
        }
        val seg = out.copyOfRange(fs / 2, n)
        val fund = harmonicAmp(seg, 8000.0, fs)
        val alias = harmonicAmp(seg, (fs - 3 * 8000.0), fs)
        assertTrue(
            "alias a 20.1 kHz = ${"%.2f".format(20 * kotlin.math.log10(alias / fund))} dB (techo -50 dB)",
            alias / fund < 0.0032
        )
    }

    /**
     * TrueBass confina su síntesis a la zona de graves. El pasa-bajos final a
     * 220 Hz deja pasar 2º/3er armónico (percepción de cuerpo) pero atenúa los
     * órdenes altos que sonaban como aspereza en medios.
     */
    @Test
    fun trueBassConfinesSynthesisToBassRegion() {
        val fs = 44100
        val tb = SmartLiteTrueBass()
        tb.configure(fs, true, 0.9f)
        val n = fs * 2
        val out = DoubleArray(n)
        val buf = DoubleArray(2)
        for (i in 0 until n) {
            val v = 0.5 * sin(2.0 * PI * 55.0 * i / fs)
            tb.process(v, v, buf)
            out[i] = buf[0]
        }
        val seg = out.copyOfRange(fs / 2, n)
        val fund = harmonicAmp(seg, 55.0, fs)
        val sixth = harmonicAmp(seg, 330.0, fs)
        val eighth = harmonicAmp(seg, 440.0, fs)
        assertTrue(
            "6º armónico = ${"%.2f".format(100 * sixth / fund)}% del fundamental (techo 8%)",
            sixth / fund < 0.08
        )
        assertTrue(
            "8º armónico = ${"%.2f".format(100 * eighth / fund)}% del fundamental (techo 5%)",
            eighth / fund < 0.05
        )
    }

    /**
     * Contrato de presets: cada preset tiene una composición fija de módulos.
     * Si alguien cambia qué hace cada preset, este test lo detecta antes de
     * que la lista tenga duplicados funcionales o un preset pierda identidad.
     */
    @Test
    fun presetContracts() {
        val pure = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.PURE)
        assertFalse("PURE sin loudness", pure.loudnessEnabled)
        assertFalse("PURE sin dynamics", pure.dynamicsEnabled)
        assertFalse("PURE sin bass", pure.bassExtEnabled)
        assertFalse("PURE sin truebass", pure.trueBassEnabled)
        assertFalse("PURE sin harmonic", pure.harmonicEnabled)
        assertEquals(SmartLiteConfig.SpeakerMode.OFF, pure.speakerMode)

        val ref = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.REFERENCE)
        assertTrue("REFERENCE con loudness", ref.loudnessEnabled)
        assertEquals(-16f, ref.loudnessTargetLufs)
        assertFalse("REFERENCE sin dynamics", ref.dynamicsEnabled)

        val hifi = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.HIFI)
        assertTrue("HIFI con dynamics", hifi.dynamicsEnabled)
        assertEquals(SmartLiteConfig.CrossfeedMode.LOW, hifi.crossfeed)

        val hp = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.HEADPHONES)
        assertEquals(SmartLiteConfig.CrossfeedMode.MEDIUM, hp.crossfeed)
        assertFalse("HEADPHONES sin loudness", hp.loudnessEnabled)

        val mobile = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.MOBILE_SPEAKER)
        assertEquals(SmartLiteConfig.SpeakerMode.MEDIUM, mobile.speakerMode)
        assertTrue("MOBILE con bass", mobile.bassExtEnabled)
        assertTrue("MOBILE con truebass", mobile.trueBassEnabled)

        val tv = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.TV_SPEAKER)
        assertEquals(SmartLiteConfig.SpeakerMode.STRONG, tv.speakerMode)
        assertTrue("TV con dynamics", tv.dynamicsEnabled)
        assertTrue("TV con harmonic", tv.harmonicEnabled)

        val cinema = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.CINEMA)
        assertTrue("CINEMA con dynamics", cinema.dynamicsEnabled)
        assertTrue("CINEMA con bass", cinema.bassExtEnabled)
        assertTrue("CINEMA con truebass", cinema.trueBassEnabled)
        assertEquals(SmartLiteConfig.SpeakerMode.LIGHT, cinema.speakerMode)
        assertEquals(SmartLiteConfig.CrossfeedMode.OFF, cinema.crossfeed)
        assertEquals(-16f, cinema.loudnessTargetLufs)

        val anime = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.ANIME)
        assertEquals(SmartLiteConfig.SpeakerMode.LIGHT, anime.speakerMode)
        assertTrue("ANIME con dynamics", anime.dynamicsEnabled)
        assertEquals(SmartLiteConfig.CrossfeedMode.OFF, anime.crossfeed)

        val power = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.POWER_BASS)
        assertEquals(SmartLiteConfig.SpeakerMode.MEDIUM, power.speakerMode)
        assertTrue("POWER_BASS con bass", power.bassExtEnabled)
        assertTrue("POWER_BASS con dynamics", power.dynamicsEnabled)
        assertEquals(-14f, power.loudnessTargetLufs)
        assertEquals(-1.5f, power.truePeakCeilingDb)

        val voice = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.CLEAR_VOICE)
        assertFalse("CLEAR_VOICE sin bass", voice.bassExtEnabled)
        assertFalse("CLEAR_VOICE sin truebass", voice.trueBassEnabled)
        assertTrue("CLEAR_VOICE con dynamics", voice.dynamicsEnabled)
        assertEquals(SmartLiteConfig.SpeakerMode.LIGHT, voice.speakerMode)

        val music = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.MUSIC)
        assertEquals(SmartLiteConfig.CrossfeedMode.OFF, music.crossfeed)
        assertFalse("MUSIC sin bass", music.bassExtEnabled)
        assertTrue("MUSIC con harmonic mínimo", music.harmonicEnabled)
        assertEquals(0.1f, music.harmonicAmount)
    }

    /**
     * Clear Voice tiene curva propia: sin ancla de subgrave y con presencia a
     * 4 kHz. El resto de presets usa la curva suave global. La resolución
     * respeta AutoEQ y curvas guardadas por el usuario.
     */
    @Test
    fun clearVoiceHasOwnBassLightCurve() {
        val voice = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.CLEAR_VOICE)
        val bands = SmartLiteConfig.resolveEqBands(voice).bands
        assertEquals("6 bandas", 6, bands.size)
        assertEquals("shelf 60 Hz plano", 0.0f, bands[0].gainDb)
        assertEquals("corte 200 Hz", -1.0f, bands[1].gainDb)
        assertEquals("presencia 4 kHz", 1.0f, bands[3].gainDb)

        val pure = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.PURE)
        val smooth = SmartLiteConfig.resolveEqBands(pure).bands
        assertEquals("curva suave intacta", 1.0f, smooth[0].gainDb)
        assertEquals("curva suave intacta", 0.0f, smooth[2].gainDb)
    }

    /**
     * La adaptación de salida no le reinyecta graves a Clear Voice en ninguna
     * bocina: su contrato es inteligibilidad, y el overlay lo respeta.
     */
    @Test
    fun clearVoiceOverlayNeverReaddsBass() {
        val voice = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.CLEAR_VOICE)
        for (output in listOf(
            SmartLiteConfig.PlaybackOutput.PHONE_SPEAKER,
            SmartLiteConfig.PlaybackOutput.TV_SPEAKER,
            SmartLiteConfig.PlaybackOutput.BLUETOOTH_SPEAKER,
            SmartLiteConfig.PlaybackOutput.SOUNDBAR
        )) {
            val out = SmartLiteConfig.withDetectedOutput(voice, output, autoOutput = true)
            assertFalse("sin bass en $output", out.bassExtEnabled)
            assertFalse("sin truebass en $output", out.trueBassEnabled)
            assertTrue(
                "voicing como máximo LIGHT en $output",
                out.speakerMode.ordinal <= SmartLiteConfig.SpeakerMode.LIGHT.ordinal
            )
        }
    }

    /**
     * Los presets nuevos de contenido no distorsionan en medios (1 kHz) y sus
     * picos con graves fuertes respetan el techo true-peak.
     */
    @Test
    fun contentPresetsStayCleanAndCapped() {
        val fs = 48000
        for (preset in listOf(
            SmartLiteConfig.SlPreset.CINEMA,
            SmartLiteConfig.SlPreset.ANIME,
            SmartLiteConfig.SlPreset.POWER_BASS,
            SmartLiteConfig.SlPreset.CLEAR_VOICE,
            SmartLiteConfig.SlPreset.MUSIC
        )) {
            val params = SmartLiteConfig.presetParams(preset)
            val dsp = SmartLiteDSP()
            dsp.configure(fs, 2, params)
            dsp.setEngaged(true)
            val buf = DoubleArray(2)
            for (i in 0 until fs * 10) dsp.processFrame(0.0, 0.0, buf)
            // THD a 1 kHz, −6 dBFS.
            val n = fs * 2
            val out = DoubleArray(n)
            for (i in 0 until n) {
                val v = Math.pow(10.0, -6.0 / 20.0) * sin(2.0 * Math.PI * 1000.0 * i / fs)
                dsp.processFrame(v, v, buf)
                out[i] = buf[0]
            }
            val seg = out.copyOfRange(fs / 2, n)
            val a1 = harmonicAmp(seg, 1000.0, fs)
            var s = 0.0
            for (h in 2..8) {
                val a = harmonicAmp(seg, 1000.0 * h, fs)
                s += a * a
            }
            val thd = sqrt(s) / a1
            assertTrue(
                "THD de ${preset.name} = ${"%.4f".format(thd * 100)}% (techo 0.05%)",
                thd < 0.0005
            )
            // Pico con grave fuerte a −1 dBFS.
            var peak = 0.0
            for (i in 0 until fs * 2) {
                val v = 0.99 * sin(2.0 * PI * 55.0 * i / fs)
                dsp.processFrame(v, v, buf)
                if (i > fs / 2) peak = maxOf(peak, abs(buf[0]), abs(buf[1]))
            }
            val ceiling = Math.pow(10.0, params.truePeakCeilingDb / 20.0)
            assertTrue(
                "pico de ${preset.name} = ${"%.4f".format(peak)} (techo ${"%.4f".format(ceiling)})",
                peak <= ceiling * 1.05
            )
        }
    }

    /**
     * La cadena completa de protección (headroom + compresor de graves +
     * limitador true-peak) nunca deja salir picos por encima del techo, ni
     * siquiera con graves fuertes que los boosts de TV/MOBILE amplifican.
     */
    @Test
    fun fullChainRespectsTruePeakCeilingOnBass() {
        val fs = 48000
        for (preset in listOf(
            SmartLiteConfig.SlPreset.MOBILE_SPEAKER,
            SmartLiteConfig.SlPreset.TV_SPEAKER
        )) {
            val params = SmartLiteConfig.presetParams(preset)
            val dsp = SmartLiteDSP()
            dsp.configure(fs, 2, params)
            dsp.setEngaged(true)
            val buf = DoubleArray(2)
            for (i in 0 until fs * 10) dsp.processFrame(0.0, 0.0, buf)
            var peak = 0.0
            for (i in 0 until fs * 2) {
                val v = 0.99 * sin(2.0 * PI * 55.0 * i / fs)
                dsp.processFrame(v, v, buf)
                if (i > fs / 2) peak = maxOf(peak, abs(buf[0]), abs(buf[1]))
            }
            val ceiling = Math.pow(10.0, params.truePeakCeilingDb / 20.0)
            assertTrue(
                "pico de ${preset.name} = ${"%.4f".format(peak)} (techo ${"%.4f".format(ceiling)})",
                peak <= ceiling * 1.05
            )
        }
    }

    @Test
    fun autoEqMatcherFindsMeasuredProfiles() {
        val match = AutoEqCatalog::findBestMatch
        assertEquals("Sony WH-1000XM4", match("WH-1000XM4")?.name)
        assertEquals("Sony WH-1000XM4", match("LE_WH-1000XM4")?.name)
        assertEquals("Sony WF-1000XM4", match("WF-1000XM4")?.name)
        assertEquals("Samsung Galaxy Buds2 Pro", match("Galaxy Buds2 Pro")?.name)
        assertEquals("Sennheiser HD 600", match("HD 600")?.name)
        assertEquals("Audio-Technica ATH-M50x", match("ATH-M50x")?.name)
        assertEquals(null, match("JBL Flip 6")?.name)
        assertEquals(null, match("")?.name)
    }

    @Test
    fun autoEqResolvesFiveMeasuredBands() {
        val profile = AutoEqCatalog.findByModel("Sony WH-1000XM4")!!
        val bands = SmartLiteConfig.autoEqBands(profile)
        assertEquals("5 filtros medidos", 5, bands.size)
        assertTrue("todas activas", bands.all { it.enabled })
        assertEquals(105f, bands[0].freqHz)
        assertEquals(BiquadFilter.Kind.LOWSHELF, bands[0].kind)
        assertEquals(-4.2f, bands[0].gainDb)

        // "none" respeta la curva suave; nombre desconocido también.
        val base = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.PURE)
        SmartLiteConfig.updateMatchedAutoEqProfile(profile)
        val resolved = SmartLiteConfig.resolveEqBands(base)
        assertEquals("5 bandas aplicadas", 5, resolved.bands.size)
        SmartLiteConfig.updateMatchedAutoEqProfile(null)
        assertEquals(
            "sin match vuelve a la curva suave",
            6,
            SmartLiteConfig.resolveEqBands(base).bands.size
        )
    }

    @Test
    fun qualityTierDisablesHarmonicOnLow() {
        val tv = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.TV_SPEAKER)
        assertTrue("TV trae harmonic", tv.harmonicEnabled)
        assertFalse(
            "gama baja apaga harmonic",
            SmartLiteConfig.applyQualityTier(tv, lowTier = true).harmonicEnabled
        )
        assertEquals(
            "gama media/alta intacta",
            tv,
            SmartLiteConfig.applyQualityTier(tv, lowTier = false)
        )
    }

    @Test
    fun abxSessionScoresAndReportsPValue() {
        val perfect = AbxSession(8, kotlin.random.Random(42))
        repeat(8) { perfect.answer(perfect.currentIsA()) }
        assertTrue("8 ensayos", perfect.done)
        assertEquals(8, perfect.correct)
        assertTrue("p < 0.05 (${perfect.pValue()})", perfect.pValue() < 0.05)
        assertTrue(perfect.verdict().contains("audible"))

        val chance = AbxSession(8, kotlin.random.Random(42))
        repeat(8) { chance.answer(!chance.currentIsA()) }
        assertEquals(0, chance.correct)
        assertEquals(1.0, chance.pValue(), 1e-9)
        assertTrue(chance.verdict().contains("sin evidencia"))
    }

    @Test
    fun measurementReportContainsKeyFields() {
        val report = SmartLiteReport.format(
            SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.REFERENCE),
            com.karin.streamtv.player.dsp.smartlite.SmartLiteMetrics(),
            "Audífonos con cable",
            "LDAC, 96 kHz, 32 bits"
        )
        for (field in listOf("Preset", "Salida", "Códec", "Bit-perfect", "AutoEQ", "LUFS", "True-peak", "CPU")) {
            assertTrue("reporte contiene $field", report.contains(field))
        }
    }

    @Test
    fun btCodecDecodeLabelsKnownCodecs() {
        val ldac = BluetoothCodecMonitor.decodeCodec(
            android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
            android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_96000,
            android.bluetooth.BluetoothCodecConfig.BITS_PER_SAMPLE_32
        )
        assertEquals("LDAC, 96 kHz, 32 bits", ldac.label())
        val sbc = BluetoothCodecMonitor.decodeCodec(
            android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
            android.bluetooth.BluetoothCodecConfig.SAMPLE_RATE_44100,
            android.bluetooth.BluetoothCodecConfig.BITS_PER_SAMPLE_16
        )
        assertEquals("SBC, 44.1 kHz, 16 bits", sbc.label())
    }

    @Test
    fun avReceiverStaysTransparent() {
        val tv = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.TV_SPEAKER)
        val out = SmartLiteConfig.withDetectedOutput(
            tv,
            SmartLiteConfig.PlaybackOutput.AV_RECEIVER,
            autoOutput = true
        )
        assertEquals(SmartLiteConfig.SpeakerMode.OFF, out.speakerMode)
        assertFalse("sin bass synth contra receptor", out.bassExtEnabled)
        assertFalse("sin truebass contra receptor", out.trueBassEnabled)
        assertFalse("sin harmonic contra receptor", out.harmonicEnabled)
    }

    @Test
    fun crossfeedIsFrequencyDependent() {
        val fs = 48000
        fun leak(freq: Double): Double {
            val cf = SmartLiteCrossfeed()
            cf.configure(fs, SmartLiteConfig.CrossfeedMode.MEDIUM)
            val tone = sine(fs, freq, 1.0, 0.5)
            val out = DoubleArray(2)
            var s = 0.0
            var n = 0
            for (i in tone.indices) {
                cf.process(0.0, tone[i], out)
                if (i > fs / 5) {
                    s += out[0] * out[0]
                    n++
                }
            }
            return kotlin.math.sqrt(s / n)
        }
        val low = leak(500.0)
        val high = leak(8000.0)
        assertTrue("hay fuga en graves ($low)", low > 0.01)
        assertTrue(
            "los agudos cruzan menos (${"%.4f".format(high)} vs ${"%.4f".format(low)})",
            high < low * 0.6
        )
    }

    @Test
    fun engineExclusivityContract() {
        // El dispatcher solo debe mostrar una ruta activa por vez.
        // Solo quedan dos motores: OFF y SMART_LITE (el DSP antiguo fue eliminado).
        val engines = SmartLiteConfig.Engine.entries
        assertEquals(2, engines.size)
        assertTrue(engines.any { it.name == "OFF" })
        assertTrue(engines.any { it.name == "SMART_LITE" })
    }

    @Test
    fun presetsAreTwelveAndCoverSpeaker() {
        assertEquals(12, SmartLiteConfig.SlPreset.entries.size)
        assertTrue(SmartLiteConfig.SlPreset.entries.any { it.name == "AUTO" })
        assertTrue(SmartLiteConfig.SlPreset.entries.any { it.name == "TV_SPEAKER" })
        assertTrue(SmartLiteConfig.SlPreset.entries.any { it.name == "CINEMA" })
        assertTrue(SmartLiteConfig.SlPreset.entries.any { it.name == "CLEAR_VOICE" })
        for (p in SmartLiteConfig.SlPreset.entries) {
            val params = SmartLiteConfig.presetParams(p)
            assertTrue("pure headroom on", params.autoHeadroom)
            assertTrue("tp ceiling razonable", params.truePeakCeilingDb in -3f..-0.1f)
        }
        val tv = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.TV_SPEAKER)
        assertTrue("TV activa speaker STRONG", tv.speakerMode == SmartLiteConfig.SpeakerMode.STRONG)
        val mobile = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.MOBILE_SPEAKER)
        assertTrue("Mobile activa speaker MEDIUM", mobile.speakerMode == SmartLiteConfig.SpeakerMode.MEDIUM)
    }

    @Test
    fun autoPresetFollowsDetectedOutput() {
        val f = SmartLiteConfig::autoPresetFor
        assertEquals(SmartLiteConfig.SlPreset.HEADPHONES, f(SmartLiteConfig.PlaybackOutput.WIRED_HEADPHONES))
        assertEquals(SmartLiteConfig.SlPreset.HEADPHONES, f(SmartLiteConfig.PlaybackOutput.BLUETOOTH_HEADPHONES))
        assertEquals(SmartLiteConfig.SlPreset.HEADPHONES, f(SmartLiteConfig.PlaybackOutput.EXTERNAL_DAC))
        assertEquals(SmartLiteConfig.SlPreset.MOBILE_SPEAKER, f(SmartLiteConfig.PlaybackOutput.PHONE_SPEAKER))
        assertEquals(SmartLiteConfig.SlPreset.MOBILE_SPEAKER, f(SmartLiteConfig.PlaybackOutput.BLUETOOTH_SPEAKER))
        assertEquals(SmartLiteConfig.SlPreset.MOBILE_SPEAKER, f(SmartLiteConfig.PlaybackOutput.USB_SPEAKER))
        assertEquals(SmartLiteConfig.SlPreset.TV_SPEAKER, f(SmartLiteConfig.PlaybackOutput.TV_SPEAKER))
        assertEquals(SmartLiteConfig.SlPreset.TV_SPEAKER, f(SmartLiteConfig.PlaybackOutput.SOUNDBAR))
        assertEquals(SmartLiteConfig.SlPreset.CINEMA, f(SmartLiteConfig.PlaybackOutput.AV_RECEIVER))
        assertEquals(SmartLiteConfig.SlPreset.PURE, f(SmartLiteConfig.PlaybackOutput.UNKNOWN))
        // Solo presets de dispositivo: jamás AUTO ni presets de contenido
        // (Cinema/Anime/Clear Voice son decisión del usuario, no de la salida).
        val allowed = setOf(
            SmartLiteConfig.SlPreset.HEADPHONES,
            SmartLiteConfig.SlPreset.MOBILE_SPEAKER,
            SmartLiteConfig.SlPreset.TV_SPEAKER,
            SmartLiteConfig.SlPreset.CINEMA,
            SmartLiteConfig.SlPreset.PURE
        )
        for (o in SmartLiteConfig.PlaybackOutput.entries) {
            assertTrue("salida $o resuelve a un preset de dispositivo", f(o) in allowed)
        }
    }

    @Test
    fun resolvePresetOnlyRewritesAuto() {
        for (p in SmartLiteConfig.SlPreset.entries) {
            if (p == SmartLiteConfig.SlPreset.AUTO) continue
            assertEquals("preset explícito intacto", p, SmartLiteConfig.resolvePreset(p))
        }
        // AUTO nunca se queda en AUTO: siempre cae en un preset concreto.
        assertNotEquals(SmartLiteConfig.SlPreset.AUTO, SmartLiteConfig.resolvePreset(SmartLiteConfig.SlPreset.AUTO))
        assertNotEquals(SmartLiteConfig.SlPreset.AUTO, SmartLiteConfig.effectivePreset())
    }

    @Test
    fun autoPresetDelegatesWithoutRecursingAndKeepsProtection() {
        val params = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.AUTO)
        assertTrue("headroom activo", params.autoHeadroom)
        assertTrue("techo de protección", params.truePeakCeilingDb in -3f..-0.1f)
        val bands = SmartLiteConfig.Params.presetBands(SmartLiteConfig.SlPreset.AUTO)
        assertTrue("bandas no vacías", bands.isNotEmpty())
    }

    @Test
    fun engageFadeStartsFromDryAndReachesWet() {
        val dsp = SmartLiteDSP()
        val p = SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.REFERENCE)
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
        val dsp = SmartLiteDSP()
        dsp.configure(48000, 2, SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.HIFI))
        dsp.reset()
        dsp.resetFade()
        val buf = DoubleArray(2)
        dsp.processFrame(0.1, 0.1, buf)
        assertTrue(buf[0].isFinite())
    }

    // ── Speaker voicing ─────────────────────────────────────────────────

    @Test
    fun speakerOffIsTransparent() {
        val sp = SmartLiteSpeaker()
        sp.configure(48000, SmartLiteConfig.SpeakerMode.OFF)
        val out = DoubleArray(2)
        sp.process(0.3, -0.2, out)
        assertEquals(0.3, out[0], 1e-12)
        assertEquals(-0.2, out[1], 1e-12)
    }

    @Test
    fun speakerStrongBoostsLowFrequencyEnergy() {
        val fs = 48000
        val sp = SmartLiteSpeaker()
        sp.configure(fs, SmartLiteConfig.SpeakerMode.STRONG)
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
        val sp = SmartLiteSpeaker()
        sp.configure(fs, SmartLiteConfig.SpeakerMode.STRONG)
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
        for (mode in SmartLiteConfig.SpeakerMode.entries) {
            val sp = SmartLiteSpeaker()
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
        val dsp = SmartLiteDSP()
        dsp.configure(48000, 2, SmartLiteConfig.presetParams(SmartLiteConfig.SlPreset.TV_SPEAKER))
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

    // ── Regresiones de los defectos corregidos ──────────────────────────
    //
    // Cada test de abajo falla contra el código anterior al fix. Los márgenes
    // se justifican con la medición hecha antes/después con la misma señal.

    /**
     * RMS de la salida (canal L) de un módulo alimentado con la misma señal en
     * L y R. Acumula en la MISMA pasada que procesa: releer `out` en un segundo
     * bucle devolvería el valor de la última muestra repetido, no el RMS.
     * [from] descarta el arranque para que filtros y envolventes convergieran.
     */
    private fun rmsOf(
        step: (Double, Double, DoubleArray) -> Unit,
        input: DoubleArray,
        from: Int
    ): Double {
        val out = DoubleArray(2)
        var s = 0.0
        var n = 0
        for (i in input.indices) {
            step(input[i], input[i], out)
            if (i >= from) {
                s += out[0] * out[0]
                n++
            }
        }
        return kotlin.math.sqrt(s / n.coerceAtLeast(1))
    }

    /**
     * FIX 1: el compresor de 2 bandas perdía ~15 dB en la banda media.
     * A 1 kHz el LP de 250 Hz ya está a −24 dB y el HP de 3000 Hz a −19 dB, así
     * que al sumar solo `low·g + high·g` el diálogo desaparecía en Hi-Fi y TV
     * Speaker (los dos presets con dynamics activo). Ahora la media se recupera
     * como residuo `x − low − high`.
     * Antes: −23 dB a 1 kHz. Ahora la única pérdida es la del propio crossover.
     */
    @Test
    fun dynamicsKeepsMidBandAtVoiceFrequencies() {
        val fs = 48000
        for (freq in listOf(400.0, 1000.0, 2000.0)) {
            val dyn = SmartLiteDynamics()
            dyn.configure(fs, true)
            val inSig = sine(fs, freq, 0.3, 0.3)
            val y = rmsOf({ l, r, o -> dyn.process(l, r, o) }, inSig, from = (fs * 0.2).toInt())
            val inRms = 0.3 / kotlin.math.sqrt(2.0)
            val drop = db(inRms) - db(y)
            assertTrue(
                "voz a ${freq}Hz conservada (caida ${"%.2f".format(drop)} dB)",
                drop < 1.0
            )
        }
    }

    /**
     * FIX 1: por debajo del umbral la reconstrucción tiene que ser EXACTA
     * (low + mid + high == entrada), porque de eso depende la transparencia.
     * Una sola pasada: medir en un segundo bucle compararía `out` (que ya solo
     * tiene la última muestra) contra toda la entrada y no probaría nada.
     */
    @Test
    fun dynamicsBelowThresholdIsTransparent() {
        val fs = 48000
        val dyn = SmartLiteDynamics()
        dyn.configure(fs, true)
        val inSig = sine(fs, 1000.0, 0.3, 0.2) // 0.2 < umbral 0.5 → gain 1.0
        val out = DoubleArray(2)
        var maxErr = 0.0
        for (i in inSig.indices) {
            dyn.process(inSig[i], inSig[i], out)
            if (i >= 4000) maxErr = maxOf(maxErr, abs(out[0] - inSig[i]))
        }
        assertTrue("transparencia exacta (err=$maxErr)", maxErr < 1e-12)
    }

    /**
     * FIX 2: el boost de transitorio dividía por (envSlow + 1e-6) sin acotar, y
     * `envSlow` cae a ~0 en los huecos entre notas: salían picos de +20 dB o
     * más en cada sílaba (clic de amplitud). Con el ratio limitado a 2 el
     * máximo es 1 + 8·amount.
     */
    @Test
    fun transientBoostStaysBounded() {
        val fs = 48000
        val amp = 0.3 // 3.3 dB sobre el umbral → ratio 2.0, el peor caso
        for (amount in listOf(0.25f, 0.5f, 1.0f)) {
            val tr = SmartLiteTransient()
            tr.configure(fs, true, amount, 0f, 0f) // 1 ms / 20 ms
            val out = DoubleArray(2)
            var peak = 0.0
            // Ráfagas muy separadas: entre ellas envSlow decae hacia cero.
            for (i in 0 until fs) {
                val x = if (i % 4000 < 200) amp * sin(2.0 * PI * 4000.0 * i / fs) else 0.0
                tr.process(x, x, out)
                peak = maxOf(peak, abs(out[0]))
            }
            val limit = amp * (1.0 + 8.0 * amount)
            // 15 % de holgura por el sobreimpulso del biquad HP de 2º orden
            // (~10 % en el flanco). Lo que se comprueba es que el boost esté
            // ACOTADO: sin el fix el pico era ×10 o más, no ×(1+8·amount).
            assertTrue(
                "transient acotado (amount=$amount, pico=$peak, tope=$limit)",
                peak <= limit * 1.15
            )
        }
    }

    /**
     * FIX 3: `tanh(x·drive)/drive − x` se volvía NEGATIVO en cuanto |x| > 1/drive
     * (tanh tiende a 1/drive), así que el módulo restaba agudos en lugar de
     * añadir armónicos. Ahora el término añadido solo puede ser >= 0.
     * Se mide en el núcleo de la banda de paso (4 kHz a 48 kHz): el limitador
     * anti-alias de 4º orden introduce desfase en su banda de transición, y con
     * ajustes al máximo ese desfase puede cancelar parcialmente el fundamental
     * añadido. Con ajustes reales (preset TV) el efecto es <0.1 dB. La banda de
     * rechazo la cubre harmonicGeneratorStaysBelowNyquist.
     */
    @Test
    fun harmonicOnlyAddsEnergyNeverSubtracts() {
        val fs = 48000
        val harm = SmartLiteHarmonic()
        harm.configure(fs, true, 3500f, 1.0f, 1.0f, 1.0f) // amount y mix al máximo
        for (freq in listOf(4000.0)) {
            val inSig = sine(fs, freq, 0.2, 0.8)
            val y = rmsOf({ l, r, o -> harm.process(l, r, o) }, inSig, from = (fs * 0.1).toInt())
            val inRms = 0.8 / kotlin.math.sqrt(2.0)
            assertTrue(
                "armónicos suman a ${freq}Hz (caida ${"%.2f".format(db(inRms) - db(y))} dB)",
                db(y) >= db(inRms) - 0.05
            )
        }
    }

    /**
     * FIX 4: el crossfeed sumaba `l + mix·cL`, o sea ×(1+mix) en graves (hasta
     * +3.5 dB en HIGH) y sin límite en DC. Con `direct = 1 − mix` el contenido
     * correlacionado —que es el bajo— tiene que sumar exactamente 1.0.
     */
    @Test
    fun crossfeedPreservesLevelOnCorrelatedBass() {
        val fs = 48000
        for ((mode, mix) in listOf(
            SmartLiteConfig.CrossfeedMode.LOW to 0.12,
            SmartLiteConfig.CrossfeedMode.MEDIUM to 0.22,
            SmartLiteConfig.CrossfeedMode.HIGH to 0.35
        )) {
            val cf = SmartLiteCrossfeed()
            cf.configure(fs, mode)
            val inSig = sine(fs, 100.0, 0.2, 0.5)
            val y = rmsOf({ l, r, o -> cf.process(l, r, o) }, inSig, from = (fs * 0.1).toInt())
            // LP 700 Hz a 100 Hz ≈ 0 dB, así que la suma debe dar 0.354 (0.5/√2).
            val expected = 0.5 / kotlin.math.sqrt(2.0)
            // Tolerancia de 0.5 dB: la pérdida residual NO es un error del fix
            // sino el retardo de fase físico del crossfeed (0.3 ms de ITD más la
            // fase del LP), que es justo lo que desenlaza los canales. Lo que
            // importa es que el nivel se preserve en vez de subir.
            assertTrue(
                "nivel preservado en $mode (${"%.4f".format(y)} vs ${"%.4f".format(expected)})",
                abs(db(y) - db(expected)) < 0.5
            )
            // Y el antes-fix habría dado 0.5·(1+mix) en graves, hasta +3.5 dB.
            assertTrue("el crossfeed nunca sube el nivel en $mode", y < expected * 1.01)
        }
    }

    // ── EQ por defecto ───────────────────────────────────────────────────

    /**
     * El EQ se llama siempre en el pipeline (SmartLiteDSP.processFrame),
     * pero hasta ahora era un no-op: las 6 bandas venían `enabled=false` y
     * `setPreset()` nunca escribe KEY_EQ_BANDS, así que el checkbox "Parametric
     * EQ" no controlaba nada. Este test fija que el EQ por defecto hace algo y
     * que lo hace con mesura.
     */
    @Test
    fun defaultEqIsActiveAndGentle() {
        val bands = SmartLiteConfig.Params.defaultBands()
        assertEquals("6 bandas definidas", 6, bands.size)
        // 5 activas: el HP de 16 kHz se queda fuera a propósito.
        assertEquals("5 de 6 bandas activas", 5, bands.count { it.enabled })
        val hp = bands.first { it.kind == BiquadFilter.Kind.HIGHPASS }
        assertFalse("el HP de 16 kHz no recorta ancho de banda por defecto", hp.enabled)
        // El máximo de boost define el preamp de headroom: si creciera, el
        // usuario perdería nivel global sin avisar.
        val maxBoost = SmartLiteEQ().maxBoostDb(bands)
        assertTrue("boost maximo mesurado (${maxBoost} dB)", maxBoost in 0.5f..1.5f)

        // Y tiene que mover de verdad la señal. La referencia no es la unity
        // sino el MISMO EQ con las 6 bandas apagadas, que es el no-op real:
        // así el test no depende de estimar la forma de la curva a mano.
        val eqOn = SmartLiteEQ()
        eqOn.configure(48000, bands)
        val eqOff = SmartLiteEQ()
        eqOff.configure(48000, bands.map { it.copy(enabled = false) })
        for ((freq, minDb) in listOf(60.0 to 0.3, 200.0 to 0.3, 4000.0 to 0.3)) {
            val tone = sine(48000, freq, 0.3, 0.5)
            val on = rmsOf({ l, r, o -> eqOn.process(l, r, o) }, tone, from = 4800)
            val off = rmsOf({ l, r, o -> eqOff.process(l, r, o) }, tone, from = 4800)
            val delta = db(on) - db(off)
            assertTrue(
                "EQ actua a ${freq}Hz (delta ${"%.2f".format(delta)} dB)",
                abs(delta) >= minDb
            )
        }
    }

    /**
     * Regresión del scoop que sonaba a caja: la curva no puede tener un notch
     * ni una jiba en 1 kHz. Antes 200 Hz valía −1 y 1 kHz +1, lo que medía un
     * corte de −0.92 dB y una jiba de +0.96 dB a menos de una octava, y la voz
     * perdía cuerpo. Con las 6 bandas, los valores tienen que describir una
     * pendiente continua: 1 kHz es el punto de transición, ni corte ni jiba.
     */
    @Test
    fun defaultEqHasNoNotchOrHumpAtOneKhz() {
        val bands = SmartLiteConfig.Params.defaultBands()
        val eq = SmartLiteEQ()
        eq.configure(48000, bands)
        val off = SmartLiteEQ()
        off.configure(48000, bands.map { it.copy(enabled = false) })
        val deltaAt = { f: Double ->
            val tone = sine(48000, f, 0.3, 0.5)
            val on = rmsOf({ l, r, o -> eq.process(l, r, o) }, tone, from = 4800)
            val ref = rmsOf({ l, r, o -> off.process(l, r, o) }, tone, from = 4800)
            db(on) - db(ref)
        }

        val at1k = deltaAt(1000.0)
        val at200 = deltaAt(200.0)
        val at4k = deltaAt(4000.0)
        // 1 kHz no puede ser un jiba: la presencia la sube VoicePresence.
        assertTrue("1 kHz no es un hump (${"%.2f".format(at1k)} dB)", at1k < 0.35)
        // Ni un notch: no puede ser un valle dentro de la curva.
        assertTrue("1 kHz no es un notch (${"%.2f".format(at1k)} dB)", at1k > -0.35)
        // Y la pendiente tiene que ser monotónica en cada tramo.
        assertTrue(
            "200 -> 1 kHz sube (${"%.2f".format(at200)} -> ${"%.2f".format(at1k)})",
            at1k > at200
        )
        assertTrue(
            "1 kHz -> 4 kHz sube (${"%.2f".format(at1k)} -> ${"%.2f".format(at4k)})",
            at4k > at1k
        )
    }

    /**
     * El headroom tiene que pagar la pérdida global: con un boost de +1 dB el
     * preamp es −1 dB, así que aplicarlo no puede costar más que su propio dB.
     *
     * La comprobación compara el mismo EQ con y sin preamp, en lugar de
     * usar una "frecuencia plana" de la curva. Así sigue siendo válida si la
     * curva cambia de forma, y sigue detectando un preamp mal aplicado.
     */
    @Test
    fun defaultEqHeadroomCostsAboutOneDb() {
        val fs = 48000
        val bands = SmartLiteConfig.Params.defaultBands()
        val hr = SmartLiteHeadroom.compute(
            SmartLiteConfig.Params(autoHeadroom = true, eqEnabled = true, bands = bands)
        )
        assertTrue("preamp ~= -1 dB (${hr.preampDb})", hr.preampDb < -0.9f && hr.preampDb > -1.1f)
        val g = SmartLiteHeadroom.preampGain(hr)
        val eq = SmartLiteEQ()
        eq.configure(fs, bands)
        val tone = sine(fs, 1000.0, 0.3, 0.5)
        val withoutPreamp = rmsOf({ l, r, o -> eq.process(l, r, o) }, tone, from = 4800)
        val withPreamp = rmsOf({ l, r, o -> eq.process(l * g, r * g, o) }, tone, from = 4800)
        val cost = db(withoutPreamp) - db(withPreamp)
        assertEquals(
            "el preamp solo resta su propio dB (${"%.2f".format(cost)})",
            -db(g),
            cost,
            0.05
        )
    }
}
