package com.karin.streamtv

import android.app.Application
import com.karin.streamtv.util.AppPreferences
import io.sentry.Sentry

class KarinTVApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        Sentry.init { options ->
            options.dsn = ""
            options.isEnabled = false
        }

        AppPreferences.init(this)
        com.karin.streamtv.util.Http.initCache(cacheDir)
        com.karin.streamtv.util.Http.initCookies(this)
        com.karin.streamtv.scraper.ScrapingEngine.init(this)
    }
}
