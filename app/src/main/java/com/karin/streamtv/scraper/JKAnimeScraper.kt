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

    override suspend fun extractServers(episodeUrl: String): List<VideoSource> {
        val doc = fetchDocument(episodeUrl) ?: return emptyList()
        val servers = mutableListOf<VideoSource>()
        val seen = mutableSetOf<String>()

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

        // Pattern 3: iframes directly in page
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
}

class JKAnimeScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = JKAnimeScraper
}