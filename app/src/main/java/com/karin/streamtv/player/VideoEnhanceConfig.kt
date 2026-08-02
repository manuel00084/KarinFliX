package com.karin.streamtv.player

import android.content.Context
import android.content.SharedPreferences

object VideoEnhanceConfig {

    private const val PREF_NAME = "karin_video_enhance"
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

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, true) ?: true
    fun setEnabled(v: Boolean) { prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply() }

    fun isInterpolationEnabled(): Boolean = prefs?.getBoolean(KEY_INTERPOLATION, true) ?: true
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
}
