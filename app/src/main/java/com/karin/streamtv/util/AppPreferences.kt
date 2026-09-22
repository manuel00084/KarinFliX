package com.karin.streamtv.util

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREF_NAME = "karin_flix_settings"

    const val KEY_SERVER_FALLBACK = "server_fallback_enabled"
    const val KEY_AUTOPLAY = "autoplay_enabled"
    const val KEY_KARIN_LINK = "karin_link_enabled"
    const val KEY_PLAYNOW = "playnow_enabled"
    const val KEY_VIDEO_PLAYER_MODE = "video_player_mode_enabled"
    const val KEY_PLAYER_VOLUME = "player_volume"
    const val KEY_PLAYER_SPEED = "player_speed"
    const val KEY_SMB_SHOW_HOME = "smb_show_on_home"
    const val KEY_LOW_END = "low_end_mode"
    const val KEY_FIRST_RUN = "first_run_done"
    const val KEY_CODEC_MODE = "codec_mode"

    const val CODEC_HW = 0
    const val CODEC_SW_GOOGLE = 1
    const val CODEC_AUTO = 2

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getPrefs(): SharedPreferences? = prefs

    fun isServerFallbackEnabled(): Boolean {
        return prefs?.getBoolean(KEY_SERVER_FALLBACK, true) ?: true
    }

    fun setServerFallbackEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SERVER_FALLBACK, enabled)?.apply()
    }

    fun isAutoPlayEnabled(): Boolean {
        return prefs?.getBoolean(KEY_AUTOPLAY, true) ?: true
    }

    fun setAutoPlayEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_AUTOPLAY, enabled)?.apply()
    }

    fun isKarinLinkEnabled(): Boolean {
        return prefs?.getBoolean(KEY_KARIN_LINK, false) ?: false
    }

    fun setKarinLinkEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_KARIN_LINK, enabled)?.apply()
    }

    fun isPlayNowEnabled(): Boolean {
        return prefs?.getBoolean(KEY_PLAYNOW, true) ?: true
    }

    fun setPlayNowEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_PLAYNOW, enabled)?.apply()
    }

    fun isVideoPlayerModeEnabled(): Boolean {
        return prefs?.getBoolean(KEY_VIDEO_PLAYER_MODE, false) ?: false
    }

    fun setVideoPlayerModeEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_VIDEO_PLAYER_MODE, enabled)?.apply()
    }

    fun getPlayerVolume(): Float {
        return (prefs?.getFloat(KEY_PLAYER_VOLUME, 1.0f) ?: 1.0f).coerceIn(0.1f, 1.0f)
    }

    fun setPlayerVolume(v: Float) {
        prefs?.edit()?.putFloat(KEY_PLAYER_VOLUME, v.coerceIn(0.1f, 1.0f))?.apply()
    }

    fun getPlayerSpeed(): Float {
        val sp = prefs?.getFloat(KEY_PLAYER_SPEED, 1.0f) ?: 1.0f
        return sp.coerceIn(0.25f, 2.0f)
    }

    fun setPlayerSpeed(v: Float) {
        prefs?.edit()?.putFloat(KEY_PLAYER_SPEED, v.coerceIn(0.25f, 2.0f))?.apply()
    }

    fun isSmbShowOnHome(): Boolean =
        prefs?.getBoolean(KEY_SMB_SHOW_HOME, true) ?: true

    fun setSmbShowOnHome(v: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SMB_SHOW_HOME, v)?.apply()
    }

    fun isLowEndMode(): Boolean =
        prefs?.getBoolean(KEY_LOW_END, false) ?: false

    fun setLowEndMode(v: Boolean) {
        prefs?.edit()?.putBoolean(KEY_LOW_END, v)?.apply()
    }

    fun isFirstRun(): Boolean {
        return prefs?.getBoolean(KEY_FIRST_RUN, false) != true
    }

    fun setFirstRunDone() {
        prefs?.edit()?.putBoolean(KEY_FIRST_RUN, true)?.apply()
    }

    fun getCodecMode(): Int {
        return prefs?.getInt(KEY_CODEC_MODE, CODEC_HW) ?: CODEC_HW
    }

    fun setCodecMode(mode: Int) {
        prefs?.edit()?.putInt(KEY_CODEC_MODE, mode)?.apply()
    }

    fun getCodecModeLabel(): String {
        return when (getCodecMode()) {
            CODEC_HW -> "Hardware (chip)"
            CODEC_SW_GOOGLE -> "Software (Google)"
            CODEC_AUTO -> "Auto"
            else -> "Hardware (chip)"
        }
    }

}
