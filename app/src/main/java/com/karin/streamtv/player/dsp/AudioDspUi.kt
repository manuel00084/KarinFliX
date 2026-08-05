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

object AudioDspUi {
    fun showPresetDialog(context: Context, onAdvanced: () -> Unit, onChanged: (() -> Unit)? = null) {
        val deviceLabel = AudioEnhanceConfig.outputDeviceLabel()
        val device = AudioEnhanceConfig.currentDeviceKind()
        val override = AudioEnhanceConfig.getDevicePreset(device)
        val auto = AudioEnhanceConfig.isAutoDevice()
        val current = AudioEnhanceConfig.preset()
        val presets = AudioEnhanceConfig.Preset.entries

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 8)
        }

        container.addView(TextView(context).apply {
            text = "Salida actual: $deviceLabel"
            textSize = 14f
            setTextColor(0xFF90CAF9.toInt())
            setPadding(0, 0, 0, 14)
        })

        val chosen = ArrayList<android.widget.RadioButton>()
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
            container.addView(row)
        }

        option(
            "Automático",
            "Detecta tu salida y afina el preset (hoy: $deviceLabel" +
                (if (override != null) " · ${override.label}" else "") + ")",
            auto
        ) {
            AudioEnhanceConfig.setDevicePreset(device, null)
            val p = AudioEnhanceConfig.params().copy(autoDevice = true)
            AudioEnhanceConfig.applyParams(p)
            onChanged?.invoke()
        }

        val subs = mapOf(
            AudioEnhanceConfig.Preset.OFF to "Sin DSP: audio original",
            AudioEnhanceConfig.Preset.ANIME to "Voz nítida + OST con cuerpo",
            AudioEnhanceConfig.Preset.CINEMA to "Cine: explosiones y banda sonora",
            AudioEnhanceConfig.Preset.BASS_BOOST to "Graves musicales, punch y sub",
            AudioEnhanceConfig.Preset.SURROUND to "3D: ancho máximo + ambiente",
            AudioEnhanceConfig.Preset.DIALOGUE to "Diálogos: voz central, máxima claridad",
            AudioEnhanceConfig.Preset.MUSIC to "Respuesta plana + micro-mejora",
            AudioEnhanceConfig.Preset.SPEAKER to "True MaxBass: máximo grave en bocina chica"
        )
        for (preset in presets) {
            option(preset.label, subs[preset] ?: "", !auto && current == preset) {
                AudioEnhanceConfig.setDevicePreset(device, preset)
                AudioEnhanceConfig.applyParams(AudioEnhanceConfig.Params().withPreset(preset))
                onChanged?.invoke()
            }
        }

        container.addView(Button(context).apply {
            text = "Ajustes avanzados (EQ, IR, tubo...)"
            setPadding(0, 16, 0, 0)
            setOnClickListener { onAdvanced() }
        })

        val scroll = ScrollView(context).apply { addView(container) }
        val dlg = AlertDialog.Builder(context)
            .setTitle("Perfiles de audio")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .create()
        dlg.setOnDismissListener { onChanged?.invoke() }
        dlg.show()
    }

    fun showAdvanced(context: Context, onPickIr: (() -> Unit)? = null) {
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
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    AudioEnhanceConfig.setHeadphone(null)
                    onStop(seekBar?.progress ?: 0)
                }
            })
            container.addView(sb)
            return sb
        }

        val activeHp = AudioEnhanceConfig.headphone()
        if (activeHp != null) {
            header("Corrección AutoEQ activa: $activeHp")
        }

        header("Audio espacial")
        container.addView(CheckBox(context).apply {
            text = "Dejar que el sistema maneje Spatial Audio (API 33+)"
            isChecked = AudioEnhanceConfig.useSystemSpatializer()
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, _ ->
                AudioEnhanceConfig.setUseSystemSpatializer(isChecked)
            }
        })

        header("Surround de campo (V4A): Haas + bass centrado")
        slider("Campo (0-100%)", (field * 100).toInt().coerceIn(0, 100)) { pr ->
            AudioEnhanceConfig.setField(pr / 100f)
        }

        header("EQ de 10 bandas (-12..+12 dB)")
        val freqs = AudioEnhanceConfig.EQ_FREQS
        for (i in 0 until 10) {
            val idx = i
            val label = if (freqs[i] >= 1000f) "${(freqs[i] / 1000f).toInt()} kHz" else "${freqs[i].toInt()} Hz"
            slider(label, ((gains[i] + 12f) * 10).toInt().coerceIn(0, 240)) { pr ->
                gains[idx] = (pr / 10f - 12f).coerceIn(-12f, 12f)
                AudioEnhanceConfig.setEq10(gains)
            }
        }

        header("Saturación de tubo (armónico par, estilo triodo)")
        slider("Tubo (0-100%)", (AudioEnhanceConfig.getTube() * 100).toInt().coerceIn(0, 100)) { pr ->
            AudioEnhanceConfig.setTube(pr / 100f)
        }

        header("Bass dinámico (adaptativo)")
        container.addView(CheckBox(context).apply {
            text = "Adaptar el boost de graves a la envolvente"
            isChecked = AudioEnhanceConfig.getDynamicBass()
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, _ ->
                AudioEnhanceConfig.setDynamicBass(isChecked)
            }
        })

        header("TV / bocina chica (ayudas para drivers pequeños)")
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

        header("IR de usuario (.wav)")
        container.addView(TextView(context).apply {
            text = "IR activo: ${AudioEnhanceConfig.userIrName() ?: "ninguno"}"
            textSize = 13f
            setPadding(0, 0, 0, 6)
        })
        if (onPickIr != null) {
            actionButton("Seleccionar archivo .wav") { onPickIr() }
        }
        actionButton("Quitar IR de usuario") {
            AudioEnhanceConfig.setUserIr(null, null)
            AudioEnhanceConfig.setIrPreset(AudioEnhanceConfig.IrPreset.NONE)
            Toast.makeText(context, "IR de usuario eliminado", Toast.LENGTH_SHORT).show()
        }

        header("EQ paramétrica (AutoEQ)")
        container.addView(TextView(context).apply {
            text = "Bandas activas: ${AutoEqParser.countLabel(AudioEnhanceConfig.getParametric())}"
            textSize = 13f
            setPadding(0, 0, 0, 6)
        })
        actionButton("Importar curvas paramétricas") {
            showParametricImport(context)
        }
        actionButton("Quitar EQ paramétrica") {
            AudioEnhanceConfig.setParametric(null)
            Toast.makeText(context, "EQ paramétrica eliminada", Toast.LENGTH_SHORT).show()
        }

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
            .setNeutralButton("Auriculares (AutoEQ)") { _, _ ->
                showHeadphonePicker(context)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showParametricImport(context: Context) {
        val input = EditText(context).apply {
            setSingleLine(false)
            minLines = 6
            textSize = 12f
            hint = "Pega aquí las líneas de la curva (AutoEq-style):\n" +
                "Filter 1: ON PK Fc 105 Hz Gain -5.2 dB Q 1.21\n" +
                "Filter 2: ON LSC Fc 30 Hz Gain 4.0 dB Q 0.71\n" +
                "Filter 3: ON HSC Fc 10000 Hz Gain -2.5 dB Q 0.71"
        }
        AlertDialog.Builder(context)
            .setTitle("Importar EQ paramétrica")
            .setMessage("Solo se aceptan bandas paramétricas (PK/LSC/HSC). Preamp se ignora: el limiter maestro ya protege de recortes.")
            .setView(input)
            .setPositiveButton("Importar") { _, _ ->
                val bands = AutoEqParser.parse(input.text.toString())
                if (bands == null) {
                    Toast.makeText(context, "No se encontraron bandas válidas", Toast.LENGTH_SHORT).show()
                } else {
                    AudioEnhanceConfig.setParametric(bands)
                    Toast.makeText(context, "${bands.size} bandas importadas", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showHeadphonePicker(context: Context) {
        val profiles = AudioEnhanceConfig.headphoneProfiles
        val labels = ArrayList<String>()
        labels.add("Ninguno (sin corrección)")
        profiles.forEach { labels.add(it.name) }
        val current = AudioEnhanceConfig.headphone()
        val idx = profiles.indexOfFirst { it.name == current }
        val selected = if (current != null && idx >= 0) idx + 1 else 0
        AlertDialog.Builder(context)
            .setTitle("Corrección por auricular (AutoEQ)")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { _, which ->
                if (which == 0) {
                    AudioEnhanceConfig.setHeadphone(null)
                } else {
                    AudioEnhanceConfig.setHeadphone(profiles[which - 1].name)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }
}
