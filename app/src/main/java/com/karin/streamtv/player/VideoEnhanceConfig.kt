package com.karin.streamtv.player

import android.content.Context
import android.content.SharedPreferences

object VideoEnhanceConfig {

    enum class ColorPreset(val label: String) {
        OFF("Sin cambio"),
        VIVIDO("Vívido"),
        CINE("Cine"),
        ANIME("Anime"),
        CALIDO("Cálido"),
        FRIO("Frío")
    }

    enum class InterpolationMode(val label: String, val intValue: Int) {
        X2("Frame x2 (simple)", 1),
        BLEND("Suavizado (Blend)", 2),
        HYBRID("Doubling + Micro-Blend (recomendado)", 4)
    }

    enum class CodecMode(val label: String, val hint: String) {
        HW("Hardware (chip)", "Usa el decodificador de hardware del dispositivo (más rápido, calidad según el chip)."),
        SW_GOOGLE("Software Google", "Fuerza los decodificadores de software de Google (c2.android.*/OMX.google.*). Calidad determinista, más CPU.")
    }

    enum class UpscalerMode(val label: String, val value: Float, val scaleFactor: Float = 1.0f, val sharpness: Float = 0.8f) {
        OFF("Apagado", 0f),
        ANIME4K("Anime4K", 2f),
        FSR_ULTRA_QUALITY("FSR Ultra Quality", 4f, 1.3f, 0.17f),   // 77% render res
        FSR_QUALITY("FSR Quality", 5f, 1.5f, 0.32f),                 // 67% render res
        FSR_BALANCED("FSR Balanced", 6f, 1.7f, 0.48f),               // 59% render res
        FSR_PERFORMANCE("FSR Performance", 7f, 2.0f, 0.62f),         // 50% render res
        BILINEAR("Bilinear", 1f),
        FSR("FSR", 3f)
    }

