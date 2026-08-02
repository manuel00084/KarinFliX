package com.karin.streamtv.util

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREF_NAME = "karin_flix_settings"

    private const val KEY_SERVER_FALLBACK = "server_fallback_enabled"
    private const val KEY_AUTOPLAY = "autoplay_enabled"
    private const val KEY_KARIN_LINK = "karin_link_enabled"
    private const val KEY_PLAYNOW = "playnow_enabled"
    private const val KEY_SKIP_OPENING = "skip_opening_enabled"
    private const val KEY_SKIP_ENDING = "skip_ending_enabled"

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

    fun isSkipOpeningEnabled(): Boolean {
        return prefs?.getBoolean(KEY_SKIP_OPENING, true) ?: true
    }

    fun setSkipOpeningEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SKIP_OPENING, enabled)?.apply()
    }

    fun isSkipEndingEnabled(): Boolean {
        return prefs?.getBoolean(KEY_SKIP_ENDING, false) ?: false
    }

    fun setSkipEndingEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SKIP_ENDING, enabled)?.apply()
    }

    fun isVideoEnhanceEnabled(): Boolean {
        return prefs?.getBoolean("video_enhance_enabled", true) ?: true
    }

    fun setVideoEnhanceEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean("video_enhance_enabled", enabled)?.apply()
    }

}
