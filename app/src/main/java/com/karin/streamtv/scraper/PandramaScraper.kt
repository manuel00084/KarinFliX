package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.EpisodeNavigation
import com.karin.streamtv.model.VideoServer
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.util.HtmlClean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Pandrama TV (pandrama.tv, doramas asiatocos en espanol).
 *
 * SPA con datos embebidos en `window.bootstrapData`:
 * - Home: `loaders.channelPage.channel.content.data[]` (secciones) con
 *   `content.data[]` titulos (id, name, slug, poster, type).
 * - Busqueda: `GET /search/{q}` con `loaders.searchPage.results[]`.
 * - Ficha: `/titulo/{id}/{slug}` con `loaders.titlePage` (title + seasons +
 *   episodes con season_number/episode_number).
 * - Temporada: `/titulo/{id}/{slug}/temporada/{s}` (`loaders.seasonPage`).
 * - Episodio: `/titulo/{id}/{slug}/temporada/{s}/episodio/{e}`
 *   con `loaders.episodePage.videos[]` (src + server.display_name).
 * - Watch alterno: `/ver/...` (misma pagina espejo).
 */
object PandramaScraper : GenericScraper() {
    override val name = "Pandrama"
    override val baseUrl = "https://www.pandrama.tv"

    override fun buildSearchUrl(query: String): String =
        "$baseUrl/search/${encodePathSegment(query)}"

    fun titleUrl(id: Int, slug: String): String = "$baseUrl/titulo/$id/$slug"

