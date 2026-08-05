package com.karin.streamtv.player

import android.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.karin.streamtv.player.VideoEnhanceConfig.CodecMode

/**
 * Construye el [MediaCodecSelector] según el modo de códec elegido en la configuración.
 *
 *  HW        -> selector por defecto (decodificador de hardware del chip).
 *  SW_GOOGLE -> solo decodificadores de software de Google (c2.android.* / OMX.google.*).
 *  FFMPEG    -> lista vacía: Media3 cae al renderer de extensión FFmpeg (media3-decoder-ffmpeg),
 *               que decodifica por software.
 */
object CodecSelectorFactory {

    private const val TAG = "CodecSelector"

    fun selector(): MediaCodecSelector {
        return buildSelector(VideoEnhanceConfig.codecMode())
    }

    fun renderersFactory(context: android.content.Context): DefaultRenderersFactory {
        return com.karin.streamtv.player.dsp.DspRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(selector())
    }

    fun buildSelector(mode: VideoEnhanceConfig.CodecMode): MediaCodecSelector = when (mode) {
        VideoEnhanceConfig.CodecMode.HW -> MediaCodecSelector.DEFAULT

        VideoEnhanceConfig.CodecMode.SW_GOOGLE -> MediaCodecSelector { mimeType, secure, tunneling ->
            try {
                val all = try {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                } catch (e: MediaCodecUtil.DecoderQueryException) {
                    emptyList()
                }
                val sw = all.filter { isGoogleSoftware(it) }
                if (sw.isNotEmpty()) sw else all
            } catch (e: Throwable) {
                Log.w(TAG, "SW_GOOGLE fallback a DEFAULT: ${e.message}")
                MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            }
        }
    }

    private fun isGoogleSoftware(info: androidx.media3.exoplayer.mediacodec.MediaCodecInfo): Boolean {
        val n = info.name ?: return false
        if (info.softwareOnly) return true
        return n.startsWith("c2.android.") || n.startsWith("OMX.google.")
    }

    fun label(mode: VideoEnhanceConfig.CodecMode): String = mode.label
}