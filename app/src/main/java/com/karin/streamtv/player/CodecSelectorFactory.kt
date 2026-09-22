package com.karin.streamtv.player

import android.content.Context
import android.media.MediaCodecInfo
import android.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
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

    /**
     * Variante con procesadores de audio propios (DSP Karin): subclase para
     * inyectar la cadena en el AudioSink. Sin procesadores, igual que la normal.
     */
    fun renderersFactoryWithAudio(
        context: Context,
        audioProcessors: Array<androidx.media3.common.audio.AudioProcessor>,
    ): DefaultRenderersFactory {
        return KarinAudioRenderersFactory(context, audioProcessors)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(selector())
    }

    /** Subclase de media3 para inyectar AudioProcessors propios al AudioSink. */
    class KarinAudioRenderersFactory(
        context: Context,
        private val audioProcessors: Array<androidx.media3.common.audio.AudioProcessor>,
    ) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): androidx.media3.exoplayer.audio.AudioSink? {
            return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setAudioProcessors(audioProcessors)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
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
                try {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                } catch (e2: Exception) {
                    Log.e(TAG, "DEFAULT también falló, lista vacía: ${e2.message}")
                    emptyList()
                }
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
                try {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                } catch (e2: Exception) {
                    Log.e(TAG, "DEFAULT también falló, lista vacía: ${e2.message}")
                    emptyList()
                }
            }
        }
    }

    private fun isGoogleSoftware(info: androidx.media3.exoplayer.mediacodec.MediaCodecInfo): Boolean {
        val n = info.name ?: return false
        if (info.softwareOnly) return true
        return n.startsWith("c2.android.") || n.startsWith("OMX.google.")
    }
}
