package com.karin.streamtv.util

import android.content.Context
import com.karin.streamtv.util.AppPreferences

/**
 * Ajuste automático inicial ("Auto-ajuste por capacidad del equipo" + "Audio pro").
 *
 * El pipeline de mejora de video/audio (VideoEnhanceConfig / AudioEnhanceConfig,
 * TrackSelectorFactory, etc.) fue eliminado del proyecto, por lo que estas
 * funciones ahora solo aplican el ajuste de gama baja (LowEndMode) y devuelven
 * una etiqueta legible para el arranque. Se conservan las firmas para no romper
 * los llamadores existentes (SplashActivity).
 */
object BootstrapTuner {

    /** "Audio pro automático": ya no hay DSP, devuelve una etiqueta informativa. */
    fun tuneAudio(context: Context): String {
        return "Audio pro (no disponible)"
    }

    /** "Auto-ajuste por capacidad del equipo" (video): umbrales seguros por gama. */
    fun tuneVideo(context: Context): String {
        val info = DeviceProfile.get(context)
        AppPreferences.setLowEndMode(info.tier == DeviceProfile.Tier.LOW)
        return info.tier.label
    }
}
