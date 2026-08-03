package com.karin.streamtv.scraper

import com.karin.streamtv.model.Episode

object DoramaYtScraper : GenericScraper() {
    override val name = "DoramasYT"
    override val baseUrl = "https://www.doramasyt.com"

    override fun buildSearchUrl(query: String): String =
        "${baseUrl}/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        return parseEpisodeCards(
            doc = doc,
            cardSel = listOf("li.col", "div.col", "article", ".card", "li"),
            titleSel = listOf("h3", "h4", ".title", "[class*='title']"),
            thumbSel = listOf("img[data-src]", "img[src]", "img[data-lazy]"),
            thumbAttrs = listOf("data-src", "abs:src", "data-lazy-src", "abs:data-lazy-src", "data-lazy", "abs:data-lazy"),
            dateSel = listOf("span.text-muted", ".date", "[class*='date']", "time"),
            epNumSelector = listOf("span.badge")
        )
    }
}

class DoramaYtScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = DoramaYtScraper
}