    fun episodeUrl(id: Int, slug: String, season: Int, ep: Int): String =
        "$baseUrl/titulo/$id/$slug/temporada/$season/episodio/$ep"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        val eps = parseHomeTitles(doc)
        if (eps.isNotEmpty()) return eps
        return DynamicParser.parseDynamic(doc, name)
    }

    override suspend fun search(query: String): List<Episode> {
        if (query.isBlank()) return emptyList()
        val searchUrl = buildSearchUrl(query)
        Log.d("Pandrama", "Searching: $searchUrl")
        val doc = withContext(Dispatchers.IO) {
            engine.fetch(searchUrl, name, "${name}::search::${query.lowercase().take(50)}")
        } ?: return emptyList()
        val eps = parseSearchResults(doc)
        if (eps.isNotEmpty()) return eps
        return DynamicParser.parseDynamic(doc, name)
    }

    /** Home: titulos aplanados de todas las secciones del channel. */
    fun parseHomeTitles(doc: Document): List<Episode> {
        val boot = bootstrap(doc) ?: return emptyList()
        val out = mutableListOf<Episode>()
        try {
            val channel = boot.optJSONObject("loaders")
                ?.optJSONObject("channelPage")?.optJSONObject("channel") ?: return emptyList()
            val sections = channel.optJSONObject("content")?.optJSONArray("data") ?: return emptyList()
            for (i in 0 until sections.length()) {
                val sec = sections.optJSONObject(i) ?: continue
                val titles = sec.optJSONObject("content")?.optJSONArray("data") ?: continue
                for (j in 0 until titles.length()) {
                    titleToEpisode(titles.optJSONObject(j))?.let { out.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w("Pandrama", "parseHomeTitles failed: ${e.message}")
        }
        Log.d("Pandrama", "parseHomeTitles -> ${out.size}")
        return out.distinctBy { it.url }.take(48)
    }

    /** Busqueda: `loaders.searchPage.results[]`. */
    fun parseSearchResults(doc: Document): List<Episode> {
        val boot = bootstrap(doc) ?: return emptyList()
        val out = mutableListOf<Episode>()
        try {
            val results = boot.optJSONObject("loaders")
                ?.optJSONObject("searchPage")?.optJSONArray("results") ?: return emptyList()
            for (i in 0 until results.length()) {
                titleToEpisode(results.optJSONObject(i))?.let { out.add(it) }
            }
        } catch (e: Exception) {
            Log.w("Pandrama", "parseSearchResults failed: ${e.message}")
        }
        Log.d("Pandrama", "parseSearchResults -> ${out.size}")
        return out.distinctBy { it.url }
    }

    private fun titleToEpisode(obj: org.json.JSONObject?, siteName: String = name): Episode? {
        obj ?: return null
        val id = obj.optInt("id", 0)
        val slug = obj.optString("slug").trim()
        val title = HtmlClean.clean(obj.optString("name"))
        if (id <= 0 || slug.isBlank() || title.isBlank()) return null
        val poster = obj.optString("poster").trim()
        val type = obj.optString("type").trim().ifBlank { obj.optString("main_type").trim() }
        return Episode(title, titleUrl(id, slug), poster, type, siteName, "")
    }

    /**
     * Ficha: `loaders.titlePage` (title + episodes con season/episode number)
     * o `loaders.seasonPage` (title + episodes de esa temporada).
     */
    fun fetchSeriesEpisodes(doc: Document, seriesUrl: String, siteName: String = name): List<Episode> {
        val boot = bootstrap(doc) ?: return emptyList()
        return try {
            val loaders = boot.optJSONObject("loaders") ?: return emptyList()
            val page = loaders.optJSONObject("titlePage") ?: loaders.optJSONObject("seasonPage")
            ?: return emptyList()
            val title = page.optJSONObject("title") ?: return emptyList()
            val id = title.optInt("id", 0)
            val slug = title.optString("slug").trim()
            val seriesTitle = HtmlClean.clean(title.optString("name"))
            val cover = title.optString("poster").trim()
            if (id <= 0 || slug.isBlank()) return emptyList()
            // Peli: item unico reproducible.
            if (title.optString("type") == "movie") {
                val url = titleUrl(id, slug)
                return listOf(Episode(seriesTitle.ifBlank { slug }, url, cover, "", siteName, "1"))
            }
            val epsArr = page.optJSONObject("episodes")?.optJSONArray("data")
                ?: page.optJSONArray("episodes") ?: return emptyList()
            val out = mutableListOf<Episode>()
            for (i in 0 until epsArr.length()) {
                val ep = epsArr.optJSONObject(i) ?: continue
                val num = ep.optInt("episode_number", 0)
                val season = ep.optInt("season_number", 1).takeIf { it > 0 } ?: 1
                if (num <= 0) continue
                val label = HtmlClean.clean(ep.optString("name")).ifBlank { "Episodio $num" }
                val epTitle = if (label.contains(seriesTitle, ignoreCase = true)) label
                else "$seriesTitle T$season E$num"
                out.add(
                    Episode(
                        title = epTitle,
                        url = episodeUrl(id, slug, season, num),
                        thumbnailUrl = cover,
                        date = "",
                        siteName = siteName,
                        episodeNum = num.toString()
                    )
                )
            }
            Log.d("Pandrama", "Fetched ${out.size} episodes for $seriesUrl")
            out.distinctBy { it.url }.sortedBy { it.episodeNum.toIntOrNull() ?: 0 }
        } catch (e: Exception) {
            Log.w("Pandrama", "fetchSeriesEpisodes failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun extractServers(episodeUrl: String): List<VideoSource> {
        val doc = withContext(Dispatchers.IO) {
            ScrapingEngine.fetch(episodeUrl, name, "${name}::episode::${episodeUrl.hashCode()}")
        } ?: return emptyList()
        // 1) JSON embebido: episodePage.videos[].
        val fromJson = extractServersFromBootstrap(doc, episodeUrl)
        if (fromJson.isNotEmpty()) return fromJson
        // 2) Respaldo generico (iframes).
        return ServerExtractor.extractServersFromDoc(doc, episodeUrl)
    }

    fun extractServersFromBootstrap(doc: Document, episodeUrl: String): List<VideoSource> {
        val boot = bootstrap(doc) ?: return emptyList()
        val out = mutableListOf<VideoSource>()
        val seen = mutableSetOf<String>()
        try {
            val videos = boot.optJSONObject("loaders")
                ?.optJSONObject("episodePage")?.optJSONArray("videos") ?: return emptyList()
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val src = v.optString("src").trim()
                if (src.isBlank() || src in seen) continue
                if (ServerExtractor.isAdUrl(src)) continue
                seen.add(src)
                val serverObj = v.optJSONObject("server")
                val label = HtmlClean.clean(serverObj?.optString("display_name").orEmpty())
                    .ifBlank { serverObj?.optString("name").orEmpty().trim() }
                val lang = v.optString("language").trim()
                val server = VideoServer.detectServer(src)
                val display = when {
                    label.isNotBlank() && lang.isNotBlank() -> "$label ($lang)"
                    label.isNotBlank() -> label
                    else -> server.displayName
                }
                out.add(
                    VideoSource(
                        name = display,
                        serverUrl = src,
                        supportsResolutionChange = server.supportsResolution,
                        speedRating = server.speedRating
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("Pandrama", "extractServersFromBootstrap failed: ${e.message}")
        }
        Log.d("Pandrama", "Extracted ${out.size} servers from $episodeUrl")
        return out.distinctBy { it.serverUrl }
    }

    override suspend fun scrapeEpisodeNavigation(episodeUrl: String): EpisodeNavigation {
        return try {
            val doc = withContext(Dispatchers.IO) {
                ScrapingEngine.fetch(episodeUrl, name, "${name}::nav::${episodeUrl.hashCode()}")
            } ?: return super.scrapeEpisodeNavigation(episodeUrl)
            val boot = bootstrap(doc) ?: return super.scrapeEpisodeNavigation(episodeUrl)
            val epPage = boot.optJSONObject("loaders")?.optJSONObject("episodePage")
                ?: return super.scrapeEpisodeNavigation(episodeUrl)
            val title = epPage.optJSONObject("title") ?: return super.scrapeEpisodeNavigation(episodeUrl)
            val ep = epPage.optJSONObject("episode") ?: return super.scrapeEpisodeNavigation(episodeUrl)
            val id = title.optInt("id", 0)
            val slug = title.optString("slug").trim()
            val season = ep.optInt("season_number", 1).takeIf { it > 0 } ?: 1
            val num = ep.optInt("episode_number", 0)
            val total = epPage.optInt("episodes_count", 0)
            if (id <= 0 || slug.isBlank() || num <= 0) return super.scrapeEpisodeNavigation(episodeUrl)
            val prev = if (num > 1) episodeUrl(id, slug, season, num - 1) else null
            val next = if (total <= 0 || num < total) episodeUrl(id, slug, season, num + 1) else null
            if (prev == null && next == null) return super.scrapeEpisodeNavigation(episodeUrl)
            EpisodeNavigation(prevUrl = prev, nextUrl = next)
        } catch (e: Exception) {
            Log.w("Pandrama", "nav failed: ${e.message}")
            super.scrapeEpisodeNavigation(episodeUrl)
        }
    }

    /** Extrae `window.bootstrapData = {...}` con parseo de llaves balanceadas. */
    fun bootstrap(doc: Document): org.json.JSONObject? {
        val html = doc.toString()
        val marker = "window.bootstrapData"
        var idx = html.indexOf(marker)
        if (idx < 0) return null
        idx = html.indexOf('{', idx)
        if (idx < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in idx until html.length) {
            val ch = html[i]
            when {
                inString -> {
                    if (escaped) escaped = false
                    else when (ch) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
                ch == '"' -> inString = true
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) {
                        return try {
                            org.json.JSONObject(html.substring(idx, i + 1))
                        } catch (e: Exception) {
                            Log.w("Pandrama", "bootstrap JSON parse failed: ${e.message}")
                            null
                        }
                    }
                }
            }
        }
        return null
    }
}

class PandramaScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = PandramaScraper
}
