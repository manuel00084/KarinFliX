package com.karin.streamtv.player.dsp

import com.karin.streamtv.player.dsp.AudioEnhanceConfig.DeviceKind
import com.karin.streamtv.player.dsp.AudioEnhanceConfig.IrPreset
import com.karin.streamtv.player.dsp.AudioEnhanceConfig.Preset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test de calidad del DSP: replica la cadena tonalStereo usando los componentes
 * reales y verifica que la salida sea limpia (sin clipping, sin THD alto, sin NaN).
 */
class DspQualityTest {

    private val fs = 44100

    /** Cadena mono (replica el orden exacto de tonalStereo del AudioEnhanceProcessor). */
    private class Chain(val fs: Int) {
        val eqL = Array(10) { BiquadFilter() }
        val eqR = Array(10) { BiquadFilter() }
        val vbL = VirtualBass()
        val vbR = VirtualBass()
        val exciteLpL = BiquadFilter()
        val exciteLpR = BiquadFilter()
        val exciteHpL = BiquadFilter()
        val exciteHpR = BiquadFilter()
        var subSynth: SubharmonicSynth? = null
        val deBoxL = BiquadFilter()
        val deBoxR = BiquadFilter()
        val tubeDcL = BiquadFilter()
        val tubeDcR = BiquadFilter()
        var paramEqL = Array(0) { BiquadFilter() }
        var paramEqR = Array(0) { BiquadFilter() }
        val fieldLp = BiquadFilter()
        val fieldDelayMax = (fs * 0.02).toInt().coerceAtLeast(8)
        var fieldDelayL = DoubleArray(fieldDelayMax)
        var fieldDelayR = DoubleArray(fieldDelayMax)
        var fieldIdxL = 0
        var fieldIdxR = 0
        var reverbL: SimpleReverb? = null
        var reverbR: SimpleReverb? = null
        val compLp = BiquadFilter()
        val compHp = BiquadFilter()
        val compLpR = BiquadFilter()
        val compHpR = BiquadFilter()
        val compEnv = DoubleArray(3)
        val compSm = DoubleArray(3) { 1.0 }
        val bassTightLp = BiquadFilter()
        val bassTightRp = BiquadFilter()
        var bassTightEnv = 0.0
        var bassTightGain = 1.0
        val loudLpL = BiquadFilter()
        val loudLpR = BiquadFilter()
        val loudHpL = BiquadFilter()
        val loudHpR = BiquadFilter()
        val scL = SpeechClarity()
        val scR = SpeechClarity()
        val surfResoL = SurfaceResonator()
        val surfResoR = SurfaceResonator()
        val convL = Convolver()
        val convR = Convolver()
        val limStereo = LookaheadLimiterPair()
        var dryDelayL = DoubleArray(1)
        var dryDelayR = DoubleArray(1)
        var dryIdxL = 0
        var dryIdxR = 0
        var dryFill = 0
        var currentVolume = 1.0f
        var masterGain = 1.0
        var bassTightEnabled = true
        var subEnabled = true
        var compressorEnabled = true
        var limiterEnabled = true
        var irEnabled = true

