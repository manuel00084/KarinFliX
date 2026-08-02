package com.karin.streamtv.player

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.media3.exoplayer.ExoPlayer

class VideoEnhanceBridge(
    private val textureView: TextureView,
    private val player: ExoPlayer
) {
    private var enhancer: GlVideoEnhancer? = null
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var inputSurface: Surface? = null
    private var videoWidth = 1920
    private var videoHeight = 1080
    private var initialized = false

    fun initialize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        glThread = HandlerThread("GlEnhancerThread").apply { start() }
        glHandler = Handler(glThread!!.looper)
        glHandler?.post { initGl() }
    }

    private fun initGl() {
        try {
            enhancer = GlVideoEnhancer()
            val outputSurface = Surface(textureView.surfaceTexture)
            if (!enhancer!!.init(videoWidth, videoHeight, outputSurface)) {
                Log.e(TAG, "GL enhancer init failed")
                enhancer?.release()
                enhancer = null
                return
            }

            val inputTexture = enhancer!!.createInputSurface()
            inputSurface = Surface(inputTexture)

            inputTexture.setOnFrameAvailableListener({
                try {
                    enhancer?.drawFrame()
                } catch (e: Exception) {
                    Log.e(TAG, "drawFrame error: ${e.message}")
                }
            }, glHandler)

            player.setVideoSurface(inputSurface)
            initialized = true
            Log.i(TAG, "VideoEnhanceBridge initialized: ${videoWidth}x${videoHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "initGl error: ${e.message}")
        }
    }

    fun release() {
        glHandler?.post {
            enhancer?.release()
            enhancer = null
        }
        inputSurface?.release()
        inputSurface = null
        glThread?.quitSafely()
        glThread = null
        glHandler = null
        initialized = false
        Log.i(TAG, "VideoEnhanceBridge released")
    }

    fun isInitialized() = initialized

    companion object {
        private const val TAG = "VideoEnhanceBridge"
    }
}
