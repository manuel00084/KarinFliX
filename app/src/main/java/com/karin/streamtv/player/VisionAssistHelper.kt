package com.karin.streamtv.player

import android.app.Activity
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.karin.streamtv.R
import androidx.appcompat.app.AlertDialog
import androidx.media3.exoplayer.ExoPlayer
import com.karin.streamtv.player.dsp.AudioEnhanceConfig
import com.karin.streamtv.player.dsp.audiophile.AudiophileConfig

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
        // Valores de audición al abrir: si el usuario cancela tras mover los
        // sliders en vivo, se restauran (el vivo escribe al DSP en directo).
        val initAudSpeech = cfg.audSpeech
        val initAudLoss = cfg.audLoss
        // Snapshot de TODAS las claves de visión para revertir en Cancelar
        // (el diálogo ahora aplica en vivo: marcar ya monta el efecto).
        val visionSnapshot: Map<String, Any?> =
            prefs.all.filterKeys { it.startsWith("vision_") }
        // Render propio MotionX2 60fps: la cadena GL no existe y la visión
        // queda en pausa (misma condición que ExoPlayerActivity).
        val ownRenderNow: Boolean = try {
            prefs.getBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false) &&
                MotionX2Mode.resolveStored(
                    prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 0),
                ).isRealFps()
        } catch (_: Exception) {
            false
        }
        val initialMounted = cfg.isActive && cfg.hasVision && !ownRenderNow
        var mountedVision = initialMounted
        // Demo split (Comparar) al abrir: también se revierte en Cancelar.
        val initDemo = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, false)
        // true cuando el usuario ya confirmó o usó un botón de arreglo (que
        // gestiona su propio estado): evita que onCancel revierta de más.
        var confirmed = false
        fun restoreSnapshot() {
            val ed = prefs.edit()
            for (k in prefs.all.keys.filter { it.startsWith("vision_") }) ed.remove(k)
            for ((k, v) in visionSnapshot) {
                when (v) {
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, v)
                    is Float -> ed.putFloat(k, v)
                    is String -> ed.putString(k, v)
                }
            }
            ed.apply()
            AudioEnhanceConfig.setAudSpeech(initAudSpeech)
            AudioEnhanceConfig.setAudLoss(initAudLoss)
        }

        fun pct(v: Float) = "Intensidad: ${(v * 100).toInt()}%"

        var pendingMask = cfg.mask

        // ---- Daltonismo: subtipo ----
        var daltonType = cfg.daltonType
        // ---- Vista cansada: modo (los radios se crean más abajo) ----
        var strainMode = cfg.strainMode
        val daltonRadios = mutableListOf<RadioButton>()
        fun syncDalton() {
            daltonRadios.forEachIndexed { i, r -> r.isChecked = i == daltonType }
        }
        val daltonNames = arrayOf("Protan (rojo)", "Deutan (verde)", "Tritan (azul)", "Monocromático")
        val daltonRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        // pushLive/saveVision se definen más abajo (tras updateSubVisibility);
        // estas lambdas se rellenan entonces para que el subtipo previsualice
        // y guarde en vivo.
        var pushLiveFn: () -> Unit = {}
        var saveVisionFn: () -> Unit = {}
        daltonNames.forEachIndexed { i, name ->
            val rb = RadioButton(activity).apply { text = name }
            daltonRadios.add(rb)
            rb.setOnClickListener {
                daltonType = i
                cfg = cfg.copy(daltonType = i)
                syncDalton()
                saveVisionFn()
                pushLiveFn()
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
            "Facilita distinguir rojo/verde/azul en tonos muy parecidos. Elige el tipo de corrección abajo. " +
                "🔎 Qué buscar: con el modo Comparar, los colores de la derecha cambian claramente (p. ej. verdes y rojos se separan).",
            "Realza bordes y detalle fino, mejora el contraste local y levanta sombras con suavidad, sin quemar altas luces. " +
                "🔎 Qué buscar: letras y bordes más nítidos a la derecha; las zonas oscuras se aclaran un poco sin volverse grises.",
            "Separa sombras, medios tonos y luces con una curva suave: sin negros rotos ni blancos quemados; conserva el detalle. " +
                "🔎 Qué buscar: la imagen gana 'pegada' (más diferencia entre claro y oscuro) sin perder detalle en sombras ni quemar el cielo.",
            "Reduce el deslumbramiento: atenúa progresivamente las zonas más brillantes sin apagar la imagen. El tinte cálido es opcional (modo noche). Filtro de confort, no médico. " +
                "🔎 Qué buscar: mira explosiones, nieve o focos: a la derecha brillan menos y molestan menos; el resto casi igual.",
            "Reduce la componente azul de la imagen y desplaza los colores hacia un tono más cálido, con intensidad y temperatura configurables. Solo modifica el color mostrado. " +
                "🔎 Qué buscar: sube Temperatura hacia 'Muy cálido' y compara: los blancos de la derecha se vuelven amarillentos. Con valores bajos el cambio es sutil a propósito.",
            "Hace la imagen menos agresiva: suaviza extremos de contraste, controla altas luces, modera la saturación y tibia muy ligeramente. No desenfoca (conserva la nitidez). Modificación visual, no es tratamiento médico. " +
                "🔎 Qué buscar: es el más sutil; compara cielos y piel: a la derecha los blancos bajan un poco y los colores se ven menos 'chillones'.",
            "Realza la voz con el DSP sobre música y efectos: presencia, compresión, despeje de FX y nivelación. Asistencia; no sustituye un aparato auditivo. " +
                "🔎 Qué buscar: se escucha al momento (sin comparar): la voz se adelanta a la música.",
            "Recupera los agudos que se desvanecen con la edad: presencia, armónicos y volumen con protección true-peak. Asistencia; no es tratamiento médico. " +
                "🔎 Qué buscar: se escucha al momento: más brillo en voces y platillos, un poco más de volumen.",
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
        pushLiveFn = { pushLive() }
        // Guarda el estado actual del diálogo en prefs (para que el rebuild
        // lea lo mismo que previsualiza).
        fun saveVision() {
            saveInto(
                prefs,
                cfg.copy(
                    mask = pendingMask,
                    daltonType = daltonType,
                    strainMode = strainMode,
                ),
            )
        }
        fun wantMounted(): Boolean =
            cfg.isActive && cfg.hasVision && !ownRenderNow
        // Sincroniza el montaje del efecto con lo marcado: si la presencia
        // de visión cambió, guarda y reconstruye la cadena (así la primera
        // activación ya se VE sin esperar a Aplicar); si no, previsualiza.
        // refreshWarnings se rellena al crear el aviso (más abajo).
        var refreshWarningsFn: () -> Unit = {}
        fun syncMount() {
            refreshWarningsFn()
            if (wantMounted() != mountedVision) {
                saveVision()
                mountedVision = wantMounted()
                val p = player
                if (p != null) onEffectsChanged(p) else pushLive()
            } else {
                pushLive()
            }
        }
        saveVisionFn = { saveVision() }

        // ---- Vista cansada: modo Suave/Confort (se crea tras pushLive) ----
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
                saveVision()
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
                // está en cero, se sugiere un valor de arranque BIEN VISIBLE
                // (70%): con 45% varias ayudas quedaban imperceptibles.
                if (pendingMask != 0 && cfg.intensity <= 0f) {
                    cfg = cfg.copy(intensity = 0.70f)
                    intensitySeek.progress = 70
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
                // Audición en vivo: el DSP lo lee del hilo de audio en el
                // siguiente buffer (~ms). Al desmarcar se apaga en vivo.
                if (needFlag[i] == VisionAssistSettings.FLAG_SPEECH) {
                    AudioEnhanceConfig.setAudSpeech(if (isChecked) cfg.audSpeech else 0f)
                }
                if (needFlag[i] == VisionAssistSettings.FLAG_HEARING_LOSS) {
                    AudioEnhanceConfig.setAudLoss(if (isChecked) cfg.audLoss else 0f)
                }
                updateSubVisibility()
                // Monta/desmonta el efecto al momento: la primera activación
                // ya se ve sin esperar a Aplicar (con OSD de confirmación).
                syncMount()
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
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // La maestra en 0 desmonta el efecto (isActive=false).
                syncMount()
            }
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
                // En vivo al DSP (se guarda definitivo al confirmar).
                AudioEnhanceConfig.setAudSpeech(cfg.audSpeech)
                refreshWarningsFn()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        audLossSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cfg = cfg.copy(audLoss = progress / 100f)
                audLossLabelFn()
                // En vivo al DSP (se guarda definitivo al confirmar).
                AudioEnhanceConfig.setAudLoss(cfg.audLoss)
                refreshWarningsFn()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // ---- Avisos con arreglo en un toque ----
        // ¿La audición pedida suena de verdad? (mismo criterio que el OSD de
        // ExoPlayerActivity: motor CURRENT + DSP habilitado + preset no OFF).
        fun hearingProblem(): String? {
            val wantsHearing =
                (pendingMask and VisionAssistSettings.FLAG_SPEECH != 0 && cfg.audSpeech > 0f) ||
                    (pendingMask and VisionAssistSettings.FLAG_HEARING_LOSS != 0 && cfg.audLoss > 0f)
            if (!wantsHearing) return null
            val eng = try { AudiophileConfig.engine() } catch (_: Exception) {
                AudiophileConfig.Engine.CURRENT
            }
            if (eng == AudiophileConfig.Engine.OFF) return "motor de sonido en OFF"
            if (eng == AudiophileConfig.Engine.AUDIOPHILE) {
                return "motor Audiophile experimental (no aplica esta asistencia)"
            }
            if (!AudioEnhanceConfig.isEnabled() ||
                AudioEnhanceConfig.preset() == AudioEnhanceConfig.Preset.OFF
            ) {
                return "DSP apagado (perfil Apagado)"
            }
            return null
        }
        val warnBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, 12)
        }
        val motionWarn = TextView(activity).apply { textSize = 13f }
        val dspWarn = TextView(activity).apply { textSize = 13f }
        warnBox.addView(motionWarn)
        if (ownRenderNow) {
            motionWarn.text = "⚠ Estás en MotionX2 60fps (render propio): la ayuda " +
                "VISUAL está en pausa aquí. La audición sí funciona."
            motionWarn.visibility = View.VISIBLE
        } else {
            motionWarn.visibility = View.GONE
        }
        warnBox.addView(dspWarn)
        val btnFixDsp = Button(activity).apply {
            text = "Activar DSP actual (para la audición)"
            visibility = View.GONE
            setOnClickListener {
                AudiophileConfig.setEngine(AudiophileConfig.Engine.CURRENT)
                AudioEnhanceConfig.setEnabled(true)
                if (AudioEnhanceConfig.preset() == AudioEnhanceConfig.Preset.OFF) {
                    AudioEnhanceConfig.applyPreset(AudioEnhanceConfig.Preset.ANIME)
                }
                refreshWarningsFn()
                Toast.makeText(activity, "DSP actual activado: la audición ya suena", Toast.LENGTH_SHORT).show()
            }
        }
        warnBox.addView(btnFixDsp)
        // Salir del render propio requiere recrear la actividad: se guarda,
        // se cierra el diálogo y se reconstruye (el OSD lo confirma).
        var dismissFn: () -> Unit = {}
        if (ownRenderNow) {
            val btnFixMotion = Button(activity).apply {
                text = "Salir de MotionX2 60fps (activa la visión)"
                setOnClickListener {
                    confirmed = true
                    saveVision()
                    prefs.edit().putBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false).apply()
                    Toast.makeText(
                        activity,
                        "MotionX2 60fps desactivado: la visión ya funciona",
                        Toast.LENGTH_SHORT,
                    ).show()
                    dismissFn()
                    player?.let { onEffectsChanged(it) }
                }
            }
            warnBox.addView(btnFixMotion)
        }
        // Se declara aquí (tras warnBox) y se usa desde syncMount().
        refreshWarningsFn = {
            val problem = hearingProblem()
            if (problem != null) {
                dspWarn.text = "⚠ Audición sin efecto: $problem."
                dspWarn.visibility = View.VISIBLE
                btnFixDsp.visibility = View.VISIBLE
            } else {
                dspWarn.visibility = View.GONE
                btnFixDsp.visibility = View.GONE
            }
            warnBox.visibility =
                if (motionWarn.visibility == View.VISIBLE || dspWarn.visibility == View.VISIBLE) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

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
            addView(warnBox)
            // Comparar: mitad izquierda original / mitad derecha con ayuda
            // (reusa el demo split de la cadena: cada efecto conserva la
            // izquierda intacta). Es la forma más rápida de VER la diferencia.
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, 12)
                    addView(CheckBox(activity).apply {
                        text = "Comparar: izquierda original / derecha con ayuda"
                        isChecked = initDemo
                        setOnCheckedChangeListener { _, isChecked ->
                            prefs.edit().putBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, isChecked).apply()
                            player?.let { onEffectsChanged(it) }
                        }
                    })
                    addView(TextView(activity).apply {
                        text = "Divide la pantalla: si la ayuda funciona, las dos mitades se ven distintas."
                        textSize = 12f
                        setPadding(52, 0, 0, 0)
                    })
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
                    text = "Los cambios se ven al momento: marcar ya monta el efecto " +
                        "y los sliders previsualizan en vivo. Aplicar confirma; " +
                        "Cancelar revierte todo lo movido."
                    textSize = 12f
                    setPadding(0, 16, 0, 8)
                },
            )
        }

        // Revierte lo movido en vivo y, si el montaje cambió, reconstruye.
        fun cancelAndRestore() {
            if (confirmed) return
            confirmed = true
            restoreSnapshot()
            cfg = fromPrefs(prefs)
            pendingMask = cfg.mask
            daltonType = cfg.daltonType
            strainMode = cfg.strainMode
            var needsRebuild = mountedVision != initialMounted
            if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, false) != initDemo) {
                prefs.edit().putBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, initDemo).apply()
                needsRebuild = true
            }
            if (needsRebuild) {
                mountedVision = initialMounted
                player?.let { onEffectsChanged(it) }
            }
        }

        refreshWarningsFn()
        val dlg = AlertDialog.Builder(activity)
            .setTitle("Asistencia: Visión y Audición")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Aplicar") { _, _ ->
                confirmed = true
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
                // Audición → DSP de audio (effectiveParams() la superpone al
                // preset activo y el hilo de audio reconfigura al detectar el
                // cambio). El OSD confirma la cadena real al reconstruir.
                AudioEnhanceConfig.setAudSpeech(finalCfg.audSpeech)
                AudioEnhanceConfig.setAudLoss(finalCfg.audLoss)
                mountedVision = finalCfg.isActive && finalCfg.hasVision && !ownRenderNow
                player?.let { onEffectsChanged(it) }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                cancelAndRestore()
            }
            .setOnCancelListener {
                // Atrás o toque fuera = cancelar.
                cancelAndRestore()
            }
            .create()
        dismissFn = { dlg.dismiss() }
        dlg.show()
    }
}