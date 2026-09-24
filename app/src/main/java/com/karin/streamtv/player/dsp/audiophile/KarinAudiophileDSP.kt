package com.karin.streamtv.player.dsp.audiophile

/**
 * Karin Audiophile DSP — Experimental.
 *
 * Pipeline modular (cada etapa con toggle):
 *   INPUT → Analysis → Headroom/Preamp → Correction (EQ) →
 *   Loudness → Dynamics → Optional (Bass/Transient/Harmonic) →
 *   Optional Spatial (Crossfeed) → Output Protection → OUTPUT
 *
 * Filosofía: transparencia, headroom, protección true-peak, medición.
 * NO reemplaza al DSP actual: solo se activa cuando AudioEngine = AUDIOPHILE.
 * Precisión interna Double; sin alloc por muestra; sin IA/ML.
 *
 * La exclusión mutua con el DSP actual la garantiza el dispatcher en
 * AudioEnhanceProcessor.queueInput (un solo punto de decisión).
 */
class KarinAudiophileDSP {

    private val eq = AudiophileEQ()
    private val loudness = AudiophileLoudness()
    private val dynamics = AudiophileDynamics()
    private val bass = AudiophileBassExtension()
    private val trueBass = AudiophileTrueBass()
    private val transient = AudiophileTransient()
    private val harmonic = AudiophileHarmonic()
    private val speaker = AudiophileSpeaker()
    private val crossfeed = AudiophileCrossfeed()
    private val output = AudiophileOutput()
    private val analyzer = AudiophileAnalyzer()

    private var fs = 48000
    private var channels = 2
    private var lastParams: AudiophileConfig.Params? = null

    // Crossfade de engage/disengage y A/B (evita clicks al alternar motor).
    // wetMix 0 = dry (original), 1 = procesado.
    private var wetMix = 0f
    private var wetTarget = 1f
    private var fadeStep = 1f / (0.015f * 48000f) // ~15 ms

    // A/B: cuando bypass está activo, target = 0 (dry).
    private var abBypass = false

    private val eqOut = DoubleArray(2)
    private val dynOut = DoubleArray(2)
    private val bassOut = DoubleArray(2)
    private val trueBassOut = DoubleArray(2)
    private val transOut = DoubleArray(2)
    private val harmOut = DoubleArray(2)
    private val spkOut = DoubleArray(2)
    private val xfOut = DoubleArray(2)
    private val outBuf = DoubleArray(2)

    private var headroomGain = 1.0
    private var monitorCounter = 0

    @Volatile
    var engaged = false
        private set

    fun configure(sampleRate: Int, channelCount: Int, params: AudiophileConfig.Params) {
        fs = sampleRate.coerceAtLeast(8000)
        channels = channelCount.coerceAtLeast(1)
        fadeStep = 1f / (0.015f * fs)
        applyParams(params)
        analyzer.configure(fs)
        lastParams = params
    }

    private fun applyParams(p: AudiophileConfig.Params) {
        val hr = AudiophileHeadroom.compute(p)
        headroomGain = AudiophileHeadroom.preampGain(hr)
        eq.configure(fs, p.bands)
        loudness.configure(fs, p.loudnessEnabled, p.loudnessTargetLufs)
        dynamics.configure(fs, p.dynamicsEnabled)
        bass.configure(fs, p.bassExtEnabled, p.bassFreqHz, p.bassAmount, p.bassHarmonicMix)
        trueBass.configure(fs, p.trueBassEnabled, p.trueBassLevel)
        transient.configure(
            fs, p.transientEnabled, p.transientAmount,
            p.transientAttack, p.transientRelease
        )
        harmonic.configure(
            fs, p.harmonicEnabled, p.harmonicFreqHz,
            p.harmonicAmount, p.harmonicDrive, p.harmonicMix
        )
        speaker.configure(fs, p.speakerMode)
        crossfeed.configure(fs, p.crossfeed)
        output.configure(fs, p.truePeakCeilingDb, p.outputGainDb)
    }

    /** Llamar si cambian params/preset/A-B desde la UI. */
    fun updateIfChanged(params: AudiophileConfig.Params, abBypassNow: Boolean) {
        if (params != lastParams) {
            applyParams(params)
            lastParams = params
        }
        if (abBypassNow != abBypass) {
            abBypass = abBypassNow
            wetTarget = if (abBypass) 0f else 1f
        }
    }

