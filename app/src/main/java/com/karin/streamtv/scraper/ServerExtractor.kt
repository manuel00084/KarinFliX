package com.karin.streamtv.scraper

import android.util.Base64
import android.util.Log
import com.karin.streamtv.model.VideoServer
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.model.EpisodeNavigation
import com.karin.streamtv.util.HtmlClean
import com.karin.streamtv.util.AppPreferences
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

object ServerExtractor {

    private const val TAG = "ServerExtractor"

    suspend fun extractServers(episodeUrl: String, siteName: String): List<VideoSource> {
        val cacheKey = "${siteName}::episode::${episodeUrl.hashCode()}"
        Log.d(TAG, "extractServers: fetching $episodeUrl with key=$cacheKey")
        val doc = try {
            ScrapingEngine.fetch(episodeUrl, siteName, cacheKey)
        } catch (e: Exception) {
            Log.e(TAG, "fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (doc == null) {
            Log.w(TAG, "extractServers: fetch returned null for $episodeUrl")
            return emptyList()
        }
        Log.d(TAG, "extractServers: doc body length = ${doc.body().html().length}")
        val hasDataPlayer = doc.select("[data-player]").size
        val hasIframes = doc.select("iframe[src]").size
        Log.d(TAG, "extractServers: found $hasDataPlayer [data-player] elements, $hasIframes iframes")
        return extractServersFromDoc(doc, episodeUrl)
    }

    fun extractServersFromDoc(doc: Document, fallbackUrl: String = ""): List<VideoSource> {
        val servers = mutableListOf<VideoSource>()
        val seen = mutableSetOf<String>()

        val iframes = doc.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("abs:src").ifBlank { continue }
            if (src in seen) continue
            if (isAdUrl(src)) continue
            seen.add(src)
            val server = VideoServer.detectServer(src)
            servers.add(VideoSource(
                name = server.displayName,
                serverUrl = src,
                supportsResolutionChange = server.supportsResolution,
                speedRating = server.speedRating
            ))
        }

        if (servers.isNotEmpty()) {
            val result = servers.distinctBy { it.serverUrl }
            Log.d(TAG, "Extracted ${result.size} video server URL(s) from iframes")
            return result
        }

        val dataPlayerServers = extractDataPlayerServers(doc, seen)
        if (dataPlayerServers.isNotEmpty()) {
            Log.d(TAG, "Extracted ${dataPlayerServers.size} server(s) from data-player attributes")
            return dataPlayerServers
        }

        return extractServerTabs(doc, fallbackUrl)
    }

