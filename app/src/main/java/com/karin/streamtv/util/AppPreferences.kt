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
    const val KEY_SMB_SHOW_HOME = "smb_show_on_home"
    const val KEY_LOW_END = "low_end_mode"
    const val KEY_SPLASH = "splash_enabled"
    const val KEY_FIRST_RUN = "first_run_done"
    const val KEY_CODEC_MODE = "codec_mode"
    const val KEY_KARIN_LINK_LAST_REMOTE_HOST = "karin_link_last_remote_host"
    const val KEY_KARIN_LINK_LAST_REMOTE_PORT = "karin_link_last_remote_port"
    const val KEY_KARIN_LINK_LAST_REMOTE_ID = "karin_link_last_remote_id"
    const val KEY_KARIN_LINK_LAST_REMOTE_NAME = "karin_link_last_remote_name"

    /** Último equipo al que se abrió el mando, por si el descubrimiento no encuentra nada. */
    data class RemoteTarget(
        val host: String,
        val port: Int,
        val deviceId: String,
        val deviceName: String,
    )

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
        return prefs?.getBoolean(KEY_KARIN_LINK, true) ?: true
    }

    fun setKarinLinkEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_KARIN_LINK, enabled)?.apply()
    }

    fun saveLastRemoteTarget(target: RemoteTarget) {
        prefs?.edit()
            ?.putString(KEY_KARIN_LINK_LAST_REMOTE_HOST, target.host)
            ?.putInt(KEY_KARIN_LINK_LAST_REMOTE_PORT, target.port)
            ?.putString(KEY_KARIN_LINK_LAST_REMOTE_ID, target.deviceId)
            ?.putString(KEY_KARIN_LINK_LAST_REMOTE_NAME, target.deviceName)
            ?.apply()
    }

    fun getLastRemoteTarget(): RemoteTarget? {
        val p = prefs ?: return null
        val host = p.getString(KEY_KARIN_LINK_LAST_REMOTE_HOST, null)?.trim().orEmpty()
        val port = p.getInt(KEY_KARIN_LINK_LAST_REMOTE_PORT, 0)
        if (host.isEmpty() || port <= 0) return null
        return RemoteTarget(
            host = host,
            port = port,
            deviceId = p.getString(KEY_KARIN_LINK_LAST_REMOTE_ID, null).orEmpty(),
            deviceName = p.getString(KEY_KARIN_LINK_LAST_REMOTE_NAME, null)
                ?.takeIf { it.isNotBlank() } ?: host,
        )
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

    fun isSplashEnabled(): Boolean {
        return prefs?.getBoolean(KEY_SPLASH, true) ?: true
    }

    fun setSplashEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SPLASH, enabled)?.apply()
    }

    fun getPlayerVolume(): Float {
        return (prefs?.getFloat(KEY_PLAYER_VOLUME, 1.0f) ?: 1.0f).coerceIn(0.1f, 1.0f)
    }

    fun setPlayerVolume(v: Float) {
        prefs?.edit()?.putFloat(KEY_PLAYER_VOLUME, v.coerceIn(0.1f, 1.0f))?.apply()
    }

    fun isSmbShowOnHome(): Boolean =
        prefs?.getBoolean(KEY_SMB_SHOW_HOME, true) ?: true

    fun setSmbShowOnHome(v: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SMB_SHOW_HOME, v)?.apply()
    }

    /** Modo ultra económico: tope 480p, sin efectos, sonido básico, poca memoria. */
    fun isUltraEconomyMode(): Boolean =
        prefs?.getBoolean(KEY_LOW_END, false) ?: false

    fun setUltraEconomyMode(v: Boolean) {
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
