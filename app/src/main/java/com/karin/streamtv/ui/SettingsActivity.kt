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
    private lateinit var switchVideoPlayer: SwitchMaterial
    private lateinit var switchGlQuality: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchServerFallback = findViewById(R.id.switch_server_fallback)
        switchAutoplay = findViewById(R.id.switch_autoplay)
        switchPlayNow = findViewById(R.id.switch_playnow)
        switchKarinLink = findViewById(R.id.switch_karin_link)
        switchVideoPlayer = findViewById(R.id.switch_video_player)
        switchGlQuality = findViewById(R.id.switch_gl_quality)

        switchServerFallback.isChecked = AppPreferences.isServerFallbackEnabled()
        switchAutoplay.isChecked = AppPreferences.isAutoPlayEnabled()
        switchPlayNow.isChecked = AppPreferences.isPlayNowEnabled()
        switchKarinLink.isChecked = AppPreferences.isKarinLinkEnabled()
        switchVideoPlayer.isChecked = AppPreferences.isVideoPlayerModeEnabled()
        switchGlQuality.isChecked = VideoEnhanceConfig.isGlQualityMode()

        val switchListener = { switch: SwitchMaterial, label: String ->
            switch.contentDescription = "$label: ${if (switch.isChecked) "activado" else "desactivado"}"
            switch.announceForAccessibility(switch.contentDescription)
        }

        switchServerFallback.setOnCheckedChangeListener { _, _ -> switchListener(switchServerFallback, "Fallback de servidores") }
        switchAutoplay.setOnCheckedChangeListener { _, _ -> switchListener(switchAutoplay, "Auto-play") }
        switchPlayNow.setOnCheckedChangeListener { _, _ -> switchListener(switchPlayNow, "PlayNow") }
        switchKarinLink.setOnCheckedChangeListener { _, _ -> switchListener(switchKarinLink, "KARIN Link") }
        switchVideoPlayer.setOnCheckedChangeListener { _, _ -> switchListener(switchVideoPlayer, "Reproductor de video del sistema") }
        switchGlQuality.setOnCheckedChangeListener { _, _ ->
            VideoEnhanceConfig.setGlQualityMode(switchGlQuality.isChecked)
            switchListener(switchGlQuality, "Calidad GL")
        }

        val rowGlQuality = findViewById<android.widget.LinearLayout>(R.id.row_gl_quality)
        rowGlQuality.setOnClickListener { switchGlQuality.toggle() }
        rowGlQuality.onActionKey { switchGlQuality.toggle() }

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
        val rowCodec = findViewById<android.widget.LinearLayout>(R.id.row_codec)
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
                    rowCodec.announceForAccessibility("Códec ExoPlayer: ${modes[which].label}")
                }
                .setNegativeButton("Cerrar", null)
                .create()
            dialog.show()
        }
        rowCodec.setOnClickListener { showDialog() }
        rowCodec.onActionKey { showDialog() }
    }

    private fun saveSettings() {
        AppPreferences.setServerFallbackEnabled(switchServerFallback.isChecked)
        AppPreferences.setAutoPlayEnabled(switchAutoplay.isChecked)
        AppPreferences.setPlayNowEnabled(switchPlayNow.isChecked)
        AppPreferences.setKarinLinkEnabled(switchKarinLink.isChecked)
        AppPreferences.setVideoPlayerModeEnabled(switchVideoPlayer.isChecked)
        AutoPlayManager.setAutoPlayEnabled(switchAutoplay.isChecked)
        VideoEnhanceConfig.setGlQualityMode(switchGlQuality.isChecked)

        val btnSave = findViewById<TextView>(R.id.btn_save)
        btnSave.announceForAccessibility("Configuración guardada")
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        finish()
    }
}