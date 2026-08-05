package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.model.VideoServer
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * JKAnime – Homepage: rows of cards under div.trending__anime div.tab-content div.tab-pane#animes
 *
 * HTML structure:
 *   <div class="mb-4 d-flex align-items-stretch mb-3 dir1">
 *     <div class="card ml-2 mr-2">
 *       <a href="{episode-url}">
 *         <div class="d-thumb">
 *           <img class="card-img-top" src="{thumb}" data-animepic="..." alt="..."/>
 *           <div class="badges badges-top">
 *             <span class="badge badge-primary">Ep {num}</span>
 *             <span class="badge badge-secondary"><i class="ti ti-clock-hour-5"></i> {date}</span>
 *           </div>
 *         </div>
 *         <div class="card-body d-flex flex-column">
 *           <h5 class="strlimit card-title">{title}</h5>
 *         </div>
 *       </a>
 *     </div>
 *   </div>
 */
object JKAnimeScraper : GenericScraper() {
    override val name = "JKAnime"
    override val baseUrl = "https://jkanime.net"

    private const val MAX_EPISODE_PAGES = 30

    override fun buildSearchUrl(query: String): String =
        "${baseUrl}/buscar/${java.net.URLEncoder.encode(query, "UTF-8")}/"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        return parseEpisodeCards(
            doc = doc,
            cardSel = listOf(
                "div.trending__anime div.tab-pane#animes div.mb-4.d-flex.align-items-stretch.mb-3.dir1",
                "div.trending__anime div.tab-pane div.mb-4",
                "div.tab-pane.active div.mb-4",
                "div.mb-4.d-flex",
                "div.d-flex.align-items-stretch"
            ),
            titleSel = listOf("h5.strlimit.card-title", "h5.card-title", "h4", ".card-title", "[class*='title']"),
            thumbSel = listOf("img.card-img-top", "img[src]", "img[data-src]"),
            thumbAttrs = listOf("abs:src", "data-src", "data-lazy-src", "data-original", "data-animepic"),
            dateSel = listOf("span.badge.badge-secondary", ".badge-secondary", "[class*='date']", "time"),
            epNumSelector = emptyList(),
            episodeExtractor = { card ->
                // First try: badge with episode number (e.g., "Ep 123")
                val badgeEp = card.selectFirst("span.badge.badge-primary")?.text()?.trim()
                if (!badgeEp.isNullOrBlank()) {
                    val match = Regex("""(\d+)""").find(badgeEp!!)
                    match?.groupValues?.get(1)?.let { return@parseEpisodeCards it }
                }
                // Fallback: extract from title (e.g., "One Piece Episodio 1000")
                val title = card.selectFirst("h5.strlimit.card-title, h5.card-title, h4, .card-title, [class*='title']")?.text()?.trim() ?: ""
                val match = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(title)
                match?.groupValues?.get(1) ?: DynamicParser.findEpisodeNum(card)
            }
        )
    }

    /**
     * JKAnime renders its episode list client-side into #episodes-content via
     * `POST https://jkanime.net/ajax/episodes/{id}/{page}` (Laravel paginator, 16/page, CSRF protected).
     * The numeric series id, slug and CSRF token are extracted from the series page markup.
     */
    suspend fun fetchSeriesEpisodes(doc: Document, seriesUrl: String, siteName: String): List<Episode> {
        val html = doc.toString()
        val token = Regex("""<meta name="csrf-token" content="([^"]+)""")
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()

        val id = Regex("""ajax/episodes/(\d+)/""").find(html)?.groupValues?.get(1)
            ?: Regex("""anime_checks\('[^']+',\s*'(\d+)'""").find(html)?.groupValues?.get(1)
            ?: ""
        if (id.isBlank() || token.isBlank()) {
            Log.w("JKAnimeScraper", "fetchSeriesEpisodes: missing id/token (id='$id' token='$token')")
            return emptyList()
        }

        val origin = try {
            val uri = java.net.URI(seriesUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) { baseUrl }
        val slug = try {
            java.net.URI(seriesUrl).path.trim('/').substringBeforeLast('/')
        } catch (_: Exception) { "" }
        if (slug.isBlank()) {
            Log.w("JKAnimeScraper", "fetchSeriesEpisodes: cannot derive slug from $seriesUrl")
            return emptyList()
        }

        // Replicate the page's cdnthumb: cover /animes/image/{dir}/ -> /animes/video/image_thumb/{dir}/
        val cdnThumb = doc.selectFirst(".anime_pic img")?.attr("abs:src")
            ?.replace("/animes/image/", "/animes/video/image_thumb/")
            ?.substringBeforeLast("/")?.plus("/").orEmpty()

        val episodes = mutableListOf<Episode>()
        var lastPage = 1

        fun parsePage(body: String) {
            val json = try { org.json.JSONObject(body) } catch (e: Exception) {
                Log.w("JKAnimeScraper", "episodes JSON parse failed: ${e.message}")
                return
            }
            val arr = json.optJSONArray("data") ?: return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val number = obj.optInt("number", 0)
                if (number <= 0) continue
                val title = obj.optString("title").trim().ifBlank { "$slug - $number" }
                val img = obj.optString("image").trim()
                episodes.add(Episode(
                    title = title,
                    url = "$origin/$slug/$number/",
                    episodeNum = number.toString(),
                    thumbnailUrl = if (cdnThumb.isNotBlank() && img.isNotBlank()) cdnThumb + img else "",
                    siteName = siteName
                ))
            }
            lastPage = json.optInt("last_page", 1)
        }

        var page = 1
        var refreshed = false
        while (page <= MAX_EPISODE_PAGES) {
            var body = ScrapingEngine.postForm(
                url = "$origin/ajax/episodes/$id/$page",
                form = mapOf("_token" to token),
                csrfToken = token,
                siteName = siteName
            )
            if (body.isNullOrBlank() && !refreshed) {
                // Session likely expired -> refresh the series page to obtain a fresh CSRF token and retry once.
                refreshed = true
                val fresh = ScrapingEngine.fetch("$origin/$slug/", siteName, null, forceFresh = true)
                val freshToken = fresh?.toString()
                    ?.let { Regex("""<meta name="csrf-token" content="([^"]+)""").find(it)?.groupValues?.get(1)?.trim() }
                    .orEmpty()
                if (freshToken.isNotBlank()) {
                    body = ScrapingEngine.postForm(
                        url = "$origin/ajax/episodes/$id/$page",
                        form = mapOf("_token" to freshToken),
                        csrfToken = freshToken,
                        siteName = siteName
                    )
                }
            }
            if (body.isNullOrBlank()) break
            parsePage(body)
            page++
            if (page > lastPage) break
        }

        Log.d("JKAnimeScraper", "Fetched ${episodes.size} episodes for '$slug' (id=$id)")
        return episodes.distinctBy { it.url }
    }

    override suspend fun extractServers(episodeUrl: String): List<VideoSource> {
        val doc = fetchDocument(episodeUrl) ?: return emptyList()
        val servers = mutableListOf<VideoSource>()
        val seen = mutableSetOf<String>()

        // JKAnime embeds the real server list as `var servers = [{"remote":"<base64>","slug":"...","server":"Nombre",...}]`
        // inside a <script>. This is the primary source — the tab links are rendered from it via JS.
        extractJkServersFromScript(doc, seen, servers)

        // JKAnime uses tabs with data-server or onclick to load iframes
        // Pattern 1: <a class="option" data-server="server-name" data-src="iframe-url">
        doc.select("a.option[data-server], a.option[data-src], .option[data-server], .option[data-src]").forEach { link ->
            val serverName = link.attr("data-server").ifBlank { link.attr("data-option") }
                .ifBlank { link.text().trim() }
            val src = link.attr("data-src").ifBlank { link.attr("data-iframe") }.ifBlank { link.attr("href") }
            if (src.isNotBlank() && src.startsWith("http") && src !in seen) {
                seen.add(src)
                val server = VideoServer.detectServer(src)
                servers.add(VideoSource(
                    name = serverName.ifBlank { server.displayName },
                    serverUrl = src,
                    supportsResolutionChange = server.supportsResolution,
                    speedRating = server.speedRating
                ))
            }
        }

        // Pattern 2: Tabs with onclick="loadServer('url')"
        doc.select("[onclick*='loadServer'], [onclick*='changeServer'], [onclick*='player']").forEach { el ->
            val onclick = el.attr("onclick")
            val url = Regex("""['\"](https?://[^'\"]+)['\"]""").find(onclick)?.groupValues?.get(1)
            if (url != null && url !in seen) {
                seen.add(url)
                val server = VideoServer.detectServer(url)
                servers.add(VideoSource(
                    name = el.text().trim().ifBlank { server.displayName },
                    serverUrl = url,
                    supportsResolutionChange = server.supportsResolution,
                    speedRating = server.speedRating
                ))
            }
        }

        // Pattern 3: iframes directly in page (jkplayer/um wrapper as fallback when the script JSON is absent)
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotBlank() && src !in seen) {
                seen.add(src)
                val server = VideoServer.detectServer(src)
                servers.add(VideoSource(
                    name = server.displayName,
                    serverUrl = src,
                    supportsResolutionChange = server.supportsResolution,
                    speedRating = server.speedRating
                ))
            }
        }

        // Pattern 4: data-player (Base64 encoded) - use public API
        val dataPlayerServers = ServerExtractor.extractServersFromDoc(doc, episodeUrl)
        servers.addAll(dataPlayerServers)

        Log.d("JKAnimeScraper", "Extracted ${servers.size} servers from $episodeUrl")
        return servers.distinctBy { it.serverUrl }
    }

    /**
     * Parses `var servers = [{"remote":"<base64>","server":"Name",...}]` from the page script.
     * `remote` is a Base64-encoded server URL. This is the reliable source for JKAnime server tabs.
     */
    private fun extractJkServersFromScript(doc: Document, seen: MutableSet<String>, out: MutableList<VideoSource>) {
        val html = doc.toString()
        val match = Regex("""var\s+servers\s*=\s*(\[[^\]\n]*\]);""", RegexOption.IGNORE_CASE).find(html) ?: return
        val raw = match.groupValues[1]
        val arr = try {
            org.json.JSONArray(raw)
        } catch (e: Exception) {
            Log.w("JKAnimeScraper", "servers JSON parse failed: ${e.message}")
            return
        }
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("server").trim().ifBlank { continue }
            val encoded = obj.optString("remote").trim()
            if (encoded.isBlank()) continue
            val url = try {
                String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)).trim()
            } catch (e: Exception) {
                continue
            }
            if (url.isBlank() || url in seen || !url.startsWith("http")) continue
            seen.add(url)
            val server = VideoServer.detectServer(url)
            out.add(VideoSource(
                name = name,
                serverUrl = url,
                supportsResolutionChange = server.supportsResolution,
                speedRating = server.speedRating
            ))
        }
        Log.d("JKAnimeScraper", "servers JSON: ${out.size} server(s)")
    }
}

class JKAnimeScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = JKAnimeScraper
}