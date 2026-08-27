package com.karin.streamtv.player

import android.os.Build
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Detecta y aplica la mejor tasa de refresco del panel (60/90/120 Hz) para que la
 * interpolación de la app se muestre a "60p/120p reales" y, cuando la interpolación
 * de la app está apagada, deja la señal limpia para que el MEMC propio del
 * televisor/teléfono (Samsung Motion Smoothing, LG TruMotion, paneles 120 Hz, etc.)
 * haga el suavizado en hardware.
 *
 * Las APIs de tasa de fotogramas (View/Surface.setFrameRate, Display.FRAME_RATE_*
 * de Android 11/API 30) se invocan por reflexión para no acoplar el compile classpath.
 */
object DisplayRateManager {

    private const val TAG = "DisplayRateManager"

    // Constantes públicas y estables de android.view.Display (API 30).
    private const val COMPAT_DEFAULT = 0
    private const val COMPAT_FIXED_SOURCE = 1

    /** Tasa de salida objetivo en fps (igual a la tasa nativa del display elegido). */
    var targetDisplayFps = 60f
        private set

    /** modeId del display seleccionado (0 = modo actual). */
    var selectedModeId = 0
        private set

    /** Tasa nativa actual del display antes de forzar un cambio. */
    var nativeRefreshRate = 60f
        private set

    /**
     * Elige la mejor tasa soportada: la más alta entre 60 y 120 Hz. Esto aprovecha
     * paneles de 120 Hz en móviles y deja 60 Hz en TVs/paneles estándar.
     */
    fun chooseBest(display: Display): Float {
        nativeRefreshRate = display.refreshRate
        val current = display.mode?.modeId ?: 0
        var best = display.refreshRate
        var bestId = current
        for (m in display.supportedModes) {
            val r = m.refreshRate
            val clean = r == 60f || r == 90f || r == 120f || r == 144f
            if (r in 60f..120f && (r > best || (r == best && clean))) {
                best = r
                bestId = m.modeId
            }
        }
        targetDisplayFps = best
        selectedModeId = bestId
        return best
    }

    /**
     * Aplica la tasa al display y avisa al compositor.
     * @param interpolationOn si la interpolación de la app está activa. Cuando está
     *        activa usamos FIXED_SOURCE para NO duplicar el suavizado con el MEMC del
     *        dispositivo; cuando está apagada usamos DEFAULT para que el TV/móvil aplique
     *        su propio MEMC sobre la señal nativa.
     */
    fun apply(window: Window, display: Display, interpolationOn: Boolean) {
        chooseBest(display)
        val compat = if (interpolationOn) COMPAT_FIXED_SOURCE else COMPAT_DEFAULT
        // 1) Cambia el modo físico del display (compatibilidad total de API).
        try {
            val lp = window.attributes
            lp.preferredDisplayModeId = selectedModeId
            window.attributes = lp
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "preferredDisplayModeId failed: ${t.message}")
        }
        // 2) Avisa al compositor (API 30+) para que el dispositivo presente a la tasa
        //    elegida y su motor de movimiento coopere (o se abstenga si FIXED_SOURCE).
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.decorView.setFrameRateReflect(targetDisplayFps, compat)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "View.setFrameRate failed: ${t.message}")
            }
        }
    }

    /** Compatibilidad a usar sobre la Surface GL (API 30+). */
    fun surfaceCompatibility(interpolationOn: Boolean): Int =
        if (interpolationOn) COMPAT_FIXED_SOURCE else COMPAT_DEFAULT
}

/** setFrameRate(float, int) de View (API 30), invocado por reflexión. */
internal fun View.setFrameRateReflect(fps: Float, compatibility: Int) {
    try {
        javaClass.getMethod("setFrameRate", Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(this, fps, compatibility)
    } catch (_: Throwable) {
    }
}
