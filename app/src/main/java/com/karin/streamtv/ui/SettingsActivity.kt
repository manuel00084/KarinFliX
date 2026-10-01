package com.karin.streamtv.ui

import android.content.Intent
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
import com.karin.streamtv.player.dsp.SpeakerCleaner
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
    private lateinit var switchKarinLink: SwitchMaterial
    private lateinit var switchLowEnd: SwitchMaterial
    private lateinit var switchSplash: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchServerFallback = findViewById(R.id.switch_server_fallback)
        switchAutoplay = findViewById(R.id.switch_autoplay)
        switchPlayNow = findViewById(R.id.switch_playnow)
        switchVideoPlayer = findViewById(R.id.switch_video_player)
        switchKarinLink = findViewById(R.id.switch_karin_link)
        switchLowEnd = findViewById(R.id.switch_low_end)
        switchSplash = findViewById(R.id.switch_splash)

        switchServerFallback.isChecked = AppPreferences.isServerFallbackEnabled()
        switchAutoplay.isChecked = AppPreferences.isAutoPlayEnabled()
        switchPlayNow.isChecked = AppPreferences.isPlayNowEnabled()
        switchVideoPlayer.isChecked = AppPreferences.isVideoPlayerModeEnabled()
        switchKarinLink.isChecked = AppPreferences.isKarinLinkEnabled()
        switchLowEnd.isChecked = AppPreferences.isUltraEconomyMode()
        switchSplash.isChecked = AppPreferences.isSplashEnabled()

        val switchListener = { switch: SwitchMaterial, label: String ->
            switch.contentDescription = "$label: ${if (switch.isChecked) "activado" else "desactivado"}"
            switch.announceForAccessibility(switch.contentDescription)
        }

        switchServerFallback.setOnCheckedChangeListener { _, _ -> switchListener(switchServerFallback, "Fallback de servidores") }
        switchAutoplay.setOnCheckedChangeListener { _, _ -> switchListener(switchAutoplay, "Continuar Episodio") }
        switchPlayNow.setOnCheckedChangeListener { _, _ -> switchListener(switchPlayNow, "Auto Play") }
        switchVideoPlayer.setOnCheckedChangeListener { _, _ -> switchListener(switchVideoPlayer, "Reproductor de video del sistema") }
        switchKarinLink.setOnCheckedChangeListener { _, _ -> switchListener(switchKarinLink, "KARIN Link") }
        switchLowEnd.setOnCheckedChangeListener { _, _ -> switchListener(switchLowEnd, "Modo ultra económico") }
        switchSplash.setOnCheckedChangeListener { _, _ -> switchListener(switchSplash, "Pantalla de carga") }

        val btnSave = findViewById<TextView>(R.id.btn_save)
        btnSave.setOnClickListener { saveSettings() }
        btnSave.onActionKey { btnSave.performClick() }

        val btnBack = findViewById<TextView>(R.id.btn_back)
        btnBack.setOnClickListener { finish() }
        btnBack.onActionKey { btnBack.performClick() }

        setupCodecRow()
        setupSpeakerCleaner()
        setupGaleriaRow()
        setupKarinLinkRow()

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
            val dlg = AlertDialog.Builder(this)
                .setTitle("Códec de reproducción")
                .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                    AppPreferences.setCodecMode(modes[which])
                    value.text = labels[which]
                    dialog.dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .create()
            // TV/D-pad: el foco debe caer en la lista, no en Cancelar.
            com.karin.streamtv.util.TvDialogHelper.makeListTvReady(dlg, dlg.listView, this)
            dlg.show()
        }
        rowCodec.setOnClickListener { clickListener() }
        rowCodec.onActionKey { clickListener() }
    }

    private fun setupSpeakerCleaner() {
        val btnClean = findViewById<TextView>(R.id.btn_speaker_clean)
        val txtStatus = findViewById<TextView>(R.id.txt_speaker_clean_status)

        fun updateUi() {
            val isActive = SpeakerCleaner.isRunning
            btnClean.text = if (isActive) "Detener" else "Iniciar"
            txtStatus.text = if (isActive) {
                "Limpiando... mantén el dispositivo en superficie estable"
            } else {
                "Vibra los graves para expulsar polvo de la rejilla"
            }
        }

        updateUi()

        btnClean.setOnClickListener {
            if (SpeakerCleaner.isRunning) {
                SpeakerCleaner.stop()
                Toast.makeText(this, "Limpieza detenida", Toast.LENGTH_SHORT).show()
            } else {
                val levels = SpeakerCleaner.Level.entries.toTypedArray()
                var chosen = levels.indexOf(SpeakerCleaner.Level.ESTANDAR).coerceAtLeast(0)
                AlertDialog.Builder(this)
                    .setTitle("Limpiar bocina")
                    .setMessage(
                        "Sacude el polvo de la rejilla con vibración de graves.\n\n" +
                            "1. Sube el volumen del multimedia al máximo.\n" +
                            "2. Pon el dispositivo en superficie firme con la rejilla hacia ABAJO.\n" +
                            "3. Si el polvo es reciente, empieza en Estándar; para polvo terco usa Máxima.\n\n" +
                            "¿Qué intensidad?"
                    )
                    .setSingleChoiceItems(
                        levels.map { it.label }.toTypedArray(),
                        chosen
                    ) { _, which -> chosen = which }
                    .setPositiveButton("Iniciar") { _, _ ->
                        val level = levels[chosen.coerceIn(levels.indices)]
                        btnClean.text = "Detener"
                        txtStatus.text = "Limpiando... 0%"
                        SpeakerCleaner.start(
                            level = level,
                            onProgress = { progress ->
                                runOnUiThread {
                                    txtStatus.text = "Limpiando... $progress%"
                                }
                            },
                            onFinished = {
                                runOnUiThread {
                                    updateUi()
                                    Toast.makeText(
                                        this@SettingsActivity,
                                        "Limpieza completada",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                    .setNegativeButton("Cancelar", null)
                    .create()
                    .apply {
                        // TV/D-pad: empezar en Iniciar, no en Cancelar.
                        setOnShowListener {
                            getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus()
                        }
                        show()
                    }
            }
        }
        btnClean.onActionKey { btnClean.performClick() }
    }

    private fun setupGaleriaRow() {
        val rowGaleria = findViewById<android.widget.LinearLayout>(R.id.row_galeria)
        val openGaleria = {
            startActivity(Intent(this, GaleriaKarinActivity::class.java))
        }
        rowGaleria.setOnClickListener { openGaleria() }
        rowGaleria.onActionKey { openGaleria() }
    }

    private fun setupKarinLinkRow() {
        val row = findViewById<android.widget.LinearLayout>(R.id.row_karin_link)
        val toggle = {
            switchKarinLink.isChecked = !switchKarinLink.isChecked
        }
        row.setOnClickListener { toggle() }
        row.onActionKey { toggle() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (SpeakerCleaner.isRunning) {
            SpeakerCleaner.stop()
        }
    }

    private fun saveSettings() {
        AppPreferences.setServerFallbackEnabled(switchServerFallback.isChecked)
        AppPreferences.setAutoPlayEnabled(switchAutoplay.isChecked)
        AppPreferences.setPlayNowEnabled(switchPlayNow.isChecked)
        AppPreferences.setVideoPlayerModeEnabled(switchVideoPlayer.isChecked)
        AppPreferences.setKarinLinkEnabled(switchKarinLink.isChecked)
        AppPreferences.setUltraEconomyMode(switchLowEnd.isChecked)
        AppPreferences.setSplashEnabled(switchSplash.isChecked)
        AutoPlayManager.setAutoPlayEnabled(switchAutoplay.isChecked)
        // Aplica el encendido/apagado de KARIN Link de inmediato:
        // encendido -> levanta el host (servidor + NSD), apagado -> lo detiene.
        try {
            if (switchKarinLink.isChecked) {
                com.karin.streamtv.karinlink.KarinLinkHost.start(this)
            } else {
                com.karin.streamtv.karinlink.KarinLinkHost.stop()
            }
        } catch (_: Exception) { }
        // Registra o retira a KarinFLiX del selector de reproductores de Android.
        com.karin.streamtv.player.SystemVideoPlayerRegistrar.apply(this)
        // El ultra económico cambia caché de imágenes y concurrencia de red:
        // se aplican en vivo para que no haga falta reiniciar.
        try {
            com.karin.streamtv.util.DiskImageCache.setUltraMode(switchLowEnd.isChecked)
            com.karin.streamtv.scraper.ScrapingEngine.setMaxConcurrent(
                if (switchLowEnd.isChecked) 2 else 8
            )
        } catch (_: Exception) { }

        val btnSave = findViewById<TextView>(R.id.btn_save)
        btnSave.announceForAccessibility("Configuración guardada")
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        finish()
    }
}