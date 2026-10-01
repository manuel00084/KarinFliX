package com.karin.streamtv.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Configuración que el framework de Cast lee a través del manifest.
 *
 * Se usa el receptor por defecto de Google (DEFAULT_MEDIA_RECEIVER), que ya
 * reproduce mp4, webm, m4a e imágenes: no hace que empaquetar un receptor
 * propio ni registrar una app.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
