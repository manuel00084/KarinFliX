package com.karin.streamtv.ui

import android.graphics.Color
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.karin.streamtv.R
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.AudioEffectsManager
import com.karin.streamtv.util.AutoPlayManager
import com.karin.streamtv.util.onActionKey

class SettingsActivity : FragmentActivity() {

    private lateinit var switchServerFallback: SwitchMaterial
    private lateinit var switchAutoplay: SwitchMaterial
    private lateinit var switchPlayNow: SwitchMaterial
    private lateinit var switchKarinLink: SwitchMaterial
    private lateinit var switchSkipOpening: SwitchMaterial
    private lateinit var switchSkipEnding: SwitchMaterial
    private lateinit var switchFxSound: SwitchMaterial
    private lateinit var tvVolumeBoostValue: TextView
    private lateinit var tvAudioPresetValue: TextView

    private var tempVolumeBoostIndex = 0
    private var tempAudioPresetIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        AppPreferences.init(this)

        switchServerFallback = findViewById(R.id.switch_server_fallback)
        switchAutoplay = findViewById(R.id.switch_autoplay)
        switchPlayNow = findViewById(R.id.switch_playnow)
        switchKarinLink = findViewById(R.id.switch_karin_link)
        switchSkipOpening = findViewById(R.id.switch_skip_opening)
        switchSkipEnding = findViewById(R.id.switch_skip_ending)
        switchFxSound = findViewById(R.id.switch_fx_sound)
        tvVolumeBoostValue = findViewById(R.id.tv_volume_boost_value)
        tvAudioPresetValue = findViewById(R.id.tv_audio_preset_value)

        switchServerFallback.isChecked = AppPreferences.isServerFallbackEnabled()
        switchAutoplay.isChecked = AppPreferences.isAutoPlayEnabled()
        switchPlayNow.isChecked = AppPreferences.isPlayNowEnabled()
        switchKarinLink.isChecked = AppPreferences.isKarinLinkEnabled()
        switchSkipOpening.isChecked = AppPreferences.isSkipOpeningEnabled()
        switchSkipEnding.isChecked = AppPreferences.isSkipEndingEnabled()
        switchFxSound.isChecked = AppPreferences.isFxSoundEnabled()

        tempVolumeBoostIndex = AppPreferences.getVolumeBoostIndex().coerceIn(0, AudioEffectsManager.VOLUME_BOOST_LABELS.size - 1)
        tempAudioPresetIndex = AppPreferences.getAudioPresetIndex().coerceIn(0, AudioEffectsManager.PRESETS.size - 1)

        tvVolumeBoostValue.text = AudioEffectsManager.VOLUME_BOOST_LABELS[tempVolumeBoostIndex]
        tvAudioPresetValue.text = AudioEffectsManager.PRESETS[tempAudioPresetIndex].name

        switchServerFallback.contentDescription = "Fallback de servidores: ${if (switchServerFallback.isChecked) "activado" else "desactivado"}"
        switchAutoplay.contentDescription = "Auto-play: ${if (switchAutoplay.isChecked) "activado" else "desactivado"}"
        switchPlayNow.contentDescription = "PlayNow: ${if (switchPlayNow.isChecked) "activado" else "desactivado"}"
        switchKarinLink.contentDescription = "KARIN Link: ${if (switchKarinLink.isChecked) "activado" else "desactivado"}"
        switchSkipOpening.contentDescription = "Saltar opening: ${if (switchSkipOpening.isChecked) "activado" else "desactivado"}"
        switchSkipEnding.contentDescription = "Saltar ending: ${if (switchSkipEnding.isChecked) "activado" else "desactivado"}"
        switchFxSound.contentDescription = "FxSound: ${if (switchFxSound.isChecked) "activado" else "desactivado"}"
        updateVolumeBoostContentDescription()
        updateAudioPresetContentDescription()

        val switchListener = { switch: SwitchMaterial, label: String ->
            switch.contentDescription = "$label: ${if (switch.isChecked) "activado" else "desactivado"}"
            switch.announceForAccessibility(switch.contentDescription)
        }

        switchServerFallback.setOnCheckedChangeListener { _, _ -> switchListener(switchServerFallback, "Fallback de servidores") }
        switchAutoplay.setOnCheckedChangeListener { _, _ -> switchListener(switchAutoplay, "Auto-play") }
        switchPlayNow.setOnCheckedChangeListener { _, _ -> switchListener(switchPlayNow, "PlayNow") }
        switchKarinLink.setOnCheckedChangeListener { _, _ -> switchListener(switchKarinLink, "KARIN Link") }
        switchSkipOpening.setOnCheckedChangeListener { _, _ -> switchListener(switchSkipOpening, "Saltar opening") }
        switchSkipEnding.setOnCheckedChangeListener { _, _ -> switchListener(switchSkipEnding, "Saltar ending") }
        switchFxSound.setOnCheckedChangeListener { _, _ -> switchListener(switchFxSound, "FxSound") }

