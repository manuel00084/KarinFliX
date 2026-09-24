package com.karin.streamtv.player

import android.app.Activity
import android.content.SharedPreferences
import android.view.Gravity
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.exoplayer.ExoPlayer
import com.karin.streamtv.enhancer.KarinLightBoostController
import com.karin.streamtv.enhancer.RestoreBoostController
import com.karin.streamtv.enhancer.parameters.KarinLightBoostParameters
import com.karin.streamtv.util.DeviceProfile

object ExoPlayerSettingsHelper {

    const val PREFS_NAME = "exoplayer_video_prefs"
    const val KEY_DEPIXEL_EN = "depixel_enabled"
    const val KEY_DEPIXEL_STRENGTH = "depixel_strength"
    const val KEY_RESTORE_EN = "restore_enabled"
    const val KEY_RESTORE_STRENGTH = "restore_strength"
    const val KEY_CRT_EN = "crt_enabled"
    const val KEY_CRT_STRENGTH = "crt_strength"
    const val KEY_SHADER_EN = "shader_enabled"
    const val KEY_SHADER_TYPE = "shader_type"
    const val KEY_SHADER_STRENGTH = "shader_strength"

    const val SHADER_OFF = 0
    const val SHADER_CRT = 1
    const val SHADER_CINE = 2
    const val SHADER_BW = 3

    fun shaderTypeName(type: Int): String = when (type) {
        SHADER_CRT -> "CRT"
        SHADER_CINE -> "Cine"
        SHADER_BW -> "B/N"
        else -> "Off"
    }

    /**
     * Selección actual: tipo (0=off, 1=CRT, 2=Cine, 3=B/N) + intensidad.
     * Migra una vez las prefs legacy del CRT.
     */
    fun shaderSelection(prefs: SharedPreferences): Pair<Int, Float> {
        if (prefs.contains(KEY_SHADER_EN)) {
            if (!prefs.getBoolean(KEY_SHADER_EN, false)) return SHADER_OFF to 0f
                val t = prefs.getInt(KEY_SHADER_TYPE, SHADER_CRT).coerceIn(0, 3)
            val s = prefs.getInt(KEY_SHADER_STRENGTH, 50) / 100f
            return t to s.coerceIn(0f, 1f)
        }
        // Migración legacy: el viejo CRT pasa a ser el tipo 1.
        if (prefs.getBoolean(KEY_CRT_EN, false)) {
            val s = prefs.getInt(KEY_CRT_STRENGTH, 50) / 100f
            prefs.edit()
                .putBoolean(KEY_SHADER_EN, true)
                .putInt(KEY_SHADER_TYPE, SHADER_CRT)
                .putInt(KEY_SHADER_STRENGTH, (s.coerceIn(0f, 1f) * 100).toInt())
                .apply()
            return SHADER_CRT to s.coerceIn(0f, 1f)
        }
        prefs.edit()
            .putBoolean(KEY_SHADER_EN, false)
            .putInt(KEY_SHADER_TYPE, SHADER_CRT)
            .putInt(KEY_SHADER_STRENGTH, 50)
            .apply()
        return SHADER_OFF to 0f
    }
    const val KEY_RESTORE_CUSTOM = "restore_custom"
    const val KEY_RETRO_EN = "retro_enabled"
    const val KEY_RETRO_STRENGTH = "retro_strength"
    const val KEY_DEBAND_STRENGTH = "deband_strength" // (legacy, sin UI: el debanding es automático)
    const val KEY_CINE_EN = "cine_enabled"
    const val KEY_CINE_STRENGTH = "cine_strength"
    const val KEY_CINE_MODE = "cine_mode"
    const val KEY_RANGE_MODE = "cine_range_mode"
    const val KEY_COLORS_EN = "colors_enabled"
    const val KEY_COLORS_STRENGTH = "colors_strength"
    const val KEY_DETAIL_BOOST_EN = "detail_boost_enabled"
    const val KEY_DETAIL_BOOST_STRENGTH = "detail_boost_strength"
    const val KEY_DEMO_EN = "demo_enabled"
    const val KEY_MOTIONX2_EN = "motionx2_enabled"
    const val KEY_MOTIONX2_MODE = "motionx2_mode"
    const val KEY_UPSCALER_EN = "upscaler_enabled"
    const val KEY_UPSCALER_MODE = "upscaler_mode"
    const val KEY_UPSCALER_SHARP = "upscaler_sharpness"
    const val KEY_3D_EN = "td_enabled"
    const val KEY_3D_MODE = "td_mode"
    const val KEY_3D_DEPTH = "td_depth"
    const val KEY_3D_SWAP = "td_swap"
    const val KEY_3D_INPUT_SBS = "td_input_sbs" // legacy (migrado a KEY_3D_INPUT)
    const val KEY_3D_INPUT = "td_input" // 0=2D, 1=SBS (TAB legacy colapsa a SBS)
    const val KEY_3D_ANAGLYPH = "td_anaglyph" // 0=rojo-cian, 1=rojo-azul, 2=rojo-verde
    const val KEY_ASPECT_RATIO_MODE = "aspect_ratio_mode"

    // Relación de aspecto estilo KODI (ciclo del botón ratio).
    const val MODE_ORIGINAL = 0
    const val MODE_ZOOM = 1
    const val MODE_STRETCH = 2
    const val MODE_4_3 = 3
    const val MODE_16_9 = 4
    const val MODE_2_35 = 5
    const val ASPECT_RATIO_MODES = 6

    fun getAspectRatioMode(prefs: SharedPreferences): Int {
        return prefs.getInt(KEY_ASPECT_RATIO_MODE, MODE_ORIGINAL).coerceIn(0, ASPECT_RATIO_MODES - 1)
    }

    fun setAspectRatioMode(prefs: SharedPreferences, mode: Int) {
        prefs.edit().putInt(KEY_ASPECT_RATIO_MODE, mode.coerceIn(0, ASPECT_RATIO_MODES - 1)).apply()
    }

