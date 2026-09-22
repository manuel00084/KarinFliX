package com.karin.streamtv.player

import android.content.SharedPreferences

/**
 * Tecnología 3D de KarinFLiX: convierte y emite video estereoscópico
 * en un solo pase GL (GLES2, 1-2 fetches, apto para cajas modestas).
 *
 * Modos:
 * - OFF: sin proceso (no-op, no ocupa pase GL).
 * - SBS_2D: entrada lado-a-lado (izq|der) -> 2D (ojo elegido, estirado).
 * - TAB_2D: entrada arriba-abajo (sup/inf) -> 2D (ojo elegido, estirado).
 * - ANAGLYPH: lentes bicolor con 3 variantes ([ANAG_RED_CYAN],
 *   [ANAG_RED_BLUE], [ANAG_RED_GREEN]). Si la entrada ya es estéreo
 *   (SBS/TAB) la combina; si es 2D genera pseudo-3D con [depth].
 * - VR_SBS: entrada 2D -> SBS duplicado para visor Cardboard/VR.
 *   Sin seguimiento de cabeza: la misma imagen a ambos ojos.
 * - POLARIZED: entrelazado por líneas (pares=un ojo, impares=otro)
 *   para pantallas/TV polarizados pasivos, desde fuente SBS o TAB.
 *
 * Uso rápido:
 * ```
 * Karin3DController.save(prefs, enabled = true, mode = Karin3DController.MODE_ANAGLYPH, ...)
 * val active = Karin3DController.isActive(prefs)
 * ```
 */
object Karin3DController {

    const val MODE_OFF = 0
    const val MODE_SBS_2D = 1
    const val MODE_TAB_2D = 2
    const val MODE_ANAGLYPH = 3
    const val MODE_VR_SBS = 4
    const val MODE_POLARIZED = 5
    const val MODE_COUNT = 6

    /** Variantes de lentes anaglifo. */
    const val ANAG_RED_CYAN = 0
    const val ANAG_RED_BLUE = 1
    const val ANAG_RED_GREEN = 2
    const val ANAG_COUNT = 3

    /** Fuente estéreo: 2D (pseudo-3D), SBS o TAB. */
    const val INPUT_2D = 0
    const val INPUT_SBS = 1
    const val INPUT_TAB = 2

    /** Profundidad por defecto (desplazamiento paralaje en % del ancho). */
    const val DEFAULT_DEPTH = 40 // 0-100 -> 0.0-1.0 interno (~0-3% ancho real)

    fun modeName(mode: Int): String = when (mode.coerceIn(0, MODE_COUNT - 1)) {
        MODE_SBS_2D -> "SBS → 2D"
        MODE_TAB_2D -> "TAB → 2D"
        MODE_ANAGLYPH -> "Anaglifo ${anaglyphName(-1)}".trim()
        MODE_VR_SBS -> "VR (Cardboard)"
        MODE_POLARIZED -> "Polarizado"
        else -> "Off"
    }

    fun modeName(mode: Int, prefs: SharedPreferences): String = when (mode.coerceIn(0, MODE_COUNT - 1)) {
        MODE_ANAGLYPH -> "Anaglifo ${anaglyphName(anaglyphType(prefs))}"
        else -> modeName(mode)
    }

    fun anaglyphName(type: Int): String = when (type) {
        ANAG_RED_BLUE -> "rojo-azul"
        ANAG_RED_GREEN -> "rojo-verde"
        ANAG_RED_CYAN -> "rojo-cian"
        else -> ""
    }

    fun inputName(kind: Int): String = when (kind) {
        INPUT_SBS -> "SBS"
        INPUT_TAB -> "TAB"
        else -> "2D"
    }

    fun modeDescription(mode: Int): String = when (mode.coerceIn(0, MODE_COUNT - 1)) {
        MODE_SBS_2D -> "Video lado-a-lado (izq|der) a 2D. Elige qué ojo ver."
        MODE_TAB_2D -> "Video arriba-abajo (sup/inf) a 2D. Elige qué ojo ver."
        MODE_ANAGLYPH -> "Lentes bicolor (rojo-cian, rojo-azul, rojo-verde). Con video SBS/TAB mezcla ambos ojos; con 2D genera pseudo-3D."
        MODE_VR_SBS -> "Duplica el 2D a lado-a-lado para visor VR/Cardboard."
        MODE_POLARIZED -> "Entrelazado por líneas para TV polarizada pasiva. Requiere fuente SBS o TAB."
        else -> "Sin proceso 3D."
    }

    fun isActive(prefs: SharedPreferences): Boolean {
        if (!prefs.getBoolean(ExoPlayerSettingsHelper.KEY_3D_EN, false)) return false
        return prefs.getInt(ExoPlayerSettingsHelper.KEY_3D_MODE, MODE_OFF)
            .coerceIn(0, MODE_COUNT - 1) != MODE_OFF
    }

