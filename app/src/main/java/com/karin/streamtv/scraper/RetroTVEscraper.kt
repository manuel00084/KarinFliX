package com.karin.streamtv.scraper

import com.karin.streamtv.model.Episode

object RetroTVEscraper : GenericScraper() {
    override val name = "RetroTVE"
    override val baseUrl = "https://retrotve.com"

    override fun buildSearchUrl(query: String): String =
        "${baseUrl}/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        return parseEpisodeCards(
            doc = doc,
            cardSel = listOf("article.TPost", "div.TPost", "div.post", "article", ".card"),
            titleSel = listOf("h2.Title", "h3.Title", ".Title", "h2", "h3"),
            urlSel = listOf("a"),
            thumbSel = listOf("img.wp-post-image", "img[src]", "img[data-src]"),
            thumbAttrs = listOf("data-src", "abs:src", "data-lazy-src", "abs:data-lazy-src", "data-lazy", "abs:data-lazy"),
            dateSel = listOf("span.Date", ".Date", "time", "[class*='date']"),
            epNumSelector = listOf("span.Capi")
        )
    }
}

class RetroTVEscraperProvider : ScraperProvider {
    override val scraper: BaseScraper = RetroTVEscraper
}
