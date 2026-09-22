package com.karin.streamtv.player

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.karin.streamtv.util.AppPreferences

/**
 * Enciende/apaga el activity-alias "ExoPlayerVideoPlayer" según la preferencia
 * KEY_VIDEO_PLAYER_MODE. Cuando el alias está habilitado, KarinFLiX aparece en
 * el selector "Abrir con..." de Android para videos (intento ACTION_VIEW con
 * MIME de video) y ExoPlayerActivity recibe el intent; cuando está
 * deshabilitado, la app no se ofrece como reproductor del sistema.
 */
object SystemVideoPlayerRegistrar {

    // Nombre de la clase del alias en el manifest (namespace = paquete del manifest).
    private const val ALIAS_CLASS = "com.karin.streamtv.player.ExoPlayerVideoPlayer"

    fun apply(context: Context) {
        val enabled = AppPreferences.isVideoPlayerModeEnabled()
        val setting = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, ALIAS_CLASS),
                setting,
                PackageManager.DONT_KILL_APP,
            )
        } catch (e: Exception) {
            android.util.Log.w("SystemVideoPlayerRegistrar", "No se pudo ajustar el reproductor del sistema: ${e.message}")
        }
    }
}