package com.karin.streamtv.player

import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.karin.streamtv.util.AppPreferences

object CodecSelectorFactory {

    private const val TAG = "CodecSelector"

    fun renderersFactory(context: Context): DefaultRenderersFactory {
        return DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(selector())
    }

    private fun selector(): MediaCodecSelector {
        return when (AppPreferences.getCodecMode()) {
            AppPreferences.CODEC_SW_GOOGLE -> swGoogleSelector()
            AppPreferences.CODEC_AUTO -> autoSelector()
            else -> MediaCodecSelector.DEFAULT
        }
    }

    private fun swGoogleSelector(): MediaCodecSelector {
        return MediaCodecSelector { mimeType, secure, tunneling ->
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

    private fun autoSelector(): MediaCodecSelector {
        return MediaCodecSelector { mimeType, secure, tunneling ->
            try {
                val all = try {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                } catch (e: MediaCodecUtil.DecoderQueryException) {
                    emptyList()
                }
                val hw = all.filter { !it.softwareOnly }
                if (hw.isNotEmpty()) hw else all
            } catch (e: Throwable) {
                Log.w(TAG, "AUTO fallback a DEFAULT: ${e.message}")
                MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            }
        }
    }

    private fun isGoogleSoftware(info: androidx.media3.exoplayer.mediacodec.MediaCodecInfo): Boolean {
        val n = info.name ?: return false
        if (info.softwareOnly) return true
        return n.startsWith("c2.android.") || n.startsWith("OMX.google.")
    }
}
