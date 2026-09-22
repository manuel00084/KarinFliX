package com.karin.streamtv.player.dsp

import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.media3.exoplayer.ExoPlayer
import com.karin.streamtv.util.AppPreferences

object AudioDspUi {
    /**
     * Ajuste rápido: solo las 5 perillas (graves, agudos, voz, espacial,
     * potencia) sobre el perfil que ya está activo. Botón dedicado en el
     * reproductor; no toca preset ni perfil definido.
     */
    fun showQuickAdjust(context: Context) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 20, 56, 8)
        }

        fun slider(
            label: String,
            initial: Float,
            min: Float,
            max: Float,
            format: (Float) -> String,
            onChange: (Float) -> Unit
        ): SeekBar {
            lateinit var labelView: TextView
            labelView = TextView(context).apply {
                textSize = 14f
                setPadding(0, 12, 0, 4)
            }
            container.addView(labelView)
            fun valueOf(p: Int): Float = min + p / 100f * (max - min)
            val sb = SeekBar(context).apply {
                this.max = 100
                progress = ((initial - min) / (max - min) * 100).toInt().coerceIn(0, 100)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = valueOf(p)
                        labelView.text = "$label · ${format(v)}"
                        if (fromUser) onChange(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            container.addView(sb)
            labelView.text = "$label · ${format(valueOf(sb.progress))}"
            return sb
        }

        container.addView(TextView(context).apply {
            text = "Se suma al perfil activo (${AudioEnhanceConfig.preset().label}) y se " +
                "mantiene al cambiar de perfil. Para cambiar perfil o volumen usa el botón de sonido."
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 0, 0, 6)
        })

        val qaBase = AudioEnhanceConfig.presetBase()

        slider("Graves", AudioEnhanceConfig.getBass(), -12f, 12f, { v -> "%+.1f dB".format(v) }) { v ->
            val c = v.coerceIn(-12f, 12f)
            AudioEnhanceConfig.setBass(c)
            AudioEnhanceConfig.setQuickAdjBass(c - qaBase.bassGain)
        }
        slider("Agudos", AudioEnhanceConfig.getTreble(), -12f, 12f, { v -> "%+.1f dB".format(v) }) { v ->
            val c = v.coerceIn(-12f, 12f)
            AudioEnhanceConfig.setTreble(c)
            AudioEnhanceConfig.setQuickAdjTreble(c - qaBase.trebleGain)
        }
        slider("Claridad de voz", AudioEnhanceConfig.getPresence(), -12f, 12f, { v -> "%+.1f dB".format(v) }) { v ->
            val c = v.coerceIn(-12f, 12f)
            AudioEnhanceConfig.setPresence(c)
            AudioEnhanceConfig.setQuickAdjPresence(c - qaBase.presenceGain)
        }
        slider("Efecto espacial", AudioEnhanceConfig.getSurround(), 0f, 1.5f, { v -> "${(v * 100).toInt()}%" }) { v ->
            val c = v.coerceIn(0f, 1.5f)
            AudioEnhanceConfig.setSurround(c)
            AudioEnhanceConfig.setQuickAdjSurround(c - qaBase.surroundWidth)
        }
        slider("Potencia general", AudioEnhanceConfig.getMaster(), 0.5f, 2f, { v -> "%.2fx".format(v) }) { v ->
            val c = v.coerceIn(0.5f, 2f)
            AudioEnhanceConfig.setMaster(c)
            AudioEnhanceConfig.setQuickAdjMaster(c - qaBase.masterGain)
        }

        val scroll = ScrollView(context).apply { addView(container) }
        AlertDialog.Builder(context)
            .setTitle("Ajuste rápido")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * Panel de sonido único para el reproductor: ajuste rápido, perfil de
     * sonido y accesos a auriculares/avanzado. Navegable por d-pad.
     */
    fun showSoundDialog(context: Context, onAdvanced: () -> Unit, onChanged: (() -> Unit)? = null, player: ExoPlayer? = null) {
        val deviceLabel = AudioEnhanceConfig.outputDeviceLabel()
        val device = AudioEnhanceConfig.currentDeviceKind()
        val override = AudioEnhanceConfig.getDevicePreset(device)
        val auto = AudioEnhanceConfig.isAutoDevice()
        val current = AudioEnhanceConfig.preset()
        val presets = AudioEnhanceConfig.Preset.entries
            .filter { it != AudioEnhanceConfig.Preset.BASS_BOOST }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 20, 56, 8)
        }

        fun header(title: String) {
            container.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setPadding(0, 18, 0, 6)
            })
        }

        fun infoLine(text: String) {
            container.addView(TextView(context).apply {
                this.text = text
                textSize = 13f
                setPadding(0, 3, 0, 3)
            })
        }

        // ── Estado actual del sonido ─────────────────────────────
        header("Estado actual")
        infoLine(
            "Salida: $deviceLabel · Perfil: ${if (auto) "Automático" else current.label}" +
                (if (override != null && auto) " (hoy: ${override.label})" else "")
        )

        // ── Volumen del reproductor ──────────────────────────────
        header("Volumen")
        fun volLabel(v: Float) = "Volumen: ${(v * 100).toInt()}%"
        val volumeLabel = TextView(context).apply {
            textSize = 14f
            setPadding(0, 12, 0, 4)
        }
        container.addView(volumeLabel)
        val volumeSeek = SeekBar(context).apply {
            this.max = 90 // 10%..100% (tope: sin amplificación sobre 100%)
            progress = ((AppPreferences.getPlayerVolume() * 100).toInt() - 10).coerceIn(0, this.max)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = (10 + progress) / 100f
                    volumeLabel.text = volLabel(v)
                    if (fromUser) {
                        AppPreferences.setPlayerVolume(v)
                        player?.setVolume(v.coerceIn(0f, 1f))
                        AudioEnhanceConfig.setAppVolume(v)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(volumeSeek)
        volumeLabel.text = volLabel(AppPreferences.getPlayerVolume())
        container.addView(TextView(context).apply {
            text = "10% – 100%. Tope en 100%: sin amplificación ni distorsión."
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 4, 0, 6)
        })

        // ── Potencia general del DSP ─────────────────────────────
        header("Potencia")
        fun powLabel(v: Float) = "Potencia: %.2fx".format(java.util.Locale.US, v)
        val powerLabel = TextView(context).apply {
            textSize = 14f
            setPadding(0, 12, 0, 4)
        }
        container.addView(powerLabel)
        val powerSeek = SeekBar(context).apply {
            this.max = 100 // 0.5x..2.0x
            progress = ((AudioEnhanceConfig.getMaster() - 0.5f) / 1.5f * 100).toInt().coerceIn(0, this.max)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = 0.5f + progress / 100f * 1.5f
                    powerLabel.text = powLabel(v)
                    if (fromUser) AudioEnhanceConfig.setMaster(v)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(powerSeek)
        powerLabel.text = powLabel(AudioEnhanceConfig.getMaster())
        container.addView(TextView(context).apply {
            text = "50% – 200% de la ganancia general del procesador de sonido."
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 4, 0, 6)
        })

        // ── Perfil de sonido ─────────────────────────────────────
        header("1 · Perfil de sonido")
        val btnProfile = Button(context)
        fun profileLabel(): String = if (auto) "Automático" else current.label
        btnProfile.text = "Perfil: ${profileLabel()}" +
            (if (auto && override != null) " (${override.label})" else "")
        btnProfile.isFocusable = true
        btnProfile.setOnClickListener {
            val subS = mapOf(
                AudioEnhanceConfig.Preset.OFF to "Sin DSP: audio original",
                AudioEnhanceConfig.Preset.ANIME to "Voz nítida + OST con cuerpo",
                AudioEnhanceConfig.Preset.SURROUND_ENVOLVENTE to "Surround Envolvente: convierte cualquier fuente (2.0/5.1) en surround real",
                AudioEnhanceConfig.Preset.DIALOGUE to "Noticias, presentadores y diálogos: voz central, máxima claridad",
                AudioEnhanceConfig.Preset.MUSIC to "Música: cuerpo, detalle y estéreo natural",
                AudioEnhanceConfig.Preset.SPEAKER to "True MaxBass: máximo grave en bocina chica"
            )
            val chosen = ArrayList<android.widget.RadioButton>()
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(56, 12, 56, 4)
            }
            fun option(label: String, subtitle: String, checked: Boolean, onClick: () -> Unit) {
                val rb = android.widget.RadioButton(context).apply {
                    text = label
                    textSize = 15f
                    setTextColor(0xFFECEFF1.toInt())
                    isChecked = checked
                    isFocusable = false
                    isClickable = false
                }
                if (checked) chosen.add(rb)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 6, 0, 6)
                    isClickable = true
                    isFocusable = true
                    addView(rb)
                    if (subtitle.isNotEmpty()) {
                        addView(TextView(context).apply {
                            text = subtitle
                            textSize = 12f
                            setTextColor(0xFF90A4AE.toInt())
                            setPadding(52, 0, 0, 0)
                        })
                    }
                    setOnClickListener {
                        if (rb.isChecked) return@setOnClickListener
                        for (s in chosen) s.isChecked = false
                        chosen.clear()
                        rb.isChecked = true
                        chosen.add(rb)
                        onClick()
                    }
                }
                list.addView(row)
            }
            val curAuto = AudioEnhanceConfig.isAutoDevice()
            val curPreset = AudioEnhanceConfig.preset()
            option(
                "Automático",
                "Detecta tu salida y afina el preset (hoy: $deviceLabel" +
                    (if (override != null) " · ${override.label}" else "") + ")",
                curAuto
            ) {
                AudioEnhanceConfig.setDevicePreset(device, null)
                AudioEnhanceConfig.setAutoDevice(true)
                onChanged?.invoke()
            }
            for (preset in presets) {
                option(preset.label, subS[preset] ?: "", !curAuto && curPreset == preset) {
                    AudioEnhanceConfig.setDevicePreset(device, preset)
                    AudioEnhanceConfig.applyPreset(preset)
                    onChanged?.invoke()
                }
            }
            val scroll = ScrollView(context).apply { addView(list) }
            AlertDialog.Builder(context)
                .setTitle("Perfil de sonido")
                .setView(scroll)
                .setNegativeButton("Cerrar", null)
                .setOnDismissListener {
                    btnProfile.setText(
                        "Perfil: ${if (AudioEnhanceConfig.isAutoDevice()) "Automático" else AudioEnhanceConfig.preset().label}"
                    )
                }
                .show()
        }
        val btnMore = Button(context).apply {
            text = "Ajustes avanzados"
            isFocusable = true
            setOnClickListener { onAdvanced() }
        }
        container.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                btnProfile,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                btnMore,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        })

        val scroll = ScrollView(context).apply { addView(container) }
        val dlg = AlertDialog.Builder(context)
            .setTitle("Sonido")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .create()
        dlg.setOnDismissListener { onChanged?.invoke() }
        dlg.show()
    }

    fun showAdvanced(context: Context) {
        val p = AudioEnhanceConfig.params()
        val gains = (AudioEnhanceConfig.getEq10() ?: AudioEnhanceConfig.deriveEq10(p)).copyOf()
        val field = AudioEnhanceConfig.getField()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 16, 56, 16)
        }

        fun header(title: String) {
            container.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setPadding(0, 20, 0, 6)
            })
        }

        fun actionButton(label: String, onClick: () -> Unit) {
            container.addView(Button(context).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }

        fun slider(label: String, progress: Int, onStop: (Int) -> Unit): SeekBar {
            container.addView(TextView(context).apply {
                text = label
                textSize = 14f
                setPadding(0, 12, 0, 4)
            })
            val sb = SeekBar(context)
            sb.max = 240
            sb.progress = progress
            sb.isFocusable = true
            sb.isFocusableInTouchMode = true
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        AudioEnhanceConfig.setHeadphone(null)
                        onStop(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    AudioEnhanceConfig.setHeadphone(null)
                    onStop(seekBar?.progress ?: 0)
                }
            })
            container.addView(sb)
            return sb
        }

        header("Acciones rápidas")
        actionButton("Ajuste rápido (graves, agudos, voz, espacial, potencia)") { showQuickAdjust(context) }
        actionButton("Restablecer todo al preset") {
            AudioEnhanceConfig.setEq10(null)
            AudioEnhanceConfig.setHeadphone(null)
            AudioEnhanceConfig.setParametric(null)
            AudioEnhanceConfig.clearQuickAdjust()
            Toast.makeText(context, "Sonido restablecido", Toast.LENGTH_SHORT).show()
        }

        header("1 · Surround de campo")
        slider("Campo (0-100%)", (field * 100).toInt().coerceIn(0, 100)) { pr ->
            AudioEnhanceConfig.setField(pr / 100f)
        }

        header("2 · EQ de 10 bandas")
        val freqs = AudioEnhanceConfig.EQ_FREQS
        for (i in 0 until 10) {
            val idx = i
            val label = if (freqs[i] >= 1000f) "${(freqs[i] / 1000f).toInt()} kHz" else "${freqs[i].toInt()} Hz"
            slider(label, ((gains[i] + 12f) * 10).toInt().coerceIn(0, 240)) { pr ->
                gains[idx] = (pr / 10f - 12f).coerceIn(-12f, 12f)
                AudioEnhanceConfig.setEq10(gains)
            }
        }

        header("3 · Tubo")
        slider("Tubo (0-100%)", (AudioEnhanceConfig.getTube() * 100).toInt().coerceIn(0, 100)) { pr ->
            AudioEnhanceConfig.setTube(pr / 100f)
        }

        header("4 · Graves dinámicos")
        container.addView(CheckBox(context).apply {
            text = "Adaptar el boost de graves a la envolvente"
            isChecked = AudioEnhanceConfig.getDynamicBass()
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, _ ->
                AudioEnhanceConfig.setDynamicBass(isChecked)
            }
        })

        header("5 · Ayudas para TV y bocina chica")
        container.addView(CheckBox(context).apply {
            text = "Compensación de sonoridad (graves/agudos a volumen bajo)"
            isChecked = AudioEnhanceConfig.getLoudnessComp()
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, _ ->
                AudioEnhanceConfig.setLoudnessComp(isChecked)
            }
        })
        container.addView(CheckBox(context).apply {
            text = "Resonancia de superficie (caja/mesa, preset Altavoz)"
            isChecked = AudioEnhanceConfig.getSurfaceResonance()
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, _ ->
                AudioEnhanceConfig.setSurfaceResonance(isChecked)
            }
        })
        container.addView(CheckBox(context).apply {
            text = "Voz clara (realce dinámico de diálogos)"
            isChecked = AudioEnhanceConfig.getSpeechClarity()
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, _ ->
                AudioEnhanceConfig.setSpeechClarity(isChecked)
            }
        })

        val scroll = ScrollView(context).apply {
            addView(container)
        }
        AlertDialog.Builder(context)
            .setTitle("Ajustes avanzados de sonido")
            .setView(scroll)
            .setPositiveButton("Restablecer EQ al preset") { _, _ ->
                AudioEnhanceConfig.setEq10(null)
                AudioEnhanceConfig.setHeadphone(null)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }
}
