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

        data class AudioPreset(
            val name: String,
            val equalizerBands: List<Short>,
            val bassBoost: Short
        )

        val PRESETS = listOf(
            AudioPreset(
                name = "Normal",
                equalizerBands = listOf(0, 0, 0, 0, 0),
                bassBoost = 0
            ),
            AudioPreset(
                name = "Cine",
                equalizerBands = listOf(1000, 500, 0, 600, 1000),
                bassBoost = 800
            ),
            AudioPreset(
                name = "Musica",
                equalizerBands = listOf(700, 400, 0, 500, 800),
                bassBoost = 500
            ),
            AudioPreset(
                name = "Bass Boost",
                equalizerBands = listOf(1200, 900, 300, 0, -300),
                bassBoost = 1000
            ),
            AudioPreset(
                name = "Voz",
                equalizerBands = listOf(-600, 0, 1000, 800, 300),
                bassBoost = 0
            )
        )

        val VOLUME_BOOST_LEVELS = listOf(0, 600, 1200, 1800, 2400)
        val VOLUME_BOOST_LABELS = listOf("1.0x", "1.5x", "2.0x", "2.5x", "3.0x")
    }

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentPresetIndex = 0
    private var currentVolumeBoostIndex = 0
    private var lastSessionId = -1
    private val handler = Handler(Looper.getMainLooper())
    private var reapplyRunnable: Runnable? = null
    private var fxEnabled: Boolean = AppPreferences.isFxSoundEnabled()

    val currentPresetName: String get() = PRESETS[currentPresetIndex].name
    val presetIndex: Int get() = currentPresetIndex
    val presetCount: Int get() = PRESETS.size
    val volumeBoostLabel: String get() = VOLUME_BOOST_LABELS[currentVolumeBoostIndex]
    val volumeBoostIndex: Int get() = currentVolumeBoostIndex

    fun attachToSession(audioSessionId: Int) {
        lastSessionId = audioSessionId
        release()

        try {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = true
            }
            Log.d(TAG, "Equalizer attached to session $audioSessionId, bands=${equalizer?.numberOfBands}, range=${equalizer?.bandLevelRange?.toList()}")
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer not supported: ${e.message}")
            equalizer = null
        }

        try {
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = true
            }
            Log.d(TAG, "BassBoost attached to session $audioSessionId")
        } catch (e: Exception) {
            Log.w(TAG, "BassBoost not supported: ${e.message}")
            bassBoost = null
        }

        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                enabled = true
            }
            Log.d(TAG, "LoudnessEnhancer attached to session $audioSessionId")
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer not supported: ${e.message}")
            loudnessEnhancer = null
        }

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
            Log.d(TAG, "FxSound is OFF — effects not applied on session attach")
        }
    }

    private fun reapplyWithDelay(delayMs: Long) {
        reapplyRunnable?.let { handler.removeCallbacks(it) }
        reapplyRunnable = Runnable {
            if (lastSessionId > 0) {
                applyPresetImmediate(currentPresetIndex)
                applyVolumeBoostImmediate(currentVolumeBoostIndex)
                Log.d(TAG, "Reapplied effects: preset=$currentPresetName, boost=${volumeBoostLabel}")
            }
        }
        handler.postDelayed(reapplyRunnable!!, delayMs)
    }

    fun cyclePreset(): String {
        currentPresetIndex = (currentPresetIndex + 1) % PRESETS.size
        AppPreferences.setAudioPresetIndex(currentPresetIndex)
        if (fxEnabled) applyPresetImmediate(currentPresetIndex)
        return PRESETS[currentPresetIndex].name
    }

    fun setPreset(index: Int) {
        currentPresetIndex = index.coerceIn(0, PRESETS.size - 1)
        AppPreferences.setAudioPresetIndex(currentPresetIndex)
        if (fxEnabled) applyPresetImmediate(currentPresetIndex)
    }

    fun cycleVolumeBoost(): Int {
        currentVolumeBoostIndex = (currentVolumeBoostIndex + 1) % VOLUME_BOOST_LEVELS.size
        AppPreferences.setVolumeBoostIndex(currentVolumeBoostIndex)
        if (fxEnabled) applyVolumeBoostImmediate(currentVolumeBoostIndex)
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
        return fxEnabled
    }

    fun disableAllFx() {
        equalizer?.let { try { it.enabled = false } catch (_: Exception) {} }
        bassBoost?.let { try { it.enabled = false } catch (_: Exception) {} }
        loudnessEnhancer?.let { try { it.enabled = false } catch (_: Exception) {} }
        Log.d(TAG, "All audio effects disabled (EQ + BB + LE)")
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
                val numBands = eq.numberOfBands.toInt()
                val minLevel = eq.bandLevelRange[0].toInt()
                val maxLevel = eq.bandLevelRange[1].toInt()
                val bandCount = minOf(preset.equalizerBands.size, numBands)

                for (i in 0 until bandCount) {
                    val level = preset.equalizerBands[i].toInt()
                        .coerceIn(minLevel, maxLevel).toShort()
                    eq.setBandLevel(i.toShort(), level)
                }
                eq.enabled = true
                Log.d(TAG, "Equalizer bands set: ${preset.equalizerBands.take(bandCount)}, range=[$minLevel, $maxLevel]")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set equalizer: ${e.message}")
            }
        }

        bassBoost?.let { bb ->
            try {
                @Suppress("DEPRECATION")
                bb.setStrength(preset.bassBoost)
                bb.enabled = preset.bassBoost > 0
                Log.d(TAG, "BassBoost strength=${preset.bassBoost}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set bass boost: ${e.message}")
            }
        }

        Log.d(TAG, "Applied preset: ${preset.name} (index=$index)")
    }

    private fun applyVolumeBoostImmediate(boostIndex: Int) {
        val gainMb = VOLUME_BOOST_LEVELS[boostIndex]

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

    fun release() {
        reapplyRunnable?.let { handler.removeCallbacks(it) }
        reapplyRunnable = null
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
        loudnessEnhancer = null
    }
}
