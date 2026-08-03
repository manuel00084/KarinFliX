package com.karin.streamtv.player

import android.content.Context
import android.content.SharedPreferences

object VideoEnhanceConfig {

    enum class Preset(val label: String) {
        OFF("Sin mejora"),
        ANIME("Anime"),
        CINE("Cine"),
        DEPORTE("Deporte"),
        VIVIDO("Vívido"),
        CINE_CLASICO("Cine Clásico"),
        VIDEO_DOMESTICO("Vídeo Doméstico")
    }

    enum class UpscalerMode(val label: String, val value: Float, val scaleFactor: Float = 1.0f, val sharpness: Float = 0.8f) {
        OFF("Apagado", 0f),
        ANIME4K("Anime4K", 1f),
        FSR_ULTRA_QUALITY("FSR Ultra Quality", 2f, 1.3f, 0.17f),   // 77% render res
        FSR_QUALITY("FSR Quality", 3f, 1.5f, 0.32f),                 // 67% render res
        FSR_BALANCED("FSR Balanced", 4f, 1.7f, 0.48f),               // 59% render res
        FSR_PERFORMANCE("FSR Performance", 5f, 2.0f, 0.62f)          // 50% render res
    }

    data class Params(
        val preset: Preset = Preset.ANIME,
        val enabled: Boolean = true,
        val saturation: Float = 1.0f,    // 0.5..2.0
        val contrast: Float = 1.0f,       // 0.5..2.0
        val brightness: Float = 0.0f,     // -0.5..+0.5
        val sharpness: Float = 0.0f,      // 0..2.0
        val colorBoost: Float = 1.0f,     // 0.5..2.0
        val denoise: Float = 0.0f,        // 0..1
        val deband: Float = 0.0f          // 0..0.06
    ) {
        fun withPreset(p: Preset): Params = when (p) {
            Preset.OFF -> Params(Preset.OFF, false, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f)
            Preset.ANIME -> Params(
                Preset.ANIME, true,
                saturation = 1.40f,   // colores vivos tipo cel
                contrast = 1.15f,     // contraste medio-alto
                brightness = 0.02f,
                sharpness = 0.55f,    // lineart nítido
                colorBoost = 1.25f,
                denoise = 0.20f,      // limpia ruido de compresión
                deband = 0.02f
            )
            Preset.CINE -> Params(
                Preset.CINE, true,
                saturation = 1.10f,
                contrast = 1.40f,     // negros profundos, look fílmico
                brightness = -0.05f,
                sharpness = 0.15f,
                colorBoost = 1.05f,
                denoise = 0.40f,      // grano suavizado
                deband = 0.05f        // gradación suave en oscuros
            )
            Preset.DEPORTE -> Params(
                Preset.DEPORTE, true,
                saturation = 1.30f,
                contrast = 1.45f,     // máximo punch
                brightness = 0.03f,
                sharpness = 0.70f,    // seguimiento de movimiento
                colorBoost = 1.20f,
                denoise = 0.25f,
                deband = 0.02f
            )
            Preset.VIVIDO -> Params(
                Preset.VIVIDO, true,
                saturation = 1.75f,   // supersaturado
                contrast = 1.25f,
                brightness = 0.05f,
                sharpness = 0.35f,
                colorBoost = 1.60f,
                denoise = 0.15f,
                deband = 0.03f
            )
            Preset.CINE_CLASICO -> Params(
                Preset.CINE_CLASICO, true,
                saturation = 0.90f,   // paleta apagada/cálida
                contrast = 1.25f,
                brightness = 0.00f,
                sharpness = 0.10f,
                colorBoost = 0.95f,
                denoise = 0.45f,
                deband = 0.06f
            )
            Preset.VIDEO_DOMESTICO -> Params(
                Preset.VIDEO_DOMESTICO, true,
                saturation = 1.05f,   // look natural
                contrast = 1.10f,
                brightness = 0.02f,
                sharpness = 0.25f,
                colorBoost = 1.00f,
                denoise = 0.30f,
                deband = 0.03f
            )
        }
    }

    private const val PREF_NAME = "karin_video_enhance"
    private const val KEY_PRESET = "enhance_preset"
    private const val KEY_ENABLED = "enhance_enabled"
    private const val KEY_INTERPOLATION = "interpolation_enabled"
    private const val KEY_SATURATION = "saturation"
    private const val KEY_CONTRAST = "contrast"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_SHARPNESS = "sharpness"
    private const val KEY_COLOR_BOOST = "color_boost"
    private const val KEY_DENOISE = "denoise"
    private const val KEY_DEBAND = "deband"
    private const val KEY_DEBUG_MODE = "debug_mode"
    private const val KEY_UPSCALER_MODE = "upscaler_mode"

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

    fun isInterpolationEnabled(): Boolean = prefs?.getBoolean(KEY_INTERPOLATION, false) ?: false
    fun setInterpolationEnabled(v: Boolean) { prefs?.edit()?.putBoolean(KEY_INTERPOLATION, v)?.apply() }

    fun qualityLabel(): String = if (isInterpolationEnabled()) "60p" else "OFF"

