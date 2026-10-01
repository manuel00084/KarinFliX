package com.karin.streamtv

import android.app.Application
import android.content.ComponentCallbacks2
import com.karin.streamtv.util.AppPreferences

class KarinTVApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Lo primero: cazar crashes de todo el proceso (incluido este onCreate).
        try {
            com.karin.streamtv.util.CrashLogger.init(this)
        } catch (_: Exception) {
        }
        registerActivityLifecycleCallbacks(com.karin.streamtv.util.AppActivityHolder)
        AppPreferences.init(this)
        com.karin.streamtv.player.SystemVideoPlayerRegistrar.apply(this)
        com.karin.streamtv.player.dsp.smartlite.SmartLiteConfig.init(this)
        com.karin.streamtv.util.Http.initCache(cacheDir)
        com.karin.streamtv.util.Http.initCookies(this)
        com.karin.streamtv.util.WatchHistory.init(this)
        com.karin.streamtv.util.EpisodeProgress.init(this)
        com.karin.streamtv.util.DiskImageCache.init(this)
        com.karin.streamtv.scraper.ScrapingEngine.init(this)
        // Ultra económico desde el arranque: caché a la mitad y red calmada.
        try {
            val ultra = AppPreferences.isUltraEconomyMode()
            com.karin.streamtv.util.DiskImageCache.setUltraMode(ultra)
            com.karin.streamtv.scraper.ScrapingEngine.setMaxConcurrent(if (ultra) 2 else 8)
        } catch (_: Exception) { }
        // Servidor Karin Link: solo si está encendido en Ajustes
        // (Ajustes -> KARIN Link). Apagado -> no se abre socket ni NSD.
        // El servicio va en primer plano para que el sistema no mate el socket
        // a mitad de un capítulo; el bind del socket + NSD no van en el hilo
        // principal (ANR en TV).
        try {
            Thread({
                try {
                    if (AppPreferences.isKarinLinkEnabled()) {
                        com.karin.streamtv.karinlink.KarinLinkService.start(this)
                    }
                } catch (_: Exception) {
                }
            }, "karinlink-host").apply {
                isDaemon = true
                start()
            }
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
