package com.karin.streamtv

import android.app.Application
import android.content.ComponentCallbacks2
import com.karin.streamtv.util.AppPreferences

class KarinTVApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(com.karin.streamtv.util.AppActivityHolder)
        AppPreferences.init(this)
        com.karin.streamtv.player.SystemVideoPlayerRegistrar.apply(this)
        com.karin.streamtv.player.dsp.        AudioEnhanceConfig.init(this)
        com.karin.streamtv.player.dsp.audiophile.AudiophileConfig.init(this)
        com.karin.streamtv.util.Http.initCache(cacheDir)
        com.karin.streamtv.util.Http.initCookies(this)
        com.karin.streamtv.util.WatchHistory.init(this)
        com.karin.streamtv.util.EpisodeProgress.init(this)
        com.karin.streamtv.util.DiskImageCache.init(this)
        com.karin.streamtv.scraper.ScrapingEngine.init(this)
        // Servidor Karin Link siempre activo: permite el control remoto
        // (botones, touch pad, teclado) sin abrir la pantalla de Karin Link.
        try {
            com.karin.streamtv.karinlink.KarinLinkHost.start(this)
        } catch (_: Exception) {
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Libera bitmaps de la caché global de imágenes según la presión de RAM
        // del sistema (TRIM_MEMORY_RUNNING_* ocurre mientras la app está en uso).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.karin.streamtv.util.DiskImageCache.trimMemory()
        }
    }
}