        fun configure(p: AudioEnhanceConfig.Params, volume: Float) {
            currentVolume = volume
            masterGain = p.masterGain.toDouble()
            val gains = gainsFor(p)
            val compAttack = Math.exp(-1.0 / (0.010 * fs))
            val compRelease = Math.exp(-1.0 / (0.150 * fs))
            val compSmooth = Math.exp(-1.0 / (0.025 * fs))
            compEnv.fill(0.0)
            for (i in 0 until 3) compSm[i] = 1.0
            val maxFreq = 0.45f * fs
            for (i in 0 until 10) {
                val g = if (AudioEnhanceConfig.EQ_FREQS[i] >= maxFreq) 0f else gains[i]
                eqL[i].configure(BiquadFilter.Kind.PEAKING, fs, AudioEnhanceConfig.EQ_FREQS[i], g, 0.8f)
                eqR[i].configure(BiquadFilter.Kind.PEAKING, fs, AudioEnhanceConfig.EQ_FREQS[i], g, 0.8f)
            }
            val loud = (1.0f - volume.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val bassDb = 3f * loud
            val trebleDb = 2f * loud
            loudLpL.configure(BiquadFilter.Kind.LOWSHELF, fs, 120f, bassDb, 0.7f)
            loudLpR.configure(BiquadFilter.Kind.LOWSHELF, fs, 120f, bassDb, 0.7f)
            loudHpL.configure(BiquadFilter.Kind.HIGHSHELF, fs, 6000f, trebleDb, 0.7f)
            loudHpR.configure(BiquadFilter.Kind.HIGHSHELF, fs, 6000f, trebleDb, 0.7f)
            vbL.configure(fs, 120f, p.dynamicBass)
            vbR.configure(fs, 120f, p.dynamicBass)
            exciteLpL.configure(BiquadFilter.Kind.LOWPASS, fs, 250f, 0f, 0.707f)
            exciteLpR.configure(BiquadFilter.Kind.LOWPASS, fs, 250f, 0f, 0.707f)
            exciteHpL.configure(BiquadFilter.Kind.HIGHPASS, fs, 4000f, 0f, 0.707f)
            exciteHpR.configure(BiquadFilter.Kind.HIGHPASS, fs, 4000f, 0f, 0.707f)
            fieldLp.configure(BiquadFilter.Kind.LOWPASS, fs, 200f, 0f, 0.707f)
            tubeDcL.configure(BiquadFilter.Kind.HIGHPASS, fs, 25f, 0f, 0.707f)
            tubeDcR.configure(BiquadFilter.Kind.HIGHPASS, fs, 25f, 0f, 0.707f)
            compLp.configure(BiquadFilter.Kind.LOWPASS, fs, 220f, 0f, 0.707f)
            compHp.configure(BiquadFilter.Kind.HIGHPASS, fs, 3200f, 0f, 0.707f)
            compLpR.configure(BiquadFilter.Kind.LOWPASS, fs, 220f, 0f, 0.707f)
            compHpR.configure(BiquadFilter.Kind.HIGHPASS, fs, 3200f, 0f, 0.707f)
            bassTightLp.configure(BiquadFilter.Kind.LOWPASS, fs, 150f, 0f, 0.707f)
            bassTightRp.configure(BiquadFilter.Kind.LOWPASS, fs, 150f, 0f, 0.707f)
            deBoxL.configure(BiquadFilter.Kind.PEAKING, fs, 300f, -6f * p.deBoxing, 1.2f)
            deBoxR.configure(BiquadFilter.Kind.PEAKING, fs, 300f, -6f * p.deBoxing, 1.2f)
            surfResoL.configure(fs, 0.15f)
            surfResoR.configure(fs, 0.15f)
            scL.configure(fs, 0.4f)
            scR.configure(fs, 0.4f)
            subSynth = SubharmonicSynth(fs)
            reverbL = SimpleReverb(fs, 0)
            reverbR = SimpleReverb(fs, 8)
            val ir = ImpulseResponses.pair(p.irType, fs)
            convL.setImpulseResponse(ir.first)
            convR.setImpulseResponse(ir.second)
            val lat = convL.latencySamples()
            dryDelayL = DoubleArray(lat)
            dryDelayR = DoubleArray(lat)
            dryIdxL = 0
            dryIdxR = 0
            dryFill = 0
            limStereo.configure(fs, 2f, 100f, 0.9)
        }

        fun process(l: Double, r: Double, p: AudioEnhanceConfig.Params): Pair<Double, Double> {
            var lo = l
            var ro = r
            if (p.reverbMix > 0f) {
                val rm = p.reverbMix.toDouble() * 0.8
                reverbL?.let { lo += rm * it.process(lo) }
                reverbR?.let { ro += rm * it.process(ro) }
            }
            if (p.harmonicBass > 0f) {
                val hl = vbL.process(lo)
                lo += bassBoost(p.harmonicBass * vbL.gainFactor(), hl)
                val hr = vbR.process(ro)
                ro += bassBoost(p.harmonicBass * vbR.gainFactor(), hr)
            }
            if (subEnabled && subSynth != null && p.subharmonicMix > 0f) {
                val volFactor = (1.0f - currentVolume.coerceIn(0.05f, 1f)).coerceIn(0f, 1f)
                val adaptiveMix = p.subharmonicMix * (1.0f + volFactor * 0.3f)
                subSynth!!.setMix(adaptiveMix)
                lo = subSynth!!.process(lo)
                ro = subSynth!!.process(ro)
            }
            lo = excite(lo, exciteLpL, exciteHpL, p.exciterAmount)
            ro = excite(ro, exciteLpR, exciteHpR, p.exciterAmount)
            for (i in eqL.indices) {
                lo = eqL[i].process(lo)
                ro = eqR[i].process(ro)
            }
            if (p.deBoxing > 0f) {
                lo = deBoxL.process(lo)
                ro = deBoxR.process(ro)
            }
            if (p.tubeDrive > 0f) {
                lo = tubeDrive(lo, tubeDcL, p.tubeDrive)
                ro = tubeDrive(ro, tubeDcR, p.tubeDrive)
            }
            for (i in paramEqL.indices) {
                lo = paramEqL[i].process(lo)
                ro = paramEqR[i].process(ro)
            }
            val field = p.fieldSurround
            if (field > 0f) {
                val fk = field.toDouble()
                val bcl = fieldLp.process(lo)
                val bcr = fieldLp.process(ro)
                val center = (bcl + bcr) * 0.5
                lo += center - bcl
                ro += center - bcr
                val dn = ((0.004 + 0.011 * field) * fs).toInt().coerceIn(1, fieldDelayMax - 1)
                val dl = fieldDelayL[(fieldIdxL + fieldDelayMax - dn) % fieldDelayMax]
                val dr = fieldDelayR[(fieldIdxR + fieldDelayMax - dn) % fieldDelayMax]
                fieldDelayL[fieldIdxL] = lo
                fieldDelayR[fieldIdxR] = ro
                fieldIdxL = (fieldIdxL + 1) % fieldDelayMax
                fieldIdxR = (fieldIdxR + 1) % fieldDelayMax
                lo += dr * 0.30 * fk
                ro += dl * 0.30 * fk
            }
            val res = doubleArrayOf(lo, ro)
            if (compressorEnabled) {
                compressStereo(lo, ro, p.compression, res, compLp, compHp, compLpR, compHpR, compEnv, compSm)
            }
            // bass tightening (speaker-like)
            if (bassTightEnabled) {
                val bL = bassTightLp.process(res[0])
                val bR = bassTightRp.process(res[1])
                val a = max(abs(bL), abs(bR))
                bassTightEnv = if (a > bassTightEnv) bassTightEnv * 0.9994 + 0.0006 * a else bassTightEnv * 0.9998 + 0.0002 * a
                val threshold = 0.08
                if (bassTightEnv > threshold) {
                    val over = bassTightEnv / threshold
                    val gr = 1.0 / (1.0 + (over - 1.0) * 5.0)
                    bassTightGain += (gr - bassTightGain) * 0.001
                } else {
                    bassTightGain += (1.0 - bassTightGain) * 0.0005
                }
                res[0] = res[0] + bL * (bassTightGain - 1.0)
                res[1] = res[1] + bR * (bassTightGain - 1.0)
            }
            if (p.loudnessComp) {
                res[0] = loudHpL.process(loudLpL.process(res[0]))
                res[1] = loudHpR.process(loudLpR.process(res[1]))
            }
            if (p.speechClarity) {
                res[0] = scL.process(res[0])
                res[1] = scR.process(res[1])
            }
            if (p.surfaceResonance) {
                res[0] = surfResoL.process(res[0])
                res[1] = surfResoR.process(res[1])
            }
            val lat = dryDelayL.size
            var dryL = res[0]
            var dryR = res[1]
            if (dryFill >= lat) {
                dryL = dryDelayL[dryIdxL]
                dryR = dryDelayR[dryIdxR]
            } else {
                dryFill++
            }
            dryDelayL[dryIdxL] = res[0]
            dryDelayR[dryIdxR] = res[1]
            dryIdxL = (dryIdxL + 1) % lat
            dryIdxR = (dryIdxR + 1) % lat
            if (irEnabled && p.irMix > 0f) {
                res[0] = dryL + p.irMix.toDouble() * convL.process(res[0])
                res[1] = dryR + p.irMix.toDouble() * convR.process(res[1])
            }
            val pl = if (limiterEnabled) {
                limStereo.setThreshold((0.90 / p.masterGain).coerceIn(0.5, 0.97))
                limStereo.process(res[0] * masterGain, res[1] * masterGain)
            } else {
                (res[0] * masterGain) to (res[1] * masterGain)
            }
            return pl
        }

        private fun compressStereo(
            l: Double, r: Double, strength: Float, res: DoubleArray,
            lp: BiquadFilter, hp: BiquadFilter, lpr: BiquadFilter, hpr: BiquadFilter,
            env: DoubleArray, sm: DoubleArray
        ) {
            val ll = lp.process(l)
            val lh = hp.process(l)
            val lm = l - ll - lh
            val rl = lpr.process(r)
            val rh = hpr.process(r)
            val rm = r - rl - rh
            var ol = 0.0
            var or = 0.0
            val attack = Math.exp(-1.0 / (0.030 * fs))
            val release = Math.exp(-1.0 / (0.250 * fs))
            val smooth = Math.exp(-1.0 / (0.025 * fs))
            for (i in 0 until 3) {
                val bl = if (i == 0) ll else if (i == 1) lm else lh
                val br = if (i == 0) rl else if (i == 1) rm else rh
                val a = max(abs(bl), abs(br))
                env[i] = if (a > env[i]) env[i] * attack + (1 - attack) * a else env[i] * release + (1 - release) * a
                val g = softKneeGain(env[i], strength)
                sm[i] = sm[i] * smooth + (1 - smooth) * g
                ol += bl * sm[i]
                or += br * sm[i]
            }
            res[0] = ol
            res[1] = or
        }
    }

