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
 *
 * Para que el primer frame aparezca lo antes posible se reduce el umbral inicial
 * de arranque (`bufferForPlaybackMs`/`bufferForPlaybackAfterRebufferMs`) y se
 * activa `setPrioritizeTimeOverSizeThresholds(true)`: el reproductor empieza en
 * cuanto se cumple el tiempo de búfer, sin esperar a llenar el objetivo de bytes
 * (que en redes lentas retrasaría el inicio). El búfer de mantenimiento (min/max)
 * se mantiene alto para evitar cortes durante la reproducción.
 *
 * En reproducción LOCAL (archivo en el dispositivo) no hay red que pueda cortar,
 * así que se usan búferes mínimos: el video arranca de inmediato y la barra de
 * tiempo no muestra el colchón de "Cargando...".
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
            2500, 6000
        )
        DeviceProfile.Tier.MID -> Bounds(
            15000, 45000, 10000, 20 * 1024 * 1024,
            2000, 5000
        )
        DeviceProfile.Tier.HIGH -> Bounds(
            20000, 80000, 15000, 40 * 1024 * 1024,
            1500, 4000
        )
    }

    private val LOCAL_BOUNDS = Bounds(
        2000, 10000, 2000, 4 * 1024 * 1024,
        500, 1000
    )

    fun create(context: Context, isLocal: Boolean = false): DefaultLoadControl {
        val b = if (isLocal) LOCAL_BOUNDS else boundsFor(DeviceProfile.get(context))
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                b.minBufferMs,
                b.maxBufferMs,
                b.bufferForPlaybackMs,
                b.bufferForPlaybackAfterRebufferMs
            )
            .setBackBuffer(b.backBufferMs, false)
            .setTargetBufferBytes(b.targetBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
}