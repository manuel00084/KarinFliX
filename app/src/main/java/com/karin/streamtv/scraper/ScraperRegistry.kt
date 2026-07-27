package com.karin.streamtv.scraper

object ScraperRegistry {
    private val scrapers = mutableMapOf<String, BaseScraper>()
    private var initialized = false

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true
        register(LatAnimeScraper)
        register(Cuevana3Scraper)
        register(JKAnimeScraper)
        register(DoramaYtScraper)
        register(MundoDonghuaScraper)
        register(RetroTVEscraper)
        register(LaCartoonsScraper)
        register(FrikiserieScraper)
    }

    fun register(scraper: BaseScraper) {
        scrapers[scraper.name] = scraper
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
