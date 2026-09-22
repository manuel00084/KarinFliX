package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.util.HtmlClean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

object LaCartoonsScraper : GenericScraper() {
    override val name = "LaCartoons"
    override val baseUrl = "https://www.lacartoons.com"

    override fun buildSearchUrl(query: String): String =
        "${baseUrl}/?Titulo=${java.net.URLEncoder.encode(query, "UTF-8")}"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument("${baseUrl}/") ?: return emptyList()
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

    /** Directorio/portada/búsqueda: tarjetas `div.conjuntos-series > a` (/serie/N). */
    fun parseDirectory(doc: Document): List<Episode> = parseSeriesCards(doc)

    /** Título de la ficha (`h2.subtitulo-serie-seccion`), sin el canal. */
    fun fetchSeriesTitle(doc: Document): String =
        HtmlClean.clean(doc.selectFirst("h2.subtitulo-serie-seccion")?.ownText().orEmpty())

    /** Reseña de la ficha (p "Reseña:" > span). */
    fun fetchSeriesDescription(doc: Document): String {
        val info = doc.select(".informacion-serie-seccion p")
        val resena = info.firstOrNull { HtmlClean.clean(it.ownText()).startsWith("Rese", ignoreCase = true) }
        val span = resena?.selectFirst("span")?.text().orEmpty().trim()
        return if (span.length > 10) span else ""
    }

    /**
     * Ficha de serie (/serie/N): episodios renderizados en servidor como
     * `ul.listas-de-episodion a[href="/serie/capitulo/M?t=S"]`.
     */
    fun fetchSeriesEpisodes(doc: Document, seriesUrl: String, siteName: String = name): List<Episode> {
        val poster = doc.selectFirst(".imagen-serie img")?.attr("abs:src").orEmpty()
        val out = mutableListOf<Episode>()
        doc.select("ul.listas-de-episodion a[href*=\"/serie/capitulo/\"]").forEach { a ->
            val url = a.attr("abs:href").ifBlank { return@forEach }
            val raw = HtmlClean.clean(a.text())
            if (raw.isBlank()) return@forEach
            val num = Regex("""Capitulo\s*(\d+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
                ?: Regex("""/capitulo/(\d+)""").find(url)?.groupValues?.get(1)
                ?: return@forEach
            val season = try {
                java.net.URI(url).query?.let { q ->
                    Regex("""(?:^|&)t=(\d+)""").find(q)?.groupValues?.get(1)
                }.orEmpty()
            } catch (_: Exception) { "" }
            val title = if (season.isNotBlank() && season != "1") "T$season · $raw" else raw
            out.add(Episode(
                title = title,
                url = url,
                episodeNum = num,
                thumbnailUrl = poster,
                siteName = siteName
            ))
        }
        Log.d("LaCartoons", "Fetched ${out.size} episodes for $seriesUrl")
        return out.distinctBy { it.url }
    }

    private fun parseSeriesCards(doc: Document): List<Episode> {        val episodes = mutableListOf<Episode>()
        doc.select("div.conjuntos-series > a").forEach { link ->
            try {
                val url = link.attr("abs:href").ifBlank { return@forEach }
                val title = link.selectFirst("p.nombre-serie")?.text()?.trim() ?: return@forEach
                val poster = link.selectFirst("img")?.let { img ->
                    val raw = listOf("data-src", "data-lazy-src", "data-original", "src").firstNotNullOfOrNull { attr ->
                        img.attr(attr).ifBlank { null }
                    } ?: img.attr("abs:src")
                    HtmlClean.resolveUrl(doc.baseUri(), raw.orEmpty())
                } ?: ""
                val year = link.selectFirst("span.marcador-ano")?.text()?.trim() ?: ""
                episodes.add(Episode(title, url, poster, year, name))
            } catch (e: Exception) {
                Log.w("LaCartoons", "Error parsing card: ${e.message}")
            }
        }
        if (episodes.isEmpty()) {
            Log.w("LaCartoons", "No cards with div.conjuntos-series > a, trying dynamic parser")
            return DynamicParser.parseDynamic(doc, name)
        }
        return episodes
    }

    override suspend fun extractServers(episodeUrl: String): List<VideoSource> {
        val doc = withContext(Dispatchers.IO) {
            ScrapingEngine.fetch(episodeUrl, name, "${name}::episode::${episodeUrl.hashCode()}")
        } ?: return emptyList()
        return extractLaCartoonsServers(doc, episodeUrl)
    }

    private fun extractLaCartoonsServers(doc: Document, episodeUrl: String): List<VideoSource> {
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

        Log.d("LaCartoons", "Extracted ${servers.size} server(s) from episode page")
        return servers.distinctBy { it.serverUrl }
    }
}

class LaCartoonsScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = LaCartoonsScraper
}
