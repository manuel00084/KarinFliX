package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

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

    override suspend fun search(query: String): List<Episode> {
        if (query.isBlank()) return emptyList()
        val searchUrl = buildSearchUrl(query)
        Log.d("DoramasYT", "Searching: $searchUrl")
        val doc = withContext(Dispatchers.IO) {
            engine.fetch(searchUrl, name, "${name}::search::${query.lowercase().take(50)}")
        } ?: return emptyList()
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

    override suspend fun extractServers(episodeUrl: String): List<VideoSource> {
        val doc = withContext(Dispatchers.IO) {
            ScrapingEngine.fetch(episodeUrl, name, "${name}::episode::${episodeUrl.hashCode()}")
        } ?: return emptyList()
        return extractDoramaYtServers(doc, episodeUrl)
    }

    private fun extractDoramaYtServers(doc: Document, episodeUrl: String): List<VideoSource> {
        val servers = mutableListOf<VideoSource>()
        val seen = mutableSetOf<String>()

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { return@forEach }
            if (src in seen || ServerExtractor.isAdUrl(src)) return@forEach
            seen.add(src)
            val server = com.karin.streamtv.model.VideoServer.detectServer(src)
            servers.add(VideoSource(
                name = server.displayName,
                serverUrl = src,
                supportsResolutionChange = server.supportsResolution,
                speedRating = server.speedRating
            ))
        }

        doc.select("[data-player]").forEach { el ->
            val encoded = el.attr("data-player").ifBlank { return@forEach }
            val decoded = try {
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                String(bytes)
            } catch (e: Exception) { null }
            if (decoded.isNullOrBlank() || decoded in seen) return@forEach
            if (ServerExtractor.isAdUrl(decoded)) return@forEach
            seen.add(decoded)
            val server = com.karin.streamtv.model.VideoServer.detectServer(decoded)
            servers.add(VideoSource(
                name = server.displayName,
                serverUrl = decoded,
                supportsResolutionChange = server.supportsResolution,
                speedRating = server.speedRating
            ))
        }

        if (servers.isEmpty()) {
            val tabContainers = doc.select("[class*='server'], [class*='option'], [class*='tab'], ul.nav, .nav-tabs")
            tabContainers.forEach { container ->
                container.select("a, button").forEach { tab ->
                    val text = tab.text().trim().lowercase()
                    if (text.isBlank() || text in seen) return@forEach
                    val server = com.karin.streamtv.model.VideoServer.detectServer(text)
                    if (server == com.karin.streamtv.model.VideoServer.GENERIC) return@forEach
                    seen.add(text)
                    val tabUrl = tab.attr("abs:href").ifBlank {
                        tab.attr("data-url").ifBlank {
                            tab.attr("data-link").ifBlank { null }
                        }
                    }
                    if (tabUrl != null && tabUrl !in seen) {
                        seen.add(tabUrl)
                        servers.add(VideoSource(
                            name = server.displayName,
                            serverUrl = tabUrl,
                            supportsResolutionChange = server.supportsResolution,
                            speedRating = server.speedRating
                        ))
                    }
                }
            }
        }

        Log.d("DoramasYT", "Extracted ${servers.size} server(s) from episode page")
        return servers.distinctBy { it.serverUrl }
    }
}

class DoramaYtScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = DoramaYtScraper
}