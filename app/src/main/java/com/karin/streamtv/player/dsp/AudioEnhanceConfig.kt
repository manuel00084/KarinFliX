package com.karin.streamtv.player.dsp

import android.content.Context
import android.content.SharedPreferences

object AudioEnhanceConfig {

    enum class Preset(val label: String) {
        BATTLE("Shonen Batalla"),
        CINE("Modo Cine"),
        BASS("Graves profundos"),
        WIDE("Ancho estéreo"),
        VOICE("Diálogos claros")
    }

    data class Params(
        val preset: Preset = Preset.BATTLE,
        val enabled: Boolean = true,
        val thx: Float = 1.0f,
        val bass: Float = 1.0f,
        val space: Float = 1.0f,
        val voice: Float = 1.0f,
        val excite: Float = 0.6f,
        val harmbass: Float = 0.8f,
        val dynamic: Float = 0.6f,
        val ambience: Float = 0.3f,
        val masterGain: Float = 1.12f
    ) {
        fun withPreset(p: Preset): Params = when (p) {
            Preset.BATTLE -> Params(Preset.BATTLE, true, 1.0f, 1.0f, 1.0f, 1.0f, 0.6f, 0.8f, 0.6f, 0.3f, 1.12f)
            Preset.CINE -> Params(Preset.CINE, true, 1.0f, 0.4f, 0.6f, 0.6f, 0.4f, 0.3f, 0.4f, 0.4f, 1.1f)
            Preset.BASS -> Params(Preset.BASS, true, 0.5f, 1.3f, 0.4f, 0.4f, 0.3f, 1.0f, 0.5f, 0.2f, 1.08f)
            Preset.WIDE -> Params(Preset.WIDE, true, 0.6f, 0.4f, 1.3f, 0.6f, 0.5f, 0.2f, 0.3f, 0.5f, 1.1f)
            Preset.VOICE -> Params(Preset.VOICE, true, 0.5f, 0.3f, 0.3f, 1.3f, 0.5f, 0.2f, 0.6f, 0.2f, 1.08f)
        }
    }

    private const val PREF_NAME = "karin_audio_dsp"
    private const val KEY_PRESET = "dsp_preset"
    private const val KEY_ENABLED = "dsp_enabled"
    private const val KEY_THX = "dsp_thx"
    private const val KEY_BASS = "dsp_bass"
    private const val KEY_SPACE = "dsp_space"
    private const val KEY_VOICE = "dsp_voice"
    private const val KEY_EXCITE = "dsp_excite"
    private const val KEY_HARMBASS = "dsp_harmbass"
    private const val KEY_DYNAMIC = "dsp_dynamic"
    private const val KEY_AMBIENCE = "dsp_ambience"
    private const val KEY_MASTER = "dsp_master"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, true) ?: true
    fun setEnabled(v: Boolean) { prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply() }

    fun preset(): Preset {
        val idx = prefs?.getInt(KEY_PRESET, 0) ?: 0
        return Preset.values().getOrElse(idx.coerceIn(0, Preset.values().size - 1)) { Preset.BATTLE }
    }
    fun setPreset(p: Preset) { prefs?.edit()?.putInt(KEY_PRESET, p.ordinal)?.apply() }

    fun getThx(): Float = prefs?.getFloat(KEY_THX, 1.0f) ?: 1.0f
    fun setThx(v: Float) { prefs?.edit()?.putFloat(KEY_THX, v.coerceIn(0f, 2f))?.apply() }

    fun getBass(): Float = prefs?.getFloat(KEY_BASS, 1.0f) ?: 1.0f
    fun setBass(v: Float) { prefs?.edit()?.putFloat(KEY_BASS, v.coerceIn(0f, 2f))?.apply() }

    fun getSpace(): Float = prefs?.getFloat(KEY_SPACE, 1.0f) ?: 1.0f
    fun setSpace(v: Float) { prefs?.edit()?.putFloat(KEY_SPACE, v.coerceIn(0f, 2f))?.apply() }

    fun getVoice(): Float = prefs?.getFloat(KEY_VOICE, 1.0f) ?: 1.0f
    fun setVoice(v: Float) { prefs?.edit()?.putFloat(KEY_VOICE, v.coerceIn(0f, 2f))?.apply() }

    fun getExcite(): Float = prefs?.getFloat(KEY_EXCITE, 0.6f) ?: 0.6f
    fun setExcite(v: Float) { prefs?.edit()?.putFloat(KEY_EXCITE, v.coerceIn(0f, 2f))?.apply() }

    fun getHarmbass(): Float = prefs?.getFloat(KEY_HARMBASS, 0.8f) ?: 0.8f
    fun setHarmbass(v: Float) { prefs?.edit()?.putFloat(KEY_HARMBASS, v.coerceIn(0f, 2f))?.apply() }

    fun getDynamic(): Float = prefs?.getFloat(KEY_DYNAMIC, 0.6f) ?: 0.6f
    fun setDynamic(v: Float) { prefs?.edit()?.putFloat(KEY_DYNAMIC, v.coerceIn(0f, 2f))?.apply() }

    fun getAmbience(): Float = prefs?.getFloat(KEY_AMBIENCE, 0.3f) ?: 0.3f
    fun setAmbience(v: Float) { prefs?.edit()?.putFloat(KEY_AMBIENCE, v.coerceIn(0f, 2f))?.apply() }

    fun getMaster(): Float = prefs?.getFloat(KEY_MASTER, 1.12f) ?: 1.12f
    fun setMaster(v: Float) { prefs?.edit()?.putFloat(KEY_MASTER, v.coerceIn(0.7f, 2f))?.apply() }

    fun params(): Params = Params(
        preset = preset(),
        enabled = isEnabled(),
        thx = getThx(),
        bass = getBass(),
        space = getSpace(),
        voice = getVoice(),
        excite = getExcite(),
        harmbass = getHarmbass(),
        dynamic = getDynamic(),
        ambience = getAmbience(),
        masterGain = getMaster()
    )

    fun applyParams(p: Params) {
        setPreset(p.preset)
        setEnabled(p.enabled)
        setThx(p.thx)
        setBass(p.bass)
        setSpace(p.space)
        setVoice(p.voice)
        setExcite(p.excite)
        setHarmbass(p.harmbass)
        setDynamic(p.dynamic)
        setAmbience(p.ambience)
        setMaster(p.masterGain)
    }
}
