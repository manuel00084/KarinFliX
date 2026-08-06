package com.karin.streamtv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Construye el [DefaultTrackSelector] acotado a la resolución real de la pantalla.
 *
 * El selector por defecto pide la mejor pista disponible sin límite de viewport, lo que
 * en una TV FullHD o un teléfono puede elegir 4K: más ancho de banda, más CPU/GPU de
 * decodificación y más rebuffering, sin ganancia visible. Acotar al tamaño del display
 * mantiene la misma calidad percibida y mejora rendimiento y fluidez.
 */
object TrackSelectorFactory {

    fun create(context: Context): DefaultTrackSelector {
        val dm = context.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        return DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters()
                .setViewportSize(width, height, true)
                .setMaxVideoSize(C.LENGTH_UNSET, C.LENGTH_UNSET)
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setMaxAudioBitrate(Int.MAX_VALUE)
            )
        }
    }
}