    fun aspectRatioLabel(mode: Int): String {
        return when (mode) {
            MODE_ZOOM -> "Zoom"
            MODE_STRETCH -> "Estirar"
            MODE_4_3 -> "4:3"
            MODE_16_9 -> "16:9"
            MODE_2_35 -> "2.35:1"
            else -> "Original"
        }
    }

    fun showAdvancedDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onStrengthChanged: (String, Float) -> Unit = { _, _ -> },
        onKarinChanged: (KarinLightBoostParameters) -> Unit = {},
        onRestoreChanged: (Float, Float, Float) -> Unit = { _, _, _ -> },
        videoInputHeight: Int = 0,
    ) {
        // label es una lambda para que cada fila relea prefs en vivo: al
        // activar una opción desde su sub-diálogo y volver, el título de la
        // fila se refresca solo (antes quedaba como snapshot y seguía en OFF).
        data class FeatureEntry(
            val label: () -> String,
            val description: String,
            val iconRes: Int,
            val action: (() -> Unit)?,
        )

        fun onOff(en: Boolean) = if (en) "ON" else "OFF"
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        val cineEn = prefs.getBoolean(KEY_CINE_EN, false)
        val colorsEn = prefs.getBoolean(KEY_COLORS_EN, false)

        // Orden = orden real del pipeline en ExoPlayerActivity para evitar confusión.
        val entries = listOf(
            // 1. Restore Boost: limpieza + reconstrucción + detalle en 1 pase.
            //    Master vinculado + ajuste fino opcional por etapa.
            FeatureEntry(
                {
                    "1. Restore Boost • ${onOff(prefs.getBoolean(KEY_RESTORE_EN, false))}" +
                        if (prefs.getBoolean(KEY_RESTORE_CUSTOM, false)) " (fino)" else ""
                },
                "Restaura en una sola pasada: limpia bloques/ruido/bandas, reconstruye bordes estilo emulador y afila micro-detalle sin reintroducir pixelado. Intensidad maestra + ajuste fino opcional.",
                android.R.drawable.ic_menu_revert,
            ) {
                showRestoreDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChanged,
                    onMasterLive = { v -> onStrengthChanged("restore", v) },
                    onStagesLive = { d, r, t -> onRestoreChanged(d, r, t) },
                )
            },

            // 2. Light Boost: brillo dinámico, contraste inteligente y color.
            //    Una sola intensidad, Manual o AUTO.
            FeatureEntry(
                { "2. Light Boost • ${onOff(cineEn || colorsEn || prefs.getInt(KEY_RANGE_MODE, 0) != 0)}" },
                "Una sola intensidad automática: luz, contraste y color juntos. Modo Manual o AUTO (la escena decide).",
                android.R.drawable.ic_menu_day,
            ) {
                showKarinLightBoostDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChanged,
                    onKarinChanged = onKarinChanged,
                )
            },
            // 3. Movimiento (blend o 60fps reales por flujo óptico)
            FeatureEntry(
                { "3. MotionX2 • ${onOff(prefs.getBoolean(KEY_MOTIONX2_EN, false))}" },
                "Suavizado de movimiento: mezcla temporal o 60 fps reales por interpolación. Va después del Upscaler para trabajar a resolución de pantalla final.",
                android.R.drawable.ic_menu_slideshow,
            ) {
                showMotionX2Dialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChanged,
                )
            },
            // 4. Upscaler de calidad (sustituye el restore bilineal; gratis
            //    si Light Boost está en la cadena, pesado si va solo)
            FeatureEntry(
                { "4. Upscaler • ${onOff(prefs.getBoolean(KEY_UPSCALER_EN, false))}" },
                "Reescala el video (KarinSuperRes, FSR o Anime4K) con afilado propio. En gama alta, Karin HiRes y FSR afilan el resultado real en 2 pases; en media/baja usan un solo pase para mantener los fps.",
                android.R.drawable.ic_menu_zoom,
            ) {
                showUpscalerDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChanged,
                    onLiveSharp = { v -> onStrengthChanged("upscaler_sharp", v) },
                    videoInputHeight = videoInputHeight,
                )
            },
            // 5. Shader: selector de acabados (CRT/Cine/B-N) al final.
            FeatureEntry(
                {
                    val (t, _) = shaderSelection(prefs)
                    "5. Shader ${shaderTypeName(t)} • ${onOff(t != SHADER_OFF)}"
                },
                "Acabado estético final tras MotionX2: CRT retro, Cine (viñeta+grano) o Blanco y negro. Un tipo a la vez.",
                android.R.drawable.ic_menu_gallery,
            ) {
                showShaderDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChanged,
                    onLiveChange = { v -> onStrengthChanged("shader", v) },
                )
            },
            // 6. Demo (solo visualización, no toca el pipeline)
            FeatureEntry(
                { "6. Demo split-screen • ${onOff(prefs.getBoolean(KEY_DEMO_EN, false))}" },
                "Comparación split-screen: izquierda el video original, derecha con mejoras, separadas por una línea blanca vertical. Ambos lados muestran el mismo instante.",
                android.R.drawable.ic_menu_view,
            ) {
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

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 8)
        }
        entries.forEach { entry ->
            val labelView = TextView(activity).apply {
                text = entry.label()
                textSize = 16f
                setTextColor(0xFFECEFF1.toInt())
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)
                isFocusable = true
                isClickable = true
                addView(ImageView(activity).apply {
                    setImageResource(entry.iconRes)
                    setColorFilter(0xFFB0BEC5.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                        marginEnd = dp(16)
                    }
                })
                addView(labelView)
                setOnClickListener {
                    entry.action?.invoke()
                    // Al volver del sub-diálogo, re-evaluar el título de la fila
                    // con las prefs en vivo (si no, sigue marcando OFF aunque se
                    // haya activado la opción).
                    labelView.text = entry.label()
                }
            }
            list.addView(row)
        }
        AlertDialog.Builder(activity)
            .setTitle("Opciones Avanzadas de Video")
            .setView(ScrollView(activity).apply {
                addView(
                    list,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            })
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * SELECTOR SHADER: un tipo a la vez (Off/CRT/Cine/B-N) + intensidad.
     * El tipo se aplica al confirmar (reconstruye la cadena); la intensidad
     * previsualiza en vivo sobre el efecto actual. Lo abre la fila 5 de
     * Opciones Avanzadas.
     */
    fun showShaderDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onLiveChange: (Float) -> Unit = {},
    ) {
        val (curType, curStrength) = shaderSelection(prefs)
        var type = curType
        var strength = curStrength

        val desc = TextView(activity).apply {
            textSize = 13f
            setPadding(0, 16, 0, 8)
        }
        fun typeDesc(t: Int) = when (t) {
            SHADER_CRT -> "CRT retro: curvatura + scanlines + rejilla RGB + viñeta."
            SHADER_CINE -> "Cine: viñeta suave + grano de película animado."
            SHADER_BW -> "Blanco y negro con contraste."
            else -> "Sin acabado (imagen tal cual sale de la cadena)."
        }
        fun pct(v: Float) = "Intensidad: ${(v * 100).toInt()}%"
        val valueLabel = TextView(activity).apply { text = pct(strength) }
        val seek = SeekBar(activity).apply {
            max = 100
            progress = (strength * 100).toInt()
        }
        val rbOff = RadioButton(activity).apply { text = "Apagado" }
        val rbCrt = RadioButton(activity).apply { text = "CRT" }
        val rbCine = RadioButton(activity).apply { text = "Cine" }
        val rbBw = RadioButton(activity).apply { text = "B/N" }
        fun syncRadios() {
            rbOff.isChecked = type == SHADER_OFF
            rbCrt.isChecked = type == SHADER_CRT
            rbCine.isChecked = type == SHADER_CINE
            rbBw.isChecked = type == SHADER_BW
            desc.text = typeDesc(type)
        }
        fun pick(t: Int) {
            type = t
            if (type != SHADER_OFF && strength <= 0f) {
                strength = 0.5f
                valueLabel.text = pct(strength)
                seek.progress = (strength * 100).toInt()
            }
            syncRadios()
        }
        rbOff.setOnClickListener { pick(SHADER_OFF) }
        rbCrt.setOnClickListener { pick(SHADER_CRT) }
        rbCine.setOnClickListener { pick(SHADER_CINE) }
        rbBw.setOnClickListener { pick(SHADER_BW) }
        val typeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(rbOff)
            addView(rbCrt)
            addView(rbCine)
            addView(rbBw)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                strength = progress / 100f
                valueLabel.text = pct(strength)
                onLiveChange(strength)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        syncRadios()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(typeRow)
            addView(desc)
            addView(valueLabel)
            addView(seek)
            addView(TextView(activity).apply {
                text = "El tipo se aplica al confirmar; la intensidad previsualiza en vivo."
                textSize = 12f
                setPadding(0, 16, 0, 8)
            })
        }
        AlertDialog.Builder(activity)
            .setTitle("Shader")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_SHADER_EN, type != SHADER_OFF)
                    .putInt(KEY_SHADER_TYPE, type)
                    .putInt(KEY_SHADER_STRENGTH, (strength * 100).toInt())
                    .apply()
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * RESTORE BOOST: intensidad maestra (vincula las 3 etapas) + ajuste fino
     * opcional por etapa. Todo corre en el mismo pase único; el fino solo
     * mueve los 3 uniforms. Tocar un slider fino = modo personalizado; mover
     * el master = vuelve a vincular. Todo con preview en vivo.
     */
    private fun showRestoreDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onMasterLive: (Float) -> Unit,
        onStagesLive: (Float, Float, Float) -> Unit,
    ) {
        var enabled = prefs.getBoolean(KEY_RESTORE_EN, false)
        var master = RestoreBoostController.masterAndEnabled(prefs).first.coerceIn(0f, 1f)
        var custom = prefs.getBoolean(KEY_RESTORE_CUSTOM, false)

        fun detailFactor(): Float {
            if (!prefs.getBoolean(KEY_UPSCALER_EN, false)) return 1f
            val mode = prefs.getInt(KEY_UPSCALER_MODE, SuperResolutionEffect.MODE_FSR)
            if (mode == SuperResolutionEffect.MODE_FSR) return 0.55f
            if (mode == SuperResolutionEffect.MODE_ANIME4K) return 0.5f
            if (mode == SuperResolutionEffect.MODE_KARIN) return 0.55f
            return 1f
        }
        var dep = if (custom) prefs.getInt(KEY_DEPIXEL_STRENGTH, 60) / 100f else master
        var ret = if (custom) prefs.getInt(KEY_RETRO_STRENGTH, 60) / 100f else master
        var det = if (custom) prefs.getInt(KEY_DETAIL_BOOST_STRENGTH, 70) / 100f
        else (master * detailFactor()).coerceIn(0f, 1f)

        fun pct(name: String, v: Float) = "$name: ${(v * 100).toInt()}%"
        val switch = Switch(activity).apply {
            text = if (enabled) "Activado" else "Desactivado"
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                enabled = isChecked
                text = if (isChecked) "Activado" else "Desactivado"
            }
        }
        val masterLabel = TextView(activity).apply { text = pct("Intensidad", master) }
        val masterSeek = SeekBar(activity).apply {
            max = 100
            progress = (master * 100).toInt()
        }
        val depLabel = TextView(activity).apply { text = pct("Limpieza", dep) }
        val depSeek = SeekBar(activity).apply {
            max = 100
            progress = (dep * 100).toInt()
        }
        val retLabel = TextView(activity).apply { text = pct("Reconstrucción", ret) }
        val retSeek = SeekBar(activity).apply {
            max = 100
            progress = (ret * 100).toInt()
        }
        val detLabel = TextView(activity).apply { text = pct("Detalle", det) }
        val detSeek = SeekBar(activity).apply {
            max = 100
            progress = (det * 100).toInt()
        }
        val modeNote = TextView(activity).apply {
            textSize = 13f
            setPadding(0, 16, 0, 8)
        }
        fun syncFine() {
            depLabel.text = pct("Limpieza", dep)
            depSeek.progress = (dep * 100).toInt()
            retLabel.text = pct("Reconstrucción", ret)
            retSeek.progress = (ret * 100).toInt()
            detLabel.text = pct("Detalle", det)
            detSeek.progress = (det * 100).toInt()
            modeNote.text = if (custom) "Modo: personalizado (cada etapa por su cuenta)."
            else "Modo: vinculado (la intensidad mueve las 3 etapas)."
        }
        masterSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                master = progress / 100f
                dep = master
                ret = master
                det = (master * detailFactor()).coerceIn(0f, 1f)
                custom = false
                masterLabel.text = pct("Intensidad", master)
                syncFine()
                onMasterLive(master)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        fun fineListener(set: (Float) -> Unit): SeekBar.OnSeekBarChangeListener {
            return object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    set(progress / 100f)
                    custom = true
                    syncFine()
                    onStagesLive(dep, ret, det)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        }
        depSeek.setOnSeekBarChangeListener(fineListener { dep = it })
        retSeek.setOnSeekBarChangeListener(fineListener { ret = it })
        detSeek.setOnSeekBarChangeListener(fineListener { det = it })
        syncFine()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(TextView(activity).apply {
                text = "Limpieza + reconstrucción + detalle en un solo pase: " +
                    "cada píxel se suaviza O se afila, nunca ambas."
                textSize = 13f
                setPadding(0, 16, 0, 8)
            })
            addView(masterLabel)
            addView(masterSeek)
            addView(TextView(activity).apply {
                text = "Ajuste fino (opcional):"
                textSize = 13f
                setPadding(0, 16, 0, 8)
            })
            addView(depLabel)
            addView(depSeek)
            addView(retLabel)
            addView(retSeek)
            addView(detLabel)
            addView(detSeek)
            addView(modeNote)
        }
        AlertDialog.Builder(activity)
            .setTitle("Restore Boost")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_RESTORE_EN, enabled)
                    .putInt(KEY_RESTORE_STRENGTH, (master * 100).toInt())
                    .putBoolean(KEY_RESTORE_CUSTOM, custom)
                    .putInt(KEY_DEPIXEL_STRENGTH, (dep * 100).toInt())
                    .putInt(KEY_RETRO_STRENGTH, (ret * 100).toInt())
                    .putInt(KEY_DETAIL_BOOST_STRENGTH, (det * 100).toInt())
                    .apply()
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
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

    /**
     * UPSCALER de calidad: sustituye el upsample bilineal de restauración
     * por FSR (default: EASU+RCAS, buena calidad por consumo),
     * KarinSuperRes (experimental: upscaler propio, nitido sin halos ni
     * ruido, DRS-aware, variante por gama) o Anime4K rápido (doG, tuneado
     * para anime).
     * Reescala 2x. Gratis cuando sustituye la pasada de restauración de
     * Light Boost half-res; si va solo, ocupa presupuesto de efectos pesados.
     */
    private fun showUpscalerDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onLiveSharp: (Float) -> Unit,
        videoInputHeight: Int = 0,
    ) {
        var enabled = prefs.getBoolean(KEY_UPSCALER_EN, false)
        var mode = prefs.getInt(KEY_UPSCALER_MODE, SuperResolutionEffect.MODE_FSR)
        // Modos válidos: FSR (0), KarinSuperRes (3) y Anime4K (2). El antiguo
        // Bicúbico (1) y cualquier valor obsoleto caen a FSR (default estable).
        if (mode != SuperResolutionEffect.MODE_KARIN &&
            mode != SuperResolutionEffect.MODE_FSR &&
            mode != SuperResolutionEffect.MODE_ANIME4K
        ) {
            mode = SuperResolutionEffect.MODE_FSR
        }
        var sharpness = prefs.getInt(KEY_UPSCALER_SHARP, 40) / 100f

        val switch = Switch(activity).apply {
            text = if (enabled) "Activado" else "Desactivado"
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                enabled = isChecked
                text = if (isChecked) "Activado" else "Desactivado"
            }
        }

        val modeKarin = RadioButton(activity).apply {
            text = "KarinSuperRes (Experimental)"
            isChecked = mode == SuperResolutionEffect.MODE_KARIN
        }
        val modeFsr = RadioButton(activity).apply {
            text = "FSR"
            isChecked = mode == SuperResolutionEffect.MODE_FSR
        }
        val modeAnime = RadioButton(activity).apply {
            text = "Anime4K"
            isChecked = mode == SuperResolutionEffect.MODE_ANIME4K
        }
        fun selectMode(m: Int) {
            mode = m
            modeKarin.isChecked = m == SuperResolutionEffect.MODE_KARIN
            modeFsr.isChecked = m == SuperResolutionEffect.MODE_FSR
            modeAnime.isChecked = m == SuperResolutionEffect.MODE_ANIME4K
        }
        modeKarin.setOnClickListener { selectMode(SuperResolutionEffect.MODE_KARIN) }
        modeFsr.setOnClickListener { selectMode(SuperResolutionEffect.MODE_FSR) }
        modeAnime.setOnClickListener { selectMode(SuperResolutionEffect.MODE_ANIME4K) }
        val modeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(modeKarin)
            addView(modeFsr)
            addView(modeAnime)
        }

        val valueLabel = TextView(activity).apply {
            text = "Nitidez: ${(sharpness * 100).toInt()}%"
        }
        val seek = SeekBar(activity).apply {
            max = 100
            progress = (sharpness * 100).toInt()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                sharpness = progress / 100f
                valueLabel.text = "Nitidez: $progress%"
                onLiveSharp(sharpness)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(
                TextView(activity).apply {
                    text = "Reescala 2x con calidad:\n" +
                        "· FSR de AMD (default): edge-adaptive con afilado adaptativo.\n" +
                        "· KarinSuperRes (experimental): upscaler propio, nitido, sin halos " +
                        "ni ruido, DRS-aware, variante por gama (ECO/CRISP/HiRes).\n" +
                        "· Anime4K rápido: afilado tuneado para anime.\n" +
                        "Al restaurar la pasada de Light Boost a media resolución, " +
                        "este upscaler la sustituye sin pases extra; solo (sin Light " +
                        "Boost) cuenta dentro del presupuesto de efectos pesados.\n" +
                        "En el demo split-screen se muestra neutro para que la " +
                        "comparación sea fiel."
                    textSize = 13f
                    setPadding(0, 16, 0, 8)
                },
            )
            if (videoInputHeight >= 1080) {
                addView(
                    TextView(activity).apply {
                        text = "Aviso: el video actual es de ${videoInputHeight}p. Este " +
                            "Upscaler hace 2x con tope en 1080p: sobre ${videoInputHeight}p " +
                            "no cambiará la imagen. Solo aplica en SD/720p."
                        textSize = 13f
                        setTextColor(0xFFFFB74D.toInt())
                        setPadding(0, 4, 0, 8)
                    },
                )
            }
            addView(modeRow)
            addView(valueLabel)
            addView(seek)
        }

        AlertDialog.Builder(activity)
            .setTitle("Upscaler")
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_UPSCALER_EN, enabled)
                    .putInt(KEY_UPSCALER_MODE, mode)
                    .putInt(KEY_UPSCALER_SHARP, (sharpness * 100).toInt())
                    .apply()
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }



    /**
     * KARIN LIGHT BOOST simplificado: interruptor + modo + una intensidad
     * maestra + presets. Las etapas (sombras, negros, blancos, luces,
     * contraste, gamma, saturación, vibrance) se derivan solas con
     * KarinLightBoostController.stagesFor(). Todo se previsualiza en vivo
     * y se aplica al confirmar.
     */
    private fun showKarinLightBoostDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onKarinChanged: (KarinLightBoostParameters) -> Unit,
    ) {
        var enabled = prefs.getBoolean(KEY_CINE_EN, false)
        var mode = prefs.getInt(KEY_CINE_MODE, KarinLightBoostController.MODE_MANUAL)
        var strength = prefs.getInt(KEY_CINE_STRENGTH, 50) / 100f
        // Una sola intensidad maestra: el color deriva de ella (simple y
        // automático). Sin segundo slider que apile ni confunda.
        fun derivedColor(s: Float) = (0.15f + 0.75f * s).coerceIn(0f, 1f)
        var colorStrength = derivedColor(strength)
        var rangeMode = prefs.getInt(KEY_RANGE_MODE, 0).coerceIn(0, 2)

        fun push() {
            // Espejo de KarinLightBoostController.fromPrefs(): con luz apagada
            // la luz es neutra (el shader la salta) y solo previsualizan
            // color y rango.
            val base = if (enabled) {
                KarinLightBoostController.stagesFor(strength)
            } else {
                KarinLightBoostParameters(
                    enabled = true,
                    prefValue = 0f,
                    shadowBoost = 0f,
                    blackLevel = 0f,
                    whiteBoost = 0f,
                    highlightControl = 0f,
                    localContrast = 0f,
                    gammaInv = 1f,
                    saturation = 1f,
                    vibrance = 0f,
                )
            }
            onKarinChanged(
                base.copy(
                    enabled = enabled || colorStrength > 0f || rangeMode != 0,
                    autoMode = mode == KarinLightBoostController.MODE_AUTO,
                    colorStrength = colorStrength.coerceIn(0f, 1f),
                    rangeMode = rangeMode,
                ),
            )
        }



        val switch = Switch(activity).apply {
            text = if (enabled) "Activado" else "Desactivado"
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                enabled = isChecked
                text = if (isChecked) "Activado" else "Desactivado"
                push()
            }
        }

        val modeManual = RadioButton(activity).apply {
            text = "Manual"
            isChecked = mode == KarinLightBoostController.MODE_MANUAL
        }
        val modeAuto = RadioButton(activity).apply {
            text = "Auto (la escena decide)"
            isChecked = mode == KarinLightBoostController.MODE_AUTO
        }
        modeManual.setOnClickListener {
            mode = KarinLightBoostController.MODE_MANUAL
            modeManual.isChecked = true
            modeAuto.isChecked = false
            push()
        }
        modeAuto.setOnClickListener {
            mode = KarinLightBoostController.MODE_AUTO
            modeManual.isChecked = false
            modeAuto.isChecked = true
            push()
        }
        val modeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(modeManual)
            addView(modeAuto)
        }

        // Compensación de rango para streams mal etiquetados (solo esos:
        // en video bien etiquetado se deja en Original o se rompe el negro).
        val rangeOrig = RadioButton(activity).apply {
            text = "Original"
            isChecked = rangeMode == 0
        }
        val rangeExpand = RadioButton(activity).apply {
            text = "Expandir"
            isChecked = rangeMode == 1
        }
        val rangeCompress = RadioButton(activity).apply {
            text = "Comprimir"
            isChecked = rangeMode == 2
        }
        fun setRange(m: Int) {
            rangeMode = m.coerceIn(0, 2)
            rangeOrig.isChecked = rangeMode == 0
            rangeExpand.isChecked = rangeMode == 1
            rangeCompress.isChecked = rangeMode == 2
            push()
        }
        rangeOrig.setOnClickListener { setRange(0) }
        rangeExpand.setOnClickListener { setRange(1) }
        rangeCompress.setOnClickListener { setRange(2) }
        val rangeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(rangeOrig)
            addView(rangeExpand)
            addView(rangeCompress)
        }

        val valueLabel = TextView(activity).apply {
            text = "Intensidad: ${(strength * 100).toInt()}%"
        }
        val seek = SeekBar(activity).apply {
            max = 100
            progress = (strength * 100).toInt()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                strength = progress / 100f
                colorStrength = derivedColor(strength)
                valueLabel.text = "Intensidad: $progress%"
                push()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        fun applyPreset(master: Float) {
            mode = KarinLightBoostController.MODE_MANUAL
            modeManual.isChecked = true
            modeAuto.isChecked = false
            strength = master.coerceIn(0f, 1f)
            colorStrength = derivedColor(strength)
            valueLabel.text = "Intensidad: ${(strength * 100).toInt()}%"
            seek.progress = (strength * 100).toInt()
            push()
        }

        val presetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(activity).apply {
                text = "Bajo"
                setOnClickListener { applyPreset(0.30f) }
            })
            addView(Button(activity).apply {
                text = "Medio"
                setOnClickListener { applyPreset(0.55f) }
            })
            addView(Button(activity).apply {
                text = "Alto"
                setOnClickListener { applyPreset(0.80f) }
            })
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(TextView(activity).apply {
                text = "Realza la imagen según la escena: sombras con detalle, " +
                    "blancos sin quemar y color vivo.\n" +
                    "MANUAL: intensidad fija. AUTO: la escena decide y la " +
                    "intensidad actúa como maestro."
                textSize = 13f
                setPadding(0, 16, 0, 8)
            })
            addView(modeRow)
            addView(presetRow)
            addView(valueLabel)
            addView(seek)
            addView(TextView(activity).apply {
                text = "La intensidad mueve luz y color juntos (automático)."
                textSize = 13f
                setPadding(0, 16, 0, 8)
            })
            addView(TextView(activity).apply {
                text = "Rango (solo streams mal etiquetados): Expandir arregla " +
                    "negros lavados (limitado tratado como completo); " +
                    "Comprimir doma negros aplastados. En video normal: Original."
                textSize = 13f
                setPadding(0, 16, 0, 8)
            })
            addView(rangeRow)
        }

        AlertDialog.Builder(activity)
            .setTitle("Light Boost")
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_CINE_EN, enabled)
                    .putInt(KEY_CINE_MODE, mode)
                    .putInt(KEY_CINE_STRENGTH, (strength * 100).toInt())
                    .putBoolean(KEY_COLORS_EN, colorStrength > 0f)
                    .putInt(KEY_COLORS_STRENGTH, (colorStrength * 100).toInt())
                    .putInt(KEY_RANGE_MODE, rangeMode)
                    .apply()
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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

    /**
     * TECNOLOGÍA 3D: diálogo del botón lentes (btn_3d) de la barra del
     * reproductor. Modos visibles: Anaglifo (3 variantes de lente),
     * VR Cardboard y Pulfrich, con profundidad (pseudo-3D / realce
     * Pulfrich), ojo intercambiable (anaglifo estéreo) y fuente
     * estéreo (2D/SBS) para el anaglifo y el VR. Cada modo muestra
     * solo sus controles. La profundidad previsualiza en vivo si el
     * 3D ya está activo; el modo se aplica al confirmar.
     */
    fun show3DDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onLiveDepth: (Float) -> Unit = {},
    ) {
        var enabled = prefs.getBoolean(KEY_3D_EN, false)
        var mode = prefs.getInt(KEY_3D_MODE, Karin3DController.MODE_OFF)
            .coerceIn(0, Karin3DController.MODE_COUNT - 1)
        var depth = (prefs.getInt(KEY_3D_DEPTH, Karin3DController.DEFAULT_DEPTH) / 100f)
            .coerceIn(0f, 1f)
        var swapEye = prefs.getBoolean(KEY_3D_SWAP, false)
        var inputKind = Karin3DController.inputKind(prefs)
        var anaglyph = Karin3DController.anaglyphType(prefs)
        // Modos visibles: Anaglifo, VR y Pulfrich (sin Apagado, SBS/TAB→2D
        // ni Polarizado). modeValues mapea índice visible -> MODE_*.
        val modeValues = intArrayOf(
            Karin3DController.MODE_ANAGLYPH,
            Karin3DController.MODE_VR_SBS,
            Karin3DController.MODE_PULFRICH,
        )
        // Nota si había un modo legacy activo que la UI ya no ofrece: la
        // cadena sigue con él hasta "Aplicar" (Cancel lo conserva).
        val hadLegacyMode = enabled && mode !in modeValues
        if (!enabled) {
            // Si estaba apagado mostramos el último modo visible si existe,
            // si no Anaglifo como punto de partida.
            val last = prefs.getInt(KEY_3D_MODE, Karin3DController.MODE_OFF)
                .coerceIn(0, Karin3DController.MODE_COUNT - 1)
            mode = if (last in modeValues) last else Karin3DController.MODE_ANAGLYPH
        } else if (mode !in modeValues) {
            mode = Karin3DController.MODE_ANAGLYPH
        }

        val switch = Switch(activity).apply {
            text = if (enabled) "Activado" else "Desactivado"
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                enabled = isChecked
                text = if (isChecked) "Activado" else "Desactivado"
            }
        }
        val titles = arrayOf(
            "Anaglifo (lentes bicolor)",
            "VR Cardboard (2D → SBS)",
            "Pulfrich (Fabulojos 1997)",
        )
        val descs = arrayOf(
            "Con SBS mezcla ambos ojos; con 2D genera pseudo-3D. Elige tus lentes abajo.",
            "Con 2D lo duplica para el visor; con SBS lo deja tal cual. Sin seguimiento de cabeza.",
            "Homenaje Fabulojos: ponte un lente oscuro en un ojo y busca movimiento lateral. Sin lentes se ve normal.",
        )
        val radios = mutableListOf<RadioButton>()
        // pending es ÍNDICE visible (0..2); modeValues lo traduce a MODE_*.
        var pending = modeValues.indexOf(mode).coerceAtLeast(0)
        // Lentes anaglifo anidados bajo la opción Anaglifo (se construyen
        // antes para poder insertarlos en la lista y mostrarlos solo ahí).
        val anagNames = arrayOf("Rojo-cian", "Rojo-azul", "Rojo-verde")
        val anagRadios = mutableListOf<RadioButton>()
        fun syncAnag() {
            anagRadios.forEachIndexed { i, r -> r.isChecked = i == anaglyph }
        }
        val anagBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 0, 0, 8)
        }
        anagNames.forEachIndexed { i, name ->
            val rb = RadioButton(activity).apply {
                text = name
                isChecked = i == anaglyph
            }
            anagRadios.add(rb)
            rb.setOnClickListener {
                anaglyph = i
                syncAnag()
            }
            anagBox.addView(rb)
        }
        // Se asigna tras crear fuente/swap/profundidad: muestra solo lo
        // que el modo pendiente usa (VR ignora profundidad y swap;
        // Pulfrich ignora fuente, lentes y swap).
        var refreshModeExtras: (() -> Unit)? = null
        fun syncRadios() {
            radios.forEachIndexed { i, r -> r.isChecked = i == pending }
            // Las 3 opciones de lentes solo se ven con Anaglifo elegido.
            anagBox.visibility =
                if (modeValues[pending.coerceIn(modeValues.indices)] == Karin3DController.MODE_ANAGLYPH) android.view.View.VISIBLE
                else android.view.View.GONE
            refreshModeExtras?.invoke()
        }
        val listBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        titles.forEachIndexed { i, title ->
            val rb = RadioButton(activity).apply {
                text = title
                isChecked = i == pending
            }
            radios.add(rb)
            val sub = TextView(activity).apply {
                text = descs[i]
                textSize = 12f
                setPadding(56, 0, 0, 12)
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(rb)
                addView(sub)
            }
            rb.setOnClickListener {
                pending = i
                syncRadios()
            }
            row.setOnClickListener {
                pending = i
                syncRadios()
            }
            listBox.addView(row)
            if (modeValues[i] == Karin3DController.MODE_ANAGLYPH) {
                listBox.addView(anagBox)
            }
        }
        syncRadios()

        // Fuente estéreo (aplica a Anaglifo; en VR evita duplicar un SBS).
        val inputTitle = TextView(activity).apply {
            text = "Fuente del video 3D:"
            textSize = 13f
            setPadding(0, 16, 0, 4)
        }
        val inputNames = arrayOf("2D (pseudo-3D)", "SBS (lado-a-lado)")
        val inputRadios = mutableListOf<RadioButton>()
        fun syncInput() {
            inputRadios.forEachIndexed { i, r -> r.isChecked = i == inputKind }
        }
        val inputBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        inputNames.forEachIndexed { i, name ->
            val rb = RadioButton(activity).apply {
                text = name
                isChecked = i == inputKind
            }
            inputRadios.add(rb)
            rb.setOnClickListener {
                inputKind = i
                syncInput()
            }
            inputBox.addView(rb)
        }

        fun pct(v: Float) =
            "Profundidad: ${(v * 100).toInt()}% (pseudo-3D desde 2D / realce Pulfrich)"
        val depthLabel = TextView(activity).apply { text = pct(depth) }
        val depthSeek = SeekBar(activity).apply {
            max = 100
            progress = (depth * 100).toInt()
        }
        depthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                depth = progress / 100f
                depthLabel.text = pct(depth)
                onLiveDepth(depth)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val swapBox = Switch(activity).apply {
            text = "Swap: ojo derecho (solo anaglifo con fuente estéreo)"
            isChecked = swapEye
            setOnCheckedChangeListener { _, isChecked -> swapEye = isChecked }
        }

        // Solo se muestra lo que el modo pendiente usa: la fuente aplica
        // a Anaglifo (y a VR para no duplicar un SBS); la profundidad a
        // Anaglifo 2D y Pulfrich; el swap solo al anaglifo estéreo.
        refreshModeExtras = {
            val m = modeValues[pending.coerceIn(modeValues.indices)]
            val isAnag = m == Karin3DController.MODE_ANAGLYPH
            val isVr = m == Karin3DController.MODE_VR_SBS
            val showInput = if (isAnag || isVr) android.view.View.VISIBLE else android.view.View.GONE
            inputTitle.visibility = showInput
            inputBox.visibility = showInput
            val showDepth = if (isVr) android.view.View.GONE else android.view.View.VISIBLE
            depthLabel.visibility = showDepth
            depthSeek.visibility = showDepth
            swapBox.visibility = if (isAnag) android.view.View.VISIBLE else android.view.View.GONE
        }
        refreshModeExtras?.invoke()

        // Avisos de compatibilidad con los shaders/filtros activos.
        // compatWarnings() corta por !isActive: con el 3D apagado la lista
        // está vacía y no debe colarse el "✓ Sin conflictos".
        val warningsNow = Karin3DController.compatWarnings(prefs)
        val activeNow = Karin3DController.isActive(prefs)
        val warnView = TextView(activity).apply {
            text = when {
                !activeNow && !enabled ->
                    "El 3D está apagado: los avisos de compatibilidad aparecen con un modo activo."
                !activeNow ->
                    "Modo nuevo sin aplicar: pulsa Aplicar para revisar conflictos con los filtros."
                warningsNow.isEmpty() ->
                    "✓ Sin conflictos: el 3D va al final de la cadena y los filtros previos no lo rompen."
                else -> "⚠ Conflictos con filtros activos:\n· " + warningsNow.joinToString("\n· ")
            }
            textSize = 12f
            setPadding(0, 16, 0, 8)
        }
        val legacyView = if (hadLegacyMode) TextView(activity).apply {
            text = "ℹ Modo anterior no disponible en la interfaz (SBS/TAB→2D): " +
                "sigue activo hasta que pulses Aplicar (se migrará a Anaglifo)."
            textSize = 12f
            setPadding(0, 8, 0, 0)
        } else null

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(TextView(activity).apply {
                text = "El botón de lentes del reproductor abre esto directo. " +
                    "El 3D corre al final de la cadena (1 pase GL). " +
                    "La profundidad previsualiza en vivo solo si el 3D ya está activo."
                textSize = 13f
                setPadding(0, 16, 0, 8)
            })
            addView(listBox)
            addView(inputTitle)
            addView(inputBox)
            addView(depthLabel)
            addView(depthSeek)
            addView(swapBox)
            legacyView?.let { addView(it) }
            addView(warnView)
        }

        AlertDialog.Builder(activity)
            .setTitle("Tecnología 3D")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Aplicar") { _, _ ->
                val finalMode = modeValues[pending.coerceIn(modeValues.indices)]
                Karin3DController.save(
                    prefs, enabled && finalMode != Karin3DController.MODE_OFF,
                    finalMode, depth, swapEye, inputKind, anaglyph,
                )
                player?.let { onEffectsChanged(it) }
                val post = Karin3DController.compatWarnings(prefs)
                if (post.any { it.startsWith("⛔") }) {
                    android.widget.Toast.makeText(
                        activity, post.first { it.startsWith("⛔") }, android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Ventana "Modo MotionX2": Apagado + modos legacy (1:1) + 60 fps reales.
    // La fila "60 fps reales (GRID)" es EXPERIMENTAL y solo se ofrece en gama
    // alta (Tier HIGH): emite cuadros intermedios en un grid absoluto de 60 Hz
    // con anti-fantasma (2 pases GL + historial propio). La antigua ruta óptica
    // daba temblor/distorsión y se desactivó; este camino es el reemplazo.
    private fun showMotionX2Dialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
    ) {
        val highEnd = try {
            DeviceProfile.get(activity).tier == DeviceProfile.Tier.HIGH
        } catch (_: Throwable) {
            false
        }
        // Ordenados de menor a mayor consumo, cada uno con su explicación simple.
        val titles = mutableListOf(
            "⏻ Apagado",
            "DOUBLING (Frame x2)",
            "BLEND (Suavizado)",
            "HYBRID (Doubling + Micro-Blend)",
            "ECO60 (60fps liviano)",
        )
        val descs = mutableListOf(
            "No hace nada. Video original.",
            "Nativo + intermedio: doble fps real. Liviano.",
            "Mezcla cuadros. Suave, puede dar fantasma.",
            "Cuadro nítido + mezcla leve. El balance.",
            "60 fps con mezcla liviana e historial a mitad de resolución. Para equipos modestos.",
        )
        if (highEnd) {
            titles.add("60 fps reales (GRID · experimental)")
            descs.add(
                "Emite cuadros intermedios en grid 60Hz real con anti-fantasma. " +
                    "2 pases GL + historial. Solo para equipo potente.",
            )
        }
        // Fila -> ordinal MotionX2Mode legacy (el 3/SPIKE ya no se ofrece).
        val dialogToLegacy = mutableListOf(-1, 1, 2, 0, MotionX2Mode.ECO60.ordinal)
        if (highEnd) dialogToLegacy.add(MotionX2Mode.REAL60.ordinal)
        // Ordinal MotionX2Mode -> fila. El 3 (INTERP/SPIKE eliminado) se muestra
        // como REAL60 en gama alta o HYBRID si no; en ejecución resolveStored lo
        // migra a REAL60 de todos modos.
        val legacyToDialog = if (highEnd) intArrayOf(3, 1, 2, 5, 5, 4) else intArrayOf(3, 1, 2, 3, 3, 4)
        val maxStored = MotionX2Mode.ECO60.ordinal
        val checkedIndex = if (prefs.getBoolean(KEY_MOTIONX2_EN, false)) {
            legacyToDialog[prefs.getInt(KEY_MOTIONX2_MODE, 0).coerceIn(0, maxStored)]
        } else {
            0
        }

        var dialog: AlertDialog? = null
        var pending = checkedIndex

        fun applyMode(which: Int) {
            val on = which != 0
            prefs.edit()
                .putBoolean(KEY_MOTIONX2_EN, on)
                .putInt(KEY_MOTIONX2_MODE, if (on) dialogToLegacy[which.coerceIn(1, dialogToLegacy.lastIndex)] else 0)
                .apply()
            player?.let { onEffectsChanged(it) }
            dialog?.dismiss()
        }

        // Filas con título + explicación; selección única manual (sin RadioGroup
        // para poder llevar subtítulo en cada fila). La selección queda en
        // espera y solo se aplica al confirmar, como en los demás diálogos.
        val radios = mutableListOf<RadioButton>()
        fun selectMode(which: Int) {
            radios.forEachIndexed { i, r -> r.isChecked = i == which }
            pending = which
        }
        val listBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        titles.forEachIndexed { i, title ->
            val rb = RadioButton(activity).apply {
                text = title
                isChecked = i == checkedIndex
            }
            radios.add(rb)
            val sub = TextView(activity).apply {
                text = descs[i]
                textSize = 12f
                setPadding(56, 0, 0, 12)
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(rb)
                addView(sub)
            }
            rb.setOnClickListener { selectMode(i) }
            row.setOnClickListener { selectMode(i) }
            listBox.addView(row)
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(listBox)
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("Modo MotionX2")
            .setView(layout)
            .setPositiveButton("Aplicar") { _, _ -> applyMode(pending) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Volumen del sistema con barra (STREAM_MUSIC), táctil y d-pad. El DSP
    // completo (perfiles, EQ, IR...) se abre desde "Perfiles de audio (DSP)".
    fun showVolumeDialog(activity: Activity) {
        val am = activity.getSystemService(android.content.Context.AUDIO_SERVICE)
            as android.media.AudioManager
        val stream = android.media.AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        var cur = am.getStreamVolume(stream).coerceIn(0, max)

        fun pct(v: Int) = "Volumen: ${(v * 100 / max)}%"
        val label = TextView(activity).apply { text = pct(cur) }
        val seek = SeekBar(activity).apply {
            this.max = max
            progress = cur
            isFocusable = true
            isFocusableInTouchMode = true
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cur = progress.coerceIn(0, max)
                am.setStreamVolume(stream, cur, 0)
                label.text = pct(cur)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(label)
            addView(seek)
        }
        val scroll = ScrollView(activity)
        scroll.addView(layout)
        AlertDialog.Builder(activity)
            .setTitle("Volumen")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .show()
        seek.requestFocus()
    }

}

