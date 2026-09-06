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
import androidx.appcompat.app.AlertDialog
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.AutoPlayManager
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.GamepadHelper
import com.karin.streamtv.util.onActionKey

class SettingsActivity : FragmentActivity() {

    private lateinit var switchServerFallback: SwitchMaterial
    private lateinit var switchAutoplay: SwitchMaterial
    private lateinit var switchPlayNow: SwitchMaterial
    private lateinit var switchVideoPlayer: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchServerFallback = findViewById(R.id.switch_server_fallback)
        switchAutoplay = findViewById(R.id.switch_autoplay)
        switchPlayNow = findViewById(R.id.switch_playnow)
        switchVideoPlayer = findViewById(R.id.switch_video_player)

        switchServerFallback.isChecked = AppPreferences.isServerFallbackEnabled()
        switchAutoplay.isChecked = AppPreferences.isAutoPlayEnabled()
        switchPlayNow.isChecked = AppPreferences.isPlayNowEnabled()
        switchVideoPlayer.isChecked = AppPreferences.isVideoPlayerModeEnabled()

        val switchListener = { switch: SwitchMaterial, label: String ->
            switch.contentDescription = "$label: ${if (switch.isChecked) "activado" else "desactivado"}"
            switch.announceForAccessibility(switch.contentDescription)
        }

        switchServerFallback.setOnCheckedChangeListener { _, _ -> switchListener(switchServerFallback, "Fallback de servidores") }
        switchAutoplay.setOnCheckedChangeListener { _, _ -> switchListener(switchAutoplay, "Continuar Episodio") }
        switchPlayNow.setOnCheckedChangeListener { _, _ -> switchListener(switchPlayNow, "Auto Play") }
        switchVideoPlayer.setOnCheckedChangeListener { _, _ -> switchListener(switchVideoPlayer, "Reproductor de video del sistema") }

        val btnSave = findViewById<TextView>(R.id.btn_save)
        btnSave.setOnClickListener { saveSettings() }
        btnSave.onActionKey { btnSave.performClick() }

        val btnBack = findViewById<TextView>(R.id.btn_back)
        btnBack.setOnClickListener { finish() }
        btnBack.onActionKey { btnBack.performClick() }

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
        val rowCodec = findViewById<android.widget.LinearLayout>(R.id.row_codec)
        val value = findViewById<TextView>(R.id.txt_codec_value)

        val labels = arrayOf("Hardware (chip)", "Software (Google)", "Auto")
        val modes = arrayOf(AppPreferences.CODEC_HW, AppPreferences.CODEC_SW_GOOGLE, AppPreferences.CODEC_AUTO)

        value.text = AppPreferences.getCodecModeLabel()

        val clickListener = {
            val current = AppPreferences.getCodecMode()
            val checkedIndex = modes.indexOf(current).coerceIn(0, labels.size - 1)
            AlertDialog.Builder(this)
                .setTitle("Códec de reproducción")
                .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                    AppPreferences.setCodecMode(modes[which])
                    value.text = labels[which]
                    dialog.dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        rowCodec.setOnClickListener { clickListener() }
        rowCodec.onActionKey { clickListener() }
    }

    private fun saveSettings() {
        AppPreferences.setServerFallbackEnabled(switchServerFallback.isChecked)
        AppPreferences.setAutoPlayEnabled(switchAutoplay.isChecked)
        AppPreferences.setPlayNowEnabled(switchPlayNow.isChecked)
        AppPreferences.setVideoPlayerModeEnabled(switchVideoPlayer.isChecked)
        AutoPlayManager.setAutoPlayEnabled(switchAutoplay.isChecked)

        val btnSave = findViewById<TextView>(R.id.btn_save)
        btnSave.announceForAccessibility("Configuración guardada")
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        finish()
    }
}