        tvVolumeBoostValue.setOnClickListener {
            tempVolumeBoostIndex = (tempVolumeBoostIndex + 1) % AudioEffectsManager.VOLUME_BOOST_LABELS.size
            tvVolumeBoostValue.text = AudioEffectsManager.VOLUME_BOOST_LABELS[tempVolumeBoostIndex]
            updateVolumeBoostContentDescription()
        }
        tvVolumeBoostValue.onActionKey { tvVolumeBoostValue.performClick() }

        tvAudioPresetValue.setOnClickListener {
            tempAudioPresetIndex = (tempAudioPresetIndex + 1) % AudioEffectsManager.PRESETS.size
            tvAudioPresetValue.text = AudioEffectsManager.PRESETS[tempAudioPresetIndex].name
            updateAudioPresetContentDescription()
        }
        tvAudioPresetValue.onActionKey { tvAudioPresetValue.performClick() }

        val btnSave = findViewById<TextView>(R.id.btn_save)
        btnSave.setOnClickListener { saveSettings() }
        btnSave.onActionKey { btnSave.performClick() }

        val btnBack = findViewById<TextView>(R.id.btn_back)
        btnBack.setOnClickListener { finish() }
        btnBack.onActionKey { btnBack.performClick() }

        val btnTutorial = findViewById<android.widget.LinearLayout>(R.id.row_tutorial)
        btnTutorial.setOnClickListener {
            startActivity(android.content.Intent(this, TutorialActivity::class.java))
        }
        btnTutorial.onActionKey { btnTutorial.performClick() }

        val rowTerms = findViewById<android.widget.LinearLayout>(R.id.row_terms)
        rowTerms.setOnClickListener {
            startActivity(android.content.Intent(this, TermsAndConditionsActivity::class.java))
        }
        rowTerms.onActionKey { rowTerms.performClick() }

        val rowCredits = findViewById<android.widget.LinearLayout>(R.id.row_credits)
        rowCredits.setOnClickListener {
            startActivity(android.content.Intent(this, CreditsActivity::class.java))
        }
        rowCredits.onActionKey { rowCredits.performClick() }

        if (findViewById<android.widget.FrameLayout>(android.R.id.content).childCount > 0) {
            switchServerFallback.requestFocus()
        }

        applyHighContrastIfNeeded()
    }

    private fun updateVolumeBoostContentDescription() {
        tvVolumeBoostValue.contentDescription = "Volumen: ${AudioEffectsManager.VOLUME_BOOST_LABELS[tempVolumeBoostIndex]}. Pulsa para cambiar."
    }

    private fun updateAudioPresetContentDescription() {
        tvAudioPresetValue.contentDescription = "Preset: ${AudioEffectsManager.PRESETS[tempAudioPresetIndex].name}. Pulsa para cambiar."
    }

    private fun applyHighContrastIfNeeded() {
        try {
            val am = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
            val method = am.javaClass.getMethod("isHighTextContrastEnabled")
            val enabled = method.invoke(am) as? Boolean ?: false
            if (enabled) {
                val btnBack = findViewById<TextView>(R.id.btn_back)
                btnBack?.setTextColor(Color.WHITE)
            }
        } catch (_: Exception) { }
    }

    private fun saveSettings() {
        val editor = AppPreferences.getPrefs()?.edit()
        editor?.putBoolean("server_fallback_enabled", switchServerFallback.isChecked)
        editor?.putBoolean("autoplay_enabled", switchAutoplay.isChecked)
        editor?.putBoolean("playnow_enabled", switchPlayNow.isChecked)
        editor?.putBoolean("karin_link_enabled", switchKarinLink.isChecked)
        editor?.putBoolean("skip_opening_enabled", switchSkipOpening.isChecked)
        editor?.putBoolean("skip_ending_enabled", switchSkipEnding.isChecked)
        editor?.putBoolean("fx_sound_enabled", switchFxSound.isChecked)
        editor?.putInt("volume_boost_level_idx", tempVolumeBoostIndex)
        editor?.putFloat("volume_boost_level", AudioEffectsManager.VOLUME_BOOST_LEVELS[tempVolumeBoostIndex].toFloat())
        editor?.putInt("audio_preset_index", tempAudioPresetIndex)
        editor?.apply()
        AutoPlayManager.setAutoPlayEnabled(switchAutoplay.isChecked)
        val btnSave = findViewById<TextView>(R.id.btn_save)
        btnSave.announceForAccessibility("Configuración guardada")
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        finish()
    }
}
