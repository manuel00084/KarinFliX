package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.EpisodeNavigation
import com.karin.streamtv.model.VideoServer
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.util.HtmlClean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * DonghuaLife (donghualife.com, Drupal).
 *
 * Estructura verificada en vivo:
 * - Home ultimos episodios: `div.episode` con `div.imagen a[href*="/episode/"]`,
 *   `img[src]`, `div.titulo` (serie + temp) y `div.subtitulo` (Episodio N).
 * - Busqueda: `GET /search?search_api_fulltext=q` con `div.serie`
 *   (`div.imagen a[href*="/series/" o "/movie/"]`, `img`, `div.titulo`).
 * - Serie `/series/slug` -> temporadas `/season/...` (`div.temporada div.serie`).
 * - Temporada `/season/slug` -> tabla `div.episodios table tbody tr` con
 *   `td a[href*="/episode/"]`, `th` numero y `time` fecha.
 * - Episodio `/episode/...` y peli `/movie/...`: player `ul.embed-links
 *   a.toggle-enlace[data-video]` + `iframe#iframe-episode[src]`,
 *   nav `a.prev-episode` / `a.next-episode` / `a.home-serie`.
 */
object DonghuaLifeScraper : GenericScraper() {
    override val name = "DonghuaLife"
    override val baseUrl = "https://donghualife.com"

