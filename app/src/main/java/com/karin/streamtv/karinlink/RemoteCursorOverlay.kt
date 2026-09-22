package com.karin.streamtv.karinlink

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Punto de cursor flotante que muestra en la TV dónde está el dedo del
 * touch pad del control remoto.
 *
 * Requiere "Mostrar sobre otras apps". Sin ese permiso los clics siguen
 * funcionando (vía [KarinRemoteService]) pero sin cursor visible.
 */
object RemoteCursorOverlay {

    private const val TAG = "RemoteCursorOverlay"
    private const val DOT_DP = 22

    private var dot: View? = null
    private var wm: WindowManager? = null
    private var lastVisible = 0L

    fun move(ctx: Context, nx: Float, ny: Float) {
        try {
            val app = ctx.applicationContext
            if (!Settings.canDrawOverlays(app)) return
            val m = app.resources.displayMetrics
            val px = (nx.coerceIn(0f, 1f) * m.widthPixels).toInt()
            val py = (ny.coerceIn(0f, 1f) * m.heightPixels).toInt()
            ensure(app)
            val params = dot?.layoutParams as? WindowManager.LayoutParams ?: return
            params.x = px
            params.y = py
            wm?.updateViewLayout(dot, params)
            lastVisible = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "move failed: ${e.message}")
        }
    }

    fun hide(ctx: Context) {
        try {
            dot?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        dot = null
        wm = null
    }

    private fun ensure(app: Context) {
        if (dot != null) return
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (DOT_DP * app.resources.displayMetrics.density).toInt()
        val v = View(app).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF6C63FF.toInt())
                setStroke((2 * app.resources.displayMetrics.density).toInt(), 0xFFFFFFFF.toInt())
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        manager.addView(v, params)
        wm = manager
        dot = v
    }
}
