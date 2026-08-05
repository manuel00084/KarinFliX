package com.karin.streamtv.player.dsp

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log

object DspLimiter {

    @Volatile
    private var dp: DynamicsProcessing? = null

    fun attach(audioSessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        release()
        if (audioSessionId == 0) return
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                2,
                false,
                0,
                false,
                0,
                false,
                0,
                true
            ).build()
            val limiter = DynamicsProcessing.Limiter(
                true,
                true,
                0,
                0.002f,
                0.120f,
                2f, // retardo mínimo: el efecto añade latencia NO compensada por ExoPlayer (A/V sync)
                -2f,
                0f
            )
            config.setLimiterAllChannelsTo(limiter)
            val effect = DynamicsProcessing(audioSessionId, 0, config)
            effect.enabled = AudioEnhanceConfig.isEnabled()
            dp = effect
            Log.i("AudioEnhance", "DspLimiter adjunto session=$audioSessionId")
        } catch (t: Throwable) {
            Log.w("AudioEnhance", "DspLimiter no disponible: ${t.message}")
        }
    }

    fun sync() {
        val d = dp ?: return
        try {
            val on = AudioEnhanceConfig.isEnabled()
            if (d.enabled != on) d.enabled = on
        } catch (_: Throwable) {}
    }

    fun release() {
        try {
            dp?.release()
        } catch (_: Throwable) {}
        dp = null
    }
}
