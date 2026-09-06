package com.karin.streamtv.player

import android.app.Activity
import android.content.SharedPreferences
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.exoplayer.ExoPlayer

object ExoPlayerSettingsHelper {

    const val PREFS_NAME = "exoplayer_video_prefs"
    const val KEY_DETAIL_BOOST_EN = "detail_boost_enabled"
    const val KEY_DETAIL_BOOST_STRENGTH = "detail_boost_strength"
    const val KEY_DEMO_EN = "demo_enabled"
    const val KEY_LOW_BITRATE_EN = "low_bitrate_enabled"
    const val KEY_LOW_BITRATE_STRENGTH = "low_bitrate_strength"
    const val KEY_LIGHT_BOOST_EN = "light_boost_enabled"
    const val KEY_LIGHT_BOOST_STRENGTH = "light_boost_strength"
    const val KEY_LIGHT_BOOST_WARMTH = "light_boost_warmth"
    const val KEY_MOTIONX2_EN = "motionx2_enabled"
    const val KEY_MOTIONX2_MODE = "motionx2_mode"
    const val KEY_MOTIONX2_STRENGTH = "motionx2_strength"
    const val KEY_MOTIONX2_BLEND = "motionx2_blend"
    const val KEY_COLORBOOST_EN = "colorboost_enabled"
    const val KEY_COLORBOOST_SATURATION = "colorboost_saturation"
    const val KEY_COLORBOOST_VIBRANCE = "colorboost_vibrance"
    const val KEY_COLORBOOST_HUE = "colorboost_hue"
    const val KEY_COLORBOOST_COLORFULNESS = "colorboost_colorfulness"
    const val KEY_UPSCALER_MODE = "upscaler_mode"
    const val KEY_FSR_SHARPNESS = "fsr_sharpness"
    const val KEY_DOG_STRENGTH = "dog_strength"

    const val MODE_OFF = 0
    const val MODE_BILINEAR = 1
    const val MODE_BICUBIC = 2
    const val MODE_FSR = 3
    const val MODE_DOG = 4

