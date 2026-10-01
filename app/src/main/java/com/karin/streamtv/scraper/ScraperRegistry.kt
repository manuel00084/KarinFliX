package com.karin.streamtv.scraper

import java.util.ServiceLoader

object ScraperRegistry {
    private val scrapers = mutableMapOf<String, BaseScraper>()
    private var initialized = false

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true

        // Load scrapers dynamically via ServiceLoader
        ServiceLoader.load(ScraperProvider::class.java).iterator().forEachRemaining { provider ->
            val scraper = provider.scraper
            scrapers[scraper.name] = scraper
        }
    }

    fun register(scraper: BaseScraper) {
        scrapers[scraper.name] = scraper
    }

    /** Registra (o reutiliza) un scraper genérico para una página temporal. */
    fun registerTempSite(name: String, url: String): BaseScraper {
        ensureInitialized()
        return scrapers.getOrPut(name) { TempScraper(name, url) }
    }

    fun unregister(name: String) {
        scrapers.remove(name)
    }

    fun getScraper(siteName: String): BaseScraper? {
        ensureInitialized()
        return scrapers[siteName]
    }

    fun hasScraper(siteName: String): Boolean {
        ensureInitialized()
        return scrapers.containsKey(siteName)
    }

    val allSites: List<BaseScraper>
        get() {
            ensureInitialized()
            return scrapers.values.toList()
        }
}
