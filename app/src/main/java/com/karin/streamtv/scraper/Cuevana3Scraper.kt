package com.karin.streamtv.scraper

import com.karin.streamtv.model.Episode

/**
 * Cuevana3 – Películas/Series site.
 *
 * Cards: li.xxx.TPostMv > div.TPost > a[href]
 *   > div.Image > figure > img[data-src]
 *   > h2.Title
 *
 * Search: /search/{query}
 */
object Cuevana3Scraper : GenericScraper() {
    override val name = "Cuevana3"
    override val baseUrl = "https://www3.cuevana3.is"

    override fun buildSearchUrl(query: String): String =
        "${baseUrl}/search/${java.net.URLEncoder.encode(query, "UTF-8")}"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        return parseEpisodeCards(
            doc = doc,
            cardSel = listOf("#tab-1 li.TPostMv", "li.TPostMv", "li.TPost", "article", ".card"),
            titleSel = listOf("h2.Title", "h3.Title", ".Title", "h2", "h3"),
            thumbSel = listOf("img[data-src]", "img[src]", "img[data-lazy]"),
            thumbAttrs = listOf("data-src", "abs:src", "data-lazy-src", "abs:data-lazy-src", "data-lazy", "abs:data-lazy"),
            dateSel = listOf("span.Date", ".Date", "time", "[class*='date']")
        )
    }
}