    private fun effectiveAnime(): AudioEnhanceConfig.Params =
        AudioEnhanceConfig.applyDeviceTuning(AudioEnhanceConfig.Params().withPreset(Preset.ANIME), DeviceKind.PHONE_SPEAKER)

    private fun effParams(preset: Preset): AudioEnhanceConfig.Params =
        AudioEnhanceConfig.applyDeviceTuning(AudioEnhanceConfig.Params().withPreset(preset), DeviceKind.PHONE_SPEAKER)

    /** THD a una frecuencia dada usando DFT en bins exactos (ventana con número entero de ciclos). */
    private fun thd(samples: DoubleArray, fs: Int, f0: Double): Double {
        val n = samples.size
        val cycles = n * f0 / fs
        assertEquals(0.0, cycles - Math.rint(cycles), 1e-6)
        fun dft(f: Double): Pair<Double, Double> {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val w = 2.0 * PI * f * i / fs
                re += samples[i] * cos(w)
                im -= samples[i] * sin(w)
            }
            return re to im
        }
        val (fr, fi) = dft(f0)
        val fund = sqrt(fr * fr + fi * fi) * 2.0 / n
        var harmSum = 0.0
        for (k in 2..12) {
            val (hr, hi) = dft(f0 * k)
            val h = sqrt(hr * hr + hi * hi) * 2.0 / n
            harmSum += h * h
        }
        return sqrt(harmSum) / fund
    }

