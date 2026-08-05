package com.karin.streamtv.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.karin.streamtv.R
import com.karin.streamtv.player.VideoEnhanceConfig
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.AutoPlayManager
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.GamepadHelper
import com.karin.streamtv.util.onActionKey

class SettingsActivity : FragmentActivity() {

    private lateinit var switchServerFallback: SwitchMaterial
    private lateinit var switchAutoplay: SwitchMaterial
    private lateinit var switchPlayNow: SwitchMaterial
    private lateinit var switchKarinLink: SwitchMaterial
    private lateinit var switchSkipOpening: SwitchMaterial
    private lateinit var switchSkipEnding: SwitchMaterial
    private lateinit var switchVideoPlayer: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchServerFallback = findViewById(R.id.switch_server_fallback)
        switchAutoplay = findViewById(R.id.switch_autoplay)
        switchPlayNow = findViewById(R.id.switch_playnow)
        switchKarinLink = findViewById(R.id.switch_karin_link)
        switchSkipOpening = findViewById(R.id.switch_skip_opening)
        switchSkipEnding = findViewById(R.id.switch_skip_ending)
        switchVideoPlayer = findViewById(R.id.switch_video_player)

        switchServerFallback.isChecked = AppPreferences.isServerFallbackEnabled()
        switchAutoplay.isChecked = AppPreferences.isAutoPlayEnabled()
        switchPlayNow.isChecked = AppPreferences.isPlayNowEnabled()
        switchKarinLink.isChecked = AppPreferences.isKarinLinkEnabled()
        switchSkipOpening.isChecked = AppPreferences.isSkipOpeningEnabled()
        switchSkipEnding.isChecked = AppPreferences.isSkipEndingEnabled()
        switchVideoPlayer.isChecked = AppPreferences.isVideoPlayerModeEnabled()

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
        switchVideoPlayer.setOnCheckedChangeListener { _, _ -> switchListener(switchVideoPlayer, "Reproductor de video del sistema") }

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

        setupCodecRow()
        setupAudioRow()

        if (DeviceUtils.isTvDevice(this)) {
            btnBack.post { btnBack.requestFocus() }
        } else {
            if (findViewById<android.widget.FrameLayout>(android.R.id.content).childCount > 0) {
                switchServerFallback.requestFocus()
            }
        }

        applyHighContrastIfNeeded()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) {
            return onKeyDown(mapped, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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

    private fun setupCodecRow() {
        VideoEnhanceConfig.init(this)
        val row = findViewById<android.widget.LinearLayout>(R.id.row_codec)
        val value = findViewById<TextView>(R.id.txt_codec_value)
        fun refresh() {
            value.text = VideoEnhanceConfig.codecMode().label
        }
        refresh()
        val showDialog = {
            val modes = VideoEnhanceConfig.CodecMode.entries
            val labels = modes.map { it.label }.toTypedArray()
            val current = VideoEnhanceConfig.codecMode()
            val selectedIdx = modes.indexOf(current).coerceAtLeast(0)
            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("Códec ExoPlayer")
                .setSingleChoiceItems(labels, selectedIdx) { _, which ->
                    VideoEnhanceConfig.setCodecMode(modes[which])
                    refresh()
                    row.announceForAccessibility("Códec ExoPlayer: ${modes[which].label}")
                }
                .setNegativeButton("Cerrar", null)
                .create()
            dialog.show()
        }
        row.setOnClickListener { showDialog() }
        row.onActionKey { showDialog() }
    }

    private fun setupAudioRow() {
        com.karin.streamtv.player.dsp.AudioEnhanceConfig.init(this)
        val row = findViewById<android.widget.LinearLayout>(R.id.row_audio)
        val value = findViewById<TextView>(R.id.txt_audio_value)
        fun refresh() {
            val config = com.karin.streamtv.player.dsp.AudioEnhanceConfig
            val preset = config.preset()
            val enabled = config.isEnabled()
            val auto = config.isAutoDevice()
            value.text = when {
                !enabled || preset == com.karin.streamtv.player.dsp.AudioEnhanceConfig.Preset.OFF -> "Apagado"
                auto -> "Automático · ${config.outputDeviceLabel()}"
                else -> preset.label
            }
        }
        refresh()
        val showDialog = {
            com.karin.streamtv.player.dsp.AudioDspUi.showPresetDialog(this, onAdvanced = {
                com.karin.streamtv.player.dsp.AudioDspUi.showAdvanced(this)
            }, onChanged = {
                refresh()
                row.announceForAccessibility(value.text.toString())
            })
        }
        row.setOnClickListener { showDialog() }
        row.onActionKey { showDialog() }
    }

    private fun saveSettings() {
        AppPreferences.setServerFallbackEnabled(switchServerFallback.isChecked)
        AppPreferences.setAutoPlayEnabled(switchAutoplay.isChecked)
        AppPreferences.setPlayNowEnabled(switchPlayNow.isChecked)
        AppPreferences.setKarinLinkEnabled(switchKarinLink.isChecked)
        AppPreferences.setSkipOpeningEnabled(switchSkipOpening.isChecked)
        AppPreferences.setSkipEndingEnabled(switchSkipEnding.isChecked)
        AppPreferences.setVideoPlayerModeEnabled(switchVideoPlayer.isChecked)
        AutoPlayManager.setAutoPlayEnabled(switchAutoplay.isChecked)

        val btnSave = findViewById<TextView>(R.id.btn_save)
        btnSave.announceForAccessibility("Configuración guardada")
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        finish()
    }
}