    override fun buildSearchUrl(query: String): String =
        "$baseUrl/search?search_api_fulltext=${java.net.URLEncoder.encode(query, "UTF-8")}"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        val eps = parseEpisodeCards(doc)
        if (eps.isNotEmpty()) return eps
        return DynamicParser.parseDynamic(doc, name)
    }

    override suspend fun search(query: String): List<Episode> {
        if (query.isBlank()) return emptyList()
        val searchUrl = buildSearchUrl(query)
        Log.d("DonghuaLife", "Searching: $searchUrl")
        val doc = withContext(Dispatchers.IO) {
            engine.fetch(searchUrl, name, "${name}::search::${query.lowercase().take(50)}")
        } ?: return emptyList()
        val eps = parseSeriesCards(doc)
        if (eps.isNotEmpty()) return eps
        return DynamicParser.parseDynamic(doc, name)
    }

    /** Home y tablas generales: tarjetas `div.episode` -> /episode/. */
    fun parseEpisodeCards(doc: Document): List<Episode> {
        val out = mutableListOf<Episode>()
        doc.select("div.episode").forEach { card ->
            try {
                val link = card.selectFirst("div.imagen a[href]") ?: return@forEach
                val url = HtmlClean.resolveUrl(doc.baseUri(), link.attr("href"))
                if (url.isBlank() || !url.contains("/episode/")) return@forEach
                val series = HtmlClean.clean(
                    card.selectFirst("div.titulo")?.text().orEmpty()
                        .ifBlank { link.selectFirst("img")?.attr("alt").orEmpty() }
                )
                if (series.isBlank()) return@forEach
                val sub = card.selectFirst("div.subtitulo")?.text().orEmpty().trim()
                val epNum = Regex("""(\d+)""").find(sub)?.groupValues?.get(1).orEmpty()
                val title = if (epNum.isNotBlank()) "$series Episodio $epNum" else series
                val thumb = card.selectFirst("div.imagen img")?.let {
                    HtmlClean.resolveUrl(doc.baseUri(), it.attr("abs:src").ifBlank { it.attr("src") })
                }.orEmpty()
                out.add(Episode(title, url, thumb, "", name, epNum))
            } catch (e: Exception) {
                Log.w("DonghuaLife", "episode card failed: ${e.message}")
            }
        }
        Log.d("DonghuaLife", "parseEpisodeCards -> ${out.size}")
        return out.distinctBy { it.url }
    }

    /** Busqueda/directorio: tarjetas `div.serie` -> /series/ o /movie/. */
    fun parseSeriesCards(doc: Document): List<Episode> {
        val out = mutableListOf<Episode>()
        doc.select("div.serie").forEach { card ->
            try {
                val link = card.selectFirst("div.imagen a[href]") ?: return@forEach
                val url = HtmlClean.resolveUrl(doc.baseUri(), link.attr("href"))
                if (url.isBlank()) return@forEach
                if (!url.contains("/series/") && !url.contains("/movie/") && !url.contains("/season/")) return@forEach
                val title = HtmlClean.clean(
                    card.selectFirst("div.titulo")?.text().orEmpty()
                        .ifBlank { link.selectFirst("img")?.attr("alt").orEmpty() }
                        .ifBlank { link.attr("title") }
                )
                if (title.isBlank()) return@forEach
                val thumb = card.selectFirst("div.imagen img")?.let {
                    HtmlClean.resolveUrl(doc.baseUri(), it.attr("abs:src").ifBlank { it.attr("src") })
                }.orEmpty()
                val date = HtmlClean.clean(card.selectFirst("div.fecha")?.text().orEmpty())
                out.add(Episode(title, url, thumb, date, name, ""))
            } catch (e: Exception) {
                Log.w("DonghuaLife", "series card failed: ${e.message}")
            }
        }
        Log.d("DonghuaLife", "parseSeriesCards -> ${out.size}")
        return out.distinctBy { it.url }
    }

    /**
     * Ficha de serie/temporada.
     * - `/season/`: tabla `div.episodios table tbody tr` directa.
     * - `/series/`: agrega episodios de cada temporada (`div.temporada div.serie`).
     * - `/movie/`: item unico reproducible.
     */
    suspend fun fetchSeriesEpisodes(doc: Document, seriesUrl: String, siteName: String = name): List<Episode> {
        // Temporada: tabla directa.
        val tableEps = parseSeasonTable(doc, seriesUrl, siteName)
        if (tableEps.isNotEmpty()) return tableEps

        val isSeries = seriesUrl.contains("/series/")
        if (isSeries) {
            val seasonLinks = doc.select("div.temporada div.serie div.imagen a[href*=\"/season/\"]")
                .map { HtmlClean.resolveUrl(doc.baseUri(), it.attr("href")) }
                .filter { it.isNotBlank() }
                .distinct()
            if (seasonLinks.isNotEmpty()) {
                val cover = findCover(doc)
                val results = coroutineScope {
                    seasonLinks.take(10).map { url ->
                        async {
                            try {
                                val sdoc = withContext(Dispatchers.IO) {
                                    ScrapingEngine.fetch(url, siteName, "$siteName::season::${url.hashCode()}")
                                } ?: return@async emptyList<Episode>()
                                parseSeasonTable(sdoc, url, siteName, fallbackCover = cover)
                            } catch (e: Exception) {
                                Log.w("DonghuaLife", "season fetch failed $url: ${e.message}")
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
                val all = results.flatten().distinctBy { it.url }
                Log.d("DonghuaLife", "Fetched ${all.size} episodes from ${seasonLinks.size} seasons ($seriesUrl)")
                if (all.isNotEmpty()) return all
                // Sin tabla (serie de 1 temporada sin listar): devuelve temporadas navegables.
                return seasonLinks.mapIndexed { idx, url ->
                    val label = try {
                        java.net.URI(url).path.trim('/').substringAfterLast('/').replace("-", " ")
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    } catch (_: Exception) { "Temporada ${idx + 1}" }
                    Episode(label, url, cover, "", siteName, "")
                }
            }
        }

        // Peli: item unico.
        if (seriesUrl.contains("/movie/")) {
            val title = HtmlClean.clean(doc.selectFirst("h1")?.text().orEmpty().ifBlank { doc.title() })
            if (title.isNotBlank()) {
                return listOf(Episode(title, seriesUrl, findCover(doc), "", siteName, "1"))
            }
        }
        return emptyList()
    }

    /** Tabla de temporada `div.episodios table tbody tr`. */
    fun parseSeasonTable(
        doc: Document,
        pageUrl: String,
        siteName: String = name,
        fallbackCover: String = ""
    ): List<Episode> {
        val rows = doc.select("div.episodios table tbody tr")
        if (rows.isEmpty()) return emptyList()
        val cover = findCover(doc).ifBlank { fallbackCover }
        val seriesTitle = HtmlClean.clean(
            doc.selectFirst("h1")?.text().orEmpty().ifBlank {
                doc.selectFirst(".titulo")?.text().orEmpty()
            }
        )
        val out = mutableListOf<Episode>()
        rows.forEach { tr ->
            try {
                val link = tr.selectFirst("td a[href*=\"/episode/\"]") ?: return@forEach
                val url = HtmlClean.resolveUrl(doc.baseUri(), link.attr("href"))
                if (url.isBlank()) return@forEach
                val num = HtmlClean.clean(tr.selectFirst("th")?.text().orEmpty())
                    .ifBlank { Regex("""(\d+)""").find(link.text())?.groupValues?.get(1).orEmpty() }
                val date = tr.selectFirst("time")?.text()?.trim().orEmpty()
                val epLabel = link.text().trim().ifBlank { if (num.isNotBlank()) "Episodio $num" else url.substringAfterLast("/") }
                val title = if (seriesTitle.isNotBlank() && !epLabel.contains(seriesTitle, ignoreCase = true)) {
                    if (num.isNotBlank()) "$seriesTitle Episodio $num" else "$seriesTitle $epLabel"
                } else epLabel
                out.add(Episode(title, url, cover, date, siteName, num))
            } catch (e: Exception) {
                Log.w("DonghuaLife", "season row failed: ${e.message}")
            }
        }
        Log.d("DonghuaLife", "parseSeasonTable($pageUrl) -> ${out.size}")
        return out.distinctBy { it.url }
    }

    private fun findCover(doc: Document): String {
        doc.selectFirst("meta[property='og:image']")?.attr("content")?.let {
            if (it.isNotBlank()) return HtmlClean.resolveUrl(doc.baseUri(), it)
        }
        // Ficha serie/temporada: primera imagen de poster (no emoji/icono).
        doc.select("img[src*=\"/sites/default/files/\"]").forEach { img ->
            val src = img.attr("abs:src").ifBlank { img.attr("src") }
            if (src.isNotBlank() && !src.contains("IcoPrueba")) return HtmlClean.resolveUrl(doc.baseUri(), src)
        }
        return ""
    }

    override suspend fun extractServers(episodeUrl: String): List<VideoSource> {
        val doc = withContext(Dispatchers.IO) {
            ScrapingEngine.fetch(episodeUrl, name, "${name}::episode::${episodeUrl.hashCode()}")
        } ?: return emptyList()
        val servers = mutableListOf<VideoSource>()
        val seen = mutableSetOf<String>()

        // Tabs del player: <a class="toggle-enlace" data-video="URL">Nombre</a>
        doc.select("ul.embed-links a.toggle-enlace[data-video], a.toggle-enlace[data-video]").forEach { a ->
            val raw = a.attr("data-video").trim()
            if (raw.isBlank() || raw in seen) return@forEach
            if (ServerExtractor.isAdUrl(raw)) return@forEach
            seen.add(raw)
            val label = HtmlClean.clean(a.text()).ifBlank { a.attr("title").trim() }
            val server = VideoServer.detectServer(raw)
            servers.add(
                VideoSource(
                    name = label.ifBlank { server.displayName },
                    serverUrl = raw,
                    supportsResolutionChange = server.supportsResolution,
                    speedRating = server.speedRating
                )
            )
        }

        // iframe principal como respaldo.
        doc.select("iframe#iframe-episode[src], div#video-container iframe[src], iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { return@forEach }
            if (src in seen || ServerExtractor.isAdUrl(src)) return@forEach
            seen.add(src)
            val server = VideoServer.detectServer(src)
            servers.add(
                VideoSource(
                    name = server.displayName,
                    serverUrl = src,
                    supportsResolutionChange = server.supportsResolution,
                    speedRating = server.speedRating
                )
            )
        }

        if (servers.isEmpty()) {
            return ServerExtractor.extractServersFromDoc(doc, episodeUrl)
        }
        Log.d("DonghuaLife", "Extracted ${servers.size} servers from $episodeUrl")
        return servers.distinctBy { it.serverUrl }
    }

    override suspend fun scrapeEpisodeNavigation(episodeUrl: String): EpisodeNavigation {
        return try {
            val doc = withContext(Dispatchers.IO) {
                ScrapingEngine.fetch(episodeUrl, name, "${name}::nav::${episodeUrl.hashCode()}")
            } ?: return super.scrapeEpisodeNavigation(episodeUrl)
            val prev = doc.selectFirst("a.prev-episode[href]")?.attr("abs:href")?.takeIf { it.isNotBlank() }
            val next = doc.selectFirst("a.next-episode[href]")?.attr("abs:href")?.takeIf { it.isNotBlank() }
            if (prev == null && next == null) return super.scrapeEpisodeNavigation(episodeUrl)
            EpisodeNavigation(prevUrl = prev, nextUrl = next)
        } catch (e: Exception) {
            Log.w("DonghuaLife", "nav failed: ${e.message}")
            super.scrapeEpisodeNavigation(episodeUrl)
        }
    }
}

class DonghuaLifeScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = DonghuaLifeScraper
}