    private fun assertNoNaN(vararg vals: Double) {
        for (v in vals) assertTrue("NaN/inf detectado: $v", v.isFinite())
    }

    @Test
    fun animePresetIsClean() {
        val p = effectiveAnime()
        val chain = Chain(fs)
        chain.configure(p, 1.0f)
        val f0 = 441.0
        val n = fs * 2
        val out = DoubleArray(n)
        var peak = 0.0
        for (i in 0 until n) {
            val s = 0.4 * sin(2.0 * PI * f0 * i / fs)
            val (l, r) = chain.process(s, s, p)
            assertNoNaN(l, r)
            out[i] = (l + r) * 0.5
            peak = max(peak, abs(l))
        }
        // Sin clipping duro
        assertTrue("pico=$peak debe ser < 0.98", peak < 0.98)
        // THD bajo en el último segundo (estado estable, sin transient de arranque)
        val steady = out.copyOfRange(fs, n)
        val t = thd(steady, fs, f0)
        assertTrue("THD del preset Anime demasiado alto: $t", t < 0.02)
    }

    @Test
    fun cleanChainHasNegligibleThd() {
        // Cadena con todo desactivado: solo EQ neutro + compresor + limiter.
        // Si esta métrica es alta, el bug es de las etapas base, no de los "efectos".
        val p = AudioEnhanceConfig.Params().withPreset(Preset.ANIME).copy(
            enabled = true,
            loudnessComp = false,
            speechClarity = false,
            surfaceResonance = false,
            harmonicBass = 0f,
            subharmonicMix = 0f,
            exciterAmount = 0f,
            reverbMix = 0f,
            tubeDrive = 0f,
            deBoxing = 0f,
            fieldSurround = 0f,
            compression = 0f,
            irType = IrPreset.NONE,
            irMix = 0f,
            masterGain = 1.0f
        )
        val chain = Chain(fs)
        chain.configure(p, 1.0f)
        val f0 = 441.0
        val n = fs * 2
        val out = DoubleArray(n)
        var peak = 0.0
        for (i in 0 until n) {
            val s = 0.4 * sin(2.0 * PI * f0 * i / fs)
            val (l, r) = chain.process(s, s, p)
            assertNoNaN(l, r)
            out[i] = (l + r) * 0.5
            peak = max(peak, abs(l))
        }
        assertTrue("pico=$peak < 0.98", peak < 0.98)
        val steady = out.copyOfRange(fs, n)
        val t = thd(steady, fs, f0)
        assertTrue("THD de la cadena base demasiado alto (bug real): $t", t < 0.005)
    }

