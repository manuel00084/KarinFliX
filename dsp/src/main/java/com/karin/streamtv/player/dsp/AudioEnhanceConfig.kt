package com.karin.streamtv.player.dsp

import android.content.Context
import android.content.SharedPreferences

object AudioEnhanceConfig {

    enum class Preset(val label: String) {
        OFF("Apagado (sin DSP)"),
        ANIME("Anime"),
        CINEMA("Cine/Películas"),
        BASS_BOOST("Bass Boost"),
        SURROUND("3D Surround"),
        DIALOGUE("Diálogos/Noticias"),
        MUSIC("Música")
    }

    data class Params(
        val preset: Preset = Preset.ANIME,
        val enabled: Boolean = true,
        val bassGain: Float = 0f,      // dB, -12..+12
        val trebleGain: Float = 0f,    // dB, -12..+12
        val subBassGain: Float = 0f,   // dB, -12..+12 (20-60Hz)
        val presenceGain: Float = 0f,  // dB, -12..+12 (2-6kHz)
        val surroundWidth: Float = 0f, // 0..1.5
        val exciterAmount: Float = 0f, // 0..1.0 (armónicos agudos)
        val harmonicBass: Float = 0f,  // 0..1.0 (saturation graves)
        val compression: Float = 0f,   // 0..1.0 (dynamic range)
        val reverbMix: Float = 0f,     // 0..0.5
        val masterGain: Float = 1.0f   // 0.5..2.0
    ) {
        fun withPreset(p: Preset): Params = when (p) {
            Preset.OFF -> Params(Preset.OFF, false, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1.0f)
            Preset.ANIME -> Params(
                Preset.ANIME, true,
                bassGain = +3.5f,      // impacto en openings/peleas
                trebleGain = +2.0f,    // brillo en voces FX
                subBassGain = +2.0f,   // rumble bajo
                presenceGain = +3.0f,  // claridad voces japón
                surroundWidth = 0.35f, // espacialidad moderada
                exciterAmount = 0.25f, // aire en agudos
                harmonicBass = 0.35f,  // cuerpo en graves
                compression = 0.45f,   // normaliza gritos/susurros
                reverbMix = 0.08f,     // ambiente sutil
                masterGain = 1.15f
            )
            Preset.CINEMA -> Params(
                Preset.CINEMA, true,
                bassGain = +2.0f,      // LFE películas
                trebleGain = +1.0f,
                subBassGain = +4.0f,   // subwoofer feel
                presenceGain = +1.5f,  // diálogos claros
                surroundWidth = 0.6f,  // ancho cinematográfico
                exciterAmount = 0.1f,
                harmonicBass = 0.2f,
                compression = 0.6f,    // rango dinámico cine
                reverbMix = 0.15f,     // sala de cine
                masterGain = 1.05f
            )
            Preset.BASS_BOOST -> Params(
                Preset.BASS_BOOST, true,
                bassGain = +5.0f,
                trebleGain = -1.0f,
                subBassGain = +4.0f,   // sub-bass potente pero sin recortar
                presenceGain = -1.0f,
                surroundWidth = 0.1f,
                exciterAmount = 0.05f,
                harmonicBass = 0.45f,  // saturación armónica suave (tanh acotada)
                compression = 0.35f,
                reverbMix = 0.02f,
                masterGain = 1.0f
            )
            Preset.SURROUND -> Params(
                Preset.SURROUND, true,
                bassGain = +1.5f,
                trebleGain = +3.0f,
                subBassGain = +1.0f,
                presenceGain = +2.0f,
                surroundWidth = 1.2f,  // estéreo ultra ancho
                exciterAmount = 0.35f, // detalle espacial
                harmonicBass = 0.15f,
                compression = 0.3f,
                reverbMix = 0.25f,     // reverb envolvente
                masterGain = 1.1f
            )
            Preset.DIALOGUE -> Params(
                Preset.DIALOGUE, true,
                bassGain = -3.0f,      // reduce graves que enmascaran voz
                trebleGain = +2.5f,
                subBassGain = -4.0f,
                presenceGain = +5.0f,  // boost 2-4kHz inteligibilidad
                surroundWidth = 0.0f,  // mono-ish para foco central
                exciterAmount = 0.2f,
                harmonicBass = 0.0f,
                compression = 0.7f,    // fuerte compresión
                reverbMix = 0.0f,
                masterGain = 1.2f
            )
            Preset.MUSIC -> Params(
                Preset.MUSIC, true,
                bassGain = +2.5f,
                trebleGain = +2.5f,    // curva en V suave
                subBassGain = +1.5f,
                presenceGain = +1.0f,
                surroundWidth = 0.4f,
                exciterAmount = 0.2f,  // aire vocal
                harmonicBass = 0.2f,
                compression = 0.25f,   // preserva dinámica
                reverbMix = 0.05f,
                masterGain = 1.08f
            )
        }
    }

