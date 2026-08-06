package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import com.karin.streamtv.util.HtmlClean
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Multi-strategy HTML parser: fast selectors first, heuristic DOM walk as fallback.
 * Extracts episode/series cards, titles, thumbnails, dates, and URLs.
 */
object DynamicParser {

    private const val TAG = "DynamicParser"

    // Common card container patterns seen across WordPress/streaming sites.
    // Ordered by specificity — first match that yields enough valid cards wins.
    private val cardSelectors = listOf(
        "div.series",                          // latanime /animes
        "div[class*='post-card']",             // WordPress card theme
        "div[class*='card']:has(img)",         // Bootstrap/various cards
        "div.md-card:has(a[href] img)",        // MundoDonghua .md-card grid
        "div[class*='item']:has(a[href] img)", // Generic items with images
        "div[class*='col-']:has(a[href] img)", // Bootstrap responsive columns
        "li[class*='post']:has(a[href] img)",  // List-based post listings
        "div[id^='post-']:has(a[href] img)",   // WordPress post divs
        "article:has(a[href] img)",            // HTML5 semantic
        "div[class*='entry']:has(a[href] img)", // WordPress entries
        "tr:has(td a[href] img)",              // Table rows
        "div[class*='list']:has(a[href] img)", // List containers
        "ul li:has(a[href] img)",              // Unordered list items
        "div:has(> a[href] > img)",            // Direct parent of img in a
        "a[href]:has(img) > div:has(h1,h2,h3,h4,h5,h6)", // Link > div > heading pattern
        "a[href] img[src]:first-child",        // Image is first child of link
        // Episodes: tablerows, cover containers, etc.
        "[class*='episode']:has(a[href] img)",
        "[class*='capitulo']:has(a[href] img)",
    )

    /**
     * Fast multi-selector card finder. Returns the first set of valid cards
     * found by any known selector, or falls back to heuristic DOM walking.
     */
    fun findCards(doc: Document, minCards: Int = 3): List<Element> {
        // 1. Try known CSS selectors — ~10x faster than DOM walk
        for (sel in cardSelectors) {
            val candidates = doc.select(sel)
            if (candidates.size < minCards) continue
            val valid = candidates.filter { isValidCard(it) }
            if (valid.size >= minCards) {
                Log.d(TAG, "Selector '$sel' → ${valid.size} cards")
                return valid
            }
        }

        // 2. Heuristic: find <a> with <img>, walk up to find repeated container
        val contentLinks = doc.select("a[href]").filter { a ->
            val href = a.attr("abs:href")
            href.isNotBlank() &&
            !href.contains("#") &&
            !href.contains("javascript:") &&
            a.selectFirst("img") != null &&
            !isSocialUrl(href)
        }
        if (contentLinks.size >= minCards) {
            val cards = walkToContainer(contentLinks, doc, minCards)
            if (cards.isNotEmpty()) return cards
        }

        // 3. Table fallback
        val tableRows = doc.select("table tbody tr").filter { tr ->
            tr.select("a[href]").size >= 2 && tr.selectFirst("img") != null
        }
        if (tableRows.size >= minCards) {
            Log.d(TAG, "Table rows → ${tableRows.size} cards")
            return tableRows
        }

        // 4. Parent grouping
        val grouped = contentLinks.groupBy { it.parent()?.tagName() ?: "" }
        val bestGroup = grouped.maxByOrNull { it.value.size }
        val parents = bestGroup?.value?.mapNotNull { it.parent() }?.distinct()
            ?.filter { isValidCard(it) } ?: emptyList()
        if (parents.size >= minCards) {
            Log.d(TAG, "Parent grouping → ${parents.size} cards")
            return parents
        }

        // 5. Last resort: return content links themselves
        if (contentLinks.size >= minCards) {
            Log.d(TAG, "Raw links → ${contentLinks.size} cards")
            return contentLinks
        }

        return emptyList()
    }

