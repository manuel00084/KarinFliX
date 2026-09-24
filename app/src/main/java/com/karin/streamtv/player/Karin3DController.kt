package com.karin.streamtv.player

import android.content.SharedPreferences

/**
 * Tecnología 3D de KarinFLiX: convierte y emite video estereoscópico
 * en un solo pase GL (GLES2, 1-3 fetches, apto para cajas modestas).
 *
 * Modos:
 * - OFF: sin proceso (no-op, no ocupa pase GL).
 * - SBS_2D: entrada lado-a-lado (izq|der) -> 2D (ojo elegido, estirado).
 * - TAB_2D: entrada arriba-abajo (sup/inf) -> 2D (ojo elegido, estirado).
 * - ANAGLYPH: lentes bicolor con 3 variantes ([ANAG_RED_CYAN],
 *   [ANAG_RED_BLUE], [ANAG_RED_GREEN]). Si la entrada ya es estéreo
 *   (SBS/TAB) la combina; si es 2D genera pseudo-3D con [depth].
 * - VR_SBS: con fuente 2D la duplica para visor Cardboard/VR; con
 *   fuente SBS la pasa tal cual (ya es SBS). Sin seguimiento de cabeza:
 *   la misma imagen a ambos ojos.
 * - PULFRICH (homenaje Fabulojos 1997): la profundidad la pone un lente
 *   OSCURO en un ojo (el ojo oscurecido procesa ~1 cuadro más lento y el
 *   movimiento lateral se vuelve profundidad). La app solo prepara la
 *   imagen (realce horizontal sutil); quieto no hay 3D (física, no bug).
 *   Sin lentes se ve normal, como debe ser. Fuente 2D.
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
    // 5 era POLARIZED (eliminado): ahora PULFRICH. Un 5 guardado migra solo.
    const val MODE_PULFRICH = 5
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
        MODE_ANAGLYPH -> "Anaglifo"
        MODE_VR_SBS -> "VR (Cardboard)"
        MODE_PULFRICH -> "Pulfrich"
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
        // Sin TAB: todo lo TAB (modo o pref vieja) colapsa a SBS.
        if (currentMode(prefs) == MODE_TAB_2D) return INPUT_SBS
        if (currentMode(prefs) == MODE_SBS_2D) return INPUT_SBS
        if (prefs.contains(ExoPlayerSettingsHelper.KEY_3D_INPUT)) {
            return prefs.getInt(ExoPlayerSettingsHelper.KEY_3D_INPUT, INPUT_2D).coerceIn(0, 1)
        }
        // Migración legacy: el boolean solo distinguía SBS vs 2D.
        val sbs = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_3D_INPUT_SBS, false)
        return if (sbs) INPUT_SBS else INPUT_2D
    }

    /** Profundidad 0..1 para anaglifo pseudo-3D y mezcla SBS. */
    fun currentDepth(prefs: SharedPreferences): Float =
        (prefs.getInt(ExoPlayerSettingsHelper.KEY_3D_DEPTH, DEFAULT_DEPTH) / 100f).coerceIn(0f, 1f)

    /** true = ojo derecho; false = contrario. (En Pulfrich el swap no aplica.) */
    fun isSwapEye(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(ExoPlayerSettingsHelper.KEY_3D_SWAP, false)

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
            // Sin TAB en la UI: se colapsa a SBS (igual que al leer).
            .putInt(ExoPlayerSettingsHelper.KEY_3D_INPUT, inputKind.coerceIn(0, 1))
            .putInt(ExoPlayerSettingsHelper.KEY_3D_ANAGLYPH, anaglyph.coerceIn(0, ANAG_COUNT - 1))
            .apply()
    }

    /** Etiqueta corta para el OSD / cadena real. */
    fun chainLabel(prefs: SharedPreferences): String {
        if (!isActive(prefs)) return ""
        val m = currentMode(prefs)
        var label = "3D:${modeName(m, prefs)}"
        if (m == MODE_ANAGLYPH) {
            label += "[${inputName(inputKind(prefs))}]"
        }
        // El swap invierte ojos en SBS→2D, TAB→2D y en anaglifo con
        // fuente estéreo (fetchStereo lo usa); en VR/Pulfrich no aplica.
        val swapApplies = m == MODE_SBS_2D || m == MODE_TAB_2D ||
            (m == MODE_ANAGLYPH && inputKind(prefs) != INPUT_2D)
        if (isSwapEye(prefs) && swapApplies) label += "(swap)"
        return label
    }

    /**
     * ESTUDIO de compatibilidad: ¿los shaders/filtros activos rompen el 3D?
     *
     * Orden real de la cadena (ExoPlayerActivity.addChainEffects):
     * Restore → Light+Color → Upscaler → MotionX2 → Shader → Visión → 3D
     * → Demo.
     * El 3D va ÚLTIMO a propósito: los filtros previos tocan la imagen
     * completa (ambos ojos a la vez), así que la geometría estéreo no se
     * rompe... salvo estos casos medidos:
     *
     * - B/N + anaglifo = MUERTO: el anaglifo codifica la disparidad en los
     *   canales de color (R vs GB/B/G). Sin color no hay separación.
     * - MotionX2(BLEND/HYBRID) + PULFRICH = MUERTO: la mezcla temporal
     *   destruye el retardo entre ojos del que vive el efecto (lo aplana
     *   a 2D). DOUBLING es no-op (seguro).
     * - PULFRICH necesita lente oscuro en un ojo + movimiento lateral;
     *   quieto no hay 3D (física). Funciona en B/N y le sienta bien Light.
     * - Upscaler + fuente SBS/TAB = COSTURA CONTAMINADA: el FSR/Anime4K
     *   reescala el cuadro SBS completo y su kernel mezcla píxeles de ambos
     *   ojos en la columna central (halo + fuga entre ojos).
     * - MotionX2(BLEND/HYBRID) + estéreo real = FANTASMA TEMPORAL: mezcla el
     *   cuadro previo con el actual y crea disparidad falsa en movimiento.
     *   DOUBLING es no-op (seguro).
     * - Light/Color(saturación) + anaglifo = DIAFONÍA DE COLOR: cambia el
     *   balance R vs GB y los lentes filtran mal (fantasma rojo/cian).
     *   Con SBS→2D / Pulfrich / VR es inocuo (misma curva en ambos ojos
     *   o imagen única).
     * - Cine(grano) + anaglifo = leve: el grano es igual en ambos ojos
     *   (se genera antes del 3D), solo suma un poco de ruido al filtrar.
     */
    fun compatWarnings(prefs: SharedPreferences): List<String> {
        if (!isActive(prefs)) return emptyList()
        val out = mutableListOf<String>()
        val mode = currentMode(prefs)
        val input = inputKind(prefs)
        val stereoSource = mode == MODE_SBS_2D || mode == MODE_TAB_2D ||
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
        if (mode == MODE_ANAGLYPH && shaderType == ExoPlayerSettingsHelper.SHADER_CINE) {
            out += "⚠ Cine + anaglifo: el grano de película añade ruido al filtrado de los lentes (leve)."
        }
        if (mode == MODE_PULFRICH && motionOn) {
            // Todos los modos MotionX2 mezclan temporalmente (Doubling ahora
            // interpola x2 real): ninguno es seguro con Pulfrich.
            out += "⛔ MotionX2 + Pulfrich incompatible: la mezcla temporal destruye el retardo entre ojos (lo deja en 2D). Apaga MotionX2."
        }
        if (stereoSource && upscalerOn) {
            out += "⚠ Upscaler + fuente ${inputName(input)}: el reescalado mezcla ambas mitades en la costura central (halo y fuga entre ojos). Apaga el Upscaler para 3D limpio."
        }
        if (stereoSource && motionOn) {
            // Todos los modos MotionX2 mezclan temporalmente (Doubling ahora
            // interpola x2 real): ninguno es seguro con 3D estéreo.
            out += "⚠ MotionX2 + 3D estéreo: la mezcla temporal crea disparidad falsa en movimiento (fantasma). Apaga MotionX2."
        }
        if (mode == MODE_ANAGLYPH && lightOn) {
            out += "⚠ Light/Color + anaglifo: la saturación y el contraste alteran el balance rojo↔cian/azul/verde y dan fantasma. Baja la intensidad o apágalo."
        }
        if (mode == MODE_VR_SBS && shaderType == ExoPlayerSettingsHelper.SHADER_CRT) {
            out += "⚠ CRT + VR: la curvatura se aplica al cuadro completo, no por ojo. Mejor apaga el Shader CRT."
        }
        if (mode == MODE_PULFRICH) {
            out += "ℹ Pulfrich: ponte un lente oscuro en un ojo (Fabulojos/gafa de sol) y busca escenas con movimiento lateral. Quieto no hay 3D."
        }
        return out
    }
}