    @Test
    fun diagnoseBassThdStages() {
        val clean = AudioEnhanceConfig.Params().withPreset(Preset.ANIME).copy(
            loudnessComp = false, speechClarity = false, surfaceResonance = false,
            harmonicBass = 0f, subharmonicMix = 0f, exciterAmount = 0f, reverbMix = 0f,
            tubeDrive = 0f, deBoxing = 0f, fieldSurround = 0f, compression = 0f,
            irType = IrPreset.NONE, irMix = 0f, masterGain = 1.0f
        )
        val variants = listOf(
            "clean" to clean,
            "harmonicBass=.2" to clean.copy(harmonicBass = 0.2f),
            "harmonicBass=.1" to clean.copy(harmonicBass = 0.1f),
            "subharmonic=.1" to clean.copy(subharmonicMix = 0.1f),
            "exciter=.05" to clean.copy(exciterAmount = 0.05f),
            "ir=SPEAKER_CAB/.12" to clean.copy(irType = IrPreset.SPEAKER_CAB, irMix = 0.12f),
            "compression=.3" to clean.copy(compression = 0.3f),
            "deBoxing=.2" to clean.copy(deBoxing = 0.2f),
            "loudness+5/3" to clean.copy(loudnessComp = true),
            "speech" to clean.copy(speechClarity = true),
            "surface" to clean.copy(surfaceResonance = true),
            "field=.08" to clean.copy(fieldSurround = 0.08f)
        )
        val f0 = 55.0
        for ((name, p) in variants) {
            val chain = Chain(fs)
            chain.configure(p, 1.0f)
            val (t, peak) = measure(p, chain, f0, 0.5)
            println("BASS-DIAG $name THD=%.4f peak=%.4f".format(t, peak))
        }
        val p = effectiveAnime()
        println("BASS-DIAG effective anime hb=%.2f comp=%.2f ir=%.2f sub=%.2f".format(
            p.harmonicBass, p.compression, p.irMix, p.subharmonicMix))
        val chain = Chain(fs)
        chain.configure(p, 1.0f)
        val (tFull, peakFull) = measure(p, chain, f0, 0.5)
        println("BASS-DIAG anime-full THD=%.4f peak=%.4f".format(tFull, peakFull))
        val chainNoBt = Chain(fs)
        chainNoBt.configure(p, 1.0f)
        chainNoBt.bassTightEnabled = false
        val (tNoBtFull, _) = measure(p, chainNoBt, f0, 0.5)
        println("BASS-DIAG anime-full-NO-bassTight THD=%.4f".format(tNoBtFull))
        val (t100, peak100) = measure(p, Chain(fs).also { it.configure(p, 1.0f) }, 100.0, 0.5)
        println("BASS-DIAG anime-full@100Hz THD=%.4f peak=%.4f".format(t100, peak100))
        // A volumen bajo (sonoridad activa), donde el usuario oye el bajo distorsionado
        val chain3 = Chain(fs)
        chain3.configure(p, 0.2f)
        for ((f3, name) in listOf(55.0 to "55Hz", 100.0 to "100Hz")) {
            val (t, pk) = measure(p, chain3, f3, 0.5)
            println("BASS-DIAG anime-full vol0.2@%s THD=%.4f peak=%.4f".format(name, t, pk))
        }
        // Niveles realistas de bajo (0.2-0.3): la música real no tiene bajo a -6dBFS sostenido
        for (amp in listOf(0.3, 0.2)) {
            val (t, pk) = measure(p, Chain(fs).also { it.configure(p, 1.0f) }, f0, amp)
            println("BASS-DIAG anime-full@55Hz amp=%.1f THD=%.4f peak=%.4f".format(amp, t, pk))
        }
    }

    /** Mide THD en estado estable: 2 s de seno, THD sobre el último segundo. */
    private fun measure(
        p: AudioEnhanceConfig.Params, chain: Chain, f: Double, amp: Double
    ): Pair<Double, Double> {
        val n = fs * 2
        val out = DoubleArray(n)
        var peak = 0.0
        for (i in 0 until n) {
            val s = amp * sin(2.0 * PI * f * i / fs)
            val (l, r) = chain.process(s, s, p)
            out[i] = (l + r) * 0.5
            peak = max(peak, abs(l))
        }
        val steady = out.copyOfRange(fs, n)
        return thd(steady, fs, f) to peak
    }

    @Test
    fun diagnoseAllPresets() {
        val presets = listOf(
            Preset.ANIME, Preset.CINEMA, Preset.BASS_BOOST, Preset.SURROUND,
            Preset.DIALOGUE, Preset.MUSIC, Preset.SPEAKER
        )
        val devices = listOf(DeviceKind.PHONE_SPEAKER, DeviceKind.TV_SPEAKER, DeviceKind.HEADPHONES)
        for (device in devices) {
            for (preset in presets) {
                val p = AudioEnhanceConfig.applyDeviceTuning(AudioEnhanceConfig.Params().withPreset(preset), device)
                println("PRESET %s/%s eff hb=%.2f comp=%.2f ir=%s irMix=%.2f sub=%.2f field=%.2f master=%.2f".format(
                    device, preset, p.harmonicBass, p.compression, p.irType, p.irMix, p.subharmonicMix, p.fieldSurround, p.masterGain))
            for ((f, amp) in listOf(55.0 to 0.5, 100.0 to 0.5, 441.0 to 0.4, 2000.0 to 0.3)) {
                val chain = Chain(fs)
                chain.configure(p, 1.0f)
                val (t, peak) = measure(p, chain, f, amp)
                println("PRESET %s/%s @%3.0fHz THD=%.4f peak=%.4f".format(device, preset, f, t, peak))
            }
        }
    }
    }

