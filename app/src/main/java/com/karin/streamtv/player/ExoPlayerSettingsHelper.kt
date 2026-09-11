package com.karin.streamtv.player

import android.app.Activity
import android.content.SharedPreferences
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.media3.exoplayer.ExoPlayer
import com.karin.streamtv.util.AudioEffectsManager

object ExoPlayerSettingsHelper {

    const val PREFS_NAME = "exoplayer_video_prefs"
    const val KEY_DEPIXEL_EN = "depixel_enabled"
    const val KEY_DEPIXEL_STRENGTH = "depixel_strength"
    const val KEY_CINE_EN = "cine_enabled"
    const val KEY_CINE_STRENGTH = "cine_strength"
    const val KEY_CINE_AO = "cine_ao"
    const val KEY_CINE_SAT = "cine_sat"
    const val KEY_CINE_BRIGHT = "cine_bright"
    const val KEY_HDR_EN = "hdr_enabled"
    const val KEY_HDR_STRENGTH = "hdr_strength"
    const val KEY_COLORS_EN = "colors_enabled"
    const val KEY_COLORS_STRENGTH = "colors_strength"
    const val KEY_DETAIL_BOOST_EN = "detail_boost_enabled"
    const val KEY_DETAIL_BOOST_STRENGTH = "detail_boost_strength"
    const val KEY_DEMO_EN = "demo_enabled"
    const val KEY_MOTIONX2_EN = "motionx2_enabled"
    const val KEY_MOTIONX2_MODE = "motionx2_mode"
    const val KEY_MOTIONX2_STRENGTH = "motionx2_strength"
    const val KEY_UPSCALER_MODE = "upscaler_mode"
    const val KEY_FSR_SHARPNESS = "fsr_sharpness"
    const val KEY_FSR_QUALITY = "fsr_quality"
    const val KEY_DOG_STRENGTH = "dog_strength"

    const val MODE_OFF = 0
    const val MODE_BILINEAR = 1
    const val MODE_BICUBIC = 2
    const val MODE_FSR = 3
    const val MODE_DOG = 4

    // region Resolución de conflictos
    private fun isFsrActive(prefs: SharedPreferences) =
        prefs.getInt(KEY_UPSCALER_MODE, MODE_OFF) == MODE_FSR

    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
    // endregion

