package com.karin.streamtv.scraper

import com.karin.streamtv.model.Episode

object LatAnimeScraper : GenericScraper() {
    override val name = "LatAnime"
    override val baseUrl = "https://latanime.org"

    override fun buildSearchUrl(query: String): String =
        "${baseUrl}/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        return parseEpisodeCards(
            doc = doc,
            cardSel = listOf(
                "div.col-6.col-md-6.col-lg-3.mb-3",
                "div.col-6.col-md-4.col-lg-3",
                "div.mb-3",
                "article",
                ".card"
            ),
            titleSel = listOf("h2.mt-3", "h3", "h4", ".title", "[class*='title']"),
            thumbSel = listOf("img.lozad.nxtmainimg", "img.lozad", "img[src]", "img[data-src]"),
            thumbAttrs = listOf("data-src", "abs:src", "data-lazy-src", "abs:data-lazy-src", "data-lazy", "abs:data-lazy"),
            dateSel = listOf("span.span-tiempo", ".date", ".fecha", "time", "[class*='date']"),
            epNumSelector = listOf("span.badge")
        )
    }
}