    fun currentMode(prefs: SharedPreferences): Int =
        prefs.getInt(ExoPlayerSettingsHelper.KEY_3D_MODE, MODE_OFF).coerceIn(0, MODE_COUNT - 1)

    fun anaglyphType(prefs: SharedPreferences): Int =
        prefs.getInt(ExoPlayerSettingsHelper.KEY_3D_ANAGLYPH, ANAG_RED_CYAN)
            .coerceIn(0, ANAG_COUNT - 1)

    /**
     * Fuente estéreo efectiva. SBS_2D fuerza SBS, TAB_2D fuerza TAB; el
     * resto usa la guardada (con migración del boolean legacy INPUT_SBS).
     */
    fun inputKind(prefs: SharedPreferences): Int {
        when (currentMode(prefs)) {
            MODE_SBS_2D -> return INPUT_SBS
            MODE_TAB_2D -> return INPUT_TAB
        }
        if (prefs.contains(ExoPlayerSettingsHelper.KEY_3D_INPUT)) {
            return prefs.getInt(ExoPlayerSettingsHelper.KEY_3D_INPUT, INPUT_2D).coerceIn(0, 2)
        }
        // Migración legacy: el boolean solo distinguía SBS vs 2D.
        val sbs = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_3D_INPUT_SBS, false)
        // El polarizado sin dato previo asume SBS (lo más común en TV 3D).
        if (currentMode(prefs) == MODE_POLARIZED && !prefs.contains(ExoPlayerSettingsHelper.KEY_3D_INPUT_SBS)) {
            return INPUT_SBS
        }
        return if (sbs) INPUT_SBS else INPUT_2D
    }

    /** Profundidad 0..1 para anaglifo pseudo-3D y mezcla SBS. */
    fun currentDepth(prefs: SharedPreferences): Float =
        (prefs.getInt(ExoPlayerSettingsHelper.KEY_3D_DEPTH, DEFAULT_DEPTH) / 100f).coerceIn(0f, 1f)

    /** true = ojo derecho / imagen inferior / líneas impares; false = contrario. */
    fun isSwapEye(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(ExoPlayerSettingsHelper.KEY_3D_SWAP, false)

    /** true si la fuente es SBS/TAB (hay que muestrear mitades). */
    fun isStereoInput(prefs: SharedPreferences): Boolean {
        val m = currentMode(prefs)
        return m == MODE_SBS_2D || m == MODE_TAB_2D || m == MODE_POLARIZED ||
            ((m == MODE_ANAGLYPH) && inputKind(prefs) != INPUT_2D)
    }

    fun save(
        prefs: SharedPreferences,
        enabled: Boolean,
        mode: Int,
        depth: Float,
        swapEye: Boolean,
        inputKind: Int,
        anaglyph: Int = anaglyphType(prefs),
    ) {
        prefs.edit()
            .putBoolean(ExoPlayerSettingsHelper.KEY_3D_EN, enabled && mode != MODE_OFF)
            .putInt(ExoPlayerSettingsHelper.KEY_3D_MODE, mode.coerceIn(0, MODE_COUNT - 1))
            .putInt(ExoPlayerSettingsHelper.KEY_3D_DEPTH, (depth.coerceIn(0f, 1f) * 100).toInt())
            .putBoolean(ExoPlayerSettingsHelper.KEY_3D_SWAP, swapEye)
            .putInt(ExoPlayerSettingsHelper.KEY_3D_INPUT, inputKind.coerceIn(0, 2))
            .putBoolean(ExoPlayerSettingsHelper.KEY_3D_INPUT_SBS, inputKind == INPUT_SBS)
            .putInt(ExoPlayerSettingsHelper.KEY_3D_ANAGLYPH, anaglyph.coerceIn(0, ANAG_COUNT - 1))
            .apply()
    }

    /** Compatibilidad con legacy (boolean SBS). No borra: migra al guardar. */
    fun save(
        prefs: SharedPreferences,
        enabled: Boolean,
        mode: Int,
        depth: Float,
        swapEye: Boolean,
        inputSbs: Boolean,
    ) {
        save(prefs, enabled, mode, depth, swapEye, if (inputSbs) INPUT_SBS else INPUT_2D)
    }

    /** Etiqueta corta para el OSD / cadena real. */
    fun chainLabel(prefs: SharedPreferences): String {
        if (!isActive(prefs)) return ""
        val m = currentMode(prefs)
        var label = "3D:${modeName(m, prefs)}"
        if (m == MODE_ANAGLYPH || m == MODE_POLARIZED) {
            label += "[${inputName(inputKind(prefs))}]"
        }
        if (isSwapEye(prefs) &&
            (m == MODE_SBS_2D || m == MODE_TAB_2D || m == MODE_POLARIZED)
        ) label += "(swap)"
        return label
    }

    /**
     * ESTUDIO de compatibilidad: ¿los shaders/filtros activos rompen el 3D?
     *
     * Orden real de la cadena (ExoPlayerActivity.addChainEffects):
     * Restore → Light+Color → Upscaler → MotionX2 → Shader → 3D → Demo.
     * El 3D va ÚLTIMO a propósito: los filtros previos tocan la imagen
     * completa (ambos ojos a la vez), así que la geometría estéreo no se
     * rompe... salvo estos casos medidos:
     *
     * - B/N + anaglifo = MUERTO: el anaglifo codifica la disparidad en los
     *   canales de color (R vs GB/B/G). Sin color no hay separación.
     * - CRT(curvatura) + polarizado = MUERTO: la distorsión barril mueve las
     *   filas y las líneas pares/impares ya no coinciden con el filtro
     *   polarizador del TV (diafonía total). Con SBS→2D es tolerable.
     * - Upscaler + fuente SBS/TAB = COSTURA CONTAMINADA: el FSR/Anime4K
     *   reescala el cuadro SBS completo y su kernel mezcla píxeles de ambos
     *   ojos en la columna central (halo + fuga entre ojos).
     * - MotionX2(BLEND/HYBRID) + estéreo real = FANTASMA TEMPORAL: mezcla el
     *   cuadro previo con el actual y crea disparidad falsa en movimiento.
     *   DOUBLING es no-op (seguro).
     * - Light/Color(saturación) + anaglifo = DIAFONÍA DE COLOR: cambia el
     *   balance R vs GB y los lentes filtran mal (fantasma rojo/cian).
     *   Con SBS→2D / polarizado / VR es inocuo (misma curva en ambos ojos).
     * - Cine(grano) + anaglifo = leve: el grano es igual en ambos ojos
     *   (se genera antes del 3D), solo suma un poco de ruido al filtrar.
     */
    fun compatWarnings(prefs: SharedPreferences): List<String> {
        if (!isActive(prefs)) return emptyList()
        val out = mutableListOf<String>()
        val mode = currentMode(prefs)
        val input = inputKind(prefs)
        val stereoSource = mode == MODE_SBS_2D || mode == MODE_TAB_2D || mode == MODE_POLARIZED ||
            (mode == MODE_ANAGLYPH && input != INPUT_2D)
        val (shaderType, _) = try {
            ExoPlayerSettingsHelper.shaderSelection(prefs)
        } catch (_: Exception) {
            ExoPlayerSettingsHelper.SHADER_OFF to 0f
        }
        val lightOn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_CINE_EN, false) ||
            prefs.getBoolean(ExoPlayerSettingsHelper.KEY_COLORS_EN, false)
        val upscalerOn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_UPSCALER_EN, false)
        val motionOn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false)

        if (mode == MODE_ANAGLYPH && shaderType == ExoPlayerSettingsHelper.SHADER_BW) {
            out += "⛔ B/N + anaglifo incompatible: el blanco y negro destruye los canales de color que separan los ojos. Apaga el Shader B/N."
        }
        if (mode == MODE_POLARIZED && shaderType == ExoPlayerSettingsHelper.SHADER_CRT) {
            out += "⛔ CRT + polarizado incompatible: la curvatura desalinea las líneas entrelazadas del TV polarizado. Apaga el Shader CRT."
        }
        if (stereoSource && upscalerOn) {
            out += "⚠ Upscaler + fuente ${inputName(input)}: el reescalado mezcla ambas mitades en la costura central (halo y fuga entre ojos). Apaga el Upscaler para 3D limpio."
        }
        if (stereoSource && motionOn) {
            val mMode = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 0)
            // 1 = DOUBLING legacy (no-op, seguro). El resto mezcla temporal.
            if (mMode != 1) {
                out += "⚠ MotionX2 + 3D estéreo: la mezcla temporal crea disparidad falsa en movimiento (fantasma). Usa Apagado o Doubling."
            }
        }
        if (mode == MODE_ANAGLYPH && lightOn) {
            out += "⚠ Light/Color + anaglifo: la saturación y el contraste alteran el balance rojo↔cian/azul/verde y dan fantasma. Baja la intensidad o apágalo."
        }
        if (mode == MODE_VR_SBS && shaderType == ExoPlayerSettingsHelper.SHADER_CRT) {
            out += "⚠ CRT + VR: la curvatura se aplica al cuadro completo, no por ojo. Mejor apaga el Shader CRT."
        }
        if (mode == MODE_POLARIZED && input == INPUT_2D) {
            out += "ℹ Polarizado con fuente 2D: no hay segunda vista real; se genera paralaje sintético leve. Para 3D real usa fuente SBS o TAB."
        }
        return out
    }
}
