package com.karin.streamtv.player

import android.app.Activity
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.exoplayer.ExoPlayer

/**
 * KARINFLIX VISION ASSIST: prefs + diálogo del botón de anteojos.
 *
 * Selección MULTIPLE de necesidades de visión (bitmask, GPU-light):
 *   FLAG_DALTONISM (1)  -> corrección de colores (subtipo Protan/Deutan/Tritan/Mono)
 *   FLAG_LOW_VISION (2) -> claridad / micro-detalle
 *   FLAG_HIGH_CONTRAST (4) -> contraste reforzado y sombras abiertas
 *   FLAG_PHOTOPHOBIA (8) -> antibrillo, tinte cálido, suavizado de colores
 *   FLAG_BLUE_LIGHT (16) -> protección de luz azul y atenuación nocturna
 *   FLAG_EYE_STRAIN (32) -> alivio de vista cansada (relaja contraste y saturación)
 *
 * El usuario marca las necesidades, la app configura automáticamente las
 * mejoras visuales (un solo pase GL, sin coste visible). La intensidad
 * previsualiza en vivo; las mejoras se aplican al confirmar.
 *
 * IMPORTANTE: la ayuda SIEMPRE está desactivada por defecto (mask = 0). Solo
 * se activa si el usuario abre el diálogo y marca al menos una necesidad.
 *
 * DECISIÓN DE DISEÑO: esta función se organiza por PROBLEMA DE VISIÓN (baja
 * visión, fotofobia, etc.), no por proceso de imagen. Los solapamientos
 * técnicos con otros efectos (Nitidez RCAS/Detail, B/N, Light Boost) son
 * intencionales: aquí el filtro comparte pase con las mejoras de salud visual
 * y va dirigido por el problema del usuario, no por estética. No fusionar.
 */
data class VisionAssistSettings(
    val mask: Int = 0,                     // bitmask de necesidades activas
    val intensity: Float = 0.45f,          // maestra global 0..1
    val daltonType: Int = 1,               // 0=Protan 1=Deutan 2=Tritan 3=Monocromo
    val lowSharp: Float = 0.40f,           // Baja Visión: definición
    val lowEdge: Float = 0.25f,            // Baja Visión: bordes
    val photoWarm: Float = 0.60f,          // Fotofobia: tinte cálido
    val photoDesat: Float = 0.40f,         // Fotofobia: suavizado de colores
    val hcLift: Float = 0.40f,             // Alto Contraste: abrir sombras
    val hcSoft: Float = 0.60f,             // Alto Contraste: suavizado de extremos
    val strainRelax: Float = 0.50f,        // Vista cansada: nivel de relajación
    val blueLight: Float = 0.60f,          // Luz azul: fuerza de protección
) {
    companion object {
        const val FLAG_DALTONISM = 1
        const val FLAG_LOW_VISION = 2
        const val FLAG_HIGH_CONTRAST = 4
        const val FLAG_PHOTOPHOBIA = 8
        const val FLAG_BLUE_LIGHT = 16
        const val FLAG_EYE_STRAIN = 32

        const val DALTON_PROTAN = 0
        const val DALTON_DEUTAN = 1
        const val DALTON_TRITAN = 2
        const val DALTON_MONO = 3

        val ALL_FLAGS = intArrayOf(
            FLAG_DALTONISM, FLAG_LOW_VISION, FLAG_HIGH_CONTRAST, FLAG_PHOTOPHOBIA,
            FLAG_BLUE_LIGHT, FLAG_EYE_STRAIN,
        )
    }

    val isActive: Boolean
        get() = mask != 0 && intensity > 0f

    val hasDaltonism: Boolean get() = mask and FLAG_DALTONISM != 0
    val hasLowVision: Boolean get() = mask and FLAG_LOW_VISION != 0
    val hasHighContrast: Boolean get() = mask and FLAG_HIGH_CONTRAST != 0
    val hasPhotophobia: Boolean get() = mask and FLAG_PHOTOPHOBIA != 0
    val hasBlueLight: Boolean get() = mask and FLAG_BLUE_LIGHT != 0
    val hasEyeStrain: Boolean get() = mask and FLAG_EYE_STRAIN != 0

    fun with(flag: Int, on: Boolean): VisionAssistSettings =
        if (on) copy(mask = mask or flag) else copy(mask = mask and flag.inv())
}

