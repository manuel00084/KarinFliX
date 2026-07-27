package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

object LaCartoonsScraper : GenericScraper() {
    override val name = "LaCartoons"
    override val baseUrl = "https://www.lacartoons.com"

    override fun buildSearchUrl(query: String): String =
        "${baseUrl}/?Titulo=${java.net.URLEncoder.encode(query, "UTF-8")}"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        return parseSeriesCards(doc)
    }

    override suspend fun search(query: String): List<Episode> {
        if (query.isBlank()) return emptyList()
        val searchUrl = buildSearchUrl(query)
        Log.d("LaCartoons", "Searching: $searchUrl")
        val doc = withContext(Dispatchers.IO) {
            engine.fetch(searchUrl, name, "${name}::search::${query.lowercase().take(50)}")
        } ?: return emptyList()
        return parseSeriesCards(doc)
    }

    private fun parseSeriesCards(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        // Structure: <a href="/serie/ID"><div class="serie">img + p.nombre-serie + span.marcador-ano</div></a>
        doc.select("div.conjuntos-series > a").forEach { link ->
            try {
                val url = link.attr("abs:href").ifBlank { return@forEach }
                val title = link.selectFirst("p.nombre-serie")?.text()?.trim() ?: return@forEach
                val poster = link.selectFirst("img")?.let { img ->
                    listOf("data-src", "data-lazy-src", "data-original", "abs:src", "src").firstNotNullOfOrNull { attr ->
                        img.attr(attr).ifBlank { null }
                    }
                } ?: ""
                val year = link.selectFirst("span.marcador-ano")?.text()?.trim() ?: ""
                episodes.add(Episode(title, url, poster, year, name))
            } catch (e: Exception) {
                Log.w("LaCartoons", "Error parsing card: ${e.message}")
            }
        }
        return episodes
    }
}
