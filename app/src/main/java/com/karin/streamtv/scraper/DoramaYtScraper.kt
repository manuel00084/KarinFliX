package com.karin.streamtv.scraper

import android.util.Log
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
            cardSel = listOf(
                "div.col-6.col-md-6.col-lg-3.mb-3",
                "div.col-6.col-md-4.col-lg-3",
                "div.mb-3"
            ),
            titleSel = listOf("h2.mt-3", "h3", "h4"),
            thumbSel = listOf("img.lozad.nxtmainimg", "img.lozad"),
            thumbAttrs = listOf("data-src", "abs:src"),
            dateSel = listOf("span.span-tiempo"),
            epNumSelector = emptyList(),
            episodeExtractor = { card ->
                val title = card.selectFirst("h2.mt-3")?.text()?.trim() ?: ""
                val match = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(title)
                match?.groupValues?.get(1) ?: ""
            }
        )
    }
}

class DoramaYtScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = DoramaYtScraper
}