    private const val PREF_NAME = "karin_audio_dsp"
    private const val KEY_PRESET = "dsp_preset"
    private const val KEY_ENABLED = "dsp_enabled"
    private const val KEY_BASS = "dsp_bass"
    private const val KEY_TREBLE = "dsp_treble"
    private const val KEY_SUBBASS = "dsp_subbass"
    private const val KEY_PRESENCE = "dsp_presence"
    private const val KEY_SURROUND = "dsp_surround"
    private const val KEY_EXCITER = "dsp_exciter"
    private const val KEY_HARMBASS = "dsp_harmbass"
    private const val KEY_COMPRESSION = "dsp_compression"
    private const val KEY_REVERB = "dsp_reverb"
    private const val KEY_MASTER = "dsp_master"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, true) ?: true
    fun setEnabled(v: Boolean) { prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply() }

    fun preset(): Preset {
        val idx = prefs?.getInt(KEY_PRESET, 1) ?: 1 // default ANIME
        return Preset.entries.getOrElse(idx.coerceIn(0, Preset.entries.size - 1)) { Preset.ANIME }
    }
    fun setPreset(p: Preset) { prefs?.edit()?.putInt(KEY_PRESET, p.ordinal)?.apply() }

    fun getBass(): Float = prefs?.getFloat(KEY_BASS, 0f) ?: 0f
    fun setBass(v: Float) { prefs?.edit()?.putFloat(KEY_BASS, v.coerceIn(-12f, 12f))?.apply() }

    fun getTreble(): Float = prefs?.getFloat(KEY_TREBLE, 0f) ?: 0f
    fun setTreble(v: Float) { prefs?.edit()?.putFloat(KEY_TREBLE, v.coerceIn(-12f, 12f))?.apply() }

    fun getSubBass(): Float = prefs?.getFloat(KEY_SUBBASS, 0f) ?: 0f
    fun setSubBass(v: Float) { prefs?.edit()?.putFloat(KEY_SUBBASS, v.coerceIn(-12f, 12f))?.apply() }

    fun getPresence(): Float = prefs?.getFloat(KEY_PRESENCE, 0f) ?: 0f
    fun setPresence(v: Float) { prefs?.edit()?.putFloat(KEY_PRESENCE, v.coerceIn(-12f, 12f))?.apply() }

    fun getSurround(): Float = prefs?.getFloat(KEY_SURROUND, 0f) ?: 0f
    fun setSurround(v: Float) { prefs?.edit()?.putFloat(KEY_SURROUND, v.coerceIn(0f, 1.5f))?.apply() }

    fun getExciter(): Float = prefs?.getFloat(KEY_EXCITER, 0f) ?: 0f
    fun setExciter(v: Float) { prefs?.edit()?.putFloat(KEY_EXCITER, v.coerceIn(0f, 1f))?.apply() }

    fun getHarmbass(): Float = prefs?.getFloat(KEY_HARMBASS, 0f) ?: 0f
    fun setHarmbass(v: Float) { prefs?.edit()?.putFloat(KEY_HARMBASS, v.coerceIn(0f, 1f))?.apply() }

    fun getCompression(): Float = prefs?.getFloat(KEY_COMPRESSION, 0f) ?: 0f
    fun setCompression(v: Float) { prefs?.edit()?.putFloat(KEY_COMPRESSION, v.coerceIn(0f, 1f))?.apply() }

    fun getReverb(): Float = prefs?.getFloat(KEY_REVERB, 0f) ?: 0f
    fun setReverb(v: Float) { prefs?.edit()?.putFloat(KEY_REVERB, v.coerceIn(0f, 0.5f))?.apply() }

    fun getMaster(): Float = prefs?.getFloat(KEY_MASTER, 1.0f) ?: 1.0f
    fun setMaster(v: Float) { prefs?.edit()?.putFloat(KEY_MASTER, v.coerceIn(0.5f, 2f))?.apply() }

    fun params(): Params = Params(
        preset = preset(),
        enabled = isEnabled(),
        bassGain = getBass(),
        trebleGain = getTreble(),
        subBassGain = getSubBass(),
        presenceGain = getPresence(),
        surroundWidth = getSurround(),
        exciterAmount = getExciter(),
        harmonicBass = getHarmbass(),
        compression = getCompression(),
        reverbMix = getReverb(),
        masterGain = getMaster()
    )

    fun applyParams(p: Params) {
        setPreset(p.preset)
        setEnabled(p.enabled)
        setBass(p.bassGain)
        setTreble(p.trebleGain)
        setSubBass(p.subBassGain)
        setPresence(p.presenceGain)
        setSurround(p.surroundWidth)
        setExciter(p.exciterAmount)
        setHarmbass(p.harmonicBass)
        setCompression(p.compression)
        setReverb(p.reverbMix)
        setMaster(p.masterGain)
    }
}