    private const val PREF_NAME = "karin_video_enhance"
    private const val KEY_ENABLED = "enhance_enabled"
    private const val KEY_COLOR_PRESET = "color_preset"
    private const val KEY_TINT = "tint"
    private const val KEY_INTERPOLATION = "interpolation_enabled"
    private const val KEY_INTERPOLATION_MODE = "interpolation_mode"
    private const val KEY_SATURATION = "saturation"
    private const val KEY_CONTRAST = "contrast"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_SHARPNESS = "sharpness"
    private const val KEY_COLOR_BOOST = "color_boost"
    private const val KEY_DENOISE = "denoise"
    private const val KEY_DEBAND = "deband"
    private const val KEY_DEBLOCK = "deblock"
    private const val KEY_DEBLOCK_EN = "deblock_en"
    private const val KEY_DESRINGING = "desringing"
    private const val KEY_DESRINGING_EN = "desringing_en"
    private const val KEY_LOCAL_CONTRAST = "local_contrast"
    private const val KEY_LOCAL_CONTRAST_EN = "local_contrast_en"
    private const val KEY_GRAIN = "grain"
    private const val KEY_GRAIN_EN = "grain_en"
    private const val KEY_DEHAZE = "dehaze"
    private const val KEY_DEHAZE_EN = "dehaze_en"
    private const val KEY_ADAPTIVE_SHARP = "adaptive_sharp"
    private const val KEY_ADAPTIVE_SHARP_EN = "adaptive_sharp_en"
    private const val KEY_HDR = "hdr"
    private const val KEY_HDR_EN = "hdr_en"
    private const val KEY_DETAIL_BOOST = "detail_boost"
    private const val KEY_DETAIL_BOOST_EN = "detail_boost_en"
    private const val KEY_LIGHT_BOOST = "light_boost"
    private const val KEY_LIGHT_BOOST_EN = "light_boost_en"
    private const val KEY_SUPER_RES = "super_res"
    private const val KEY_SUPER_RES_EN = "super_res_en"
    private const val KEY_DEBUG_MODE = "debug_mode"
    private const val KEY_UPSCALER_MODE = "upscaler_mode"
    private const val KEY_CODEC_MODE = "codec_mode"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, true) ?: true
    fun setEnabled(v: Boolean) { prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply() }

    fun colorPreset(): ColorPreset {
        val idx = prefs?.getInt(KEY_COLOR_PRESET, 0) ?: 0
        return ColorPreset.entries.getOrElse(idx.coerceIn(0, ColorPreset.entries.size - 1)) { ColorPreset.OFF }
    }
    fun setColorPreset(p: ColorPreset) { prefs?.edit()?.putInt(KEY_COLOR_PRESET, p.ordinal)?.apply() }

    fun getTint(): Float = prefs?.getFloat(KEY_TINT, 0.0f) ?: 0.0f
    fun setTint(v: Float) { prefs?.edit()?.putFloat(KEY_TINT, v.coerceIn(-0.2f, 0.2f))?.apply() }

    fun applyColorPreset(p: ColorPreset) {
        setColorPreset(p)
        when (p) {
            ColorPreset.OFF -> {
                setSaturation(1.0f); setContrast(1.0f); setBrightness(0.0f); setSharpness(0.0f); setTint(0.0f); setColorBoost(1.0f); setDenoise(0f)
            }
            ColorPreset.VIVIDO -> {
                setSaturation(1.65f); setContrast(1.25f); setBrightness(0.03f); setSharpness(0.35f); setTint(0.0f); setColorBoost(1.1f); setDenoise(0.05f)
            }
            ColorPreset.CINE -> {
                setSaturation(0.9f); setContrast(1.4f); setBrightness(-0.04f); setSharpness(0.15f); setTint(-0.03f); setColorBoost(0.95f); setDenoise(0.1f)
            }
            ColorPreset.ANIME -> {
                setSaturation(1.55f); setContrast(1.3f); setBrightness(0.02f); setSharpness(0.6f); setTint(0.02f); setColorBoost(1.15f); setDenoise(0.08f)
            }
            ColorPreset.CALIDO -> {
                setSaturation(1.15f); setContrast(1.12f); setBrightness(0.03f); setSharpness(0.1f); setTint(0.1f); setColorBoost(1.05f); setDenoise(0.05f)
            }
            ColorPreset.FRIO -> {
                setSaturation(1.15f); setContrast(1.12f); setBrightness(0.0f); setSharpness(0.1f); setTint(-0.1f); setColorBoost(1.05f); setDenoise(0.05f)
            }
        }
    }

    fun isInterpolationEnabled(): Boolean = prefs?.getBoolean(KEY_INTERPOLATION, false) ?: false
    fun setInterpolationEnabled(v: Boolean) { prefs?.edit()?.putBoolean(KEY_INTERPOLATION, v)?.apply() }

    fun interpolationMode(): InterpolationMode {
        val idx = prefs?.getInt(KEY_INTERPOLATION_MODE, InterpolationMode.HYBRID.ordinal) ?: InterpolationMode.HYBRID.ordinal
        return InterpolationMode.entries.getOrElse(idx.coerceIn(0, InterpolationMode.entries.size - 1)) { InterpolationMode.HYBRID }
    }
    fun setInterpolationMode(m: InterpolationMode) { prefs?.edit()?.putInt(KEY_INTERPOLATION_MODE, m.ordinal)?.apply() }

    fun qualityLabel(): String = if (isInterpolationEnabled()) "60p ${interpolationMode().label}" else "OFF"

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

    fun deblockEnabled(): Boolean = prefs?.getBoolean(KEY_DEBLOCK_EN, false) ?: false
    fun setDeblockEnabled(b: Boolean) { prefs?.edit()?.putBoolean(KEY_DEBLOCK_EN, b)?.apply() }
    fun getDeblock(): Float = prefs?.getFloat(KEY_DEBLOCK, 0.3f) ?: 0.3f
    fun setDeblock(v: Float) { prefs?.edit()?.putFloat(KEY_DEBLOCK, v.coerceIn(0f, 1f))?.apply() }

    fun desringingEnabled(): Boolean = prefs?.getBoolean(KEY_DESRINGING_EN, false) ?: false
    fun setDesringingEnabled(b: Boolean) { prefs?.edit()?.putBoolean(KEY_DESRINGING_EN, b)?.apply() }
    fun getDesringing(): Float = prefs?.getFloat(KEY_DESRINGING, 0.3f) ?: 0.3f
    fun setDesringing(v: Float) { prefs?.edit()?.putFloat(KEY_DESRINGING, v.coerceIn(0f, 1f))?.apply() }

    fun localContrastEnabled(): Boolean = prefs?.getBoolean(KEY_LOCAL_CONTRAST_EN, false) ?: false
    fun setLocalContrastEnabled(b: Boolean) { prefs?.edit()?.putBoolean(KEY_LOCAL_CONTRAST_EN, b)?.apply() }
    fun getLocalContrast(): Float = prefs?.getFloat(KEY_LOCAL_CONTRAST, 0.3f) ?: 0.3f
    fun setLocalContrast(v: Float) { prefs?.edit()?.putFloat(KEY_LOCAL_CONTRAST, v.coerceIn(0f, 1f))?.apply() }

    fun grainEnabled(): Boolean = prefs?.getBoolean(KEY_GRAIN_EN, false) ?: false
    fun setGrainEnabled(b: Boolean) { prefs?.edit()?.putBoolean(KEY_GRAIN_EN, b)?.apply() }
    fun getGrain(): Float = prefs?.getFloat(KEY_GRAIN, 0.3f) ?: 0.3f
    fun setGrain(v: Float) { prefs?.edit()?.putFloat(KEY_GRAIN, v.coerceIn(0f, 1f))?.apply() }

    fun dehazeEnabled(): Boolean = prefs?.getBoolean(KEY_DEHAZE_EN, false) ?: false
    fun setDehazeEnabled(b: Boolean) { prefs?.edit()?.putBoolean(KEY_DEHAZE_EN, b)?.apply() }
    fun getDehaze(): Float = prefs?.getFloat(KEY_DEHAZE, 0.3f) ?: 0.3f
    fun setDehaze(v: Float) { prefs?.edit()?.putFloat(KEY_DEHAZE, v.coerceIn(0f, 1f))?.apply() }

    fun adaptiveSharpEnabled(): Boolean = prefs?.getBoolean(KEY_ADAPTIVE_SHARP_EN, false) ?: false
    fun setAdaptiveSharpEnabled(b: Boolean) { prefs?.edit()?.putBoolean(KEY_ADAPTIVE_SHARP_EN, b)?.apply() }
    fun getAdaptiveSharp(): Float = prefs?.getFloat(KEY_ADAPTIVE_SHARP, 0.4f) ?: 0.4f
    fun setAdaptiveSharp(v: Float) { prefs?.edit()?.putFloat(KEY_ADAPTIVE_SHARP, v.coerceIn(0f, 1f))?.apply() }

    fun hdrEnabled(): Boolean = prefs?.getBoolean(KEY_HDR_EN, false) ?: false
    fun setHdrEnabled(b: Boolean) { prefs?.edit()?.putBoolean(KEY_HDR_EN, b)?.apply() }
    fun getHdr(): Float = prefs?.getFloat(KEY_HDR, 0.5f) ?: 0.5f
    fun setHdr(v: Float) { prefs?.edit()?.putFloat(KEY_HDR, v.coerceIn(0f, 1f))?.apply() }

    fun detailBoostEnabled(): Boolean = prefs?.getBoolean(KEY_DETAIL_BOOST_EN, false) ?: false
    fun setDetailBoostEnabled(b: Boolean) { prefs?.edit()?.putBoolean(KEY_DETAIL_BOOST_EN, b)?.apply() }
     fun getDetailBoost(): Float = prefs?.getFloat(KEY_DETAIL_BOOST, 0.7f) ?: 0.7f
     fun setDetailBoost(v: Float) { prefs?.edit()?.putFloat(KEY_DETAIL_BOOST, v.coerceIn(0f, 1f))?.apply() }

    fun lightBoostEnabled(): Boolean = prefs?.getBoolean(KEY_LIGHT_BOOST_EN, false) ?: false
    fun setLightBoostEnabled(b: Boolean) { prefs?.edit()?.putBoolean(KEY_LIGHT_BOOST_EN, b)?.apply() }
     fun getLightBoost(): Float = prefs?.getFloat(KEY_LIGHT_BOOST, 0.7f) ?: 0.7f
     fun setLightBoost(v: Float) { prefs?.edit()?.putFloat(KEY_LIGHT_BOOST, v.coerceIn(0f, 1f))?.apply() }

    fun superResEnabled(): Boolean = prefs?.getBoolean(KEY_SUPER_RES_EN, false) ?: false
    fun setSuperResEnabled(b: Boolean) { prefs?.edit()?.putBoolean(KEY_SUPER_RES_EN, b)?.apply() }
     fun getSuperRes(): Float = prefs?.getFloat(KEY_SUPER_RES, 0.7f) ?: 0.7f
     fun setSuperRes(v: Float) { prefs?.edit()?.putFloat(KEY_SUPER_RES, v.coerceIn(0f, 1f))?.apply() }

    fun getDebugMode(): Int = prefs?.getInt(KEY_DEBUG_MODE, 0) ?: 0
    fun setDebugMode(v: Int) { prefs?.edit()?.putInt(KEY_DEBUG_MODE, v.coerceIn(0, 7))?.apply() }

    fun getUpscalerMode(): UpscalerMode {
        val idx = prefs?.getInt(KEY_UPSCALER_MODE, 0) ?: 0
        return UpscalerMode.entries.getOrElse(idx.coerceIn(0, UpscalerMode.entries.size - 1)) { UpscalerMode.OFF }
    }
    fun setUpscalerMode(mode: UpscalerMode) { prefs?.edit()?.putInt(KEY_UPSCALER_MODE, mode.ordinal)?.apply() }

    val mainUpscalers: List<UpscalerMode>
        get() = listOf(UpscalerMode.OFF, UpscalerMode.ANIME4K, UpscalerMode.BILINEAR, UpscalerMode.FSR)

    val fsrQualities: List<UpscalerMode>
        get() = listOf(
            UpscalerMode.FSR_ULTRA_QUALITY,
            UpscalerMode.FSR_QUALITY,
            UpscalerMode.FSR_BALANCED,
            UpscalerMode.FSR_PERFORMANCE
        )

    fun isFsr(mode: UpscalerMode): Boolean = mode.value >= 4f
    fun currentFsr(): UpscalerMode = fsrQualities.firstOrNull { it.ordinal == getUpscalerMode().ordinal } ?: UpscalerMode.FSR_ULTRA_QUALITY

    fun codecMode(): CodecMode {
        val idx = prefs?.getInt(KEY_CODEC_MODE, 0) ?: 0
        return CodecMode.entries.getOrElse(idx.coerceIn(0, CodecMode.entries.size - 1)) { CodecMode.HW }
    }
    fun setCodecMode(mode: CodecMode) { prefs?.edit()?.putInt(KEY_CODEC_MODE, mode.ordinal)?.apply() }

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

    fun seekBarToDeblock(progress: Int): Float = progress / 100f
    fun deblockToSeekBar(value: Float): Int = (value * 100).toInt().coerceIn(0, 100)

    fun seekBarToLocalContrast(progress: Int): Float = progress / 100f
    fun localContrastToSeekBar(value: Float): Int = (value * 100).toInt().coerceIn(0, 100)

    fun seekBarToGrain(progress: Int): Float = progress / 100f
    fun grainToSeekBar(value: Float): Int = (value * 100).toInt().coerceIn(0, 100)

    fun seekBarToDehaze(progress: Int): Float = progress / 100f
    fun dehazeToSeekBar(value: Float): Int = (value * 100).toInt().coerceIn(0, 100)

    fun seekBarToHdr(progress: Int): Float = progress / 100f
    fun hdrToSeekBar(value: Float): Int = (value * 100).toInt().coerceIn(0, 100)

    fun seekBarToDetailBoost(progress: Int): Float = progress / 100f
    fun detailBoostToSeekBar(value: Float): Int = (value * 100).toInt().coerceIn(0, 100)

    fun seekBarToLightBoost(progress: Int): Float = progress / 100f
    fun lightBoostToSeekBar(value: Float): Int = (value * 100).toInt().coerceIn(0, 100)

    fun seekBarToSuperRes(progress: Int): Float = progress / 100f
    fun superResToSeekBar(value: Float): Int = (value * 100).toInt().coerceIn(0, 100)
}