    private fun isSocialUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") || lower.contains("twitter.com") ||
               lower.contains("instagram.com") || lower.contains("tiktok.com") ||
               lower.contains("pinterest.com") || lower.contains("telegram.me") ||
               lower.contains("t.me") || lower.contains("whatsapp.com")
    }

    private fun isValidCard(card: Element): Boolean {
        if (card.select("a[href]").isEmpty()) return false
        // Skip tiny images (icons, avatars)
        val imgs = card.select("img")
        if (imgs.isEmpty()) return false
        val hasReal = imgs.any { img ->
            val w = img.attr("width").toIntOrNull() ?: 0
            val h = img.attr("height").toIntOrNull() ?: 0
            (w == 0 && h == 0) || w >= 50 || h >= 50
        }
        return hasReal
    }

    private fun walkToContainer(links: List<Element>, doc: Document, minCards: Int): List<Element> {
        val candidates = mutableListOf<Element>()
        for (link in links.take(300)) {
            var el = link.parent()
            var depth = 0
            while (el != null && el !== doc.body() && depth < 5) {
                val tag = el.tagName()
                val siblings = el.parent()?.children() ?: emptyList()
                val sameTagCount = siblings.count { it.tagName() == tag }
                if (sameTagCount >= minCards && el.selectFirst("img") != null) {
                    if (tag !in listOf("td", "th")) {
                        candidates.add(el)
                        break
                    }
                }
                el = el.parent()
                depth++
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val groups = candidates.groupBy { Pair(it.tagName(), it.parent()?.tagName() ?: "") }
        val bestGroup = groups.maxByOrNull { it.value.size }
        return bestGroup?.value?.distinct()?.filter { isValidCard(it) } ?: emptyList()
    }

    /**
     * Find the most likely title element within a card.
     */
    fun findTitle(card: Element): String? {
        // Priority order: heading tags, then elements with "title" in class, then largest text
        val heading = card.selectFirst("h1, h2, h3, h4, h5, h6")
        if (heading != null) {
            val text = heading.text().trim()
            if (text.isNotBlank()) return text
        }

        val titleClass = card.selectFirst("[class*='title' i], [class*='heading' i], [class*='name' i]")
        if (titleClass != null) {
            val text = titleClass.text().trim()
            if (text.isNotBlank()) return text
        }

        val bold = card.selectFirst("b, strong, .bold, .highlight")
        if (bold != null) {
            val text = bold.text().trim()
            if (text.isNotBlank()) return text
        }

        val link = card.selectFirst("a") ?: return HtmlClean.clean(card.text()).takeIf { it.length > 3 }
        val text = link.text().trim()
        if (text.isNotBlank() && text.length > 3) return HtmlClean.clean(text)
        val titleAttr = HtmlClean.clean(link.attr("title"))
        if (titleAttr.isNotBlank()) return titleAttr

        // Last resort: longest non-empty text in card
        return HtmlClean.clean(card.text()).takeIf { it.length > 3 }
    }

    /**
     * Find the most likely thumbnail URL within a card.
     */
    fun findThumbnail(card: Element): String {
        // First pass: find the largest image (by dimensions or by area)
        val images = card.select("img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-echo]")
        if (images.isEmpty()) return findBackgroundImage(card)

        val thumbs = images.mapNotNull { img ->
            val src = img.attr("data-src").ifBlank {
                img.attr("data-lazy").ifBlank {
                    img.attr("data-original").ifBlank {
                        img.attr("data-lazy-src").ifBlank {
                            img.attr("data-echo").ifBlank {
                                img.attr("data-srcset").ifBlank {
                                    img.attr("abs:src").ifBlank { null }
                                }
                            }
                        }
                    }
                }
            } ?: return@mapNotNull null

            val w = img.attr("width").toIntOrNull() ?: 0
            val h = img.attr("height").toIntOrNull() ?: 0
            val area = w * h

            // Skip obvious icons (tiny images)
            if ((w > 0 && w < 30) || (h > 0 && h < 30)) return@mapNotNull null
            if ((w > 0 || h > 0) && area < 2500) return@mapNotNull null

            src to area
        }
        // Resuelve la URL elegida contra la base del documento (RFC 3986)
        val best = thumbs.maxByOrNull { it.second }?.first
        if (best != null) return HtmlClean.resolveUrl(images.firstOrNull()?.baseUri() ?: "", best)

        val firstImg = images.firstOrNull() ?: return ""
        return HtmlClean.resolveUrl(firstImg.baseUri(), firstImg.attr("data-src").ifBlank {
            firstImg.attr("data-lazy").ifBlank {
                firstImg.attr("data-original").ifBlank {
                    firstImg.attr("data-lazy-src").ifBlank {
                        firstImg.attr("data-echo").ifBlank {
                            firstImg.attr("data-srcset").ifBlank {
                                firstImg.attr("abs:src")
                            }
                        }
                    }
                }
            }
        })
    }

    private fun findBackgroundImage(card: Element): String {
        val bgPattern = Regex("""background-image\s*:\s*url\(['"]?(.*?)['"]?\)""", RegexOption.IGNORE_CASE)
        val elements = card.select("[style*=background-image]")
        for (el in elements) {
            val match = bgPattern.find(el.attr("style"))
            val url = match?.groupValues?.get(1)?.trim()?.ifBlank { null }
            if (url != null) return HtmlClean.resolveUrl(card.baseUri(), url)
        }
        return ""
    }

    /**
     * Find a date-like string within a card using pattern matching.
     */
    fun findDate(card: Element): String {
        val text = card.text()

        // Common Spanish/English date patterns
        val patterns = listOf(
            Regex("""\b(?:hace\s+\d+\s+(?:minutos?|horas?|días?|meses?|años?|segundos?))\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:ayer|hoy|mañana|ahora)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4}\b"""),
            Regex("""\b\d{1,2}\s+(?:de\s+)?(?:enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre|january|february|march|april|may|june|july|august|september|october|november|december)\s+(?:de\s+)?\d{2,4}\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:lunes|martes|miércoles|jueves|viernes|sábado|domingo|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d{1,2}:\d{2}\b"""),  // time like "14:30"
            Regex("""\b(?:just now|minute|hour|day|week|month|year)\s+ago\b""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.value
        }

        // Look for elements with date-related classes/attributes
        val dateEl = card.selectFirst("[class*='date' i], [class*='fecha' i], [class*='time' i], time, [datetime]")
        if (dateEl != null) {
            val txt = dateEl.text().trim()
            if (txt.isNotBlank() && txt.length < 60) return txt
            val dt = dateEl.attr("datetime").trim()
            if (dt.isNotBlank() && dt.length < 60) return dt
        }

        return ""
    }

    /**
     * Find the most likely content URL within a card.
     */
    fun findUrl(card: Element): String? {
        val links = card.select("a[href]")

        // Prefer links to content pages (not anchors, not external scripts, not social)
        for (link in links) {
            val href = link.attr("abs:href")
            if (href.isBlank()) continue
            if (href.contains("#") && !href.contains("!#")) continue
            if (href.contains("javascript:")) continue
            if (href.contains("facebook.com") || href.contains("twitter.com") ||
                href.contains("instagram.com") || href.contains("whatsapp.com") ||
                href.contains("tiktok.com") || href.contains("pinterest.com") ||
                href.contains("telegram.me") || href.contains("t.me")) continue
            return href
        }

        return links.firstOrNull()?.attr("abs:href")?.takeIf { it.isNotBlank() }
    }

    /**
     * Try to extract an episode number from the card's text.
     */
    fun findEpisodeNum(card: Element): String {
        val text = card.text()
        val patterns = listOf(
            Regex("""(?:Episodio|Epí|Ep|Capítulo|Cap|Episode|EP)\s*\.?\s*#?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""#(\d+)"""),
            Regex("""(\d+)\s*(?:x\d{2})"""),  // "3x24"
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.groupValues[1].ifBlank { match.value }
        }
        // Bare number fallback: only match if preceded by space/start and followed by space/end/punctuation
        val bareMatch = Regex("""(?:^|\s)(\d{1,4})(?:\s|$|[,\.\)])""").find(text)
        if (bareMatch != null) return bareMatch.groupValues[1]
        return ""
    }

    private val episodeUrlPatterns = listOf(
        "/ver/", "/episode/", "/episodio/", "/watch/", "/capitulo/",
        "-episodio-", "-episode-", "-capitulo-", "-ep-"
    )

    fun isEpisodeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return episodeUrlPatterns.any { pattern -> lower.contains(pattern) }
    }

    fun parseEpisodeLinks(doc: Document, siteName: String): List<Episode> {
        val links = doc.select("a[href]").filter { a ->
            val href = a.attr("abs:href").lowercase()
            href.isNotBlank() && episodeUrlPatterns.any { pattern -> href.contains(pattern) }
        }
        if (links.isEmpty()) return emptyList()

        val episodes = links.mapNotNull { link ->
            try {
                val url = link.attr("abs:href")
                if (url.isBlank()) return@mapNotNull null

                val rawTitle = link.text().trim().ifBlank {
                    link.selectFirst("img")?.attr("alt")?.trim() ?: ""
                }
                val title = if (rawTitle.isNotBlank()) HtmlClean.clean(rawTitle) else {
                    url.substringAfterLast("/").replace("-", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }

                val img = link.selectFirst("img")
                val thumb = img?.let {
                    HtmlClean.resolveUrl(link.baseUri(), it.attr("data-src").ifBlank {
                        it.attr("data-lazy-src").ifBlank {
                            it.attr("data-lazy").ifBlank {
                                it.attr("data-original").ifBlank {
                                    it.attr("abs:src")
                                }
                            }
                        }
                    })
                } ?: ""

                val epNum = findEpisodeNum(link)
                Episode(title, url, thumb, siteName = siteName, episodeNum = epNum)
            } catch (_: Exception) { null }
        }

        Log.i(TAG, "Episode links: found ${episodes.size} episodes from ${links.size} links ($siteName)")
        return episodes
    }

    fun parseDynamic(doc: Document, siteName: String, minCards: Int = 3): List<Episode> {
        if (doc.body().html().isBlank()) {
            Log.w(TAG, "Empty document body for $siteName")
            return emptyList()
        }
        // Some sites (e.g. JKAnime directory) embed the whole list as
        // `var animes = {"current_page":1,"data":[{"title":...,"url":...,"image":...}]}`
        // in a <script>; the visible cards are rendered by JS. Parse that first.
        val jsonEpisodes = parseEmbeddedAnimeJson(doc, siteName)
        if (jsonEpisodes.isNotEmpty()) {
            Log.i(TAG, "Embedded JSON parsing: ${jsonEpisodes.size} episodes ($siteName)")
            return jsonEpisodes
        }
        val cards = findCards(doc, minCards)
        val episodes = cards.mapNotNull { card ->
            try {
                val url = findUrl(card) ?: return@mapNotNull null
                val title = findTitle(card) ?: return@mapNotNull null
                val thumb = findThumbnail(card)
                val date = findDate(card)
                val epNum = findEpisodeNum(card)
                Episode(title, url, thumb, date, siteName, epNum)
            } catch (_: Exception) { null }
        }

        Log.i(TAG, "Dynamic parsing: ${episodes.size} episodes from ${cards.size} cards ($siteName)")
        return episodes
    }

    /**
     * Parses `var animes = {"current_page":1,"data":[{"title":...,"url":...,"image":...}, ...]}` embedded
     * in a page <script> (used by JS-rendered sites like the JKAnime directory).
     */
    private fun parseEmbeddedAnimeJson(doc: Document, siteName: String): List<Episode> {
        val html = doc.toString()
        val jsonText = extractJsonObjectVar(html, "animes") ?: return emptyList()
        val obj = try {
            org.json.JSONObject(jsonText)
        } catch (e: Exception) {
            Log.w(TAG, "animes JSON parse failed ($siteName): ${e.message}")
            return emptyList()
        }
        val data = try { obj.optJSONArray("data") } catch (e: Exception) { null } ?: return emptyList()
        val episodes = mutableListOf<Episode>()
        for (i in 0 until data.length()) {
            try {
                val item = data.optJSONObject(i) ?: continue
                val title = HtmlClean.clean(item.optString("title"))
                val url = HtmlClean.resolveUrl(doc.baseUri(), item.optString("url"))
                if (title.isBlank() || url.isBlank()) continue
                val image = HtmlClean.resolveUrl(doc.baseUri(), item.optString("image"))
                val synopsis = HtmlClean.clean(item.optString("synopsis"))
                episodes.add(Episode(title, url, image, synopsis.take(80), siteName, ""))
            } catch (_: Exception) { }
        }
        return episodes
    }

    /** Extracts the balanced JSON object assigned to `var <name> = { ... }`. */
    private fun extractJsonObjectVar(html: String, varName: String): String? {
        val marker = "var $varName = {"
        val start = html.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length - 1 // index of '{'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in from until html.length) {
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
                    if (depth == 0) return html.substring(from, i + 1)
                }
            }
        }
        return null
    }

    fun findNextPageUrl(doc: Document, currentUrl: String): String? {
        val nextLink = doc.selectFirst("a[rel='next']")
        if (nextLink != null) {
            val href = nextLink.attr("abs:href")
            if (href.isNotBlank() && href != currentUrl) {
                Log.d(TAG, "Found next page link: $href")
                return href
            }
        }

        val currentUri = try { java.net.URI(currentUrl) } catch (_: Exception) { return null }
        val currentQuery = currentUri.rawQuery ?: ""
        val currentPage = Regex("""(?:^|&)p=(\d+)""").find(currentQuery)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val nextNum = currentPage + 1

        val nextLinkByPage = doc.select("a[href]").firstOrNull { a ->
            val href = a.attr("abs:href")
            val pageMatch = Regex("""[?&]p=(\d+)""").find(href)
            val page = pageMatch?.groupValues?.get(1)?.toIntOrNull()
            page == nextNum && href != currentUrl
        }
        if (nextLinkByPage != null) {
            val href = nextLinkByPage.attr("abs:href")
            Log.d(TAG, "Found next page by number: $href")
            return href
        }

        val pageLinks = doc.select("a.page-link, a[rel='next'], .pagination a, .page-item a")
        for (link in pageLinks) {
            val text = link.text().trim()
            val href = link.attr("abs:href")
            if (href.isBlank() || href == currentUrl) continue
            if (text == "›" || text == "»" || text.equals("next", ignoreCase = true) ||
                text.equals("siguiente", ignoreCase = true) || text.contains("›")) {
                Log.d(TAG, "Found next page by text '$text': $href")
                return href
            }
        }

        Log.d(TAG, "No next page found for $currentUrl")
        return null
    }

    fun findPrevPageUrl(doc: Document, currentUrl: String): String? {
        val prevLink = doc.selectFirst("a[rel='prev']")
        if (prevLink != null) {
            val href = prevLink.attr("abs:href")
            if (href.isNotBlank() && href != currentUrl) {
                Log.d(TAG, "Found prev page link: $href")
                return href
            }
        }

        val currentUri = try { java.net.URI(currentUrl) } catch (_: Exception) { return null }
        val currentQuery = currentUri.rawQuery ?: ""
        val currentPage = Regex("""(?:^|&)p=(\d+)""").find(currentQuery)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        if (currentPage <= 1) return null
        val prevNum = currentPage - 1

        val prevLinkByPage = doc.select("a[href]").firstOrNull { a ->
            val href = a.attr("abs:href")
            val pageMatch = Regex("""[?&]p=(\d+)""").find(href)
            val page = pageMatch?.groupValues?.get(1)?.toIntOrNull()
            page == prevNum && href != currentUrl
        }
        if (prevLinkByPage != null) {
            val href = prevLinkByPage.attr("abs:href")
            Log.d(TAG, "Found prev page by number: $href")
            return href
        }

        val pageLinks = doc.select("a.page-link, a[rel='prev'], .pagination a, .page-item a")
        for (link in pageLinks) {
            val text = link.text().trim()
            val href = link.attr("abs:href")
            if (href.isBlank() || href == currentUrl) continue
            if (text == "‹" || text == "«" || text.equals("prev", ignoreCase = true) ||
                text.equals("anterior", ignoreCase = true) || text.contains("‹")) {
                Log.d(TAG, "Found prev page by text '$text': $href")
                return href
            }
        }

        return null
    }

    data class SeriesPage(
        val title: String,
        val coverUrl: String = "",
        val description: String = "",
        val type: String = "",
        val status: String = "",
        val episodes: List<Episode> = emptyList()
    )

    fun parseSeriesPage(doc: Document, baseUrl: String, siteName: String = ""): SeriesPage {
        val title = findSeriesTitle(doc)
        val cover = findSeriesCover(doc)
        val description = findDescription(doc)
        val episodes = findSeriesEpisodes(doc, baseUrl, siteName)
        val type = extractMeta(doc, "type", "tipo")
        val status = extractMeta(doc, "status", "estado")

        Log.d(TAG, "Series: '$title' cover='${cover.take(40)}' desc='${description.take(40)}' ${episodes.size}eps")
        return SeriesPage(title, cover, description, type, status, episodes)
    }

    /**
     * Site-aware series page parser. JKAnime renders its episode list via AJAX (CSRF protected),
     * so for that site the episodes are fetched from `POST /ajax/episodes/{id}/{page}`.
     */
    suspend fun parseSeriesPageAsync(doc: Document, baseUrl: String, siteName: String = ""): SeriesPage {
        if (siteName.equals("JKAnime", ignoreCase = true)) {
            val jkEpisodes = JKAnimeScraper.fetchSeriesEpisodes(doc, baseUrl, siteName)
            if (jkEpisodes.isNotEmpty()) {
                return SeriesPage(
                    title = findSeriesTitle(doc),
                    coverUrl = findSeriesCover(doc),
                    description = findDescription(doc),
                    episodes = jkEpisodes
                )
            }
        }
        return parseSeriesPage(doc, baseUrl, siteName)
    }

    private fun findSeriesTitle(doc: Document): String {
        val candidates = listOf(
            "div.anime_info h3", ".anime_info h3",
            "h1", "h2", "h3",
            "[class*='title']", "[class*='heading']", "[class*='name']",
            "[class*='anime']", "[id*='title']", "[id*='anime']"
        )
        for (sel in candidates) {
            val el = doc.selectFirst(sel) ?: continue
            val text = el.text().trim()
            if (text.isNotBlank() && text.length in 3..120) return text
        }
        return doc.title().trim()
    }

    private fun findSeriesCover(doc: Document): String {
        val selectors = listOf(
            "div.movpic img", ".movpic img", "div.anime_pic img", "div.anime_info img",
            "div.serieimgficha img",
            "div.series2 img",
            "[class*='cover'] img", "[class*='poster'] img", "[class*='anime'] img",
            "[class*='series'] img", "[class*='banner'] img", "[id*='cover'] img",
            "[class*='imagen'] img", "[class*='portada'] img",
            "img[class*='cover']", "img[class*='poster']", "img[class*='anime']",
            "img[class*='series']", "img[class*='portada']",
            "img.img-fluid2", "img.img-fluid",
            "div.entry-content img:first-of-type",
            "article img", "main img",
            "img:first-of-type"
        )
        for (sel in selectors) {
            val img = doc.selectFirst(sel) ?: continue
            val src = img.attr("abs:src").ifBlank {
                img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank { "" }
                }
            }
            if (src.isNotBlank() && !src.contains("logo") && !src.contains("icon") && !src.contains("banner")) return HtmlClean.resolveUrl(doc.baseUri(), src)
        }
        return ""
    }

    private fun findDescription(doc: Document): String {
        val selectors = listOf(
            "div.anime_info p.scroll", "div.anime_info p", ".anime_info p",
            "[class*='sinopsis']", "[class*='description']", "[class*='synopsis']",
            "[class*='descripcion']", "[class*='summary']", "[class*='resumen']",
            "[class*='argumento']", "[class*='story']",
            "[itemprop='description']", "meta[name='description']",
            "div.col-lg-9 p", "div.col-md-8 p",
            "div.entry-content p", "article p"
        )
        for (sel in selectors) {
            val el = doc.selectFirst(sel) ?: continue
            if (sel.startsWith("meta")) {
                val content = el.attr("content").trim()
                if (content.isNotBlank() && content.length > 20) return content
            }
            val text = el.text().trim()
            if (text.isNotBlank() && text.length > 20) return text
        }

        val allParagraphs = doc.select("p")
        for (p in allParagraphs) {
            val text = p.text().trim()
            if (text.length > 30 && !text.contains("copyright") && !text.contains("facebook")
                && !text.contains("twitter") && !text.contains("compartir")
                && !text.contains("Descargo de responsabilidad") && !text.contains("servidores")) {
                return text
            }
        }
        return ""
    }

    private fun findSeriesEpisodes(doc: Document, baseUrl: String, siteName: String = ""): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val linkSelectors = listOf(
            "a[href*='/ver/']", "a[href*='/episode/']", "a[href*='/episodio/']",
            "a[href*='/watch/']", "a[href*='/capitulo/']",
            "[class*='episode'] a[href]", "[class*='episodio'] a[href]",
            "[class*='chapter'] a[href]", "[class*='cap'] a[href]",
            "div.cap-layout a[href]", ".cap-layout a[href]",
            "div.cap-layout", ".cap-layout",
            "[class*='cap-layout'] a[href]", "[class*='cap-layout']",
            "a:has(div[class*='cap'])"
        )
        val seenUrls = mutableSetOf<String>()
        for (sel in linkSelectors) {
            val links = doc.select(sel)
            for (link in links) {
                val href = if (link.tagName() == "a") {
                    link.attr("abs:href").ifBlank { continue }
                } else {
                    link.selectFirst("a[href]")?.attr("abs:href")?.ifBlank {
                        link.parent()?.attr("abs:href")?.ifBlank { continue } ?: continue
                    } ?: continue
                }
                if (href in seenUrls || href == baseUrl || href.contains("#")) continue
                if (!DynamicParser.isEpisodeUrl(href)) continue
                seenUrls.add(href)
                var text = link.text().trim()
                if (text.isBlank()) {
                    text = HtmlClean.clean(link.selectFirst("img")?.attr("alt") ?: "")
                }
                if (text.isBlank()) {
                    text = link.selectFirst("a")?.text()?.trim() ?: ""
                }
                if (text.isBlank()) {
                    text = href.substringAfterLast("/").replace("-", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                val epNum = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(text)?.groupValues?.get(1)
                    ?: Regex("""(\d+)""").find(text)?.groupValues?.get(1)
                    ?: ""
                val thumb = link.selectFirst("img")?.let { img ->
                    HtmlClean.resolveUrl(link.baseUri(), img.attr("data-src").ifBlank {
                        img.attr("data-lazy-src").ifBlank {
                            img.attr("data-lazy").ifBlank {
                                img.attr("data-original").ifBlank {
                                    img.attr("abs:src")
                                }
                            }
                        }
                    })
                } ?: ""
                episodes.add(Episode(title = text, url = href, thumbnailUrl = thumb, episodeNum = epNum, siteName = siteName))
            }
        }

        if (episodes.isEmpty()) {
            val allLinks = doc.select("a[href]").filter { a ->
                val href = a.attr("abs:href").lowercase()
                href.isNotBlank() && episodeUrlPatterns.any { pattern -> href.contains(pattern) }
            }
            for (link in allLinks) {
                val href = link.attr("abs:href")
                if (href in seenUrls || href == baseUrl || href.contains("#")) continue
                seenUrls.add(href)
                var text = link.text().trim()
                if (text.isBlank()) {
                    text = HtmlClean.clean(link.selectFirst("img")?.attr("alt") ?: "")
                }
                if (text.isBlank()) {
                    text = href.substringAfterLast("/").replace("-", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                val epNum = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(text)?.groupValues?.get(1)
                    ?: Regex("""(\d+)""").find(text)?.groupValues?.get(1)
                    ?: ""
                val thumb = link.selectFirst("img")?.let { img ->
                    HtmlClean.resolveUrl(link.baseUri(), img.attr("data-src").ifBlank {
                        img.attr("data-lazy-src").ifBlank {
                            img.attr("data-lazy").ifBlank {
                                img.attr("data-original").ifBlank {
                                    img.attr("abs:src")
                                }
                            }
                        }
                    })
                } ?: ""
                episodes.add(Episode(title = text, url = href, thumbnailUrl = thumb, episodeNum = epNum, siteName = siteName))
            }
        }

        if (episodes.isEmpty()) {
            val cards = findCards(doc, 1)
            for (card in cards) {
                val url = findUrl(card) ?: continue
                if (url in seenUrls) continue
                seenUrls.add(url)
                val title = findTitle(card) ?: continue
                val epNum = findEpisodeNum(card)
                val thumb = findThumbnail(card)
                episodes.add(Episode(title = title, url = url, thumbnailUrl = thumb, episodeNum = epNum, siteName = siteName))
            }
        }

        return episodes.distinctBy { it.url }
    }

    private fun extractMeta(doc: Document, vararg keys: String): String {
        for (key in keys) {
            val sel = "[class*='$key'], [id*='$key'], span.$key, div.$key, li.$key"
            val el = doc.selectFirst(sel) ?: continue
            val text = el.text().trim()
            if (text.isNotBlank() && text.length < 60) return text
        }
        return ""
    }
}