    fun showAdvancedDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onStrengthChanged: (String, Float) -> Unit = { _, _ -> },
    ) {
        data class FeatureEntry(val label: String, val action: (() -> Unit)?)

        val upscalerLabels = arrayOf(
            "Apagado",
            "Bilineal",
            "Bicúbico",
            "FSR 1.0 (EASU + RCAS)",
            "DOG Sharpen",
        )

        fun upscalerName(): String {
            return upscalerLabels.getOrNull(prefs.getInt(KEY_UPSCALER_MODE, MODE_OFF)) ?: "Apagado"
        }

        fun onOff(en: Boolean) = if (en) "ON" else "OFF"
        val fsrOn = isFsrActive(prefs)
        val detailEn = prefs.getBoolean(KEY_DETAIL_BOOST_EN, false)
        val cineEn = prefs.getBoolean(KEY_CINE_EN, false)
        val hdrEn = prefs.getBoolean(KEY_HDR_EN, false)
        val colorsEn = prefs.getBoolean(KEY_COLORS_EN, false)

        // Orden = orden real del pipeline en ExoPlayerActivity para evitar confusión.
        val entries = listOf(
            // 1. Escalador (exclusivo: solo uno a la vez)
            FeatureEntry("📐 1. Escalador • ${upscalerName()}") {
                val current = prefs.getInt(KEY_UPSCALER_MODE, MODE_OFF)
                AlertDialog.Builder(activity)
                    .setTitle("Escalador de video (exclusivo)")
                    .setSingleChoiceItems(upscalerLabels, current) { dialog, which ->
                        val editor = prefs.edit().putInt(KEY_UPSCALER_MODE, which)
                        // Conflicto FSR <-> Detail: FSR ya trae su propio realce,
                        // sumar Detail mete ruido. Se apaga Detail automáticamente.
                        if (which == MODE_FSR && prefs.getBoolean(KEY_DETAIL_BOOST_EN, false)) {
                            editor.putBoolean(KEY_DETAIL_BOOST_EN, false)
                            toast(activity, "Detail Boost apagado: incompatible con FSR")
                        }
                        editor.apply()
                        player?.let { onEffectsChanged(it) }
                        dialog.dismiss()
                        when (which) {
                            MODE_FSR -> showFsrDialog(
                                activity = activity,
                                prefs = prefs,
                                player = player,
                                onEffectsChanged = onEffectsChanged,
                                onLiveQualityChange = { v -> onStrengthChanged("fsr_quality", v) },
                                onLiveSharpnessChange = { v -> onStrengthChanged("fsr", v) },
                            )
                            MODE_DOG -> showDogDialog(
                                activity = activity,
                                prefs = prefs,
                                player = player,
                                onEffectsChanged = onEffectsChanged,
                                onLiveChange = { v -> onStrengthChanged("dog", v) },
                            )
                        }
                        // El menú se refresca la próxima vez que se abra (sin apilar diálogos).
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            },
            // 2. Limpieza (siempre primero en el pipeline, sin conflictos)
            FeatureEntry("🧱 2. Depixel Boost • ${onOff(prefs.getBoolean(KEY_DEPIXEL_EN, false))}") {
                showSingleFeatureDialog(
                    activity = activity,
                    title = "Depixel Boost",
                    getEnabled = { prefs.getBoolean(KEY_DEPIXEL_EN, false) },
                    getValue = { prefs.getInt(KEY_DEPIXEL_STRENGTH, 60) / 100f },
                    description = "Repara videos con bajo bitrate: quita grano en zonas planas, " +
                        "suaviza bloques y artefactos, limpia el color guiado por luma y " +
                        "no toca los bordes reales. Barato para equipos modestos.",
                    onLiveChange = { v -> onStrengthChanged("depixel", v) },
                    onSave = { en, v ->
                        prefs.edit()
                            .putBoolean(KEY_DEPIXEL_EN, en)
                            .putInt(KEY_DEPIXEL_STRENGTH, (v * 100).toInt())
                            .apply()
                        player?.let { onEffectsChanged(it) }
                    },
                )
            },
            // 3. Detalle (bloqueado mientras FSR esté activo)
            FeatureEntry(
                if (fsrOn) "🔹 3. Detail Boost • BLOQUEADO por FSR"
                else "🔹 3. Detail Boost • ${onOff(detailEn)}",
            ) {
                if (isFsrActive(prefs)) {
                    AlertDialog.Builder(activity)
                        .setTitle("Detail Boost bloqueado")
                        .setMessage(
                            "Detail Boost es incompatible con FSR (FSR ya aplica su propio " +
                                "realce y sumarlos genera ruido).\n\n¿Apagar FSR y activar Detail Boost?",
                        )
                        .setPositiveButton("Apagar FSR y seguir") { _, _ ->
                            prefs.edit()
                                .putInt(KEY_UPSCALER_MODE, MODE_OFF)
                                .putBoolean(KEY_DETAIL_BOOST_EN, true)
                                .apply()
                            player?.let { onEffectsChanged(it) }
                            toast(activity, "FSR apagado, Detail Boost activado")
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    showSingleFeatureDialog(
                        activity = activity,
                        title = "Detail Boost",
                        getEnabled = { prefs.getBoolean(KEY_DETAIL_BOOST_EN, false) },
                        getValue = { prefs.getInt(KEY_DETAIL_BOOST_STRENGTH, 70) / 100f },
                    description = "Realza bordes y micro-detalles (texturas, pelo, vegetación) con " +
                        "enfoque direccional y respuesta adaptativa: más donde falta, " +
                        "preciso en bordes, sin ruido ni halos.\n" +
                        "No se puede usar junto con FSR.",
                        onLiveChange = { v -> onStrengthChanged("detailboost", v) },
                        onSave = { en, v ->
                            prefs.edit()
                                .putBoolean(KEY_DETAIL_BOOST_EN, en)
                                .putInt(KEY_DETAIL_BOOST_STRENGTH, (v * 100).toInt())
                                .apply()
                            player?.let { onEffectsChanged(it) }
                        },
                    )
                }
            },
            // 4. Combo CineHDR (HDR + Cine coordinados, con sus intensidades)
            FeatureEntry("🎬☀️ 4. CineHDR • ${onOff(hdrEn && cineEn)}") {
                showCineHdrDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChanged,
                    onLiveHdrChange = { v -> onStrengthChanged("hdr", v) },
                    onLiveCineChange = { v -> onStrengthChanged("cine", v) },
                )
            },
            // 5. Colores (remate, sin conflictos)
            FeatureEntry("🎨 5. Colors Boost • ${onOff(colorsEn)}") {
                showSingleFeatureDialog(
                    activity = activity,
                    title = "Colors Boost",
                    getEnabled = { prefs.getBoolean(KEY_COLORS_EN, false) },
                    getValue = { prefs.getInt(KEY_COLORS_STRENGTH, 60) / 100f },
                    description = "Colores más vívidos: saturación + vibrance que empuja " +
                        "los apagados y protege la piel para no dejar caras naranjas.\n" +
                        "Remate de color: va después de HDR/Cine.",
                    onLiveChange = { v -> onStrengthChanged("colors", v) },
                    onSave = { en, v ->
                        prefs.edit()
                            .putBoolean(KEY_COLORS_EN, en)
                            .putInt(KEY_COLORS_STRENGTH, (v * 100).toInt())
                            .apply()
                        player?.let { onEffectsChanged(it) }
                    },
                )
            },
            // 6. Movimiento (independiente, sin conflictos)
            FeatureEntry("⚡ 6. MotionX2 Boost • ${onOff(prefs.getBoolean(KEY_MOTIONX2_EN, false))}") {
                showMotionX2Dialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChanged,
                    onLiveModeChange = { v -> onStrengthChanged("motionx2_mode", v) },
                    onLiveStrengthChange = { v -> onStrengthChanged("motionx2", v) },
                )
            },
            // 7. Demo (solo visualización, no toca el pipeline)
            FeatureEntry("🖥 7. Demo split-screen • ${onOff(prefs.getBoolean(KEY_DEMO_EN, false))}") {
                showToggleDialog(
                    activity = activity,
                    title = "Demo mode",
                    description = "Comparación split-screen: izquierda video original, derecha con " +
                        "mejoras, separadas por una línea blanca vertical.\n" +
                        "Ambas mitades siempre muestran el mismo instante.",

                    getEnabled = { prefs.getBoolean(KEY_DEMO_EN, false) },
                    onSave = { en ->
                        prefs.edit().putBoolean(KEY_DEMO_EN, en).apply()
                        player?.let { onEffectsChanged(it) }
                    },
                )
            },
        )

        val options = entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Opciones avanzadas (en orden del pipeline)")
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
        fun pct(v: Float) = "Intensidad: ${(v * 100).toInt()}%"
        val valueLabel = TextView(activity).apply { text = pct(value) }
        val seek = SeekBar(activity).apply {
            max = 100
            progress = (value * 100).toInt()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                value = progress / 100f
                valueLabel.text = pct(value)
                onLiveChange(value)
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

    private fun showFsrDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onLiveQualityChange: (Float) -> Unit,
        onLiveSharpnessChange: (Float) -> Unit,
    ) {
        val qualities = arrayOf(
            "Rendimiento (rápido)",
            "Equilibrado",
            "Calidad (lento)",
        )
        var qualityIndex = prefs.getInt(KEY_FSR_QUALITY, 1).coerceIn(0, qualities.size - 1)
        var sharpness = prefs.getInt(KEY_FSR_SHARPNESS, 60) / 100f
        val fsrActive = prefs.getInt(KEY_UPSCALER_MODE, MODE_OFF) == MODE_FSR

        val qualityButton = Button(activity).apply {
            text = "Calidad: ${qualities[qualityIndex]}"
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Calidad FSR")
                    .setSingleChoiceItems(qualities, qualityIndex) { dialog, which ->
                        qualityIndex = which
                        text = "Calidad: ${qualities[qualityIndex]}"
                        onLiveQualityChange(qualityIndex.toFloat())
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        val sharpLabel = TextView(activity).apply { text = "Nitidez: ${(sharpness * 100).toInt()}%" }

        val sharpSeek = SeekBar(activity).apply { max = 100; progress = (sharpness * 100).toInt() }

        sharpSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sharpness = progress / 100f
                sharpLabel.text = "Nitidez: ${(sharpness * 100).toInt()}%"
                onLiveSharpnessChange(sharpness)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(TextView(activity).apply {
                text = if (fsrActive) {
                    "Núcleo EASU + RCAS del FSR 1.0 original. Rendimiento = rápido para gama baja; Calidad = máximo detalle."
                } else {
                    "Requiere Tipo de escalador = FSR para verse. Rendimiento = rápido para gama baja; Calidad = máximo detalle."
                }
                textSize = 13f
                setPadding(0, 0, 0, 8)
            })
            addView(qualityButton)
            addView(sharpLabel)
            addView(sharpSeek)
        }

        AlertDialog.Builder(activity)
            .setTitle("FSR")
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.edit()
                    .putInt(KEY_FSR_QUALITY, qualityIndex)
                    .putInt(KEY_FSR_SHARPNESS, (sharpness * 100).toInt())
                    .apply()
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showCineHdrDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onLiveHdrChange: (Float) -> Unit,
        onLiveCineChange: (Float) -> Unit,
    ) {
        var hdr = prefs.getInt(KEY_HDR_STRENGTH, 60) / 100f
        var cine = prefs.getInt(KEY_CINE_STRENGTH, 60) / 100f
        val enabled = prefs.getBoolean(KEY_HDR_EN, false) &&
            prefs.getBoolean(KEY_CINE_EN, false)

        val switch = Switch(activity).apply {
            text = if (enabled) "Activado" else "Desactivado"
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                text = if (isChecked) "Activado" else "Desactivado"
            }
        }

        fun pct(name: String, v: Float) = "$name: ${(v * 100).toInt()}%"
        val hdrLabel = TextView(activity).apply { text = pct("HDR", hdr) }
        val cineLabel = TextView(activity).apply { text = pct("Cine", cine) }

        fun seek(initial: Float, onChange: (Float) -> Unit): SeekBar {
            return SeekBar(activity).apply {
                max = 100
                progress = (initial * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        onChange(progress / 100f)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
        }
        val hdrSeek = seek(hdr) {
            hdr = it
            hdrLabel.text = pct("HDR", it)
            onLiveHdrChange(it)
        }
        val cineSeek = seek(cine) {
            cine = it
            cineLabel.text = pct("Cine", it)
            onLiveCineChange(it)
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(TextView(activity).apply {
                text = "HDR expande el rango y Cine remata con volumen, tonos cálidos/fríos " +
                    "y viñeta suavizando su curva para no pelearse."
                textSize = 13f
                setPadding(0, 16, 0, 8)
            })
            addView(hdrLabel)
            addView(hdrSeek)
            addView(cineLabel)
            addView(cineSeek)
        }

        AlertDialog.Builder(activity)
            .setTitle("CineHDR")
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_HDR_EN, switch.isChecked)
                    .putBoolean(KEY_CINE_EN, switch.isChecked)
                    .putInt(KEY_HDR_STRENGTH, (hdr * 100).toInt())
                    .putInt(KEY_CINE_STRENGTH, (cine * 100).toInt())
                    .apply()
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDogDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onLiveChange: (Float) -> Unit,
    ) {
        // DOG vive dentro de UPSCALER_MODE, no tiene flag propio.
        // El switch significa: MODE_DOG activo o no.
        showSingleFeatureDialog(
            activity = activity,
            title = "DOG Sharpen",
            getEnabled = { prefs.getInt(KEY_UPSCALER_MODE, MODE_OFF) == MODE_DOG },
            getValue = { prefs.getInt(KEY_DOG_STRENGTH, 50) / 100f },
            description = "Nitidez por diferencia de gaussianas. Exclusivo con los " +
                "demás escaladores (Bilineal, Bicúbico, FSR). Compatible con Detail Boost.",
            onLiveChange = onLiveChange,
            onSave = { en, v ->
                val editor = prefs.edit()
                    .putInt(KEY_DOG_STRENGTH, (v * 100).toInt())
                editor.putInt(KEY_UPSCALER_MODE, if (en) MODE_DOG else MODE_OFF)
                editor.apply()
                player?.let { onEffectsChanged(it) }
            },
        )
    }

    private fun showToggleDialog(
        activity: Activity,
        title: String,
        description: String?,
        getEnabled: () -> Boolean,
        onSave: (Boolean) -> Unit,
    ) {
        val switch = Switch(activity).apply {
            text = if (getEnabled()) "Activado" else "Desactivado"
            isChecked = getEnabled()
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                text = if (isChecked) "Activado" else "Desactivado"
            }
        }
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
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ -> onSave(switch.isChecked) }
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
    ) {
        val modes = arrayOf(
            "⏻ Apagado (no genera nada)",
            "HYBRID (Doubling + Micro-Blend)",
            "DOUBLING (Frame x2)",
            "BLEND (Suavizado)",
        )
        val modeDescriptions = arrayOf(
            "Apaga el efecto por completo: no mezcla ni genera nada, el video pasa intacto.",
            "Recomendado. Combina cuadro nítido + un poco de mezcla suave. Mejor balance.",
            "Más ligero, menos suave. Muestra cada cuadro tal cual, sin mezcla ni fantasma.",
            "Mezcla suave entre el cuadro anterior y el actual. Suave, pero puede verse fantasma.",
        )
        // Índice 0 = apagado; 1..3 = ordinal del enum MotionX2Mode + 1.
        var modeIndex = if (prefs.getBoolean(KEY_MOTIONX2_EN, false)) {
            prefs.getInt(KEY_MOTIONX2_MODE, 0).coerceIn(0, modes.size - 2) + 1
        } else {
            0
        }
        var strength = prefs.getInt(KEY_MOTIONX2_STRENGTH, 50) / 100f
        val enabled = prefs.getBoolean(KEY_MOTIONX2_EN, false)

        val switch = Switch(activity).apply {
            text = if (enabled) "Activado" else "Desactivado"
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                text = if (isChecked) "Activado" else "Desactivado"
            }
        }

        val modeDesc = TextView(activity).apply {
            text = modeDescriptions[modeIndex]
            textSize = 13f
            setPadding(0, 16, 0, 8)
        }
        val modeButton = Button(activity).apply {
            text = "Modo: ${modes[modeIndex]}"
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Modo MotionX2")
                    .setSingleChoiceItems(modes, modeIndex) { dialog, which ->
                        modeIndex = which
                        text = "Modo: ${modes[modeIndex]}"
                        modeDesc.text = modeDescriptions[modeIndex]
                        // Elegir un modo prende; elegir Apagado apaga.
                        switch.isChecked = which != 0
                        if (which != 0) onLiveModeChange((which - 1).toFloat())
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        val strengthLabel = TextView(activity).apply { text = "Intensidad: ${(strength * 100).toInt()}%" }

        val strengthSeek = SeekBar(activity).apply { max = 100; progress = (strength * 100).toInt() }

        strengthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                strength = progress / 100f
                strengthLabel.text = "Intensidad: ${(strength * 100).toInt()}%"
                onLiveStrengthChange(strength)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(modeDesc)
            addView(modeButton)
            addView(strengthLabel)
            addView(strengthSeek)
        }

        AlertDialog.Builder(activity)
            .setTitle("MotionX2 Boost")
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                // Apagado manda: no genera ni mezcla nada.
                val on = switch.isChecked && modeIndex != 0
                prefs.edit()
                    .putBoolean(KEY_MOTIONX2_EN, on)
                    .putInt(KEY_MOTIONX2_MODE, (modeIndex - 1).coerceIn(0, modes.size - 2))
                    .putInt(KEY_MOTIONX2_STRENGTH, (strength * 100).toInt())
                    .apply()
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showFxDialog(activity: Activity, fx: AudioEffectsManager) {
        val switch = Switch(activity).apply {
            text = if (fx.isFxEnabled) "FxSound activado" else "FxSound desactivado"
            isChecked = fx.isFxEnabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                fx.toggleFx()
                text = if (isChecked) "FxSound activado" else "FxSound desactivado"
            }
        }

        val presetButton = Button(activity).apply {
            text = "Preset: ${fx.currentPresetName}"
            setOnClickListener {
                val items = AudioEffectsManager.PRESETS.map { it.name }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle("Preset de audio")
                    .setSingleChoiceItems(items, fx.presetIndex) { dialog, which ->
                        fx.setPreset(which)
                        text = "Preset: ${fx.currentPresetName}"
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        val boostButton = Button(activity).apply {
            text = "Volumen: ${fx.volumeBoostLabel}"
            setOnClickListener {
                fx.cycleVolumeBoost()
                text = "Volumen: ${fx.volumeBoostLabel}"
            }
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(
                TextView(activity).apply {
                    text = "Equalizer + BassBoost + LoudnessEnhancer del sistema.\nLos cambios se aplican en vivo y se guardan."
                    textSize = 13f
                    setPadding(0, 16, 0, 8)
                },
            )
            addView(presetButton)
            addView(boostButton)
        }

        AlertDialog.Builder(activity)
            .setTitle("FxSound")
            .setView(layout)
            .setPositiveButton("Listo", null)
            .show()
    }
}