    private fun extractDataPlayerServers(doc: Document, seen: MutableSet<String>): List<VideoSource> {
        val servers = mutableListOf<VideoSource>()
        val playerLinks = doc.select("a[data-player]")
        for (link in playerLinks) {
            val encoded = link.attr("data-player").ifBlank { continue }
            val decoded = try {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                String(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to Base64 decode data-player: $encoded")
                null
            }
            if (decoded.isNullOrBlank() || decoded in seen) continue
            if (isAdUrl(decoded)) continue
            seen.add(decoded)

            val decodedClean = if (decoded.contains("%")) {
                try { URLDecoder.decode(decoded, "UTF-8") } catch (e: Exception) { decoded }
            } else decoded

            val server = VideoServer.detectServer(decodedClean)
            val displayName = link.text().trim().ifBlank { server.displayName }
            servers.add(VideoSource(
                name = displayName,
                serverUrl = decodedClean,
                supportsResolutionChange = server.supportsResolution,
                speedRating = server.speedRating
            ))
        }

        val dataPlayerDivs = doc.select("[data-player]")
        for (div in dataPlayerDivs) {
            if (div.tagName() == "a") continue
            val encoded = div.attr("data-player").ifBlank { continue }
            val decoded = try {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                String(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to Base64 decode data-player: $encoded")
                null
            }
            if (decoded.isNullOrBlank() || decoded in seen) continue
            if (isAdUrl(decoded)) continue
            seen.add(decoded)
            val server = VideoServer.detectServer(decoded)
            servers.add(VideoSource(
                name = server.displayName,
                serverUrl = decoded,
                supportsResolutionChange = server.supportsResolution,
                speedRating = server.speedRating
            ))
        }

        return servers.distinctBy { it.serverUrl }
    }

    private fun extractServerTabs(doc: Document, fallbackUrl: String): List<VideoSource> {
        val servers = mutableListOf<VideoSource>()
        val seenNames = mutableSetOf<String>()

        val knownServerNames = VideoServer.entries.map { it.displayName.lowercase() } +
                listOf("dsvplay", "bysekoze", "byse", "hexload", "savefiles", "nuuuppp")

        val tabSelectors = listOf(
            "[class*='option'] a, [class*='option'] button",
            "[class*='server'] a, [class*='server'] button",
            "[class*='tab'] a, [class*='tab'] button",
            "li[class*='option'] a, li[class*='server'] a",
            "[data-option] a, [data-server] a",
            "a.option, button.option, a.server, button.server",
            "a[data-option], button[data-option], a[data-server], button[data-server]",
            "ul.nav li a, .nav-tabs li a"
        )

        for (sel in tabSelectors) {
            val tabs = doc.select(sel)
            if (tabs.isEmpty()) continue

            for (tab in tabs) {
                val text = tab.text().trim().lowercase()
                if (text.isBlank() || text in seenNames) continue

                val cleanText = text.replace(Regex("[\\s_#]+"), "").trim()
                val matchedServer = knownServerNames.firstOrNull { cleanText.contains(it) || it.contains(cleanText) } ?: continue

                val displayName = VideoServer.entries.firstOrNull { it.displayName.lowercase() == matchedServer }?.displayName
                    ?: matchedServer.replaceFirstChar { it.uppercase() }

                seenNames.add(text)
                val tabUrl = resolveTabUrl(tab, doc, fallbackUrl)
                val serverUrl = tabUrl ?: if (AppPreferences.isServerFallbackEnabled()) {
                    "$fallbackUrl?server=$displayName"
                } else {
                    null
                }
                if (serverUrl != null) {
                    servers.add(VideoSource(
                        name = displayName,
                        serverUrl = serverUrl,
                        speedRating = 3
                    ))
                }
            }
            if (servers.isNotEmpty()) break
        }

        val tabContainers = doc.select("ul.nav, .nav-tabs, [class*='option'], [class*='server'], [id*='option'], [id*='server']")
        if (AppPreferences.isServerFallbackEnabled() && servers.isEmpty() && tabContainers.isNotEmpty()) {
            for (container in tabContainers) {
                val links = container.select("a, button, span")
                for (link in links) {
                    val text = link.text().trim().lowercase()
                    if (text.isBlank() || text in seenNames) continue
                    val matchedServer = knownServerNames.firstOrNull { text.contains(it) } ?: continue
                    seenNames.add(text)
                    val displayName = VideoServer.entries.firstOrNull { it.displayName.lowercase() == matchedServer }?.displayName
                        ?: matchedServer.replaceFirstChar { it.uppercase() }
                    servers.add(VideoSource(
                        name = displayName,
                        serverUrl = "$fallbackUrl?server=$displayName",
                        speedRating = 3
                    ))
                }
            }
        }

        val result = servers.distinctBy { it.name.lowercase() }
        if (result.isNotEmpty()) {
            Log.d(TAG, "Extracted ${result.size} server tab(s) from HTML")
        } else {
            Log.d(TAG, "No server tabs found in HTML (JS-rendered)")
        }
        return result
    }

    private fun resolveTabUrl(tab: Element, doc: Document, fallbackUrl: String): String? {
        // Acepta URLs absolutas, protocol-relative y relativas al documento
        // (RFC 3986 vía HtmlClean), no solo las que ya empiezan con "http".
        val base = doc.baseUri().ifBlank { fallbackUrl }
        listOf("abs:href", "data-src", "data-url", "data-link", "data-iframe", "data-embed").forEach { attr ->
            val value = tab.attr(attr).takeIf { it.isNotBlank() } ?: return@forEach
            val resolved = HtmlClean.resolveUrl(base, value)
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) return resolved
        }

        val href = tab.attr("href")
        if (href.startsWith("#")) {
            val id = href.removePrefix("#")
            // Sin selectores con el id interpolado: un id con comillas
            // rompería el parser del selector (SelectorParseException).
            // getElementById + búsqueda literal por atributo lo evitan.
            val anchor = doc.getElementById(id)
                ?: doc.getElementsByAttributeValue("name", id).firstOrNull()
            if (anchor != null) {
                val iframe = anchor.selectFirst("iframe[src]")
                if (iframe != null) {
                    val src = iframe.attr("abs:src")
                    if (src.isNotBlank()) return src
                }
            }
        }

        val dataTarget = tab.attr("data-target").ifBlank { tab.attr("data-tab") }
        if (dataTarget.startsWith("#")) {
            val id = dataTarget.removePrefix("#")
            // getElementById ya cubre [id=...]; el selectFirst con el id
            // interpolado solo aportaba riesgo de SelectorParseException.
            val target = doc.getElementById(id)
            if (target != null) {
                val iframe = target.selectFirst("iframe[src]")
                if (iframe != null) {
                    val src = iframe.attr("abs:src")
                    if (src.isNotBlank()) return src
                }
            }
        }

        val optionVal = tab.attr("data-option").ifBlank { tab.attr("data-opcion") }
        if (optionVal.isNotBlank() && AppPreferences.isServerFallbackEnabled()) {
            return "$fallbackUrl?option=$optionVal"
        }

        return null
    }

    suspend fun extractEpisodeNavigation(episodeUrl: String, siteName: String): com.karin.streamtv.model.EpisodeNavigation {
        val cacheKey = "${siteName}::nav::${episodeUrl.hashCode()}"
        val doc = try {
            ScrapingEngine.fetch(episodeUrl, siteName, cacheKey)
        } catch (e: Exception) {
            Log.e(TAG, "Nav fetch failed: ${e.message}")
            null
        } ?: return com.karin.streamtv.model.EpisodeNavigation()
        return extractNavigationFromDoc(doc)
    }

    fun extractNavigationFromDoc(doc: Document): com.karin.streamtv.model.EpisodeNavigation {
        var prevUrl: String? = null
        var prevTitle: String? = null
        var nextUrl: String? = null
        var nextTitle: String? = null
        var listUrl: String? = null

        fun clickableHref(link: Element): String? {
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            val style = link.attr("style") + " " + (link.parent()?.attr("style") ?: "")
            if (href.isBlank() || href == "#" || href.startsWith("javascript:")) return null
            if (style.contains("pointer-events: none") || link.hasClass("nextcapd")) return null
            return href
        }

        fun scanNavigation(links: List<Element>) {
            for (link in links) {
                val href = clickableHref(link) ?: continue
                val text = link.text().trim().lowercase()
                when {
                    prevUrl == null && (text.contains("anterior") || text.contains("previous")) -> {
                        prevUrl = href
                        prevTitle = link.text().trim()
                    }
                    nextUrl == null && (text.contains("siguiente") || text.contains("next")
                        || text.contains("próximo") || text.contains("proximo")) -> {
                        nextUrl = href
                        nextTitle = link.text().trim()
                    }
                    listUrl == null && (text.contains("ver todos") || text.contains("todos los cap")
                        || text.contains("lista de episodios") || text.contains("lista de cap")) -> {
                        listUrl = href
                    }
                }
            }
        }

        // 1) Contenedores de navegación conocidos (LatAnime/PeliPops: div.controles; JKAnime: anime_slug/videonav)
        scanNavigation(doc.select("div.controles a[href], div.anime_slug a[href], a:has(div.videonav)").toList())

        // 2) Fallback por patrón de URL (/ver/)
        if (prevUrl == null && nextUrl == null) {
            scanNavigation(doc.select("a[href*='/ver/']").toList())
        }

        // 3) Fallback global por texto de enlace
        if (prevUrl == null || nextUrl == null) {
            scanNavigation(doc.select("a[href]").toList())
        }

        // 4) listUrl: patrón /anime/ o directorio del slug
        if (listUrl == null) {
            val listLink = doc.selectFirst("a[href*='/anime/'], a[href*='/series/'], a[href*='/serie/']")
            if (listLink != null) {
                listUrl = listLink.attr("abs:href").ifBlank { listLink.attr("href") }
            } else {
                val directoryLink = doc.selectFirst("div.anime_slug a[href], div.controles a[href], a:has(div.videonav)")
                if (directoryLink != null) {
                    listUrl = directoryLink.attr("abs:href").ifBlank { directoryLink.attr("href") }
                }
            }
        }

        Log.d(TAG, "Navigation: prev=$prevUrl, next=$nextUrl, list=$listUrl")
        return com.karin.streamtv.model.EpisodeNavigation(prevUrl, prevTitle, nextUrl, nextTitle, listUrl)
    }

    fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("doubleclick") || lower.contains("googlesyndication") ||
                lower.contains("googleadservices") || lower.contains("adsense") ||
                lower.contains("popads") || lower.contains("exoclick") ||
                lower.contains("adserver") || lower.contains("adnxs") ||
                lower.contains("advertising") || lower.contains("adroll") ||
                lower.contains("taboola") || lower.contains("outbrain") ||
                lower.contains("mgid.com") || lower.contains("popcash") ||
                lower.contains("propellerads") || lower.contains("clickadu") ||
                lower.contains("criteo") || lower.contains("amazon-adsystem") ||
                lower.contains("casalemedia") || lower.contains("pubmatic") ||
                lower.contains("rubiconproject") || lower.contains("openx") ||
                lower.contains("moatads") || lower.contains("quantserve") ||
                lower.contains("scorecardresearch") || lower.contains("facebook.com/plugins") ||
                lower.contains("onclickads") || lower.contains("juicyads") ||
                lower.contains("hilltopads") || lower.contains("trafficjunky") ||
                lower.contains("richpush") || lower.contains("clickserve")
    }
}
