package com.karin.streamtv.util

import android.content.Context
import com.karin.streamtv.player.VideoEnhanceConfig
import com.karin.streamtv.player.dsp.AudioEnhanceConfig
import com.karin.streamtv.util.AppPreferences

/**
 * Ajuste automático inicial ("Auto-ajuste por capacidad del equipo" + "Audio pro"):
 * se ejecuta una sola vez desde el [com.karin.streamtv.ui.SplashActivity] y guarda el
 * resultado en preferencias, de modo que los arranques siguientes cargan el perfil
 * persistido sin volver a medir nada.
 *
 *  - [tuneAudio]: sonido "profesional" en bocinas/audífonos baratos. Asegura DSP
 *    activo + modo Auto y recuerda un preset base por tipo de salida física
 *    (bocina TV/celular → True MaxBass, auriculares → Anime, barra → Cine).
 *  - [tuneVideo]: umbrales seguros por gama del equipo (resolución, upscaler,
 *    60 fps, calidad GL) para que el hardware humilde nunca se atasque y el
 *    potente arranque con la mejor experiencia.
 */
object BootstrapTuner {

    /** Preset base por tipo de salida física (memoria por dispositivo, modo Auto). */
    fun defaultPresetFor(device: AudioEnhanceConfig.DeviceKind): AudioEnhanceConfig.Preset =
        when (device) {
            AudioEnhanceConfig.DeviceKind.TV_SPEAKER -> AudioEnhanceConfig.Preset.SPEAKER
            AudioEnhanceConfig.DeviceKind.PHONE_SPEAKER -> AudioEnhanceConfig.Preset.SPEAKER
            AudioEnhanceConfig.DeviceKind.HEADPHONES -> AudioEnhanceConfig.Preset.ANIME
            AudioEnhanceConfig.DeviceKind.SOUNDBAR -> AudioEnhanceConfig.Preset.CINEMA
            AudioEnhanceConfig.DeviceKind.NEUTRAL -> AudioEnhanceConfig.Preset.ANIME
        }

    /** "Audio pro automático": DSP activo + modo Auto + preset por salida guardado.
     *  Devuelve una etiqueta legible para mostrar en el arranque. */
    fun tuneAudio(context: Context): String {
        if (!AudioEnhanceConfig.isEnabled()) AudioEnhanceConfig.setEnabled(true)
        if (!AudioEnhanceConfig.isAutoDevice()) AudioEnhanceConfig.setAutoDevice(true)
        val device = AudioEnhanceConfig.currentDeviceKind()
        if (AudioEnhanceConfig.getDevicePreset(device) == null) {
            AudioEnhanceConfig.setDevicePreset(device, defaultPresetFor(device))
        }
        val preset = AudioEnhanceConfig.getDevicePreset(device)
            ?: AudioEnhanceConfig.preset()
        return "${AudioEnhanceConfig.outputDeviceLabel()} · ${preset.label}"
    }

    /** "Auto-ajuste por capacidad del equipo" (video): umbrales seguros por gama. */
    fun tuneVideo(context: Context): String {
        val info = DeviceProfile.get(context)
        // Modo gama baja guardado de una vez: TrackSelectorFactory y el DRS/frame-drop
        // del pipeline GL lo leen para recortar la carga en hardware modesto.
        AppPreferences.setLowEndMode(info.tier == DeviceProfile.Tier.LOW)
        when (info.tier) {
            DeviceProfile.Tier.LOW -> {
                VideoEnhanceConfig.setEnabled(false)
                VideoEnhanceConfig.setGlQualityMode(false)
                VideoEnhanceConfig.setUpscalerMode(VideoEnhanceConfig.UpscalerMode.OFF)
                VideoEnhanceConfig.setInterpolationEnabled(false)
            }
            DeviceProfile.Tier.MID -> {
                VideoEnhanceConfig.setEnabled(false)
                VideoEnhanceConfig.setGlQualityMode(false)
                VideoEnhanceConfig.setInterpolationEnabled(false)
                if (VideoEnhanceConfig.getUpscalerMode() == VideoEnhanceConfig.UpscalerMode.OFF) {
                    VideoEnhanceConfig.setUpscalerMode(VideoEnhanceConfig.UpscalerMode.BILINEAR)
                }
            }
            DeviceProfile.Tier.HIGH -> {
                VideoEnhanceConfig.setEnabled(true)
                VideoEnhanceConfig.setGlQualityMode(true)
                if (VideoEnhanceConfig.getUpscalerMode() == VideoEnhanceConfig.UpscalerMode.OFF) {
                    VideoEnhanceConfig.setUpscalerMode(VideoEnhanceConfig.UpscalerMode.BILINEAR)
                }
                VideoEnhanceConfig.setInterpolationEnabled(true)
                VideoEnhanceConfig.setInterpolationMode(VideoEnhanceConfig.InterpolationMode.HYBRID)
            }
        }
        return info.tier.label
    }
}
