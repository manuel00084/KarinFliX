package com.karin.streamtv.util

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREF_NAME = "karin_flix_settings"

    private const val KEY_SERVER_FALLBACK = "server_fallback_enabled"
    private const val KEY_AUTOPLAY = "autoplay_enabled"
    private const val KEY_KARIN_LINK = "karin_link_enabled"
    private const val KEY_PLAYNOW = "playnow_enabled"
    private const val KEY_VIDEO_PLAYER_MODE = "video_player_mode_enabled"
    private const val KEY_PLAYER_VOLUME = "player_volume"
    private const val KEY_FIRST_RUN = "first_run_done"

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
        return prefs?.getFloat(KEY_PLAYER_VOLUME, 1.0f) ?: 1.0f
    }

    fun setPlayerVolume(v: Float) {
        prefs?.edit()?.putFloat(KEY_PLAYER_VOLUME, v.coerceIn(0.1f, 3.0f))?.apply()
    }

    fun isFirstRun(): Boolean {
        return prefs?.getBoolean(KEY_FIRST_RUN, false) != true
    }

    fun setFirstRunDone() {
        prefs?.edit()?.putBoolean(KEY_FIRST_RUN, true)?.apply()
    }

}
