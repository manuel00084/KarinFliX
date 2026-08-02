package com.karin.streamtv.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.widget.SeekBar
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
    private lateinit var switchVideoEnhance: SwitchMaterial
    private lateinit var switchInterpolation: SwitchMaterial

    private lateinit var seekSaturation: SeekBar
    private lateinit var seekContrast: SeekBar
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekSharpness: SeekBar
    private lateinit var seekColorBoost: SeekBar
    private lateinit var seekDenoise: SeekBar
    private lateinit var seekDeband: SeekBar

    private lateinit var tvSaturation: TextView
    private lateinit var tvContrast: TextView
    private lateinit var tvBrightness: TextView
    private lateinit var tvSharpness: TextView
    private lateinit var tvColorBoost: TextView
    private lateinit var tvDenoise: TextView
    private lateinit var tvDeband: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        AppPreferences.init(this)
        VideoEnhanceConfig.init(this)

        switchServerFallback = findViewById(R.id.switch_server_fallback)
        switchAutoplay = findViewById(R.id.switch_autoplay)
        switchPlayNow = findViewById(R.id.switch_playnow)
        switchKarinLink = findViewById(R.id.switch_karin_link)
        switchSkipOpening = findViewById(R.id.switch_skip_opening)
        switchSkipEnding = findViewById(R.id.switch_skip_ending)
        switchVideoEnhance = findViewById(R.id.switch_video_enhance)
        switchInterpolation = findViewById(R.id.switch_interpolation)

        seekSaturation = findViewById(R.id.seekbar_saturation)
        seekContrast = findViewById(R.id.seekbar_contrast)
        seekBrightness = findViewById(R.id.seekbar_brightness)
        seekSharpness = findViewById(R.id.seekbar_sharpness)
        seekColorBoost = findViewById(R.id.seekbar_color_boost)
        seekDenoise = findViewById(R.id.seekbar_denoise)
        seekDeband = findViewById(R.id.seekbar_deband)

        tvSaturation = findViewById(R.id.tv_saturation_value)
        tvContrast = findViewById(R.id.tv_contrast_value)
        tvBrightness = findViewById(R.id.tv_brightness_value)
        tvSharpness = findViewById(R.id.tv_sharpness_value)
        tvColorBoost = findViewById(R.id.tv_color_boost_value)
        tvDenoise = findViewById(R.id.tv_denoise_value)
        tvDeband = findViewById(R.id.tv_deband_value)

        switchServerFallback.isChecked = AppPreferences.isServerFallbackEnabled()
        switchAutoplay.isChecked = AppPreferences.isAutoPlayEnabled()
        switchPlayNow.isChecked = AppPreferences.isPlayNowEnabled()
        switchKarinLink.isChecked = AppPreferences.isKarinLinkEnabled()
        switchSkipOpening.isChecked = AppPreferences.isSkipOpeningEnabled()
        switchSkipEnding.isChecked = AppPreferences.isSkipEndingEnabled()
        switchVideoEnhance.isChecked = VideoEnhanceConfig.isEnabled()
        switchInterpolation.isChecked = VideoEnhanceConfig.isInterpolationEnabled()

        seekSaturation.progress = VideoEnhanceConfig.saturationToSeekBar(VideoEnhanceConfig.getSaturation())
        seekContrast.progress = VideoEnhanceConfig.contrastToSeekBar(VideoEnhanceConfig.getContrast())
        seekBrightness.progress = VideoEnhanceConfig.brightnessToSeekBar(VideoEnhanceConfig.getBrightness())
        seekSharpness.progress = VideoEnhanceConfig.sharpnessToSeekBar(VideoEnhanceConfig.getSharpness())
        seekColorBoost.progress = VideoEnhanceConfig.colorBoostToSeekBar(VideoEnhanceConfig.getColorBoost())
        seekDenoise.progress = VideoEnhanceConfig.denoiseToSeekBar(VideoEnhanceConfig.getDenoise())
        seekDeband.progress = VideoEnhanceConfig.debandToSeekBar(VideoEnhanceConfig.getDeband())

        updateSliderTexts()

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
        switchVideoEnhance.setOnCheckedChangeListener { _, _ -> switchListener(switchVideoEnhance, "Mejora de video") }
        switchInterpolation.setOnCheckedChangeListener { _, _ -> switchListener(switchInterpolation, "Motionx2 60p") }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateSliderTexts()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        seekSaturation.setOnSeekBarChangeListener(seekListener)
        seekContrast.setOnSeekBarChangeListener(seekListener)
        seekBrightness.setOnSeekBarChangeListener(seekListener)
        seekSharpness.setOnSeekBarChangeListener(seekListener)
        seekColorBoost.setOnSeekBarChangeListener(seekListener)
        seekDenoise.setOnSeekBarChangeListener(seekListener)
        seekDeband.setOnSeekBarChangeListener(seekListener)

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

        if (DeviceUtils.isTvDevice(this)) {
            btnBack.post { btnBack.requestFocus() }
        } else {
            if (findViewById<android.widget.FrameLayout>(android.R.id.content).childCount > 0) {
                switchServerFallback.requestFocus()
            }
        }

        applyHighContrastIfNeeded()
    }

    private fun updateSliderTexts() {
        val sat = VideoEnhanceConfig.seekBarToSaturation(seekSaturation.progress)
        val con = VideoEnhanceConfig.seekBarToContrast(seekContrast.progress)
        val bri = VideoEnhanceConfig.seekBarToBrightness(seekBrightness.progress)
        val sha = VideoEnhanceConfig.seekBarToSharpness(seekSharpness.progress)
        val col = VideoEnhanceConfig.seekBarToColorBoost(seekColorBoost.progress)
        val den = VideoEnhanceConfig.seekBarToDenoise(seekDenoise.progress)
        val deb = VideoEnhanceConfig.seekBarToDeband(seekDeband.progress)

        tvSaturation.text = "${(sat * 100).toInt()}%"
        tvContrast.text = "${(con * 100).toInt()}%"
        tvBrightness.text = "${(bri * 100).toInt()}%"
        tvSharpness.text = "${(sha * 100).toInt()}%"
        tvColorBoost.text = "${(col * 100).toInt()}%"
        tvDenoise.text = "${(den * 100).toInt()}%"
        tvDeband.text = "${(deb / 0.06f * 100).toInt()}%"
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

    private fun saveSettings() {
        val editor = AppPreferences.getPrefs()?.edit()
        editor?.putBoolean("server_fallback_enabled", switchServerFallback.isChecked)
        editor?.putBoolean("autoplay_enabled", switchAutoplay.isChecked)
        editor?.putBoolean("playnow_enabled", switchPlayNow.isChecked)
        editor?.putBoolean("karin_link_enabled", switchKarinLink.isChecked)
        editor?.putBoolean("skip_opening_enabled", switchSkipOpening.isChecked)
        editor?.putBoolean("skip_ending_enabled", switchSkipEnding.isChecked)
        editor?.apply()
        AutoPlayManager.setAutoPlayEnabled(switchAutoplay.isChecked)

        VideoEnhanceConfig.setEnabled(switchVideoEnhance.isChecked)
        VideoEnhanceConfig.setInterpolationEnabled(switchInterpolation.isChecked)
        VideoEnhanceConfig.setSaturation(VideoEnhanceConfig.seekBarToSaturation(seekSaturation.progress))
        VideoEnhanceConfig.setContrast(VideoEnhanceConfig.seekBarToContrast(seekContrast.progress))
        VideoEnhanceConfig.setBrightness(VideoEnhanceConfig.seekBarToBrightness(seekBrightness.progress))
        VideoEnhanceConfig.setSharpness(VideoEnhanceConfig.seekBarToSharpness(seekSharpness.progress))
        VideoEnhanceConfig.setColorBoost(VideoEnhanceConfig.seekBarToColorBoost(seekColorBoost.progress))
        VideoEnhanceConfig.setDenoise(VideoEnhanceConfig.seekBarToDenoise(seekDenoise.progress))
        VideoEnhanceConfig.setDeband(VideoEnhanceConfig.seekBarToDeband(seekDeband.progress))

        val btnSave = findViewById<TextView>(R.id.btn_save)
        btnSave.announceForAccessibility("Configuración guardada")
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        finish()
    }
}
