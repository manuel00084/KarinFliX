package com.karin.streamtv.player

import android.app.Activity
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.karin.streamtv.R
import androidx.appcompat.app.AlertDialog
import androidx.media3.exoplayer.ExoPlayer
import com.karin.streamtv.player.dsp.AudioEnhanceConfig

/**
 * KARINFLIX VISION ASSIST: prefs + diálogo del botón de anteojos.
 *
 * Selección MULTIPLE de necesidades de visión (bitmask, GPU-light):
 *   FLAG_DALTONISM (1)  -> corrección de colores (subtipo Protan/Deutan/Tritan/Mono)
 *   FLAG_LOW_VISION (2) -> legibilidad: realce adaptativo de bordes/detalle,
 *                           contraste local acotado y lift de sombras (GPU)
 *   FLAG_HIGH_CONTRAST (4) -> separación sombras/medios/luces con curva-S
 *                           suave, sin negros rotos ni blancos quemados (GPU)
 *   FLAG_PHOTOPHOBIA (8) -> confort visual: anti-glare adaptativo en altas
 *                           luminancias (filtro de confort, no médico)
 *   FLAG_BLUE_LIGHT (16) -> tono cálido: reduce el azul de la imagen y
 *                           desplaza el balance hacia lo cálido (intensidad y
 *                           temperatura propias; modificación visual)
 *   FLAG_EYE_STRAIN (32) -> confort visual: suaviza extremos de contraste y
 *                           altas luces, modera la saturación y tibía muy
 *                           ligeramente (modos Suave/Confort; sin blur;
 *                           modificación visual, no médica)
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
    val photoWarm: Float = 0.60f,          // Fotofobia: tinte cálido (modo noche, opcional)
    val photoDesat: Float = 0.40f,         // Fotofobia: atenuar colores brillantes
    val photoIntensity: Float = 1.0f,      // Fotofobia: intensidad propia (0=OFF)
    val hcLift: Float = 0.40f,             // Alto Contraste: abrir sombras
    val hcSoft: Float = 0.60f,             // Alto Contraste: protección de luces
    val hcIntensity: Float = 1.0f,         // Alto Contraste: intensidad propia (0=OFF)
    val strainRelax: Float = 0.50f,        // Vista cansada: relajación (fuerza)
    val strainInt: Float = 1.0f,           // Vista cansada: intensidad propia (0=OFF)
    val strainMode: Int = 1,               // Vista cansada: 0=Suave, 1=Confort
    val blueLight: Float = 0.60f,          // Luz azul: intensidad propia (0=OFF)
    val blueTemp: Float = 0.50f,           // Luz azul: temperatura 0..1 (0=Normal, 1=muy cálido)
    val lowShadow: Float = 0.40f,          // Baja Visión: elevación de sombras
    val lowIntensity: Float = 1.0f,        // Baja Visión: intensidad propia (0=OFF)
    val audSpeech: Float = 0f,             // Audición: claridad de diálogo 0..1 (0=OFF)
    val audLoss: Float = 0f,               // Audición: realce de agudos (presbicia) 0..1 (0=OFF)
) {
    companion object {
        const val FLAG_DALTONISM = 1
        const val FLAG_LOW_VISION = 2
        const val FLAG_HIGH_CONTRAST = 4
        const val FLAG_PHOTOPHOBIA = 8
        const val FLAG_BLUE_LIGHT = 16
        const val FLAG_EYE_STRAIN = 32
        // Audición (van en el MISMO mask para la UX del diálogo; el DSP los
        // lee de AudioEnhanceConfig, no del shader).
        const val FLAG_SPEECH = 64
        const val FLAG_HEARING_LOSS = 128

        const val DALTON_PROTAN = 0
        const val DALTON_DEUTAN = 1
        const val DALTON_TRITAN = 2
        const val DALTON_MONO = 3

        const val STRAIN_MODE_SOFT = 0
        const val STRAIN_MODE_COMFY = 1

        val ALL_FLAGS = intArrayOf(
            FLAG_DALTONISM, FLAG_LOW_VISION, FLAG_HIGH_CONTRAST, FLAG_PHOTOPHOBIA,
            FLAG_BLUE_LIGHT, FLAG_EYE_STRAIN, FLAG_SPEECH, FLAG_HEARING_LOSS,
        )

        /** Flags que dibuja el shader de visión (la audición no es GL). */
        val VISION_FLAGS = FLAG_DALTONISM or FLAG_LOW_VISION or FLAG_HIGH_CONTRAST or
            FLAG_PHOTOPHOBIA or FLAG_BLUE_LIGHT or FLAG_EYE_STRAIN
    }

    val isActive: Boolean
        get() = mask != 0 && intensity > 0f

    val hasDaltonism: Boolean get() = mask and FLAG_DALTONISM != 0
    val hasLowVision: Boolean get() = mask and FLAG_LOW_VISION != 0
    val hasHighContrast: Boolean get() = mask and FLAG_HIGH_CONTRAST != 0
    val hasPhotophobia: Boolean get() = mask and FLAG_PHOTOPHOBIA != 0
    val hasBlueLight: Boolean get() = mask and FLAG_BLUE_LIGHT != 0
    val hasEyeStrain: Boolean get() = mask and FLAG_EYE_STRAIN != 0
    val hasAudSpeech: Boolean get() = audSpeech > 0f
    val hasAudLoss: Boolean get() = audLoss > 0f

    /** ¿Hay alguna necesidad VISUAL? (la cadena GL solo se monta si esto). */
    val hasVision: Boolean get() = mask and VISION_FLAGS != 0

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
    const val KEY_LOW_SHADOW = "vision_low_shadow"
    const val KEY_LOW_INT = "vision_low_int"
    const val KEY_PHOTO_WARM = "vision_photo_warm"
    const val KEY_PHOTO_DESAT = "vision_photo_desat"
    const val KEY_PHOTO_INT = "vision_photo_int"
    const val KEY_HC_LIFT = "vision_hc_lift"
    const val KEY_HC_SOFT = "vision_hc_soft"
    const val KEY_HC_INT = "vision_hc_int"
    const val KEY_STRAIN_RELAX = "vision_strain_relax"
    const val KEY_STRAIN_INT = "vision_strain_int"
    const val KEY_STRAIN_MODE = "vision_strain_mode"
    const val KEY_BLUE_LIGHT = "vision_blue_light"
    const val KEY_BLUE_TEMP = "vision_blue_temp"
    const val KEY_AUD_SPEECH = "vision_aud_speech"
    const val KEY_AUD_LOSS = "vision_aud_loss"

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
                VisionAssistSettings.FLAG_EYE_STRAIN or
                VisionAssistSettings.FLAG_SPEECH or
                VisionAssistSettings.FLAG_HEARING_LOSS)
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
            lowShadow = (prefs.getInt(KEY_LOW_SHADOW, 40).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            lowIntensity = (prefs.getInt(KEY_LOW_INT, 100).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            photoWarm = (prefs.getInt(KEY_PHOTO_WARM, 60).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            photoDesat = (prefs.getInt(KEY_PHOTO_DESAT, 40).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            photoIntensity = (prefs.getInt(KEY_PHOTO_INT, 100).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            hcLift = (prefs.getInt(KEY_HC_LIFT, 40).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            hcSoft = (prefs.getInt(KEY_HC_SOFT, 60).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            hcIntensity = (prefs.getInt(KEY_HC_INT, 100).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            strainRelax = (prefs.getInt(KEY_STRAIN_RELAX, 50).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            strainInt = (prefs.getInt(KEY_STRAIN_INT, 100).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            strainMode = prefs.getInt(KEY_STRAIN_MODE, VisionAssistSettings.STRAIN_MODE_COMFY).coerceIn(0, 1),
            blueLight = (prefs.getInt(KEY_BLUE_LIGHT, 60).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            blueTemp = (prefs.getInt(KEY_BLUE_TEMP, 50).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            audSpeech = (prefs.getInt(KEY_AUD_SPEECH, 0).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
            audLoss = (prefs.getInt(KEY_AUD_LOSS, 0).coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
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
        if (s.hasAudSpeech) add("Diálogos")
        if (s.hasAudLoss) add("Agudos")
    }.joinToString(" + ").ifEmpty { "Apagado" }

    fun saveInto(prefs: SharedPreferences, s: VisionAssistSettings) {
        prefs.edit()
            .putInt(KEY_MASK, s.mask)
            .putInt(KEY_INTENSITY, (s.intensity.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_DALTON, s.daltonType.coerceIn(0, 3))
            .putInt(KEY_LOW_SHARP, (s.lowSharp.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_LOW_EDGE, (s.lowEdge.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_LOW_SHADOW, (s.lowShadow.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_LOW_INT, (s.lowIntensity.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_PHOTO_WARM, (s.photoWarm.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_PHOTO_DESAT, (s.photoDesat.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_PHOTO_INT, (s.photoIntensity.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_HC_LIFT, (s.hcLift.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_HC_SOFT, (s.hcSoft.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_HC_INT, (s.hcIntensity.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_STRAIN_RELAX, (s.strainRelax.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_STRAIN_INT, (s.strainInt.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_STRAIN_MODE, s.strainMode.coerceIn(0, 1))
            .putInt(KEY_BLUE_LIGHT, (s.blueLight.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_BLUE_TEMP, (s.blueTemp.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_AUD_SPEECH, (s.audSpeech.coerceIn(0f, 1f) * 100).toInt())
            .putInt(KEY_AUD_LOSS, (s.audLoss.coerceIn(0f, 1f) * 100).toInt())
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
        val lowShadowLabel = TextView(activity).apply { text = "" }
        val lowShadowSeek = SeekBar(activity).apply { max = 100 }
        val lowIntLabel = TextView(activity).apply { text = "" }
        val lowIntSeek = SeekBar(activity).apply { max = 100 }
        fun lowLabel() {
            lowIntLabel.text = if (cfg.lowIntensity <= 0f) {
                "Intensidad: OFF (Baja Visión desactivada)"
            } else {
                "Intensidad: ${(cfg.lowIntensity * 100).toInt()}%"
            }
            lowSharpLabel.text = "Definición (detalle fino): ${(cfg.lowSharp * 100).toInt()}%"
            lowEdgeLabel.text = "Bordes: ${(cfg.lowEdge * 100).toInt()}%"
            lowShadowLabel.text = "Sombras: ${(cfg.lowShadow * 100).toInt()}%"
        }
        val lowVisionGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(lowIntLabel)
            addView(lowIntSeek)
            addView(lowSharpLabel)
            addView(lowSharpSeek)
            addView(lowEdgeLabel)
            addView(lowEdgeSeek)
            addView(lowShadowLabel)
            addView(lowShadowSeek)
        }
        lowIntSeek.progress = (cfg.lowIntensity * 100).toInt()
        lowSharpSeek.progress = (cfg.lowSharp * 100).toInt()
        lowEdgeSeek.progress = (cfg.lowEdge * 100).toInt()
        lowShadowSeek.progress = (cfg.lowShadow * 100).toInt()
        lowLabel()

        val photoIntLabel = TextView(activity).apply { text = "" }
        val photoIntSeek = SeekBar(activity).apply { max = 100 }
        val photoWarmLabel = TextView(activity).apply { text = "" }
        val photoWarmSeek = SeekBar(activity).apply { max = 100 }
        val photoDesatLabel = TextView(activity).apply { text = "" }
        val photoDesatSeek = SeekBar(activity).apply { max = 100 }
        fun photoLabel() {
            photoIntLabel.text = if (cfg.photoIntensity <= 0f) {
                "Intensidad: OFF (Fotofobia desactivada)"
            } else {
                "Intensidad: ${(cfg.photoIntensity * 100).toInt()}%"
            }
            photoWarmLabel.text = "Tinte cálido (modo noche): ${(cfg.photoWarm * 100).toInt()}%"
            photoDesatLabel.text = "Atenuar colores brillantes: ${(cfg.photoDesat * 100).toInt()}%"
        }
        val photoGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(photoIntLabel)
            addView(photoIntSeek)
            addView(photoWarmLabel)
            addView(photoWarmSeek)
            addView(photoDesatLabel)
            addView(photoDesatSeek)
        }
        photoIntSeek.progress = (cfg.photoIntensity * 100).toInt()
        photoWarmSeek.progress = (cfg.photoWarm * 100).toInt()
        photoDesatSeek.progress = (cfg.photoDesat * 100).toInt()
        photoLabel()

        val hcIntLabel = TextView(activity).apply { text = "" }
        val hcIntSeek = SeekBar(activity).apply { max = 100 }
        val hcLiftLabel = TextView(activity).apply { text = "" }
        val hcLiftSeek = SeekBar(activity).apply { max = 100 }
        val hcSoftLabel = TextView(activity).apply { text = "" }
        val hcSoftSeek = SeekBar(activity).apply { max = 100 }
        fun hcLabel() {
            hcIntLabel.text = if (cfg.hcIntensity <= 0f) {
                "Intensidad: OFF (Alto contraste desactivado)"
            } else {
                "Intensidad: ${(cfg.hcIntensity * 100).toInt()}%"
            }
            hcLiftLabel.text = "Abrir sombras: ${(cfg.hcLift * 100).toInt()}%"
            hcSoftLabel.text = "Protección de luces: ${(cfg.hcSoft * 100).toInt()}%"
        }
        val hcGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(hcIntLabel)
            addView(hcIntSeek)
            addView(hcLiftLabel)
            addView(hcLiftSeek)
            addView(hcSoftLabel)
            addView(hcSoftSeek)
        }
        hcIntSeek.progress = (cfg.hcIntensity * 100).toInt()
        hcLiftSeek.progress = (cfg.hcLift * 100).toInt()
        hcSoftSeek.progress = (cfg.hcSoft * 100).toInt()
        hcLabel()

        val strainIntLabel = TextView(activity).apply { text = "" }
        val strainIntSeek = SeekBar(activity).apply { max = 100 }
        val strainRelaxLabel = TextView(activity).apply { text = "" }
        val strainRelaxSeek = SeekBar(activity).apply { max = 100 }
        val strainModeRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun strainLabel() {
            strainIntLabel.text = if (cfg.strainInt <= 0f) {
                "Intensidad: OFF (Vista cansada desactivada)"
            } else {
                "Intensidad: ${(cfg.strainInt * 100).toInt()}%"
            }
            strainRelaxLabel.text = "Relajación: ${(cfg.strainRelax * 100).toInt()}%"
        }
        val strainGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(strainIntLabel)
            addView(strainIntSeek)
            addView(TextView(activity).apply {
                text = "Modo:"
                textSize = 13f
                setPadding(52, 4, 0, 4)
            })
            addView(strainModeRow)
            addView(strainRelaxLabel)
            addView(strainRelaxSeek)
        }
        strainIntSeek.progress = (cfg.strainInt * 100).toInt()
        strainRelaxSeek.progress = (cfg.strainRelax * 100).toInt()
        strainLabel()

        val blueLightLabel = TextView(activity).apply { text = "" }
        val blueLightSeek = SeekBar(activity).apply { max = 100 }
        val blueTempLabel = TextView(activity).apply { text = "" }
        val blueTempSeek = SeekBar(activity).apply { max = 100 }
        fun blueLabel() {
            blueLightLabel.text = if (cfg.blueLight <= 0f) {
                "Intensidad: OFF (Luz azul desactivada)"
            } else {
                "Intensidad: ${(cfg.blueLight * 100).toInt()}%"
            }
            val p = (cfg.blueTemp * 100).toInt()
            val word = when {
                cfg.blueTemp <= 0.001f -> "Normal (sin cambio)"
                cfg.blueTemp <= 0.34f -> "Ligeramente cálido"
                cfg.blueTemp <= 0.67f -> "Cálido"
                else -> "Muy cálido"
            }
            blueTempLabel.text = "Temperatura: $word ($p%)"
        }
        val blueGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(blueLightLabel)
            addView(blueLightSeek)
            addView(blueTempLabel)
            addView(blueTempSeek)
        }
        blueLightSeek.progress = (cfg.blueLight * 100).toInt()
        blueTempSeek.progress = (cfg.blueTemp * 100).toInt()
        blueLabel()

        // ---- Audición: sub-grupos (va al DSP, no al shader) ----
        val audSpeechLabel = TextView(activity).apply { text = "" }
        val audSpeechSeek = SeekBar(activity).apply { max = 100 }
        fun audSpeechLabelFn() {
            audSpeechLabel.text = if (cfg.audSpeech <= 0f) {
                "Claridad de voz: OFF"
            } else {
                "Claridad de voz: ${(cfg.audSpeech * 100).toInt()}%"
            }
        }
        val audSpeechGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "Sube la voz sobre música y efectos: presencia, compresión " +
                    "del rango, despeje de explosiones y nivelación R128."
                textSize = 12f
                setTextColor(0xFF90A4AE.toInt())
                setPadding(52, 0, 0, 4)
            })
            addView(audSpeechLabel)
            addView(audSpeechSeek)
        }
        audSpeechSeek.progress = (cfg.audSpeech * 100).toInt()
        audSpeechLabelFn()

        val audLossLabel = TextView(activity).apply { text = "" }
        val audLossSeek = SeekBar(activity).apply { max = 100 }
        fun audLossLabelFn() {
            audLossLabel.text = if (cfg.audLoss <= 0f) {
                "Realce de agudos: OFF"
            } else {
                "Realce de agudos: ${(cfg.audLoss * 100).toInt()}%"
            }
        }
        val audLossGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "Recupera los agudos que se pierden con la edad (presbicia): " +
                    "realce de presencia/armónicos y volumen extra con protección " +
                    "true-peak. Asistencia; no es tratamiento médico."
                textSize = 12f
                setTextColor(0xFF90A4AE.toInt())
                setPadding(52, 0, 0, 4)
            })
            addView(audLossLabel)
            addView(audLossSeek)
        }
        audLossSeek.progress = (cfg.audLoss * 100).toInt()
        audLossLabelFn()

        // ---- Necesidades (checkbox, selección múltiple) ----
        val needNames = arrayOf(
            "Colores (daltonismo)",
            "Claridad (baja visión)",
            "Alto contraste",
            "Antibrillo y confort ocular",
            "Luz azul (tono cálido)",
            "Vista cansada (confort)",
            "Dificultad para oír diálogos",
            "Pérdida de audición (agudos)",
        )
        val needDescs = arrayOf(
            "Facilita distinguir rojo/verde/azul en tonos muy parecidos. Elige el tipo de corrección abajo.",
            "Realza bordes y detalle fino, mejora el contraste local y levanta sombras con suavidad, sin quemar altas luces.",
            "Separa sombras, medios tonos y luces con una curva suave: sin negros rotos ni blancos quemados; conserva el detalle.",
            "Reduce el deslumbramiento: atenúa progresivamente las zonas más brillantes sin apagar la imagen. El tinte cálido es opcional (modo noche). Filtro de confort, no médico.",
            "Reduce la componente azul de la imagen y desplaza los colores hacia un tono más cálido, con intensidad y temperatura configurables. Solo modifica el color mostrado.",
            "Hace la imagen menos agresiva: suaviza extremos de contraste, controla altas luces, modera la saturación y tibia muy ligeramente. No desenfoca (conserva la nitidez). Modificación visual, no es tratamiento médico.",
            "Realza la voz con el DSP sobre música y efectos: presencia, compresión, despeje de FX y nivelación. Asistencia; no sustituye un aparato auditivo.",
            "Recupera los agudos que se desvanecen con la edad: presencia, armónicos y volumen con protección true-peak. Asistencia; no es tratamiento médico.",
        )
        val needFlag = intArrayOf(
            VisionAssistSettings.FLAG_DALTONISM,
            VisionAssistSettings.FLAG_LOW_VISION,
            VisionAssistSettings.FLAG_HIGH_CONTRAST,
            VisionAssistSettings.FLAG_PHOTOPHOBIA,
            VisionAssistSettings.FLAG_BLUE_LIGHT,
            VisionAssistSettings.FLAG_EYE_STRAIN,
            VisionAssistSettings.FLAG_SPEECH,
            VisionAssistSettings.FLAG_HEARING_LOSS,
        )
        // Icono por necesidad: anteojos = visión, oreja = audición.
        val needIcon = intArrayOf(
            R.drawable.ic_glasses, R.drawable.ic_glasses, R.drawable.ic_glasses,
            R.drawable.ic_glasses, R.drawable.ic_glasses, R.drawable.ic_glasses,
            R.drawable.ic_ear, R.drawable.ic_ear,
        )
        fun dp(v: Int): Int =
            (v * activity.resources.displayMetrics.density + 0.5f).toInt()

        fun updateSubVisibility() {
            daltonSub.visibility = if (pendingMask and VisionAssistSettings.FLAG_DALTONISM != 0) View.VISIBLE else View.GONE
            lowVisionGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_LOW_VISION != 0) View.VISIBLE else View.GONE
            photoGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_PHOTOPHOBIA != 0) View.VISIBLE else View.GONE
            hcGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_HIGH_CONTRAST != 0) View.VISIBLE else View.GONE
            strainGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_EYE_STRAIN != 0) View.VISIBLE else View.GONE
            blueGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_BLUE_LIGHT != 0) View.VISIBLE else View.GONE
            audSpeechGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_SPEECH != 0) View.VISIBLE else View.GONE
            audLossGroup.visibility = if (pendingMask and VisionAssistSettings.FLAG_HEARING_LOSS != 0) View.VISIBLE else View.GONE
        }
        fun pushLive() {
            onLive(cfg)
        }

        // ---- Vista cansada: modo Suave/Confort (se crea tras pushLive) ----
        var strainMode = cfg.strainMode
        val strainModeRadios = mutableListOf<RadioButton>()
        fun syncStrainMode() {
            strainModeRadios.forEachIndexed { idx, rb -> rb.isChecked = idx == strainMode }
        }
        arrayOf("Suave", "Confort").forEachIndexed { idx, name ->
            val rb = RadioButton(activity).apply { text = name }
            strainModeRadios.add(rb)
            rb.setOnClickListener {
                strainMode = idx
                cfg = cfg.copy(strainMode = idx)
                syncStrainMode()
                strainLabel()
                pushLive()
            }
            strainModeRow.addView(rb)
        }
        syncStrainMode()

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
            val head = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(activity).apply {
                    setImageResource(needIcon[i])
                    contentDescription = name
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                })
                addView(cb, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(6) })
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(head)
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
                // Audición: al marcar, sugiere una intensidad de arranque.
                if (needFlag[i] == VisionAssistSettings.FLAG_SPEECH && isChecked && cfg.audSpeech <= 0f) {
                    cfg = cfg.copy(audSpeech = 0.6f)
                    audSpeechSeek.progress = 60
                    audSpeechLabelFn()
                }
                if (needFlag[i] == VisionAssistSettings.FLAG_HEARING_LOSS && isChecked && cfg.audLoss <= 0f) {
                    cfg = cfg.copy(audLoss = 0.6f)
                    audLossSeek.progress = 60
                    audLossLabelFn()
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
        lowIntSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(lowIntensity = progress / 100f)
                lowLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        lowShadowSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(lowShadow = progress / 100f)
                lowLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        photoIntSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(photoIntensity = progress / 100f)
                photoLabel()
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
        hcIntSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(hcIntensity = progress / 100f)
                hcLabel()
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
        strainIntSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(strainInt = progress / 100f)
                strainLabel()
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
        blueTempSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(blueTemp = progress / 100f)
                blueLabel()
                pushLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        audSpeechSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(audSpeech = progress / 100f)
                audSpeechLabelFn()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        audLossSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(audLoss = progress / 100f)
                audLossLabelFn()
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
                    text = "La asistencia está DESACTIVADA por defecto: no modifica " +
                        "la imagen ni el sonido. Actívala marcando las necesidades que " +
                        "tengas (puedes marcar varias). Visión: colores, claridad, " +
                        "contraste, antibrillo, luz azul y vista cansada (un pase en " +
                        "GPU). Audición: claridad de diálogo y realce de agudos con el " +
                        "DSP. Asistencia práctica; no es tratamiento médico."
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
            addView(audSpeechGroup)
            addView(audLossGroup)
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
            .setTitle("Asistencia: Visión y Audición")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Aplicar") { _, _ ->
                // Si la necesidad de audición está desmarcada, su intensidad se
                // cera: el DSP solo se enciende con valor > 0.
                val finalCfg = cfg.copy(
                    mask = pendingMask,
                    daltonType = daltonType,
                    strainMode = strainMode,
                    audSpeech = if (pendingMask and VisionAssistSettings.FLAG_SPEECH != 0) cfg.audSpeech else 0f,
                    audLoss = if (pendingMask and VisionAssistSettings.FLAG_HEARING_LOSS != 0) cfg.audLoss else 0f,
                )
                saveInto(prefs, finalCfg)
                // Audición → DSP de audio (params() la superpone al preset activo
                // y el hilo de audio reconfigura solo al detectar el cambio).
                AudioEnhanceConfig.setAudSpeech(finalCfg.audSpeech)
                AudioEnhanceConfig.setAudLoss(finalCfg.audLoss)
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}