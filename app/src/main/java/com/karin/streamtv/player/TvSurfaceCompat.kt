package com.karin.streamtv.player

import android.view.SurfaceView
import android.view.SurfaceHolder
import androidx.media3.ui.PlayerView
import android.opengl.GLSurfaceView

/**
 * Android TV compatibility: many TVs/boxes report the app window (and thus the OpenGL /
 * SurfaceView buffer) at a logical resolution LOWER than the physical panel (e.g. UI "1080p"
 * logical but 4K panel, or 720p logical). The video is rendered into that smaller buffer and the
 * TV compositor upscales it, which makes 1080p sources look soft / low-res ("pixelado").
 *
 * On an emulator the window == the physical screen, so it always looks sharp. That asymmetry is
 * exactly what the user reports.
 *
 * The correct fix for a hardware MediaCodec SurfaceView: set the surface holder's fixed buffer
 * size to the panel-native resolution (or the video's native resolution), so the decoder/GL
 * writes pixels at native size and the compositor just blits 1:1.
 */
object TvSurfaceCompat {

    /** Returns the physical (native) pixel size of the screen, not the logical/mode size. */
    fun nativeDisplaySize(context: android.content.Context): Pair<Int, Int> {
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            ?: return 0 to 0
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val b = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                @Suppress("DEPRECATION")
                val metrics = android.graphics.Point()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(metrics)
                metrics.x to metrics.y
            }
        } catch (_: Throwable) {
            0 to 0
        }
    }

    /** Force a hardware SurfaceView buffer to render at native/wanted resolution. */
    fun forceSurfaceSize(surfaceView: SurfaceView?, width: Int, height: Int) {
        if (surfaceView == null || width <= 0 || height <= 0) return
        try {
            val holder: SurfaceHolder = surfaceView.holder
            // setFixedSize on the holder forces the Surface buffer dimensions; the decoder then
            // fills it at native res and the compositor scales the surface to the on-screen rect.
            holder.setFixedSize(width, height)
        } catch (_: Throwable) {}
    }

    /** Resolve the ideal output size: panel-native if available, else the video size. */
    fun idealSurfaceSize(
        context: android.content.Context,
        videoWidth: Int,
        videoHeight: Int
    ): Pair<Int, Int> {
        val (nativeW, nativeH) = nativeDisplaySize(context)
        val w = nativeW.takeIf { it > 0 } ?: videoWidth
        val h = nativeH.takeIf { it > 0 } ?: videoHeight
        if (w <= 0 || h <= 0) return videoWidth to videoHeight
        // Keep the video aspect ratio but match the native resolution so it stays sharp (no blur)
        return w to h
    }

    /** Apply native-size buffer to a GLSurfaceView used by the enhanced pipeline. */
    fun forceGlSurfaceSize(glSurface: GLSurfaceView?, width: Int, height: Int) {
        if (glSurface == null || width <= 0 || height <= 0) return
        try {
            glSurface.holder.setFixedSize(width, height)
        } catch (_: Throwable) {}
    }

    /** For a media3 PlayerView: get the underlying SurfaceView holder and set its size. */
    fun forcePlayerViewSurface(playerView: PlayerView?, width: Int, height: Int) {
        if (playerView == null) return
        val v = playerView.getVideoSurfaceView() as? SurfaceView ?: return
        forceSurfaceSize(v, width, height)
    }
}