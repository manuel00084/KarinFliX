package com.karin.streamtv.player

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import com.karin.streamtv.util.DeviceProfile

/**
 * Construye un [DefaultLoadControl] cuyo tamaño de búfer se escala
 * automáticamente con el perfil de recursos del dispositivo (ver [DeviceProfile]).
 *
 * El búfer pre-cargado de video es contención nativa directa. En gama baja el
 * colchón anti-rebuffer debe ser mayor (la CPU descodifica lento y la red suele
 * ser más irregular), por eso LOW usa buffer largo y una gran espera al reanudar
 * tras un corte: evita el bucle "Cargando... -> corte -> Cargando...". La calidad
 * (resolución/bitrate) no varía; solo se redondea el colchón.
 */
object RamAwareLoadControl {

    private data class Bounds(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val backBufferMs: Int,
        val targetBytes: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int
    )

    private fun boundsFor(profile: DeviceProfile.Info): Bounds = when (profile.tier) {
        DeviceProfile.Tier.LOW -> Bounds(
            15000, 60000, 10000, 16 * 1024 * 1024,
            5000, 12000
        )
        DeviceProfile.Tier.MID -> Bounds(
            15000, 45000, 10000, 20 * 1024 * 1024,
            4000, 8000
        )
        DeviceProfile.Tier.HIGH -> Bounds(
            20000, 80000, 15000, 40 * 1024 * 1024,
            3500, 7000
        )
    }

    fun create(context: Context): DefaultLoadControl {
        val b = boundsFor(DeviceProfile.get(context))
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                b.minBufferMs,
                b.maxBufferMs,
                b.bufferForPlaybackMs,
                b.bufferForPlaybackAfterRebufferMs
            )
            .setBackBuffer(b.backBufferMs, false)
            .setTargetBufferBytes(b.targetBytes)
            .build()
    }
}