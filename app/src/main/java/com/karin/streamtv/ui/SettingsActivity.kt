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
import com.karin.streamtv.player.dsp.AudioEnhanceConfig
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
    private lateinit var switchDspEnabled: SwitchMaterial
    private lateinit var switchVideoPlayer: SwitchMaterial

    private lateinit var tvDspPreset: TextView
    private lateinit var tvDspBass: TextView
    private lateinit var tvDspSubbass: TextView
    private lateinit var tvDspTreble: TextView
    private lateinit var tvDspPresence: TextView
    private lateinit var tvDspSurround: TextView
    private lateinit var tvDspExciter: TextView
    private lateinit var tvDspHarmbass: TextView
    private lateinit var tvDspCompression: TextView
    private lateinit var tvDspReverb: TextView
    private lateinit var tvDspMaster: TextView

    private lateinit var seekDspBass: SeekBar
    private lateinit var seekDspSubbass: SeekBar
    private lateinit var seekDspTreble: SeekBar
    private lateinit var seekDspPresence: SeekBar
    private lateinit var seekDspSurround: SeekBar
    private lateinit var seekDspExciter: SeekBar
    private lateinit var seekDspHarmbass: SeekBar
    private lateinit var seekDspCompression: SeekBar
    private lateinit var seekDspReverb: SeekBar
    private lateinit var seekDspMaster: SeekBar

    private var dspPreset = AudioEnhanceConfig.Preset.ANIME

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
        com.karin.streamtv.player.dsp.AudioEnhanceConfig.init(this)

        switchServerFallback = findViewById(R.id.switch_server_fallback)
        switchAutoplay = findViewById(R.id.switch_autoplay)
        switchPlayNow = findViewById(R.id.switch_playnow)
        switchKarinLink = findViewById(R.id.switch_karin_link)
        switchSkipOpening = findViewById(R.id.switch_skip_opening)
        switchSkipEnding = findViewById(R.id.switch_skip_ending)
        switchVideoEnhance = findViewById(R.id.switch_video_enhance)
        switchInterpolation = findViewById(R.id.switch_interpolation)
        switchDspEnabled = findViewById(R.id.switch_dsp_enabled)
        switchVideoPlayer = findViewById(R.id.switch_video_player)

        tvDspPreset = findViewById(R.id.tv_dsp_preset_value)
        tvDspBass = findViewById(R.id.tv_dsp_bass_value)
        tvDspSubbass = findViewById(R.id.tv_dsp_subbass_value)
        tvDspTreble = findViewById(R.id.tv_dsp_treble_value)
        tvDspPresence = findViewById(R.id.tv_dsp_presence_value)
        tvDspSurround = findViewById(R.id.tv_dsp_surround_value)
        tvDspExciter = findViewById(R.id.tv_dsp_exciter_value)
        tvDspHarmbass = findViewById(R.id.tv_dsp_harmbass_value)
        tvDspCompression = findViewById(R.id.tv_dsp_compression_value)
        tvDspReverb = findViewById(R.id.tv_dsp_reverb_value)
        tvDspMaster = findViewById(R.id.tv_dsp_master_value)

        seekDspBass = findViewById(R.id.seekbar_dsp_bass)
        seekDspSubbass = findViewById(R.id.seekbar_dsp_subbass)
        seekDspTreble = findViewById(R.id.seekbar_dsp_treble)
        seekDspPresence = findViewById(R.id.seekbar_dsp_presence)
        seekDspSurround = findViewById(R.id.seekbar_dsp_surround)
        seekDspExciter = findViewById(R.id.seekbar_dsp_exciter)
        seekDspHarmbass = findViewById(R.id.seekbar_dsp_harmbass)
        seekDspCompression = findViewById(R.id.seekbar_dsp_compression)
        seekDspReverb = findViewById(R.id.seekbar_dsp_reverb)
        seekDspMaster = findViewById(R.id.seekbar_dsp_master)

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
        dspPreset = AudioEnhanceConfig.preset()
        switchDspEnabled.isChecked = AudioEnhanceConfig.isEnabled()
        tvDspPreset.text = dspPreset.label
        seekDspBass.progress = dbToSeekBar(AudioEnhanceConfig.getBass())
        seekDspSubbass.progress = dbToSeekBar(AudioEnhanceConfig.getSubBass())
        seekDspTreble.progress = dbToSeekBar(AudioEnhanceConfig.getTreble())
        seekDspPresence.progress = dbToSeekBar(AudioEnhanceConfig.getPresence())
        seekDspSurround.progress = unitToSeekBar(AudioEnhanceConfig.getSurround(), 1.5f)
        seekDspExciter.progress = unitToSeekBar(AudioEnhanceConfig.getExciter(), 1f)
        seekDspHarmbass.progress = unitToSeekBar(AudioEnhanceConfig.getHarmbass(), 1f)
        seekDspCompression.progress = unitToSeekBar(AudioEnhanceConfig.getCompression(), 1f)
        seekDspReverb.progress = unitToSeekBar(AudioEnhanceConfig.getReverb(), 0.5f)
        seekDspMaster.progress = masterToSeekBar(AudioEnhanceConfig.getMaster())

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
        switchDspEnabled.setOnCheckedChangeListener { _, _ -> switchListener(switchDspEnabled, "Activar DSP en ExoPlayer") }
        switchVideoPlayer.setOnCheckedChangeListener { _, _ -> switchListener(switchVideoPlayer, "Reproductor de video del sistema") }

        val rowDspPreset = findViewById<android.widget.LinearLayout>(R.id.row_dsp_preset)
        rowDspPreset.setOnClickListener {
            val next = AudioEnhanceConfig.Preset.values()[(dspPreset.ordinal + 1) % AudioEnhanceConfig.Preset.values().size]
            dspPreset = next
            tvDspPreset.text = next.label
            val p = AudioEnhanceConfig.Params().withPreset(next)
            switchDspEnabled.isChecked = true
            seekDspBass.progress = dbToSeekBar(p.bassGain)
            seekDspSubbass.progress = dbToSeekBar(p.subBassGain)
            seekDspTreble.progress = dbToSeekBar(p.trebleGain)
            seekDspPresence.progress = dbToSeekBar(p.presenceGain)
            seekDspSurround.progress = unitToSeekBar(p.surroundWidth, 1.5f)
            seekDspExciter.progress = unitToSeekBar(p.exciterAmount, 1f)
            seekDspHarmbass.progress = unitToSeekBar(p.harmonicBass, 1f)
            seekDspCompression.progress = unitToSeekBar(p.compression, 1f)
            seekDspReverb.progress = unitToSeekBar(p.reverbMix, 0.5f)
            seekDspMaster.progress = masterToSeekBar(p.masterGain)
            updateSliderTexts()
        }
        rowDspPreset.onActionKey { rowDspPreset.performClick() }

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
        seekDspBass.setOnSeekBarChangeListener(seekListener)
        seekDspSubbass.setOnSeekBarChangeListener(seekListener)
        seekDspTreble.setOnSeekBarChangeListener(seekListener)
        seekDspPresence.setOnSeekBarChangeListener(seekListener)
        seekDspSurround.setOnSeekBarChangeListener(seekListener)
        seekDspExciter.setOnSeekBarChangeListener(seekListener)
        seekDspHarmbass.setOnSeekBarChangeListener(seekListener)
        seekDspCompression.setOnSeekBarChangeListener(seekListener)
        seekDspReverb.setOnSeekBarChangeListener(seekListener)
        seekDspMaster.setOnSeekBarChangeListener(seekListener)

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

        tvDspBass.text = "${seekBarToDb(seekDspBass.progress)} dB"
        tvDspSubbass.text = "${seekBarToDb(seekDspSubbass.progress)} dB"
        tvDspTreble.text = "${seekBarToDb(seekDspTreble.progress)} dB"
        tvDspPresence.text = "${seekBarToDb(seekDspPresence.progress)} dB"
        tvDspSurround.text = "${(seekBarToUnit(seekDspSurround.progress, 1.5f) * 100).toInt()}%"
        tvDspExciter.text = "${(seekBarToUnit(seekDspExciter.progress, 1f) * 100).toInt()}%"
        tvDspHarmbass.text = "${(seekBarToUnit(seekDspHarmbass.progress, 1f) * 100).toInt()}%"
        tvDspCompression.text = "${(seekBarToUnit(seekDspCompression.progress, 1f) * 100).toInt()}%"
        tvDspReverb.text = "${(seekBarToUnit(seekDspReverb.progress, 0.5f) * 100).toInt()}%"
        tvDspMaster.text = "${(seekBarToMaster(seekDspMaster.progress) * 100).toInt()}%"
    }

    private fun dbToSeekBar(value: Float): Int = ((value + 12f) / 24f * 100).toInt().coerceIn(0, 100)
    private fun seekBarToDb(progress: Int): Float = progress / 100.0f * 24f - 12f

    private fun unitToSeekBar(value: Float, max: Float): Int = (value / max * 100).toInt().coerceIn(0, 100)
    private fun seekBarToUnit(progress: Int, max: Float): Float = progress / 100.0f * max

    private fun masterToSeekBar(value: Float): Int = ((value - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)
    private fun seekBarToMaster(progress: Int): Float = 0.5f + progress / 100.0f * 1.5f

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
        editor?.putBoolean("video_player_mode_enabled", switchVideoPlayer.isChecked)
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

        AudioEnhanceConfig.applyParams(AudioEnhanceConfig.Params(
            preset = dspPreset,
            enabled = switchDspEnabled.isChecked,
            bassGain = seekBarToDb(seekDspBass.progress),
            trebleGain = seekBarToDb(seekDspTreble.progress),
            subBassGain = seekBarToDb(seekDspSubbass.progress),
            presenceGain = seekBarToDb(seekDspPresence.progress),
            surroundWidth = seekBarToUnit(seekDspSurround.progress, 1.5f),
            exciterAmount = seekBarToUnit(seekDspExciter.progress, 1f),
            harmonicBass = seekBarToUnit(seekDspHarmbass.progress, 1f),
            compression = seekBarToUnit(seekDspCompression.progress, 1f),
            reverbMix = seekBarToUnit(seekDspReverb.progress, 0.5f),
            masterGain = seekBarToMaster(seekDspMaster.progress)
        ))

        val btnSave = findViewById<TextView>(R.id.btn_save)
        btnSave.announceForAccessibility("Configuración guardada")
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        finish()
    }
}