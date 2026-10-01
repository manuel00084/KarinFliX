package com.karin.streamtv.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager

object DeviceUtils {

    fun isTvDevice(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        // Fire TV Stick, TV-box baratos y Chromecast con Google TV a veces
        // reportan UI_MODE_TYPE_NORMAL: se detectan por feature Leanback
        // o por ausencia de touchscreen.
        try {
            val pm = context.packageManager
            if (pm.hasSystemFeature("android.software.leanback")) return true
            if (!pm.hasSystemFeature("android.hardware.touchscreen")) return true
        } catch (_: Exception) { }
        return false
    }

    fun isTablet(context: Context): Boolean {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val widthInches = metrics.widthPixels.toDouble() / metrics.xdpi
        val heightInches = metrics.heightPixels.toDouble() / metrics.ydpi
        val diagonalInches = Math.sqrt(widthInches * widthInches + heightInches * heightInches)
        return diagonalInches >= 7.0
    }
}