    fun showAdvancedDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onStrengthChanged: (String, Float) -> Unit = { _, _ -> },
        onWarmthChanged: (Float) -> Unit = { _ -> },
    ) {
        data class FeatureEntry(val label: String, val action: (() -> Unit)?)

        val upscalerLabels = arrayOf(
            "Apagado",
            "Bilineal",
            "Bicúbico",
            "FSR (1.0 + 3.1 + 4.0)",
            "DOG Sharpen",
        )

        val entries = listOf(
            FeatureEntry("📐 Tipo de escalador") {
                val current = prefs.getInt(KEY_UPSCALER_MODE, MODE_OFF)
                AlertDialog.Builder(activity)
                    .setTitle("Escalador de video")
                    .setSingleChoiceItems(upscalerLabels, current) { dialog, which ->
                        prefs.edit().putInt(KEY_UPSCALER_MODE, which).apply()
                        player?.let { onEffectsChanged(it) }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            },
            FeatureEntry("⚡ Low Bitrate Boost") {
                showSingleFeatureDialog(
                    activity = activity,
                    title = "Low Bitrate Boost",
                    getEnabled = { prefs.getBoolean(KEY_LOW_BITRATE_EN, false) },
                    getValue = { prefs.getInt(KEY_LOW_BITRATE_STRENGTH, 60) / 100f },
                    description = "Repara artefactos de baja tasa de bits: deblocking, " +
                        "ruido mosquito, posterización, y recupera detalle perdido por compresión.",
                    onLiveChange = { v -> onStrengthChanged("lowbitrate", v) },
                    onSave = { en, v ->
                        prefs.edit()
                            .putBoolean(KEY_LOW_BITRATE_EN, en)
                            .putInt(KEY_LOW_BITRATE_STRENGTH, (v * 100).toInt())
                            .apply()
                        player?.let { onEffectsChanged(it) }
                    },
                )
            },
            FeatureEntry("🔹 Detail Boost") {
                val detailNote = if (prefs.getInt(KEY_UPSCALER_MODE, MODE_OFF) == MODE_FSR) {
                    " ⚠️ Inactivo mientras FSR esté activo (evita sumar ruido). " +
                        "Se reactiva al usar otro escalador."
                } else ""
                showSingleFeatureDialog(
                    activity = activity,
                    title = "Detail Boost",
                    getEnabled = {
                        prefs.getBoolean(KEY_DETAIL_BOOST_EN, false) &&
                            prefs.getInt(KEY_UPSCALER_MODE, MODE_OFF) != MODE_FSR
                    },
                    getValue = { prefs.getInt(KEY_DETAIL_BOOST_STRENGTH, 70) / 100f },
                    description = "Realza bordes y micro-detalles (texturas, pelo, vegetación) con " +
                        "máscara de enfoque, sin ruido ni halos artificiales.$detailNote",
                    onLiveChange = { v -> onStrengthChanged("detailboost", v) },
                    onSave = { en, v ->
                        prefs.edit()
                            .putBoolean(KEY_DETAIL_BOOST_EN, en)
                            .putInt(KEY_DETAIL_BOOST_STRENGTH, (v * 100).toInt())
                            .apply()
                        player?.let { onEffectsChanged(it) }
                    },
                )
            },
            FeatureEntry("💡 Light Boost") {
                showLightBoostDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChanged,
                    onLiveStrengthChange = { v -> onStrengthChanged("lightboost", v) },
                    onLiveWarmthChange = { v -> onWarmthChanged(v) },
                )
            },
            FeatureEntry("🎨 Color Boost") {
                showColorBoostDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChanged,
                    onLiveSaturationChange = { v -> onStrengthChanged("colorboost_sat", v) },
                    onLiveVibranceChange = { v -> onStrengthChanged("colorboost_vib", v) },
                    onLiveHueChange = { v -> onStrengthChanged("colorboost_hue", v) },
                    onLiveColorfulnessChange = { v -> onStrengthChanged("colorboost_col", v) },
                )
            },

            FeatureEntry("⚡ MotionX2 Boost") {
                showMotionX2Dialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChanged,
                    onLiveModeChange = { v -> onStrengthChanged("motionx2_mode", v) },
                    onLiveStrengthChange = { v -> onStrengthChanged("motionx2", v) },
                    onLiveBlendChange = { v -> onStrengthChanged("motionx2_blend", v) },
                )
            },

            FeatureEntry("🖥 Demo mode") {
                showSingleFeatureDialog(
                    activity = activity,
                    title = "Demo mode",
                    getEnabled = { prefs.getBoolean(KEY_DEMO_EN, false) },
                    getValue = {
                        if (prefs.getBoolean(KEY_DETAIL_BOOST_EN, false)) {
                            prefs.getInt(KEY_DETAIL_BOOST_STRENGTH, 70) / 100f
                        } else {
                            0.6f
                        }
                    },
                    description = "Comparación split-screen: izquierda video original, derecha con " +
                        "mejoras, separadas por una línea blanca vertical.",
                    onSave = { en, _ ->
                        prefs.edit().putBoolean(KEY_DEMO_EN, en).apply()
                        player?.let { onEffectsChanged(it) }
                    },
                )
            },
        )

        val options = entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Opciones avanzadas")
            .setItems(options) { _, which -> entries[which].action?.invoke() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showSingleFeatureDialog(
        activity: Activity,
        title: String,
        getEnabled: () -> Boolean,
        getValue: () -> Float,
        description: String? = null,
        valueFormatter: (Float) -> String = { "${(it * 100).toInt()}%" },
        onLiveChange: (Float) -> Unit = { _ -> },
        onSave: (Boolean, Float) -> Unit,
    ) {
        var value = getValue().coerceIn(0f, 1f)
        val switch = Switch(activity).apply {
            text = if (getEnabled()) "Activado" else "Desactivado"
            isChecked = getEnabled()
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                text = if (isChecked) "Activado" else "Desactivado"
            }
        }
        val valueLabel = TextView(activity).apply {
            text = "Intensidad: ${valueFormatter(value)}"
        }
        val seek = SeekBar(activity).apply {
            max = 100
            progress = (value * 100).toInt()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                value = progress / 100f
                valueLabel.text = "Intensidad: ${valueFormatter(value)}"
                onLiveChange(value)  // LIVE UPDATE
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            if (description != null) {
                addView(
                    TextView(activity).apply {
                        text = description
                        textSize = 13f
                        setPadding(0, 16, 0, 8)
                    },
                )
            }
            addView(valueLabel)
            addView(seek)
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ -> onSave(switch.isChecked, value) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLightBoostDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onLiveStrengthChange: (Float) -> Unit,
        onLiveWarmthChange: (Float) -> Unit,
    ) {
        var strength = prefs.getInt(KEY_LIGHT_BOOST_STRENGTH, 50) / 100f
        var warmth = (prefs.getInt(KEY_LIGHT_BOOST_WARMTH, 50) / 50f) - 1f
        val enabled = prefs.getBoolean(KEY_LIGHT_BOOST_EN, false)

        val switch = Switch(activity).apply {
            text = if (enabled) "Activado" else "Desactivado"
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                text = if (isChecked) "Activado" else "Desactivado"
            }
        }

        val strengthLabel = TextView(activity).apply { text = "Intensidad: ${(strength * 100).toInt()}%" }
        val warmthLabel = TextView(activity).apply { text = "Temperatura: ${(warmth * 100).toInt()}%" }

        val strengthSeek = SeekBar(activity).apply { max = 100; progress = (strength * 100).toInt() }
        val warmthSeek = SeekBar(activity).apply { max = 100; progress = ((warmth + 1f) * 50).toInt() }

        strengthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                strength = progress / 100f
                strengthLabel.text = "Intensidad: ${(strength * 100).toInt()}%"
                onLiveStrengthChange(strength)  // LIVE UPDATE
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        warmthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                warmth = (progress / 50f) - 1f
                warmthLabel.text = "Temperatura: ${(warmth * 100).toInt()}%"
                onLiveWarmthChange(warmth)  // LIVE UPDATE
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(TextView(activity).apply {
                text = "Iluminación inteligente: levanta sombras, preserva brillos. Temperatura: cálido/frío."
                textSize = 13f
                setPadding(0, 16, 0, 8)
            })
            addView(strengthLabel)
            addView(strengthSeek)
            addView(warmthLabel)
            addView(warmthSeek)
        }

        AlertDialog.Builder(activity)
            .setTitle("Light Boost")
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_LIGHT_BOOST_EN, switch.isChecked)
                    .putInt(KEY_LIGHT_BOOST_STRENGTH, (strength * 100).toInt())
                    .putInt(KEY_LIGHT_BOOST_WARMTH, ((warmth + 1f) * 50).toInt())
                    .apply()
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showMotionX2Dialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onLiveModeChange: (Float) -> Unit,
        onLiveStrengthChange: (Float) -> Unit,
        onLiveBlendChange: (Float) -> Unit,
    ) {
        val modes = arrayOf("Blend Simple", "Adaptativo (Edge-Aware)", "Vectores de Movimiento", "Duplicar + Blend")
        var modeIndex = prefs.getInt(KEY_MOTIONX2_MODE, 1)
        var strength = prefs.getInt(KEY_MOTIONX2_STRENGTH, 50) / 100f
        var blend = prefs.getInt(KEY_MOTIONX2_BLEND, 50) / 100f
        val enabled = prefs.getBoolean(KEY_MOTIONX2_EN, false)

        val switch = Switch(activity).apply {
            text = if (enabled) "Activado" else "Desactivado"
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                text = if (isChecked) "Activado" else "Desactivado"
            }
        }

        val modeLabel = TextView(activity).apply { text = "Modo: ${modes[modeIndex]}" }
        val strengthLabel = TextView(activity).apply { text = "Intensidad: ${(strength * 100).toInt()}%" }
        val blendLabel = TextView(activity).apply { text = "Blend Factor: ${(blend * 100).toInt()}%" }

        val modeSeek = SeekBar(activity).apply { max = modes.size - 1; progress = modeIndex }
        val strengthSeek = SeekBar(activity).apply { max = 100; progress = (strength * 100).toInt() }
        val blendSeek = SeekBar(activity).apply { max = 100; progress = (blend * 100).toInt() }

        modeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                modeIndex = progress
                modeLabel.text = "Modo: ${modes[modeIndex]}"
                onLiveModeChange(modeIndex.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        strengthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                strength = progress / 100f
                strengthLabel.text = "Intensidad: ${(strength * 100).toInt()}%"
                onLiveStrengthChange(strength)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        blendSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                blend = progress / 100f
                blendLabel.text = "Blend Factor: ${(blend * 100).toInt()}%"
                onLiveBlendChange(blend)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(TextView(activity).apply {
                text = "Interpola frames para 2x FPS. Modos: Blend=simple, Adaptativo=evita ghosting, Vectores=compensa movimiento, Duplicar=frames dobles con blend."
                textSize = 13f
                setPadding(0, 16, 0, 8)
            })
            addView(modeLabel)
            addView(modeSeek)
            addView(strengthLabel)
            addView(strengthSeek)
            addView(blendLabel)
            addView(blendSeek)
        }

        AlertDialog.Builder(activity)
            .setTitle("MotionX2 Boost")
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_MOTIONX2_EN, switch.isChecked)
                    .putInt(KEY_MOTIONX2_MODE, modeIndex)
                    .putInt(KEY_MOTIONX2_STRENGTH, (strength * 100).toInt())
                    .putInt(KEY_MOTIONX2_BLEND, (blend * 100).toInt())
                    .apply()
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showColorBoostDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onLiveSaturationChange: (Float) -> Unit,
        onLiveVibranceChange: (Float) -> Unit,
        onLiveHueChange: (Float) -> Unit,
        onLiveColorfulnessChange: (Float) -> Unit,
    ) {
        var saturation = prefs.getInt(KEY_COLORBOOST_SATURATION, 30) / 100f
        var vibrance = prefs.getInt(KEY_COLORBOOST_VIBRANCE, 20) / 100f
        var hueShift = prefs.getInt(KEY_COLORBOOST_HUE, 0) / 100f
        var colorfulness = prefs.getInt(KEY_COLORBOOST_COLORFULNESS, 15) / 100f
        val enabled = prefs.getBoolean(KEY_COLORBOOST_EN, false)

        val switch = Switch(activity).apply {
            text = if (enabled) "Activado" else "Desactivado"
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                text = if (isChecked) "Activado" else "Desactivado"
            }
        }

        val satLabel = TextView(activity).apply { text = "Saturación: ${(saturation * 100).toInt()}%" }
        val vibLabel = TextView(activity).apply { text = "Vibrancia: ${(vibrance * 100).toInt()}%" }
        val hueLabel = TextView(activity).apply { text = "Tono: ${(hueShift * 100).toInt()}%" }
        val colLabel = TextView(activity).apply { text = "Colorido: ${(colorfulness * 100).toInt()}%" }

        val satSeek = SeekBar(activity).apply { max = 100; progress = (saturation * 100).toInt() }
        val vibSeek = SeekBar(activity).apply { max = 100; progress = (vibrance * 100).toInt() }
        val hueSeek = SeekBar(activity).apply { max = 100; progress = ((hueShift + 0.5f) * 100).toInt() }
        val colSeek = SeekBar(activity).apply { max = 100; progress = (colorfulness * 100).toInt() }

        satSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                saturation = progress / 100f
                satLabel.text = "Saturación: ${(saturation * 100).toInt()}%"
                onLiveSaturationChange(saturation)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        vibSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                vibrance = progress / 100f
                vibLabel.text = "Vibrancia: ${(vibrance * 100).toInt()}%"
                onLiveVibranceChange(vibrance)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        hueSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                hueShift = (progress / 100f) - 0.5f
                hueLabel.text = "Tono: ${(hueShift * 100).toInt()}%"
                onLiveHueChange(hueShift)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        colSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                colorfulness = progress / 100f
                colLabel.text = "Colorido: ${(colorfulness * 100).toInt()}%"
                onLiveColorfulnessChange(colorfulness)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(TextView(activity).apply {
                text = "Mejora colores: Saturación=global, Vibrancia=inteligente (protege piel), Colorido=perceptual, Tono=shift sutil."
                textSize = 13f
                setPadding(0, 16, 0, 8)
            })
            addView(satLabel)
            addView(satSeek)
            addView(vibLabel)
            addView(vibSeek)
            addView(hueLabel)
            addView(hueSeek)
            addView(colLabel)
            addView(colSeek)
        }

        AlertDialog.Builder(activity)
            .setTitle("Color Boost")
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_COLORBOOST_EN, switch.isChecked)
                    .putInt(KEY_COLORBOOST_SATURATION, (saturation * 100).toInt())
                    .putInt(KEY_COLORBOOST_VIBRANCE, (vibrance * 100).toInt())
                    .putInt(KEY_COLORBOOST_HUE, ((hueShift + 0.5f) * 100).toInt())
                    .putInt(KEY_COLORBOOST_COLORFULNESS, (colorfulness * 100).toInt())
                    .apply()
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}