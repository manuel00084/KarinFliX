package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object RetroTVEscraper : BaseScraper {
    override val name = "RetroTVE"
    override val baseUrl = "https://retrotve.com"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        return parseRetroTVECards(doc)
    }

    override suspend fun search(query: String): List<Episode> {
        if (query.isBlank()) return emptyList()
        val searchUrl = "${baseUrl}/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        Log.d("RetroTVE", "Searching: $searchUrl")
        val doc = withContext(Dispatchers.IO) {
            ScrapingEngine.fetch(searchUrl, name, "${name}::search::${query.lowercase().take(50)}")
        } ?: return emptyList()
        return parseRetroTVECards(doc)
    }

    override suspend fun extractServers(episodeUrl: String): List<VideoSource> {
        val doc = withContext(Dispatchers.IO) {
            ScrapingEngine.fetch(episodeUrl, name, "${name}::episode::${episodeUrl.hashCode()}")
        } ?: return emptyList()
        return extractRetroTVEServers(doc, episodeUrl)
    }

    private suspend fun fetchDocument(url: String? = null): Document? {
        val target = url ?: baseUrl
        return withContext(Dispatchers.IO) {
            ScrapingEngine.fetch(target, name, "${name}::home")
        }
    }

    private fun parseRetroTVECards(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val cardSelectors = listOf(
            "article.TPost",
            "div.TPost",
            "div.post",
            "article",
            ".card",
            "div[class*='item']:has(a[href] img)",
            "div[class*='col']:has(a[href] img)"
        )

        var cards: List<Element> = emptyList()
        for (sel in cardSelectors) {
            val found = doc.select(sel)
            if (found.isNotEmpty()) {
                cards = found
                Log.d("RetroTVE", "Found ${cards.size} card(s) with selector '$sel'")
                break
            }
        }

        if (cards.isEmpty()) {
            Log.w("RetroTVE", "No cards found with known selectors, trying dynamic parser")
            return DynamicParser.parseDynamic(doc, name)
        }

        cards.forEachIndexed { i, card ->
            try {
                val urlEl = card.selectFirst("a[href]") ?: return@forEachIndexed
                val url = urlEl.attr("abs:href").ifBlank { return@forEachIndexed }
                if (url == baseUrl || url.contains("#")) return@forEachIndexed

                val titleEl = card.selectFirst("h2, h3, h4, .Title, .title, [class*='title']")
                    ?: urlEl.selectFirst("img")?.let { img ->
                        img.attr("alt").takeIf { it.isNotBlank() }?.let { titleText ->
                            // Create a temporary element to hold the title
                            null
                        }
                    }
                val title = titleEl?.text()?.trim()
                    ?: urlEl.text().trim()
                    ?: url.substringAfterLast("/").replace("-", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                if (title.isBlank()) return@forEachIndexed

                val thumbEl = card.selectFirst("img.wp-post-image")
                    ?: card.selectFirst("img[src]")
                    ?: card.selectFirst("img[data-src]")
                    ?: card.selectFirst("img[data-lazy-src]")
                    ?: card.selectFirst("img[data-original]")
                    ?: card.selectFirst("img[data-echo]")
                val thumb = thumbEl?.let { img ->
                    listOf("data-src", "data-lazy-src", "data-original", "data-echo", "abs:src", "src")
                        .firstNotNullOfOrNull { attr -> img.attr(attr).ifBlank { null } }
                } ?: ""

                val dateEl = card.selectFirst("span.Date, .Date, time, [class*='date'], [class*='fecha']")
                val date = dateEl?.text()?.trim() ?: ""

                val epNumEl = card.selectFirst("span.Capi, [class*='ep'], [class*='cap']")
                val epNum = epNumEl?.text()?.trim() ?: ""

                episodes.add(Episode(title, url, thumb, date, name, epNum))
            } catch (e: Exception) {
                Log.w("RetroTVE", "Failed to parse card #$i: ${e.message}")
            }
        }

        Log.i("RetroTVE", "Extracted ${episodes.size} episode(s)")
        return episodes
    }

    private fun extractRetroTVEServers(doc: Document, episodeUrl: String): List<VideoSource> {
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
            val tabContainers = doc.select(
                "[class*='server'], [class*='option'], [class*='tab'], " +
                "ul.nav, .nav-tabs, [id*='server'], [id*='option']"
            )
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

        if (servers.isEmpty()) {
            val allLinks = doc.select("a[href]").filter { a ->
                val href = a.attr("abs:href")
                href.isNotBlank() && !href.contains("#") && !href.contains("javascript:") &&
                !ServerExtractor.isAdUrl(href) &&
                (href.contains("player") || href.contains("embed") || href.contains("server") ||
                 href.contains("video") || href.contains("/e/") || href.contains("/play/"))
            }
            allLinks.forEach { link ->
                val href = link.attr("abs:href")
                if (href in seen) return@forEach
                seen.add(href)
                val server = com.karin.streamtv.model.VideoServer.detectServer(href)
                servers.add(VideoSource(
                    name = server.displayName,
                    serverUrl = href,
                    supportsResolutionChange = server.supportsResolution,
                    speedRating = server.speedRating
                ))
            }
        }

        Log.d("RetroTVE", "Extracted ${servers.size} server(s) from episode page")
        return servers.distinctBy { it.serverUrl }
    }
}

class RetroTVEscraperProvider : ScraperProvider {
    override val scraper: BaseScraper = RetroTVEscraper
}