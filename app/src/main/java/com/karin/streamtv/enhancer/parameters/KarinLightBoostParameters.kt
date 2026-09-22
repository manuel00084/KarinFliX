package com.karin.streamtv.enhancer.parameters

/**
 * Parámetros del Light Boost v2 (fake HDR: curva tonal por tramos,
 * clarity local y color único con gate anti-morado).
 *
 * Todo normalizado a rango útil para el shader:
 * - shadowBoost / blackLevel / whiteBoost / highlightControl / localContrast,
 *   saturation... : 0..1 (cuánto actúa cada etapa), salvo indicación.
 * - gammaInv : 1/gamma, gamma 0.85..1.25 -> invertido 1.176..0.8.
 * - saturation : 0.85..1.35 (1 = sin cambio).
 * - vibrance : 0..1 (empuje de croma en colores apagados).
 * - contentHdr : 0 = SDR (BT.709), 1 = HDR PQ (ST.2084), 2 = HDR HLG.
 * - displayHdr : 0 = pantalla SDR, 1 = pantalla HDR.
 */
data class KarinLightBoostParameters(
    /** Efecto activo (interruptor del usuario). */
    val enabled: Boolean = false,
    /** true = AUTO (la escena decide el boost), false = MANUAL (usa prefValue). */
    val autoMode: Boolean = false,
    /** Energía maestra 0..1 (en AUTO escala el boost de escena, 0.5 = neutro). */
    val prefValue: Float = 0.5f,
    /** Brillo dinámico en sombras 0..1. */
    val shadowBoost: Float = 0.5f,
    /** Nivel de negros 0..1 (piso + detalle oscuro). */
    val blackLevel: Float = 0.5f,
    /** Boost de blancos 0..1 (exposición pre-tonemap). */
    val whiteBoost: Float = 0.5f,
    /** Control de altas luces 0..1 (fuerza del hombro). */
    val highlightControl: Float = 0.5f,
    /** Contraste inteligente 0..1 (clarity local + curva S). */
    val localContrast: Float = 0.45f,
    val gammaInv: Float = 1.0f,
    val saturation: Float = 1.08f,
    val vibrance: Float = 0.24f,
    /**
     * Función extra de color (ex-Colors Boost) 0..1, 0 = apagada.
     * Corre DENTRO del mismo pase, después de la luz y antes del dither,
     * con gate anti-morado en sombras (Y < 0.04 no se satura).
     */
    val colorStrength: Float = 0f,
    /**
     * Compensación manual de rango 0..2 (0 = no tocar).
     * 1 = expandir limitado→completo (arregla negros lavados de streams
     * mal etiquetados); 2 = comprimir completo→limitado. Se aplica al
     * inicio, antes del análisis: todo lo demás trabaja ya corregido.
     */
    val rangeMode: Int = 0,
    val contentHdr: Int = 0,
    val displayHdr: Int = 0,
) {
    val isNoOp: Boolean get() = !enabled && colorStrength <= 0f && rangeMode == 0

    companion object {
        const val GAMMA_MIN = 0.85f
        const val GAMMA_MAX = 1.25f
        const val SAT_MIN = 0.85f
        const val SAT_MAX = 1.35f

        /** Slider 0..100 de gamma -> gamma real, centrado ~1.0 en ~38. */
        fun gammaFromSlider(slider: Int): Float =
            GAMMA_MIN + (GAMMA_MAX - GAMMA_MIN) * slider.coerceIn(0, 100) / 100f

        /** Slider 0..100 de saturación -> multiplicador real (1 = neutro en ~30). */
        fun satFromSlider(slider: Int): Float =
            SAT_MIN + (SAT_MAX - SAT_MIN) * slider.coerceIn(0, 100) / 100f
    }
}