    @Test
    fun dialoguePresetIsClean() {
        val p = effParams(Preset.DIALOGUE)
        val chain = Chain(fs)
        chain.configure(p, 1.0f)
        val f0 = 441.0
        val n = fs * 2
        val out = DoubleArray(n)
        var peak = 0.0
        for (i in 0 until n) {
            val s = 0.4 * sin(2.0 * PI * f0 * i / fs)
            val (l, r) = chain.process(s, s, p)
            assertNoNaN(l, r)
            out[i] = (l + r) * 0.5
            peak = max(peak, abs(l))
        }
        assertTrue("pico=$peak < 0.98", peak < 0.98)
        val steady = out.copyOfRange(fs, n)
        val t = thd(steady, fs, f0)
        assertTrue("THD del preset Diálogos demasiado alto: $t", t < 0.02)
    }

    @Test
    fun noClippingOnTransientBurst() {
        val p = effectiveAnime()
        val chain = Chain(fs)
        chain.configure(p, 1.0f)
        // Burst abrupto de silencio a full-scale: el limiter NO debe dejar pasar >1.0
        var peak = 0.0
        var nan = false
        for (i in 0 until 1200) {
            val s = if (i > 300) 0.95 else 0.0
            val (l, r) = chain.process(s, s, p)
            if (!l.isFinite() || !r.isFinite()) nan = true
            peak = max(peak, max(abs(l), abs(r)))
        }
        assertTrue("NaN en burst", !nan)
        assertTrue("overshoot=$peak debe ser < 1.0 (sin clip)", peak < 1.0)
    }

    @Test
    fun loudnessAtLowVolumeNoClip() {
        val p = effectiveAnime()
        val chain = Chain(fs)
        chain.configure(p, 0.15f)
        val f0 = 441.0
        var peak = 0.0
        for (i in 0 until fs) {
            val s = 0.9 * sin(2.0 * PI * f0 * i / fs)
            val (l, r) = chain.process(s, s, p)
            assertNoNaN(l, r)
            peak = max(peak, abs(l))
        }
        // La compensación de sonoridad (+5dB bass/+3dB treble) no debe saturar
        assertTrue("pico a volumen bajo=$peak < 0.98", peak < 0.98)
    }

    @Test
    fun subharmonicIsBoundedAndTracks() {
        val sub = SubharmonicSynth(fs)
        sub.setMix(0.5f)
        // Señal con fundamental de graves fuerte (55 Hz) para que la detección funcione
        var peak = 0.0
        for (i in 0 until fs * 2) {
            val s = 0.8 * sin(2.0 * PI * 55.0 * i / fs) + 0.2 * sin(2.0 * PI * 220.0 * i / fs)
            val o = sub.process(s)
            assertNoNaN(o)
            peak = max(peak, abs(o))
        }
        // Subarmónico + señal: acotado, sin disparar
        assertTrue("sub pico=$peak < 1.5", peak < 1.5)
        // En silencio NO debe generar tono (gate): tras 1s de silencio, salida ~0
        val sub2 = SubharmonicSynth(fs)
        sub2.setMix(0.5f)
        var silencePeak = 0.0
        for (i in 0 until fs) {
            val o = sub2.process(0.0)
            silencePeak = max(silencePeak, abs(o))
        }
        assertTrue("sub en silencio genera tono: $silencePeak", silencePeak < 1e-4)
    }

    @Test
    fun limiterCatchesPeaksWithoutOvershoot() {
        val lim = LookaheadLimiterPair()
        lim.configure(fs, 2f, 100f, 0.9)
        var peak = 0.0
        for (i in 0 until 1000) {
            val s = if (i > 200) 1.2 else 0.2
            val (l, r) = lim.process(s, s)
            peak = max(peak, max(abs(l), abs(r)))
        }
        // Con lookahead de 2ms la salida debe quedarse muy cerca del umbral
        assertTrue("limiter overshoot=$peak", peak < 1.0)
    }

    /** Pico absoluto por periodo (el periodo debe tener muestras enteras). */
    private fun periodPeakStats(x: DoubleArray, period: Int, warmup: Int): Triple<Double, Double, Double> {
        var mean = 0.0
        var min = Double.MAX_VALUE
        var max = 0.0
        var cnt = 0
        var i = warmup
        while (i + period <= x.size) {
            var pk = 0.0
            for (j in i until i + period) pk = max(pk, abs(x[j]))
            mean += pk
            min = minOf(min, pk)
            max = maxOf(max, pk)
            cnt++
            i += period
        }
        return Triple(mean / cnt, min, max)
    }

