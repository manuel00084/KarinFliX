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

    /**
     * Variante ultra económica para música: sin DSP propio y con offload
     * pedido al chip de audio (si el equipo/formato no lo soporta, el sink
     * vuelve solo a decodificación normal).
     */
    fun renderersFactoryOffload(context: Context): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
                    .also {
                        try {
                            it.setOffloadMode(
                                androidx.media3.exoplayer.audio.AudioSink
                                    .OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED
                            )
                        } catch (_: Exception) {
                        }
                    }
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
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

    /** Consulta con un reintento: la lista de MediaCodec a veces falla de forma
     *  transitoria y devolver vacío cierra el reproductor sin motivo. */
    private fun queryWithRetry(
        mimeType: String,
        secure: Boolean,
        tunneling: Boolean,
    ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        try {
            return MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            Log.w(TAG, "query falló, reintentando: $mimeType (${e.message})")
        }
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {
        }
        return try {
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        } catch (e2: MediaCodecUtil.DecoderQueryException) {
            Log.e(TAG, "query falló 2 veces, lista vacía: $mimeType (${e2.message})")
            emptyList()
        }
    }

    private fun swGoogleSelector(): MediaCodecSelector {
        return MediaCodecSelector { mimeType, secure, tunneling ->
            try {
                val all = queryWithRetry(mimeType, secure, tunneling)
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
                val all = queryWithRetry(mimeType, secure, tunneling)
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
