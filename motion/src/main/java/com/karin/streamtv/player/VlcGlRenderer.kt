package com.karin.streamtv.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Reproduce un stream con libVLC 3.x escribiendo los frames en un
 * [SurfaceTexture] de Android. El SurfaceTexture se crea por el
 * [InterpolationRenderer] sobre una textura GLES externa (OES), de modo que
 * el pipeline de interpolación puede muestrearla vía GLSL.
 */
class VlcGlRenderer(
    private val context: Context,
    private val url: String
) {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    fun attach(surfaceTexture: SurfaceTexture) {
        if (attached) return
        libVLC = LibVLC(context)
        libVLC!!.setUserAgent("KarinFLiX/1.3.0 libVLC/${LibVLC.version()}", "KarinFLiX")
        val mp = MediaPlayer(libVLC)
        mediaPlayer = mp
        val vout = mp.vlcVout
        vout.setVideoSurface(surfaceTexture)
        vout.attachViews()
        val media = Media(libVLC, Uri.parse(url))
        mp.media = media
        media.release()
        mp.play()
        Log.i(TAG, "playing: ${url.takeLast(80)}")
    }

    private val attached: Boolean
        get() = mediaPlayer != null

    fun setVideoSize(width: Int, height: Int) {
        // VLC escolhe o tamanho da janela por si mismo; colisão é tratada por
        // setWindowSize quando conhecermos a resolução real do stream.
        mediaPlayer?.vlcVout?.setWindowSize(width, height)
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.time = positionMs
    }

    fun pause() {
        mediaPlayer?.pause()
    }

    fun resume() {
        mediaPlayer?.play()
    }

    fun setRate(rate: Float) {
        mediaPlayer?.rate = rate
    }

    fun stop() {
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
        try { libVLC?.release() } catch (_: Throwable) {}
        libVLC = null
    }

    companion object {
        private const val TAG = "VlcGlRenderer"
    }
}