object VisionAssistHelper {

    const val KEY_MASK = "vision_mask"
    @Deprecated("Legacy KEY_EN, migrado a KEY_MASK")
    const val KEY_EN = "vision_enabled"
    @Deprecated("Legacy KEY_PROFILE, migrado a KEY_MASK")
    const val KEY_PROFILE = "vision_profile"
    const val KEY_INTENSITY = "vision_intensity"
    const val KEY_DALTON = "vision_dalton"
    const val KEY_LOW_SHARP = "vision_low_sharp"
    const val KEY_LOW_EDGE = "vision_low_edge"
    const val KEY_PHOTO_WARM = "vision_photo_warm"
    const val KEY_PHOTO_DESAT = "vision_photo_desat"
    const val KEY_HC_LIFT = "vision_hc_lift"
    const val KEY_HC_SOFT = "vision_hc_soft"
    const val KEY_STRAIN_RELAX = "vision_strain_relax"
    const val KEY_BLUE_LIGHT = "vision_blue_light"

    private fun legacyProfileToMask(p: Int): Int = when (p) {
        1 -> VisionAssistSettings.FLAG_DALTONISM
        2 -> VisionAssistSettings.FLAG_LOW_VISION
        3 -> VisionAssistSettings.FLAG_PHOTOPHOBIA
        4 -> VisionAssistSettings.FLAG_HIGH_CONTRAST
        else -> 0
    }

    private fun readMask(prefs: SharedPreferences): Int {
        if (prefs.contains(KEY_MASK)) {
            val v = prefs.getInt(KEY_MASK, 0)
            return v and (VisionAssistSettings.FLAG_DALTONISM or
                VisionAssistSettings.FLAG_LOW_VISION or
                VisionAssistSettings.FLAG_HIGH_CONTRAST or
                VisionAssistSettings.FLAG_PHOTOPHOBIA or
                VisionAssistSettings.FLAG_BLUE_LIGHT or
                VisionAssistSettings.FLAG_EYE_STRAIN)
        }
        // Migración de la versión anterior (un solo perfil).
        if (!prefs.getBoolean(KEY_EN, false)) return 0
        return legacyProfileToMask(prefs.getInt(KEY_PROFILE, VisionAssistSettings.FLAG_LOW_VISION))
            .takeIf { it != 0 } ?: VisionAssistSettings.FLAG_LOW_VISION
    }

