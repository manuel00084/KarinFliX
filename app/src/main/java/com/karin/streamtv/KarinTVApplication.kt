package com.karin.streamtv

import android.app.Application
import com.karin.streamtv.util.AppPreferences

class KarinTVApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(com.karin.streamtv.util.AppActivityHolder)
        AppPreferences.init(this)
        com.karin.streamtv.util.Http.initCache(cacheDir)
        com.karin.streamtv.util.Http.initCookies(this)
        com.karin.streamtv.util.WatchHistory.init(this)
        com.karin.streamtv.util.EpisodeProgress.init(this)
        com.karin.streamtv.util.DiskImageCache.init(this)
        com.karin.streamtv.scraper.ScrapingEngine.init(this)
        com.karin.streamtv.player.VideoEnhanceConfig.init(this)
        com.karin.streamtv.player.dsp.AudioEnhanceConfig.init(this)
    }
}
