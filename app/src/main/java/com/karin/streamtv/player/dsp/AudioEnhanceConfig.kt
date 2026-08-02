package com.karin.streamtv.player.dsp

import android.content.Context
import android.content.SharedPreferences

object AudioEnhanceConfig {

    enum class Preset(val label: String) {
        BATTLE("Shonen Batalla"),
        THX("Cine THX"),
        TRUBASS("TruBass"),
        SPACE("3D Space"),
        VOICE("Voz clara")
    }

    data class Params(
        val preset: Preset = Preset.BATTLE,
        val enabled: Boolean = true,
        val thx: Float = 1.0f,
        val bass: Float = 1.0f,
        val space: Float = 1.0f,
        val voice: Float = 1.0f,
        val masterGain: Float = 1.12f
    ) {
        fun withPreset(p: Preset): Params = when (p) {
            Preset.BATTLE -> Params(Preset.BATTLE, true, 1.0f, 1.0f, 1.0f, 1.0f, 1.12f)
            Preset.THX -> Params(Preset.THX, true, 1.0f, 0.4f, 0.6f, 0.6f, 1.1f)
            Preset.TRUBASS -> Params(Preset.TRUBASS, true, 0.5f, 1.3f, 0.4f, 0.4f, 1.08f)
            Preset.SPACE -> Params(Preset.SPACE, true, 0.6f, 0.4f, 1.3f, 0.6f, 1.1f)
            Preset.VOICE -> Params(Preset.VOICE, true, 0.5f, 0.3f, 0.3f, 1.3f, 1.08f)
        }
    }

    private const val PREF_NAME = "karin_audio_dsp"
    private const val KEY_PRESET = "dsp_preset"
    private const val KEY_ENABLED = "dsp_enabled"
    private const val KEY_THX = "dsp_thx"
    private const val KEY_BASS = "dsp_bass"
    private const val KEY_SPACE = "dsp_space"
    private const val KEY_VOICE = "dsp_voice"
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

    fun getMaster(): Float = prefs?.getFloat(KEY_MASTER, 1.12f) ?: 1.12f
    fun setMaster(v: Float) { prefs?.edit()?.putFloat(KEY_MASTER, v.coerceIn(0.7f, 2f))?.apply() }

    fun params(): Params = Params(
        preset = preset(),
        enabled = isEnabled(),
        thx = getThx(),
        bass = getBass(),
        space = getSpace(),
        voice = getVoice(),
        masterGain = getMaster()
    )

    fun applyParams(p: Params) {
        setPreset(p.preset)
        setEnabled(p.enabled)
        setThx(p.thx)
        setBass(p.bass)
        setSpace(p.space)
        setVoice(p.voice)
        setMaster(p.masterGain)
    }
}