    fun fromPrefs(prefs: SharedPreferences): VisionAssistSettings {
        val mask = readMask(prefs)
        return VisionAssistSettings(
            mask = mask,
            intensity = (prefs.getInt(KEY_INTENSITY, 45).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            daltonType = prefs.getInt(KEY_DALTON, VisionAssistSettings.DALTON_DEUTAN).coerceIn(0, 3),
            lowSharp = (prefs.getInt(KEY_LOW_SHARP, 40).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            lowEdge = (prefs.getInt(KEY_LOW_EDGE, 25).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            photoWarm = (prefs.getInt(KEY_PHOTO_WARM, 60).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            photoDesat = (prefs.getInt(KEY_PHOTO_DESAT, 40).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            hcLift = (prefs.getInt(KEY_HC_LIFT, 40).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            hcSoft = (prefs.getInt(KEY_HC_SOFT, 60).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            strainRelax = (prefs.getInt(KEY_STRAIN_RELAX, 50).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            blueLight = (prefs.getInt(KEY_BLUE_LIGHT, 60).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
        )
    }

    fun isActive(prefs: SharedPreferences): Boolean = fromPrefs(prefs).isActive

    fun needsLabel(s: VisionAssistSettings): String = buildList {
        if (s.hasDaltonism) add("Colores")
        if (s.hasLowVision) add("Claridad")
        if (s.hasHighContrast) add("Alto contraste")
        if (s.hasPhotophobia) add("Antibrillo")
        if (s.hasBlueLight) add("Luz azul")
        if (s.hasEyeStrain) add("Vista cansada")
    }.joinToString(" + ").ifEmpty { "Apagado" }

    fun saveInto(prefs: SharedPreferences, s: VisionAssistSettings) {
        prefs.edit()
            .putInt(KEY_MASK, s.mask)
            .putInt(KEY_INTENSITY, (s.intensity.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_DALTON, s.daltonType.coerceIn(0, 3))
            .putInt(KEY_LOW_SHARP, (s.lowSharp.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_LOW_EDGE, (s.lowEdge.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_PHOTO_WARM, (s.photoWarm.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_PHOTO_DESAT, (s.photoDesat.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_HC_LIFT, (s.hcLift.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_HC_SOFT, (s.hcSoft.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_STRAIN_RELAX, (s.strainRelax.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_BLUE_LIGHT, (s.blueLight.coerceIn(0f, 1f) * 100).toInt())
            .apply()
    }

    /**
     * Diálogo del botón de anteojos. El usuario marca UNA O VARIAS necesidades
     * de visión y la app configura automáticamente las mejoras (single-pass GL).
     * La intensidad previsualiza en vivo vía [onLive]; se aplica al confirmar.
     */
    fun showVisionDialog(
        activity: Activity,
        prefs: SharedPreferences,
        player: ExoPlayer?,
        onEffectsChanged: (ExoPlayer) -> Unit,
        onLive: (VisionAssistSettings) -> Unit = { _ -> },
    ) {
        var cfg = fromPrefs(prefs)

        fun pct(v: Float) = "Intensidad: ${(v * 100).toInt()}%"

        var pendingMask = cfg.mask

        // ---- Daltonismo: subtipo ----
        var daltonType = cfg.daltonType
        val daltonRadios = mutableListOf<RadioButton>()
        fun syncDalton() {
            daltonRadios.forEachIndexed { i, r -> r.isChecked = i == daltonType }
        }
        val daltonNames = arrayOf("Protan (rojo)", "Deutan (verde)", "Tritan (azul)", "Monocromático")
        val daltonRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        daltonNames.forEachIndexed { i, name ->
            val rb = RadioButton(activity).apply { text = name }
            daltonRadios.add(rb)
            rb.setOnClickListener {
                daltonType = i
                syncDalton()
            }
            daltonRow.addView(rb)
        }
        syncDalton()
        val daltonSub = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(TextView(activity).apply {
                text = "Tipo de corrección:"
                textSize = 13f
                setPadding(52, 4, 0, 4)
            })
            addView(daltonRow)
        }

        // ---- Sub-grupos por necesidad (parámetros finos) ----
        val lowSharpLabel = TextView(activity).apply { text = "" }
        val lowSharpSeek = SeekBar(activity).apply { max = 100 }
        val lowEdgeLabel = TextView(activity).apply { text = "" }
        val lowEdgeSeek = SeekBar(activity).apply { max = 100 }
        fun lowLabel() {
            lowSharpLabel.text = "Definición: ${(cfg.lowSharp * 100).toInt()}%"
            lowEdgeLabel.text = "Bordes: ${(cfg.lowEdge * 100).toInt()}%"
        }
        val lowVisionGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(lowSharpLabel)
            addView(lowSharpSeek)
            addView(lowEdgeLabel)
            addView(lowEdgeSeek)
        }
        lowSharpSeek.progress = (cfg.lowSharp * 100).toInt()
        lowEdgeSeek.progress = (cfg.lowEdge * 100).toInt()
        lowLabel()

        val photoWarmLabel = TextView(activity).apply { text = "" }
        val photoWarmSeek = SeekBar(activity).apply { max = 100 }
        val photoDesatLabel = TextView(activity).apply { text = "" }
        val photoDesatSeek = SeekBar(activity).apply { max = 100 }
        fun photoLabel() {
            photoWarmLabel.text = "Tinte cálido: ${(cfg.photoWarm * 100).toInt()}%"
            photoDesatLabel.text = "Suavizado de colores: ${(cfg.photoDesat * 100).toInt()}%"
        }
        val photoGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(photoWarmLabel)
            addView(photoWarmSeek)
            addView(photoDesatLabel)
            addView(photoDesatSeek)
        }
        photoWarmSeek.progress = (cfg.photoWarm * 100).toInt()
        photoDesatSeek.progress = (cfg.photoDesat * 100).toInt()
        photoLabel()

        val hcLiftLabel = TextView(activity).apply { text = "" }
        val hcLiftSeek = SeekBar(activity).apply { max = 100 }
        val hcSoftLabel = TextView(activity).apply { text = "" }
        val hcSoftSeek = SeekBar(activity).apply { max = 100 }
        fun hcLabel() {
            hcLiftLabel.text = "Abrir sombras: ${(cfg.hcLift * 100).toInt()}%"
            hcSoftLabel.text = "Suavizado de extremos: ${(cfg.hcSoft * 100).toInt()}%"
        }
        val hcGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(hcLiftLabel)
            addView(hcLiftSeek)
            addView(hcSoftLabel)
            addView(hcSoftSeek)
        }
        hcLiftSeek.progress = (cfg.hcLift * 100).toInt()
        hcSoftSeek.progress = (cfg.hcSoft * 100).toInt()
        hcLabel()

        val strainRelaxLabel = TextView(activity).apply { text = "" }
        val strainRelaxSeek = SeekBar(activity).apply { max = 100 }
        fun strainLabel() {
            strainRelaxLabel.text = "Relajación: ${(cfg.strainRelax * 100).toInt()}%"
        }
        val strainGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(strainRelaxLabel)
            addView(strainRelaxSeek)
        }
        strainRelaxSeek.progress = (cfg.strainRelax * 100).toInt()
        strainLabel()

        val blueLightLabel = TextView(activity).apply { text = "" }
        val blueLightSeek = SeekBar(activity).apply { max = 100 }
        fun blueLabel() {
            blueLightLabel.text = "Filtro azul: ${(cfg.blueLight * 100).toInt()}%"
        }
        val blueGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(blueLightLabel)
            addView(blueLightSeek)
        }
        blueLightSeek.progress = (cfg.blueLight * 100).toInt()
        blueLabel()

        // ---- Necesidades (checkbox, selección múltiple) ----
        val needNames = arrayOf(
            "Colores (daltonismo)",
            "Claridad (baja visión)",
            "Alto contraste",
            "Antibrillo y confort ocular",
            "Luz azul (protección)",
            "Vista cansada (relajación)",
        )
        val needDescs = arrayOf(
            "Facilita distinguir rojo/verde/azul en tonos muy parecidos. Elige el tipo de corrección abajo.",
            "Realza bordes, micro-detalle y contraste de medios para leer mejor, sin halos.",
            "Contraste reforzado y sombras abiertas para que bordes y texto se vean más nítidos.",
            "Atenúa brillos, agrega tinte cálido y suaviza colores; reduce el deslumbramiento.",
            "Reduce la luz azul y atenúa el brillo hacia la noche; protege el descanso ocular.",
            "Alivia el esfuerzo de la vista cansada: baja el contraste duro, suaviza colores y abre sombras.",
        )
        val needFlag = intArrayOf(
            VisionAssistSettings.FLAG_DALTONISM,
            VisionAssistSettings.FLAG_LOW_VISION,
            VisionAssistSettings.FLAG_HIGH_CONTRAST,
            VisionAssistSettings.FLAG_PHOTOPHOBIA,
            VisionAssistSettings.FLAG_BLUE_LIGHT,
            VisionAssistSettings.FLAG_EYE_STRAIN,
        )

        fun updateSubVisibility() {
            daltonSub.visibility = if (pendingMask and VisionAssistSettings.FLAG_DALTONISM != 0) View.VISIBLE else View.GONE
            lowVisionGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_LOW_VISION != 0) View.VISIBLE else View.GONE
            photoGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_PHOTOPHOBIA != 0) View.VISIBLE else View.GONE
            hcGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_HIGH_CONTRAST != 0) View.VISIBLE else View.GONE
            strainGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_EYE_STRAIN != 0) View.VISIBLE else View.GONE
            blueGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_BLUE_LIGHT != 0) View.VISIBLE else View.GONE
        }
        fun pushLive() {
            onLive(cfg)
        }

        // ---- Intensidad maestra (live) ----
        val intensityLabel = TextView(activity).apply { text = pct(cfg.intensity) }
        val intensitySeek = SeekBar(activity).apply {
            max = 100
            progress = (cfg.intensity * 100).toInt()
        }

        val needBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        needNames.forEachIndexed { i, name ->
            val cb = CheckBox(activity).apply {
                text = name
                isChecked = pendingMask and needFlag[i] != 0
            }
            val sub = TextView(activity).apply {
                text = needDescs[i]
                textSize = 12f
                setPadding(52, 0, 0, 12)
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(cb)
                addView(sub)
            }
            cb.setOnCheckedChangeListener { _, isChecked ->
                cfg = cfg.with(needFlag[i], isChecked)
                pendingMask = cfg.mask
                // Configura automáticamente: si ahora hay mejoras y la intensidad
                // está en cero, se sugiere un valor cómodo de arranque.
                if (pendingMask != 0 && cfg.intensity <= 0f) {
                    cfg = cfg.copy(intensity = 0.45f)
                    intensitySeek.progress = 45
                    intensityLabel.text = pct(cfg.intensity)
                }
                updateSubVisibility()
                pushLive()
            }
            row.setOnClickListener { cb.isChecked = !cb.isChecked }
            needBox.addView(row)
        }
        updateSubVisibility()

        intensitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(intensity = progress / 100f)
                intensityLabel.text = pct(cfg.intensity)
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        lowSharpSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(lowSharp = progress / 100f)
                lowLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        lowEdgeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(lowEdge = progress / 100f)
                lowLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        photoWarmSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(photoWarm = progress / 100f)
                photoLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        photoDesatSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(photoDesat = progress / 100f)
                photoLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        hcLiftSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(hcLift = progress / 100f)
                hcLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        hcSoftSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(hcSoft = progress / 100f)
                hcLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        strainRelaxSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(strainRelax = progress / 100f)
                strainLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        blueLightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(blueLight = progress / 100f)
                blueLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(48, 24, 48, 8)
            addView(
                TextView(activity).apply {
                    text = "La ayuda de visión está DESACTIVADA por defecto: no modifica " +
                        "la imagen. Actívala marcando las necesidades de visión que tengas " +
                        "(puedes marcar varias). KarinFLiX configura automáticamente las " +
                        "mejoras: colores, claridad, contraste, antibrillo, luz azul y vista " +
                        "cansada. Todo en un solo pase en GPU."
                    textSize = 13f
                    setPadding(0, 0, 0, 12)
                },
            )
            addView(needBox)
            addView(daltonSub)
            addView(lowVisionGroup)
            addView(hcGroup)
            addView(strainGroup)
            addView(photoGroup)
            addView(blueGroup)
            addView(intensityLabel)
            addView(intensitySeek)
            addView(
                TextView(activity).apply {
                    text = "Se aplica al confirmar; los cambios previsualizan en vivo. " +
                        "Si dejas todo sin marcar, la ayuda queda desactivada."
                    textSize = 12f
                    setPadding(0, 16, 0, 8)
                },
            )
        }

        AlertDialog.Builder(activity)
            .setTitle("Ayuda para Problemas de Visión")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Aplicar") { _, _ ->
                val finalCfg = cfg.copy(mask = pendingMask, daltonType = daltonType)
                saveInto(prefs, finalCfg)
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}