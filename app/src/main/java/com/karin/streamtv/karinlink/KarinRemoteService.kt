package com.karin.streamtv.karinlink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Servicio de accesibilidad para el control remoto Karin Link.
 *
 * Permite lo que una app normal no puede hacer sin root:
 * - Toques y scrolls en cualquier coordenada de pantalla ([tapAt], [scrollAt]),
 *   es decir, el touch pad funciona como mouse de verdad en toda la TV.
 * - Escribir texto en el campo enfocado ([setTextOnFocused]).
 * - Acciones globales Atrás/Inicio ([globalBack], [globalHome]).
 *
 * Es OPCIONAL: sin activarlo, el control remoto sigue funcionando dentro de
 * KarinFLiX (teclas, reproductor, texto en buscadores). Se activa en
 * Ajustes del sistema → Accesibilidad → "Karin Link Remote".
 */
class KarinRemoteService : AccessibilityService() {

    companion object {
        private const val TAG = "KarinRemoteService"

        @Volatile private var instance: KarinRemoteService? = null

        val isEnabled: Boolean get() = instance != null

        private fun px(ctx: Context, nx: Float, ny: Float): Pair<Float, Float> {
            val m = ctx.resources.displayMetrics
            return (nx.coerceIn(0f, 1f) * m.widthPixels) to (ny.coerceIn(0f, 1f) * m.heightPixels)
        }

        fun tapAt(ctx: Context, nx: Float, ny: Float): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            val (x, y) = px(ctx.applicationContext, nx, ny)
            return svc.gesture(x, y, x, y, 80)
        }

        fun scrollAt(ctx: Context, nx: Float, ny: Float, dxn: Float, dyn: Float): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            val m = ctx.applicationContext.resources.displayMetrics
            val (x, y) = px(ctx.applicationContext, nx, ny)
            return svc.gesture(x, y, x + dxn * m.widthPixels, y + dyn * m.heightPixels, 350)
        }

        fun setTextOnFocused(text: String, append: Boolean): Boolean {
            val svc = instance ?: return false
            if (text.isEmpty()) return true
            return try {
                val root = svc.rootInActiveWindow ?: return false
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
                if (!focused.isEditable) return false
                val current = if (append) focused.text?.toString().orEmpty() else ""
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        current + text,
                    )
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (e: Exception) {
                Log.w(TAG, "setText failed: ${e.message}")
                false
            }
        }

        fun globalBack(): Boolean {
            return try {
                instance?.performGlobalAction(GLOBAL_ACTION_BACK) == true
            } catch (_: Exception) {
                false
            }
        }

        fun globalHome(): Boolean {
            return try {
                instance?.performGlobalAction(GLOBAL_ACTION_HOME) == true
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // No necesitamos eventos; solo gestos/acciones bajo demanda.
    }

    override fun onInterrupt() {}

    private fun gesture(x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x0, y0)
                lineTo(x1, y1)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val desc = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(desc, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "gesture failed: ${e.message}")
            false
        }
    }
}
