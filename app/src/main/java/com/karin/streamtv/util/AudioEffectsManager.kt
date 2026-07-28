package com.karin.streamtv.util

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.util.Log

class AudioEffectsManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioEffects"
        private const val MAX_SAFE_GAIN_MB = 1800

        data class AudioPreset(
            val name: String,
            val equalizerBands: List<Short>,
            val bassBoost: Short
        )

        val PRESETS = listOf(
            AudioPreset(name = "Normal", equalizerBands = listOf(0, 0, 0, 0, 0), bassBoost = 0),
            AudioPreset(name = "Cine", equalizerBands = listOf(1000, 500, 0, 600, 1000), bassBoost = 800),
            AudioPreset(name = "Musica", equalizerBands = listOf(700, 400, 0, 500, 800), bassBoost = 500),
            AudioPreset(name = "Bass Boost", equalizerBands = listOf(1200, 900, 300, 0, -300), bassBoost = 1000),
            AudioPreset(name = "Voz", equalizerBands = listOf(-600, 0, 1000, 800, 300), bassBoost = 0),
            AudioPreset(name = "Gaming", equalizerBands = listOf(800, 300, -200, 500, 1100), bassBoost = 600),
            AudioPreset(name = "Podcast", equalizerBands = listOf(-400, 200, 1200, 900, -200), bassBoost = 200)
        )

        val VOLUME_BOOST_LEVELS = listOf(0, 600, 1200, 1800, 2400)
        val VOLUME_BOOST_LABELS = listOf("1.0x", "1.5x", "2.0x", "2.5x", "3.0x")
    }

    interface FxStateListener {
        fun onFxStateChanged(enabled: Boolean, presetName: String, boostLabel: String)
    }

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentPresetIndex = 0
    private var currentVolumeBoostIndex = 0
    private var lastSessionId = -1
    private var isAttached = false
    private val handler = Handler(Looper.getMainLooper())
    private var reapplyRunnable: Runnable? = null
    private var fxEnabled: Boolean = AppPreferences.isFxSoundEnabled()
    private var listener: FxStateListener? = null

    private var cachedMinLevel: Short = -1500
    private var cachedMaxLevel: Short = 1500
    private var cachedNumBands: Short = 5

    val currentPresetName: String get() = PRESETS[currentPresetIndex].name
    val presetIndex: Int get() = currentPresetIndex
    val presetCount: Int get() = PRESETS.size
    val volumeBoostLabel: String get() = VOLUME_BOOST_LABELS[currentVolumeBoostIndex]
    val volumeBoostIndex: Int get() = currentVolumeBoostIndex
    val isSessionAttached: Boolean get() = isAttached

    fun setListener(l: FxStateListener?) { listener = l }

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId == lastSessionId && isAttached) {
            Log.d(TAG, "Session $audioSessionId already attached, skipping")
            return
        }
        lastSessionId = audioSessionId
        release()

        try {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = true
                cachedMinLevel = bandLevelRange[0]
                cachedMaxLevel = bandLevelRange[1]
                cachedNumBands = numberOfBands
            }
            Log.d(TAG, "Equalizer attached: session=$audioSessionId, bands=$cachedNumBands, range=[$cachedMinLevel, $cachedMaxLevel]")
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer not supported: ${e.message}")
            equalizer = null
        }

        try {
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = true
            }
            Log.d(TAG, "BassBoost attached: session=$audioSessionId")
        } catch (e: Exception) {
            Log.w(TAG, "BassBoost not supported: ${e.message}")
            bassBoost = null
        }

        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                enabled = true
            }
            Log.d(TAG, "LoudnessEnhancer attached: session=$audioSessionId")
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer not supported: ${e.message}")
            loudnessEnhancer = null
        }

        isAttached = true
        val savedPreset = AppPreferences.getAudioPresetIndex()
        currentPresetIndex = savedPreset.coerceIn(0, PRESETS.size - 1)
        val savedBoost = AppPreferences.getVolumeBoostIndex()
        currentVolumeBoostIndex = savedBoost.coerceIn(0, VOLUME_BOOST_LEVELS.size - 1)
        fxEnabled = AppPreferences.isFxSoundEnabled()

        if (fxEnabled) {
            applyPresetImmediate(currentPresetIndex)
            applyVolumeBoostImmediate(currentVolumeBoostIndex)
            reapplyWithDelay(500)
        } else {
            disableAllFx()
            Log.d(TAG, "FxSound is OFF - effects not applied on session attach")
        }

        notifyListener()
    }

    private fun reapplyWithDelay(delayMs: Long) {
        reapplyRunnable?.let { handler.removeCallbacks(it) }
        reapplyRunnable = Runnable {
            if (isAttached && lastSessionId > 0) {
                applyPresetImmediate(currentPresetIndex)
                applyVolumeBoostImmediate(currentVolumeBoostIndex)
                Log.d(TAG, "Reapplied effects: preset=$currentPresetName, boost=${volumeBoostLabel}")
            }
        }
        handler.postDelayed(reapplyRunnable!!, delayMs)
    }

    fun reapplyEffects() {
        if (!isAttached || !fxEnabled) return
        applyPresetImmediate(currentPresetIndex)
        applyVolumeBoostImmediate(currentVolumeBoostIndex)
    }

    fun cyclePreset(): String {
        currentPresetIndex = (currentPresetIndex + 1) % PRESETS.size
        AppPreferences.setAudioPresetIndex(currentPresetIndex)
        if (fxEnabled) applyPresetImmediate(currentPresetIndex)
        notifyListener()
        return PRESETS[currentPresetIndex].name
    }

    fun setPreset(index: Int) {
        currentPresetIndex = index.coerceIn(0, PRESETS.size - 1)
        AppPreferences.setAudioPresetIndex(currentPresetIndex)
        if (fxEnabled) applyPresetImmediate(currentPresetIndex)
        notifyListener()
    }

    fun cycleVolumeBoost(): Int {
        currentVolumeBoostIndex = (currentVolumeBoostIndex + 1) % VOLUME_BOOST_LEVELS.size
        AppPreferences.setVolumeBoostIndex(currentVolumeBoostIndex)
        if (fxEnabled) applyVolumeBoostImmediate(currentVolumeBoostIndex)
        notifyListener()
        return currentVolumeBoostIndex
    }

    fun getVolumeBoostGainMb(): Int = VOLUME_BOOST_LEVELS[currentVolumeBoostIndex]

    val isFxEnabled: Boolean get() = fxEnabled

    fun toggleFx(): Boolean {
        fxEnabled = !fxEnabled
        AppPreferences.setFxSoundEnabled(fxEnabled)
        if (fxEnabled) {
            applyPresetImmediate(currentPresetIndex)
            applyVolumeBoostImmediate(currentVolumeBoostIndex)
            Log.d(TAG, "FxSound ENABLED: preset=$currentPresetName, boost=${volumeBoostLabel}")
        } else {
            disableAllFx()
            Log.d(TAG, "FxSound DISABLED: all effects off")
        }
        notifyListener()
        return fxEnabled
    }

    fun disableAllFx() {
        equalizer?.let { try { it.enabled = false } catch (_: Exception) {} }
        bassBoost?.let { try { it.enabled = false } catch (_: Exception) {} }
        loudnessEnhancer?.let { try { it.enabled = false } catch (_: Exception) {} }
        Log.d(TAG, "All audio effects disabled")
    }

    fun enableAllFx() {
        if (!fxEnabled) return
        applyPresetImmediate(currentPresetIndex)
        applyVolumeBoostImmediate(currentVolumeBoostIndex)
        Log.d(TAG, "All audio effects re-enabled: preset=$currentPresetName, boost=${volumeBoostLabel}")
    }

    private fun applyPresetImmediate(index: Int) {
        val preset = PRESETS[index]

        equalizer?.let { eq ->
            try {
                val numBands = cachedNumBands.toInt()
                val minLevel = cachedMinLevel.toInt()
                val maxLevel = cachedMaxLevel.toInt()
                val bandCount = minOf(preset.equalizerBands.size, numBands)

                for (i in 0 until bandCount) {
                    val level = preset.equalizerBands[i].toInt()
                        .coerceIn(minLevel, maxLevel).toShort()
                    eq.setBandLevel(i.toShort(), level)
                }
                eq.enabled = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set equalizer: ${e.message}")
            }
        }

        bassBoost?.let { bb ->
            try {
                @Suppress("DEPRECATION")
                bb.setStrength(preset.bassBoost)
                bb.enabled = preset.bassBoost > 0
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set bass boost: ${e.message}")
            }
        }
    }

    private fun applyVolumeBoostImmediate(boostIndex: Int) {
        val gainMb = VOLUME_BOOST_LEVELS[boostIndex].coerceAtMost(MAX_SAFE_GAIN_MB)

        loudnessEnhancer?.let { le ->
            try {
                le.setTargetGain(gainMb)
                le.enabled = gainMb > 0
                Log.d(TAG, "LoudnessEnhancer gain=${gainMb}mB (${gainMb / 100}dB)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set loudness enhancer: ${e.message}")
            }
        }
    }

    private fun notifyListener() {
        listener?.onFxStateChanged(fxEnabled, currentPresetName, volumeBoostLabel)
    }

    fun release() {
        reapplyRunnable?.let { handler.removeCallbacks(it) }
        reapplyRunnable = null
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
        loudnessEnhancer = null
        isAttached = false
        lastSessionId = -1
    }
}