    @Test
    fun diagnoseSubGateStability() {
        val signals = listOf(
            "55Hz" to { i: Int -> 0.4 * sin(2.0 * PI * 55.0 * i / fs) },
            "55+110+220" to { i: Int ->
                0.3 * sin(2.0 * PI * 55.0 * i / fs) + 0.2 * sin(2.0 * PI * 110.0 * i / fs) + 0.15 * sin(2.0 * PI * 220.0 * i / fs)
            },
            "kick-AM" to { i: Int ->
                val beat = (i % fs).toDouble() / fs
                val env = if (beat < 0.4) 1.0 else 0.05
                env * 0.5 * sin(2.0 * PI * 55.0 * i / fs)
            }
        )
        for ((name, sig) in signals) {
            val sub = SubharmonicSynth(fs)
            sub.setMix(0.5f)
            val n = fs * 4
            val out = DoubleArray(n)
            for (i in 0 until n) {
                val s = sig(i)
                out[i] = sub.process(s) - s
            }
            val (mean, mn, mx) = periodPeakStats(out, 441, fs / 2)
            println("SUB-GATE %s mean=%.4f ratio=%.2f (min=%.4f max=%.4f)".format(name, mean, if (mn > 1e-6) mx / mn else 99.0, mn, mx))
        }
        // Cambio de tono: el sub debe seguir sin "wobble" de frecuencia/ganancia
        val sub2 = SubharmonicSynth(fs)
        sub2.setMix(0.5f)
        val n2 = fs * 4
        val out2 = DoubleArray(n2)
        for (i in 0 until n2) {
            val s = when {
                i < fs -> 0.4 * sin(2.0 * PI * 55.0 * i / fs)
                i < 2 * fs -> 0.4 * sin(2.0 * PI * 82.0 * i / fs)
                else -> 0.4 * sin(2.0 * PI * 41.0 * i / fs)
            }
            out2[i] = sub2.process(s) - s
        }
        val (m2, mn2, mx2) = periodPeakStats(out2, 441, fs / 2)
        println("SUB-GATE pitch-change ratio=%.2f (min=%.4f max=%.4f)".format(if (mn2 > 1e-6) mx2 / mn2 else 99.0, mn2, mx2))
    }

    @Test
    fun diagnoseChainPumping() {
        val presets = listOf(Preset.ANIME, Preset.SPEAKER, Preset.BASS_BOOST, Preset.CINEMA, Preset.SURROUND, Preset.DIALOGUE)
        val devices = listOf(DeviceKind.TV_SPEAKER, DeviceKind.PHONE_SPEAKER, DeviceKind.HEADPHONES)
        val f0 = 100.0
        val period = (fs / f0).toInt()
        for (device in devices) {
            for (preset in presets) {
                val p = AudioEnhanceConfig.applyDeviceTuning(AudioEnhanceConfig.Params().withPreset(preset), device)
                val chain = Chain(fs)
                chain.configure(p, 1.0f)
                val n = fs * 5
                val out = DoubleArray(n)
                for (i in 0 until n) {
                    val s = 0.4 * sin(2.0 * PI * f0 * i / fs)
                    val (l, r) = chain.process(s, s, p)
                    out[i] = (l + r) * 0.5
                }
                val (_, mn, mx) = periodPeakStats(out, period, fs * 2)
                println("PUMP %s/%s@100Hz ratio=%.2f (min=%.4f max=%.4f)".format(device, preset, if (mn > 1e-6) mx / mn else 99.0, mn, mx))
            }
        }
        // Atribución: SPEAKER, apagar etapas de a una
        val base = AudioEnhanceConfig.applyDeviceTuning(AudioEnhanceConfig.Params().withPreset(Preset.SPEAKER), DeviceKind.TV_SPEAKER)
        val variants = listOf(
            "full" to { _: Chain -> },
            "sub-off" to { c: Chain -> c.subEnabled = false },
            "comp-off" to { c: Chain -> c.compressorEnabled = false },
            "lim-off" to { c: Chain -> c.limiterEnabled = false },
            "ir-off" to { c: Chain -> c.irEnabled = false },
            "bt-off" to { c: Chain -> c.bassTightEnabled = false }
        )
        for ((name, apply) in variants) {
            val chain = Chain(fs)
            chain.configure(base, 1.0f)
            apply(chain)
            val n = fs * 5
            val out = DoubleArray(n)
            for (i in 0 until n) {
                val s = 0.4 * sin(2.0 * PI * f0 * i / fs)
                val (l, r) = chain.process(s, s, base)
                out[i] = (l + r) * 0.5
            }
            val (_, mn, mx) = periodPeakStats(out, period, fs * 2)
            println("PUMP-ATTR TV/SPEAKER %s ratio=%.2f (min=%.4f max=%.4f)".format(name, if (mn > 1e-6) mx / mn else 99.0, mn, mx))
        }
    }