    /** Activa/desactiva el engage crossfade desde el dispatcher del motor. */
    fun setEngaged(on: Boolean) {
        if (engaged == on) return
        engaged = on
        wetTarget = if (on && !abBypass) 1f else 0f
        if (on) wetMix = 0f // entra desde dry → sube suave (sin click)
    }

    /**
     * Procesa un frame estéreo. `out` recibe [L, R] ya protegidos.
     * Mono: L se duplica a R (el caller puede pasar lo mismo).
     * No alloc; todos los estados son campos reutilizados.
     */
    fun processFrame(lIn: Double, rIn: Double, out: DoubleArray) {
        // Análisis de entrada (solo medición).
        analyzer.tapInput(lIn, rIn)

        // ── Headroom / Preamp ──
        var l = lIn * headroomGain
        var r = rIn * headroomGain

        // ── Correction (EQ paramétrica) ──
        eq.process(l, r, eqOut)
        l = eqOut[0]; r = eqOut[1]

        // ── Loudness (corrección suave hacia objetivo) ──
        val lg = loudness.processGain(l, r)
        l *= lg; r *= lg

        // ── Dynamics (opcional) ──
        dynamics.process(l, r, dynOut)
        l = dynOut[0]; r = dynOut[1]

        // ── Optional Enhancement ──
        bass.process(l, r, bassOut)
        l = bassOut[0]; r = bassOut[1]
        trueBass.process(l, r, trueBassOut)
        l = trueBassOut[0]; r = trueBassOut[1]
        transient.process(l, r, transOut)
        l = transOut[0]; r = transOut[1]
        harmonic.process(l, r, harmOut)
        l = harmOut[0]; r = harmOut[1]

        // ── Optional Speaker voicing (TV/bocina pequeña) ──
        speaker.process(l, r, spkOut)
        l = spkOut[0]; r = spkOut[1]

        // ── Optional Spatial (crossfeed) ──
        crossfeed.process(l, r, xfOut)
        l = xfOut[0]; r = xfOut[1]

        // ── Output Protection ──
        output.process(l, r, outBuf)

        // ── Crossfade dry ↔ wet (A/B y engine engage) ──
        // Avanza la mezcla de forma lineal (~15 ms) para evitar clicks.
        if (wetMix < wetTarget) {
            wetMix = (wetMix + fadeStep).coerceAtMost(wetTarget)
        } else if (wetMix > wetTarget) {
            wetMix = (wetMix - fadeStep).coerceAtLeast(wetTarget)
        }
        val w = wetMix.toDouble()
        val d = 1.0 - w
        // dry = señal de entrada (sin preamp/EQ): nivel de referencia para A/B.
        out[0] = lIn * d + outBuf[0] * w
        out[1] = rIn * d + outBuf[1] * w

        // Sanidad final: nunca emitir NaN/Inf al DAC.
        if (!out[0].isFinite()) out[0] = 0.0
        if (!out[1].isFinite()) out[1] = 0.0

        // Publicar métricas ~100 veces/s (cada N frames).
        monitorCounter++
        if (monitorCounter >= (fs / 100).coerceAtLeast(64)) {
            monitorCounter = 0
            publishMetrics()
        }
    }

    private fun publishMetrics() {
        val m = analyzer.metrics(output, loudness, dynamics)
        AudiophileMetrics.current = AudiophileMetrics(
            inputPeakDb = m.inputPeakDb,
            outputPeakDb = m.outputPeakDb,
            truePeakDbtp = m.truePeakDbtp,
            lufs = m.lufs,
            dynamicRangeDb = m.dynamicRangeDb,
            correlation = m.correlation,
            cpuPercent = m.cpuPercent
        )
    }

    fun reportBlockTime(ns: Long, frames: Int) {
        analyzer.reportBlockTime(ns, frames, fs)
    }

    fun isFading(): Boolean = wetMix != wetTarget

    fun reset() {
        eq.reset()
        loudness.reset()
        dynamics.reset()
        bass.reset()
        trueBass.reset()
        transient.reset()
        harmonic.reset()
        speaker.reset()
        crossfeed.reset()
        output.reset()
        analyzer.reset()
        monitorCounter = 0
        // wetMix se conserva para no perder el estado de engage entre flush
        // (seek no debe re-arrancar en 0 si ya estaba activo).
    }

    /** Full reset de crossfade (solo al cambiar de motor de forma dura). */
    fun resetFade() {
        wetMix = 0f
        wetTarget = if (engaged && !abBypass) 1f else 0f
    }
}
