package com.karin.streamtv.player

import android.app.Activity
import android.content.Intent
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.media3.exoplayer.ExoPlayer
import com.karin.streamtv.R
import com.karin.streamtv.enhancer.KarinLightBoostController
import com.karin.streamtv.enhancer.RestoreBoostController
import com.karin.streamtv.enhancer.parameters.KarinLightBoostParameters
import com.karin.streamtv.ui.GlassesTutorialActivity
import com.karin.streamtv.util.DeviceProfile
import com.karin.streamtv.util.onActionKey

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
    const val SHADER_ANIME = 4
    const val SHADER_SHARPEN = 5
    const val SHADER_GRAIN = 6

    fun shaderTypeName(type: Int): String = when (type) {
        SHADER_CRT -> "CRT pantalla plana"
        SHADER_CINE -> "Cine"
        SHADER_BW -> "B/N"
        SHADER_ANIME -> "Anime"
        SHADER_SHARPEN -> "Sharpen"
        SHADER_GRAIN -> "Grain"
        else -> "Off"
    }

    /**
     * Selección actual: tipo (0=off, 1=CRT, 2=Cine, 3=B/N, 4=Anime,
     * 5=Sharpen, 6=Grain) + intensidad. Migra una vez las prefs legacy del CRT.
     */
    fun shaderSelection(prefs: SharedPreferences): Pair<Int, Float> {
        if (prefs.contains(KEY_SHADER_EN)) {
            if (!prefs.getBoolean(KEY_SHADER_EN, false)) return SHADER_OFF to 0f
                val t = prefs.getInt(KEY_SHADER_TYPE, SHADER_CRT).coerceIn(0, 6)
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
    const val KEY_CINE_EN = "cine_enabled"
    const val KEY_CINE_STRENGTH = "cine_strength"
    const val KEY_CINE_MODE = "cine_mode"
    const val KEY_CINE_FAKEHDR = "cine_fakehdr"
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
        // KEY_COLORS_EN ya no se lee aquí: el switch maestro de Light Boost
        // guarda el grupo entero, así que la fila depende solo de KEY_CINE_EN.

        // Refresco de las etiquetas ON/OFF. BUG QUE SE ARREGLA AQUI: el
        // sub-dialogo se abre con AlertDialog.show(), que es ASINCRONO
        // (retorna de inmediato). Antes, la fila releia prefs justo despues
        // de invoke(), o sea ANTES de que el usuario tocara nada, y nunca
        // volvia a releer -> la etiqueta se quedaba en el estado viejo
        // (seguia marcando OFF aunque activaras la opcion). Ahora se
        // relee cuando el sub-dialogo APLICA los cambios.
        val rowViews = mutableListOf<Pair<FeatureEntry, TextView>>()
        // Ultra económico: los efectos están apagados aunque el ajuste diga
        // ON (se conservan para al apagar el modo). Las filas se muestran en
        // gris, sin acción, con la etiqueta forzada a OFF.
        val ultraOff = try {
            com.karin.streamtv.util.AppPreferences.isUltraEconomyMode()
        } catch (_: Exception) { false }
        fun displayLabel(entry: FeatureEntry): String {
            val base = entry.label()
            if (!ultraOff) return base
            return base.replace("• ON", "• OFF")
        }
        fun refreshRows() {
            for ((entry, tv) in rowViews) tv.text = displayLabel(entry)
        }
        // Envuelve onEffectsChanged: todos los sub-dialogos lo llaman al
        // aplicar, asi que es el punto fiable para refrescar las filas.
        val onEffectsChangedWrapped: (ExoPlayer) -> Unit = { p ->
            onEffectsChanged(p)
            refreshRows()
        }

        // Orden = orden real del pipeline en ExoPlayerActivity para evitar confusión.
        val entries = listOf(
            // 1. Restore Boost: limpieza + reconstrucción + detalle en 1 pase.
            //    Master vinculado + ajuste fino opcional por etapa.
            FeatureEntry(
                {
                    "1. Restore Boost • ${onOff(prefs.getBoolean(KEY_RESTORE_EN, false))}" +
                        if (prefs.getBoolean(KEY_RESTORE_CUSTOM, false)) " (fino)" else ""
                },
                "Restaura en una sola pasada: limpia bloques/ruido/bandas, reconstruye bordes con coherencia de pixel inspirada en CRT (sin scanlines/mascara/glow) y afila micro-detalle sin reintroducir pixelado. Intensidad maestra + ajuste fino opcional.",
                R.drawable.ic_image,
            ) {
                showRestoreDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChangedWrapped,
                    onMasterLive = { v -> onStrengthChanged("restore", v) },
                    onStagesLive = { d, r, t -> onRestoreChanged(d, r, t) },
                )
            },

            // 2. Light Boost: brillo dinámico, contraste inteligente y color.
            //    Una sola intensidad, Manual o AUTO.
            FeatureEntry(
                {
                    "2. Light Boost • ${onOff(cineEn)}" +
                        if (cineEn && !prefs.getBoolean(KEY_CINE_FAKEHDR, true)) " (sin HDR)" else ""
                },
                "Una sola intensidad automática: luz, contraste y color juntos. Modo Manual o AUTO (la escena decide). El interruptor gobierna el grupo completo: al apagar se anulan luz, color y rango.",
                R.drawable.ic_sun,
            ) {
                showKarinLightBoostDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChangedWrapped,
                    onKarinChanged = onKarinChanged,
                )
            },
            // 3. Movimiento (blend o 60fps reales por flujo óptico)
            FeatureEntry(
                { "3. MotionX2 • ${onOff(prefs.getBoolean(KEY_MOTIONX2_EN, false))}" },
                "Suavizado de movimiento: mezcla temporal o 60 fps reales por interpolación. Va después del Upscaler para trabajar a resolución de pantalla final.",
                R.drawable.ic_forward,
            ) {
                showMotionX2Dialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChangedWrapped,
                )
            },
            // 4. Upscaler de calidad (sustituye el restore bilineal; gratis
            //    si Light Boost está en la cadena, pesado si va solo)
            FeatureEntry(
                { "4. Upscaler • ${onOff(prefs.getBoolean(KEY_UPSCALER_EN, false))}" },
                "Reescala el video (KarinSuperRes, FSR o Anime4K) con afilado propio. En gama alta, Karin HiRes y FSR afilan el resultado real en 2 pases; en media/baja usan un solo pase para mantener los fps.",
                R.drawable.ic_aspect_ratio,
            ) {
                showUpscalerDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChangedWrapped,
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
                "Acabado estético final tras MotionX2: CRT, Cine, B/N, Anime, Sharpen o Grain. Un tipo a la vez.",
                R.drawable.ic_video,
            ) {
                showShaderDialog(
                    activity = activity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = onEffectsChangedWrapped,
                    onLiveChange = { v -> onStrengthChanged("shader", v) },
                )
            },
            // 6. Demo (solo visualización, no toca el pipeline)
            FeatureEntry(
                { "6. Demo split-screen • ${onOff(prefs.getBoolean(KEY_DEMO_EN, false))}" },
                "Comparación split-screen: izquierda el video original, derecha con mejoras, separadas por una línea blanca vertical. Ambos lados muestran el mismo instante.",
                R.drawable.ic_stats_compare,
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
                        player?.let { onEffectsChangedWrapped(it) }
                    },
                )
            },
        )

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 8)
        }
        if (ultraOff) {
            list.addView(TextView(activity).apply {
                text = "☘️ Ultra económico activo: efectos apagados. " +
                    "Apágalo en Configuración para usarlos."
                textSize = 13f
                setTextColor(0xFF9E9E9E.toInt())
                setPadding(16, 12, 16, 16)
            })
        }
        var firstRow: LinearLayout? = null
        entries.forEach { entry ->
            val labelView = TextView(activity).apply {
                text = displayLabel(entry)
                textSize = 16f
                setTextColor(if (ultraOff) 0xFF616161.toInt() else 0xFFECEFF1.toInt())
            }
            rowViews += entry to labelView
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)
                isFocusable = !ultraOff
                isClickable = !ultraOff
                addView(ImageView(activity).apply {
                    setImageResource(entry.iconRes)
                    setColorFilter(if (ultraOff) 0xFF616161.toInt() else 0xFFB0BEC5.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                        marginEnd = dp(16)
                    }
                })
                addView(labelView)
                if (!ultraOff) {
                    setOnClickListener {
                        // Abre el sub-diálogo (asíncrono). El refresco real de
                        // la etiqueta ocurre en onEffectsChangedWrapped, cuando
                        // el usuario aplique el cambio; esta llamada solo sincroniza
                        // el estado actual por si el sub-diálogo se cerrara sin
                        // tocar nada.
                        entry.action?.invoke()
                        labelView.text = displayLabel(entry)
                    }
                }
            }
            list.addView(row)
            if (firstRow == null && !ultraOff) firstRow = row
        }
        AlertDialog.Builder(activity)
            .setTitle(if (ultraOff) "Opciones Avanzadas de Video (apagadas ☘️)" else "Opciones Avanzadas de Video")
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
            .create()
            .apply {
                // TV/D-pad: el foco debe empezar en la primera fila, no en Cerrar.
                setOnShowListener { firstRow?.requestFocus() }
                show()
            }
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

        fun pct(v: Float) = "Intensidad: ${(v * 100).toInt()}%"
        val valueLabel = TextView(activity).apply { text = pct(strength) }
        val seek = SeekBar(activity).apply {
            max = 100
            progress = (strength * 100).toInt()
        }

        // Cada tipo con su descripción debajo (como Upscaler/MotionX2): el
        // detalle va pegado a su opción, no en un bloque de texto arriba.
        val shaderDefs = listOf(
            SHADER_OFF to ("Apagado" to "Sin acabado (imagen tal cual sale de la cadena)."),
            SHADER_CRT to ("CRT pantalla plana" to "TV retro: curvatura + scanlines + rejilla RGB + viñeta."),
            SHADER_CINE to ("Cine" to "Aspecto cinematográfico: viñeta suave + grano de película animado."),
            SHADER_BW to ("B/N" to "Blanco y negro monocromático con contraste."),
            SHADER_ANIME to ("Anime" to "Mejora de líneas para anime: define bordes y limpia el cel."),
            SHADER_SHARPEN to ("Sharpen" to "Nitidez inteligente: afila sin halos, respeta zonas ya nítidas."),
            SHADER_GRAIN to ("Grain" to "Textura cinematográfica: grano animado, más en medios tonos."),
        )
        val radios = mutableListOf<RadioButton>()
        fun syncRadios() {
            radios.forEachIndexed { i, r -> r.isChecked = shaderDefs[i].first == type }
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
        val typeBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        shaderDefs.forEach { (t, pair) ->
            val (title, descText) = pair
            val rb = RadioButton(activity).apply {
                text = title
                isChecked = t == type
            }
            radios.add(rb)
            val sub = TextView(activity).apply {
                text = descText
                textSize = 12f
                setPadding(56, 0, 0, 12)
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(rb)
                addView(sub)
            }
            rb.setOnClickListener { pick(t) }
            row.setOnClickListener { pick(t) }
            typeBox.addView(row)
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
            addView(typeBox)
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
                applyWithIncompatibilityGuard(activity, prefs, player, onEffectsChanged)
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

        fun linkedStages(m: Float): Triple<Float, Float, Float> {
            val upOn = prefs.getBoolean(KEY_UPSCALER_EN, false)
            val upMode = prefs.getInt(KEY_UPSCALER_MODE, SuperResolutionEffect.MODE_FSR)
            // Preview sin topes de gama (la cadena los aplica al confirmar).
            val s = RestoreBoostController.stagesFor(m, upOn, upMode, lowEnd = false)
            return Triple(s.depixel, s.retro, s.detail)
        }
        var dep: Float
        var ret: Float
        var det: Float
        if (custom) {
            dep = prefs.getInt(KEY_DEPIXEL_STRENGTH, 60) / 100f
            ret = prefs.getInt(KEY_RETRO_STRENGTH, 55) / 100f
            det = prefs.getInt(KEY_DETAIL_BOOST_STRENGTH, 55) / 100f
        } else {
            val (d, r, t) = linkedStages(master)
            dep = d; ret = r; det = t
        }

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
                val (d, r, t) = linkedStages(master)
                dep = d
                ret = r
                det = t
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
                text = "Limpieza + reconstrucción con coherencia CRT sutil + detalle en un solo pase: " +
                    "la reconstrucción cohesiona píxeles vecinos y luego el detalle afila encima."
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

        // Cada modo con su descripción debajo (como MotionX2): arriba solo
        // una línea, el detalle va pegado a su opción.
        val modeDefs = listOf(
            Triple(
                SuperResolutionEffect.MODE_KARIN,
                "KarinSuperRes (Experimental)",
                "Upscaler propio: nítido sin halos ni ruido, DRS-aware, variante por gama (ECO/CRISP/HiRes).",
            ),
            Triple(
                SuperResolutionEffect.MODE_FSR,
                "FSR",
                "AMD edge-adaptive con afilado adaptativo. El default estable.",
            ),
            Triple(
                SuperResolutionEffect.MODE_ANIME4K,
                "Anime4K",
                "Afilado rápido tuneado para anime.",
            ),
        )
        val radios = mutableListOf<RadioButton>()
        fun selectMode(m: Int) {
            mode = m
            radios.forEachIndexed { i, r -> r.isChecked = modeDefs[i].first == m }
        }
        val modeBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        modeDefs.forEach { (m, title, desc) ->
            val rb = RadioButton(activity).apply {
                text = title
                isChecked = m == mode
            }
            radios.add(rb)
            val sub = TextView(activity).apply {
                text = desc
                textSize = 12f
                setPadding(56, 0, 0, 12)
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(rb)
                addView(sub)
            }
            rb.setOnClickListener { selectMode(m) }
            row.setOnClickListener { selectMode(m) }
            modeBox.addView(row)
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
                    text = "Reescala 2x con calidad, tope 1080p."
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
            addView(modeBox)
            addView(valueLabel)
            addView(seek)
            addView(
                TextView(activity).apply {
                    text = "Con Light Boost sustituye su pasada sin costo extra; " +
                        "solo cuenta del presupuesto. En demo se muestra neutro."
                    textSize = 13f
                    setPadding(0, 16, 0, 8)
                },
            )
        }

        AlertDialog.Builder(activity)
            .setTitle("Upscaler")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Aplicar") { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_UPSCALER_EN, enabled)
                    .putInt(KEY_UPSCALER_MODE, mode)
                    .putInt(KEY_UPSCALER_SHARP, (sharpness * 100).toInt())
                    .apply()
                applyWithIncompatibilityGuard(activity, prefs, player, onEffectsChanged)
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
        // automático). Sin segundo slider que apile ni confunda. El factor
        // 1.05 mantiene la respuesta del punto medio (0.5 -> 52%, igual que
        // el 0.15+0.75 anterior) pero permite llegar a 0 real: con suelo, el
        // color nunca se apagaba y la opción quedaba siempre ON.
        fun derivedColor(s: Float) = (1.05f * s).coerceIn(0f, 1f)
        var colorStrength = derivedColor(strength)
        var rangeMode = prefs.getInt(KEY_RANGE_MODE, 0).coerceIn(0, 2)
        var fakeHdrOn = prefs.getBoolean(KEY_CINE_FAKEHDR, true)

        fun push() {
            // El switch es el maestro de TODO el grupo (luz + color + rango),
            // igual que lo trata el resto de la app: al apagar se anulan los
            // tres, así el OFF es un OFF de verdad y no un "medio apagado".
            val effColor = if (enabled) colorStrength else 0f
            val effRange = if (enabled) rangeMode else 0
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
                    enabled = enabled,
                    autoMode = mode == KarinLightBoostController.MODE_AUTO,
                    colorStrength = effColor.coerceIn(0f, 1f),
                    rangeMode = effRange,
                    fakeHdr = fakeHdrOn,
                ),
            )
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
            text = "Original Sin cambios"
            isChecked = rangeMode == 0
        }
        val rangeExpand = RadioButton(activity).apply {
            text = "Completo (16-235 → 0-255)"
            isChecked = rangeMode == 1
        }
        val rangeCompress = RadioButton(activity).apply {
            text = "Limitado (0-255 → 16-235)"
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
            // Vertical: las etiquetas técnicas son largas y en horizontal se
            // cortan o se enciman.
            orientation = LinearLayout.VERTICAL
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

        // Interruptor del HDR simulado (curva tonal + clarity). Apagado: la
        // luz no toca brillo/contraste y solo aplican color y rango. Se
        // declara antes de syncEnabledUi para que este lo atenúe con el grupo.
        val fakeHdrSwitch = Switch(activity).apply {
            text = if (fakeHdrOn) "HDR simulado: ON" else "HDR simulado: OFF"
            isChecked = fakeHdrOn
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                fakeHdrOn = isChecked
                text = if (isChecked) "HDR simulado: ON" else "HDR simulado: OFF"
                push()
            }
        }

        // El switch es el maestro de todo el grupo (luz + color + rango): con
        // el grupo apagado se atenúan los controles que no aplican, para que
        // quede claro que no están haciendo nada. Se declara aquí porque su
        // listener usa modeManual/rangeRow/seek, ya declarados; el orden en
        // que se añaden al layout no depende del orden de declaración.
        fun syncEnabledUi() {
            listOf(modeManual, modeAuto).forEach { it.isEnabled = enabled }
            listOf(rangeOrig, rangeExpand, rangeCompress).forEach { it.isEnabled = enabled }
            rangeRow.alpha = if (enabled) 1f else 0.4f
            seek.isEnabled = enabled
            valueLabel.alpha = if (enabled) 1f else 0.4f
            fakeHdrSwitch.isEnabled = enabled
        }
        val switch = Switch(activity).apply {
            text = if (enabled) "Activado" else "Desactivado"
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, isChecked ->
                enabled = isChecked
                text = if (isChecked) "Activado" else "Desactivado"
                syncEnabledUi()
                push()
            }
        }

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

        // Encabezado de bloque: el diálogo se lee por secciones
        // (maestro → modo → HDR → intensidad → rango), no como lista revuelta.
        fun header(t: String): TextView = TextView(activity).apply {
            text = t
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 20, 0, 4)
        }
        fun hint(t: String): TextView = TextView(activity).apply {
            text = t
            textSize = 13f
            setPadding(0, 4, 0, 4)
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(switch)
            addView(hint("Luz, contraste y color en una sola intensidad."))
            addView(header("MODO DE INTENSIDAD"))
            addView(modeRow)
            addView(hint("Manual: intensidad fija. Auto: la escena decide; " +
                "la intensidad sigue mandando como maestro."))
            addView(presetRow)
            addView(valueLabel)
            addView(seek)
            addView(hint("Mueve luz y color juntos."))
            addView(header("HDR SIMULADO"))
            addView(fakeHdrSwitch)
            addView(hint("Curva tonal + clarity. Apagado: la luz no toca " +
                "brillo/contraste; solo aplican color y rango."))
            addView(header("RANGO"))
            addView(hint("Rango de colores."))
            addView(rangeRow)
        }
        syncEnabledUi()

        AlertDialog.Builder(activity)
            .setTitle("Light Boost")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Aplicar") { _, _ ->
                // El switch manda sobre el grupo entero: si está off se
                // guardan luz, color y rango neutros. Antes el color quedaba
                // siempre a 15% y el rango sobrevive, así que la opción
                // reaparecía como ON en el menú tras apagar.
                val saveColor = if (enabled) colorStrength else 0f
                val saveRange = if (enabled) rangeMode else 0
                prefs.edit()
                    .putBoolean(KEY_CINE_EN, enabled)
                    .putInt(KEY_CINE_MODE, mode)
                    .putInt(KEY_CINE_STRENGTH, (strength * 100).toInt())
                    .putBoolean(KEY_COLORS_EN, saveColor > 0f)
                    .putInt(KEY_COLORS_STRENGTH, (saveColor * 100).toInt())
                    .putInt(KEY_RANGE_MODE, saveRange)
                    .putBoolean(KEY_CINE_FAKEHDR, fakeHdrOn)
                    .apply()
                applyWithIncompatibilityGuard(activity, prefs, player, onEffectsChanged)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // COMPATIBILIDAD ENTRE OPCIONES (3D vs filtros)
    // ------------------------------------------------------------------

    /** Un conflicto real entre dos funciones que no pueden estar activas a la vez. */
    private data class Incompatibility(
        val optA: String,
        val optB: String,
        val reason: String,
        /** Conservar A (optA): desactiva la opción B y reconstruye. */
        val keepA: () -> Unit,
        /** Conservar B (optB): desactiva la opción A y reconstruye. */
        val keepB: () -> Unit,
    )

    /**
     * Detecta el primer conflicto MORTAL (⛔) en el estado actual de prefs.
     * Solo bloquean los que destruyen el efecto (diálogo de elección):
     *  - Anaglifo + Shader B/N  (sin color no hay separación de ojos).
     *  - Pulfrich + MotionX2    (la mezcla temporal anula el retardo entre ojos).
     * El resto (estéreo+MotionX2/Upscaler, anaglifo+Light) solo degrada
     * (fantasma/halo/diafonía): avisa con ⚠ en compatWarnings(), nunca
     * apaga nada solo. Antes el Light bloqueaba al anaglifo y el "Conservar
     * Light Boost" apagaba el 3D en silencio ("no se aplica").
     */
    private fun findIncompatibility(prefs: SharedPreferences): Incompatibility? {
        val mode = Karin3DController.currentMode(prefs)
        if (!Karin3DController.isActive(prefs)) return null
        val anaglifo = mode == Karin3DController.MODE_ANAGLYPH
        val pulfrich = mode == Karin3DController.MODE_PULFRICH
        val shaderBw = prefs.getBoolean(KEY_SHADER_EN, false) &&
            prefs.getInt(KEY_SHADER_TYPE, SHADER_OFF) == SHADER_BW
        val motionOn = prefs.getBoolean(KEY_MOTIONX2_EN, false)

        fun turnOff3D() { prefs.edit().putBoolean(KEY_3D_EN, false).apply() }
        fun turnOffShader() {
            prefs.edit().putBoolean(KEY_SHADER_EN, false).putInt(KEY_SHADER_TYPE, SHADER_OFF).apply()
        }
        fun turnOffMotion() { prefs.edit().putBoolean(KEY_MOTIONX2_EN, false).apply() }

        return when {
            anaglifo && shaderBw -> Incompatibility(
                optA = "3D Anaglifo",
                optB = "Shader B/N",
                reason = "El blanco y negro destruye los canales de color que separan los ojos del anaglifo.",
                keepA = { turnOffShader() },
                keepB = { turnOff3D() },
            )
            pulfrich && motionOn -> Incompatibility(
                optA = "3D Pulfrich",
                optB = "MotionX2",
                reason = "La mezcla temporal de MotionX2 destruye el retardo entre ojos del que vive Pulfrich (se aplana a 2D).",
                keepA = { turnOffMotion() },
                keepB = { turnOff3D() },
            )
            else -> null
        }
    }

    /**
     * Aplica los cambios (ya escritos en prefs) protegiendo contra
     * incompatibilidades reales: si el estado resultante tiene un conflicto
     * ⛔, en lugar de aplicar en silencio muestra una ventana que avisa que
     * las dos funciones no pueden trabajar en conjunto y DEJA ELEGIR cuál
     * conservar; la otra se desactiva automáticamente. Sin conflicto, llama
     * [onEffectsChanged] directo. Se resuelven en cascada (varios a la vez).
     */
    private fun applyWithIncompatibilityGuard(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onResolved: () -> Unit = {},
    ) {
        val conflict = findIncompatibility(prefs)
        if (conflict == null) {
            player?.let { onEffectsChanged(it) }
            onResolved()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Opciones incompatibles")
            .setMessage(
                "«${conflict.optA}» y «${conflict.optB}» no pueden funcionar en conjunto.\n\n" +
                    conflict.reason +
                    "\n\nElige cuál conservar: la otra se desactivará automáticamente.",
            )
            .setPositiveButton("Conservar ${conflict.optA}") { _, _ ->
                conflict.keepA()
                applyWithIncompatibilityGuard(activity, prefs, player, onEffectsChanged, onResolved)
            }
            .setNegativeButton("Conservar ${conflict.optB}") { _, _ ->
                conflict.keepB()
                applyWithIncompatibilityGuard(activity, prefs, player, onEffectsChanged, onResolved)
            }
            .setCancelable(false)
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
      * VR duplicado (compatibilidad Cardboard, sin profundidad) y
      * Pulfrich temporal (retardo real de 1 cuadro), con profundidad
      * (pseudo-3D experimental / mezcla temporal Pulfrich), ojo
      * intercambiable y fuente estéreo (2D/SBS) para el anaglifo y el
      * VR. Cada modo muestra solo sus controles. La profundidad
      * previsualiza en vivo si el 3D ya está activo; el modo se aplica
      * al confirmar.
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
            "VR duplicado (Cardboard, sin profundidad)",
            "Pulfrich temporal (movimiento → profundidad)",
        )
        val descs = arrayOf(
            "Con SBS mezcla ambos ojos reales (3D real); con 2D genera pseudo-3D experimental por luma. Elige tus lentes abajo.",
            "Compatibilidad: con 2D lo duplica para el visor (disparidad 0, no es 3D); con SBS lo deja tal cual. Sin seguimiento de cabeza.",
            "Retardo real de 1 cuadro (L=actual, R=previo) con lentes bicolor. En quieto se ve normal; depth=0 → 2D puro para lente oscuro físico.",
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
        // Pulfrich ignora fuente y lentes solo tiñen la síntesis).
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
        val inputNames = arrayOf("2D (pseudo-3D experimental)", "SBS (lado-a-lado)")
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
            "Profundidad: ${(v * 100).toInt()}% (pseudo-3D experimental / mezcla temporal Pulfrich)"
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
            text = "Swap: ojo derecho (anaglifo estéreo / ojo retardado Pulfrich)"
            isChecked = swapEye
            setOnCheckedChangeListener { _, isChecked -> swapEye = isChecked }
        }

        // Solo se muestra lo que el modo pendiente usa: la fuente aplica
        // a Anaglifo (y a VR para no duplicar un SBS); la profundidad a
        // Anaglifo 2D y Pulfrich; el swap al anaglifo estéreo y a Pulfrich.
        refreshModeExtras = {
            val m = modeValues[pending.coerceIn(modeValues.indices)]
            val isAnag = m == Karin3DController.MODE_ANAGLYPH
            val isVr = m == Karin3DController.MODE_VR_SBS
            val isPul = m == Karin3DController.MODE_PULFRICH
            val showInput = if (isAnag || isVr) android.view.View.VISIBLE else android.view.View.GONE
            inputTitle.visibility = showInput
            inputBox.visibility = showInput
            val showDepth = if (isVr) android.view.View.GONE else android.view.View.VISIBLE
            depthLabel.visibility = showDepth
            depthSeek.visibility = showDepth
            swapBox.visibility = if (isAnag || isPul) android.view.View.VISIBLE else android.view.View.GONE
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

        // Tutorial de fabricación de gafas (anaglifo / Pulfrich / Cardboard):
        // materiales, dónde conseguirlos, medidas, plano y armado paso a paso.
        val glassesLink = TextView(activity).apply {
            text = "📖 Gafas 3D caseras: cómo fabricarlas (anaglifo, Pulfrich, Cardboard)"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#6C63FF"))
            setPadding(0, 16, 0, 0)
            isFocusable = true
            isClickable = true
            setOnClickListener {
                activity.startActivity(Intent(activity, GlassesTutorialActivity::class.java))
            }
            onActionKey { performClick() }
        }

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
            addView(glassesLink)
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
                // Incompatibilidad mortal (p. ej. B/N + Anaglifo, MotionX2 +
                // Pulfrich): ventana que deja elegir cuál conservar en vez del
                // cambio silencioso. Al resolver se avisa con Toast del estado
                // real (antes el 3D podía quedar apagado sin decir nada).
                applyWithIncompatibilityGuard(activity, prefs, player, onEffectsChanged) {
                    val msg = if (Karin3DController.isActive(prefs)) {
                        val d = (prefs.getInt(KEY_3D_DEPTH, Karin3DController.DEFAULT_DEPTH))
                        "3D aplicado: ${Karin3DController.chainLabel(prefs)} · ${d}%"
                    } else {
                        "3D apagado"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Ventana "Modo MotionX2": Apagado + modos legacy (1:1) + 60 fps reales.
    // Todos los modos se ofrecen siempre: el render propio (sin grafo de
    // Media3) eliminó el riesgo de Error 7001 que motivaba el gate de gama.
    private fun showMotionX2Dialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
    ) {
        // Ordenados de MEJOR rendimiento (arriba) al más pesado (abajo).
        // Coste real medido en pases de GPU: DOUBLING no entra al render
        // propio (0 pases), HYBRID/BLEND son 1 pase con 2 muestras, y
        // ECO60/REAL60 arrancan el render propio a 60fps (historial a media
        // y a resolución completa respectivamente).
        val titles = mutableListOf(
            "⏻ Apagado",
            "DOUBLING (Frame x2) · sin coste",
            "HYBRID (Doubling + Micro-Blend) · recomendado",
            "BLEND (Suavizado) · mezcla fuerte",
            "ECO60 (60fps liviano) · historial a media res",
            "60 fps reales (GRID) · el más pesado",
        )
        val descs = mutableListOf(
            "No hace nada. Video original.",
            "Muestra cada cuadro nítido tal cual y lo repite el panel. 0 pases de GPU: el más rápido.",
            "Cuadro nítido + micro-mezcla (25%). Un solo pase. Mejor balance de fluidez y coste.",
            "Mezcla cuadro anterior y actual al 50%. Un solo pase: más fluido, pero puede verse fantasma.",
            "Render propio a 60fps con historial a media resolución. Para equipos modestos.",
            "Cuadros intermedios en grid 60Hz real con anti-fantasma. Máxima fluidez, máximo consumo.",
        )
        // Fila -> ordinal MotionX2Mode legacy (el 3/SPIKE ya no se ofrece).
        val dialogToLegacy = mutableListOf(-1, 1, 0, 2, MotionX2Mode.ECO60.ordinal, MotionX2Mode.REAL60.ordinal)
        // Ordinal MotionX2Mode -> fila. El 3 (INTERP/SPIKE eliminado) se muestra
        // como REAL60; en ejecución resolveStored lo migra de todos modos.
        val legacyToDialog = intArrayOf(2, 1, 3, 5, 5, 4)
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
            dialog?.dismiss()
            // MotionX2 puede chocar con el 3D Pulfrich o estéreo: ventana de
            // elección (conservar uno desactiva el otro).
            applyWithIncompatibilityGuard(activity, prefs, player, onEffectsChanged)
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