    fun getSaturation(): Float = prefs?.getFloat(KEY_SATURATION, 1.0f) ?: 1.0f
    fun setSaturation(v: Float) { prefs?.edit()?.putFloat(KEY_SATURATION, v)?.apply() }

    fun getContrast(): Float = prefs?.getFloat(KEY_CONTRAST, 1.0f) ?: 1.0f
    fun setContrast(v: Float) { prefs?.edit()?.putFloat(KEY_CONTRAST, v)?.apply() }

    fun getBrightness(): Float = prefs?.getFloat(KEY_BRIGHTNESS, 0.0f) ?: 0.0f
    fun setBrightness(v: Float) { prefs?.edit()?.putFloat(KEY_BRIGHTNESS, v)?.apply() }

    fun getSharpness(): Float = prefs?.getFloat(KEY_SHARPNESS, 0.0f) ?: 0.0f
    fun setSharpness(v: Float) { prefs?.edit()?.putFloat(KEY_SHARPNESS, v)?.apply() }

    fun getColorBoost(): Float = prefs?.getFloat(KEY_COLOR_BOOST, 1.0f) ?: 1.0f
    fun setColorBoost(v: Float) { prefs?.edit()?.putFloat(KEY_COLOR_BOOST, v)?.apply() }

    fun getDenoise(): Float = prefs?.getFloat(KEY_DENOISE, 0.0f) ?: 0.0f
    fun setDenoise(v: Float) { prefs?.edit()?.putFloat(KEY_DENOISE, v.coerceIn(0f, 1f))?.apply() }

    fun getDeband(): Float = prefs?.getFloat(KEY_DEBAND, 0.0f) ?: 0.0f
    fun setDeband(v: Float) { prefs?.edit()?.putFloat(KEY_DEBAND, v.coerceIn(0f, 0.06f))?.apply() }

    fun getDebugMode(): Int = prefs?.getInt(KEY_DEBUG_MODE, 0) ?: 0
    fun setDebugMode(v: Int) { prefs?.edit()?.putInt(KEY_DEBUG_MODE, v.coerceIn(0, 7))?.apply() }

    fun getUpscalerMode(): UpscalerMode {
        val idx = prefs?.getInt(KEY_UPSCALER_MODE, 0) ?: 0
        return UpscalerMode.entries.getOrElse(idx.coerceIn(0, UpscalerMode.entries.size - 1)) { UpscalerMode.OFF }
    }
    fun setUpscalerMode(mode: UpscalerMode) { prefs?.edit()?.putInt(KEY_UPSCALER_MODE, mode.ordinal)?.apply() }

    fun debugModeLabel(mode: Int): String = when (mode) {
        1 -> "PREV"
        2 -> "CURR"
        3 -> "UV"
        4 -> "FACTOR"
        5 -> "MOTION"
        6 -> "V0V1"
        7 -> "VISUAL"
        else -> "OFF"
    }

    fun seekBarToSaturation(progress: Int): Float = 0.5f + (progress / 100f) * 1.5f
    fun saturationToSeekBar(value: Float): Int = ((value - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)

    fun seekBarToContrast(progress: Int): Float = 0.5f + (progress / 100f) * 1.5f
    fun contrastToSeekBar(value: Float): Int = ((value - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)

    fun seekBarToBrightness(progress: Int): Float = -0.5f + (progress / 100f) * 1.0f
    fun brightnessToSeekBar(value: Float): Int = ((value + 0.5f) / 1.0f * 100).toInt().coerceIn(0, 100)

    fun seekBarToSharpness(progress: Int): Float = (progress / 100f) * 2.0f
    fun sharpnessToSeekBar(value: Float): Int = (value / 2.0f * 100).toInt().coerceIn(0, 100)

    fun seekBarToColorBoost(progress: Int): Float = 0.5f + (progress / 100f) * 1.5f
    fun colorBoostToSeekBar(value: Float): Int = ((value - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)

    fun seekBarToDenoise(progress: Int): Float = progress / 100f
    fun denoiseToSeekBar(value: Float): Int = (value * 100).toInt().coerceIn(0, 100)

    fun seekBarToDeband(progress: Int): Float = (progress / 100f) * 0.06f
    fun debandToSeekBar(value: Float): Int = (value / 0.06f * 100).toInt().coerceIn(0, 100)

    fun params(): Params = Params(
        preset = preset(),
        enabled = isEnabled(),
        saturation = getSaturation(),
        contrast = getContrast(),
        brightness = getBrightness(),
        sharpness = getSharpness(),
        colorBoost = getColorBoost(),
        denoise = getDenoise(),
        deband = getDeband()
    )

    fun applyParams(p: Params) {
        setPreset(p.preset)
        setEnabled(p.enabled)
        setSaturation(p.saturation)
        setContrast(p.contrast)
        setBrightness(p.brightness)
        setSharpness(p.sharpness)
        setColorBoost(p.colorBoost)
        setDenoise(p.denoise)
        setDeband(p.deband)
    }

    fun applyPreset(p: Preset) {
        applyParams(Params().withPreset(p))
    }
}