    /** Mide el "bombeo" real: tono de medios constante (200 Hz) + patadas de graves (55 Hz). */
    @Test
    fun diagnoseKickPumping() {
        val fMid = 200.0
        val fKick = 55.0
        val period = (fs / fMid).toInt()
        val hpMid = BiquadFilter().apply { configure(BiquadFilter.Kind.HIGHPASS, fs, 150f, 0f, 0.707f) }
        val lpMid = BiquadFilter().apply { configure(BiquadFilter.Kind.LOWPASS, fs, 260f, 0f, 0.707f) }
        val kickLen = fs / 2          // 0.5 s con patada
        val gapLen = fs / 2           // 0.5 s sin patada
        val presets = listOf(Preset.ANIME, Preset.SPEAKER, Preset.BASS_BOOST, Preset.CINEMA, Preset.DIALOGUE, Preset.MUSIC)
        for (device in listOf(DeviceKind.TV_SPEAKER, DeviceKind.HEADPHONES)) {
            for (preset in presets) {
                val p = AudioEnhanceConfig.applyDeviceTuning(AudioEnhanceConfig.Params().withPreset(preset), device)
                val chain = Chain(fs)
                chain.configure(p, 1.0f)
                val n = fs * 6
                val out = DoubleArray(n)
                val kickOn = BooleanArray(n)
                var phase = 0
                for (i in 0 until n) {
                    val mid = 0.12 * sin(2.0 * PI * fMid * i / fs)
                    val on = (i % (kickLen + gapLen)) < kickLen
                    kickOn[i] = on
                    val kick = if (on) 0.45 * sin(2.0 * PI * fKick * i / fs) else 0.0
                    val (l, r) = chain.process(mid + kick, mid + kick, p)
                    out[i] = (l + r) * 0.5
                }
                // Nivel de la banda de medios (150-260 Hz) en ventanas con/sin patada
                var kickRms = 0.0
                var noKickRms = 0.0
                var kc = 0
                var nc = 0
                var i = fs
                while (i + period <= n) {
                    var e = 0.0
                    for (j in i until i + period) {
                        val v = lpMid.process(hpMid.process(out[j]))
                        e += v * v
                    }
                    e = sqrt(e / period)
                    if (kickOn[i]) { kickRms += e; kc++ } else { noKickRms += e; nc++ }
                    i += period
                }
                kickRms /= kc
                noKickRms /= nc
                // Bombeo = cuánto cae el medio cuando hay graves. >1.0 = ducking audible
                val pump = if (noKickRms > 1e-6) kickRms / noKickRms else 99.0
                println("KICK %s/%s mid-during-kick=%.4f mid-no-kick=%.4f pump=%.2f".format(device, preset, kickRms, noKickRms, pump))
            }
        }
        // Atribución en el peor caso (ANIME): apagar etapas de a una
        val attr = listOf(
            "full" to { _: Chain -> },
            "sub-off" to { c: Chain -> c.subEnabled = false },
            "comp-off" to { c: Chain -> c.compressorEnabled = false },
            "lim-off" to { c: Chain -> c.limiterEnabled = false },
            "ir-off" to { c: Chain -> c.irEnabled = false },
            "bt-off" to { c: Chain -> c.bassTightEnabled = false }
        )
        for (device in listOf(DeviceKind.TV_SPEAKER, DeviceKind.HEADPHONES)) {
            val p = AudioEnhanceConfig.applyDeviceTuning(AudioEnhanceConfig.Params().withPreset(Preset.ANIME), device)
            for ((name, apply) in attr) {
                val chain = Chain(fs)
                chain.configure(p, 1.0f)
                apply(chain)
                val n = fs * 6
                val out = DoubleArray(n)
                val kickOn = BooleanArray(n)
                for (i in 0 until n) {
                    val mid = 0.12 * sin(2.0 * PI * fMid * i / fs)
                    val on = (i % (kickLen + gapLen)) < kickLen
                    kickOn[i] = on
                    val kick = if (on) 0.45 * sin(2.0 * PI * fKick * i / fs) else 0.0
                    val (l, r) = chain.process(mid + kick, mid + kick, p)
                    out[i] = (l + r) * 0.5
                }
                var kickRms = 0.0
                var noKickRms = 0.0
                var kc = 0
                var nc = 0
                var i = fs
                while (i + period <= n) {
                    var e = 0.0
                    for (j in i until i + period) {
                        val v = lpMid.process(hpMid.process(out[j]))
                        e += v * v
                    }
                    e = sqrt(e / period)
                    if (kickOn[i]) { kickRms += e; kc++ } else { noKickRms += e; nc++ }
                    i += period
                }
                kickRms /= kc
                noKickRms /= nc
                val pump = if (noKickRms > 1e-6) kickRms / noKickRms else 99.0
                println("KICK-ATTR %s/ANIME %s pump=%.2f (during=%.4f no-kick=%.4f)".format(device, name, pump, kickRms, noKickRms))
            }
        }
    }
}

private fun gainsFor(p: AudioEnhanceConfig.Params): FloatArray {
    val g = AudioEnhanceConfig.deriveEq10(p).copyOf()
    // Compensación de sonoridad por EQ solo cuando loudnessComp está apagado
    if (!p.loudnessComp) {
        val loud = 0f
        g[2] += 6f * loud
        g[3] += 4f * loud
        g[8] += 4f * loud
        g[9] += 2f * loud
    }
    return g
}
