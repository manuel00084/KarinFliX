package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Heuristic HTML parser that finds episode cards, titles, thumbnails, dates,
 * and URLs without relying on hardcoded CSS selectors.
 *
 * Used as fallback when site-specific selectors fail (e.g. after a site redesign).
 */
object DynamicParser {

    private const val TAG = "DynamicParser"

    /**
     * Find repeated card-like containers in the document efficiently.
     * Instead of scanning all elements with :has(), finds <a> links with images
     * and walks up to find reusable parent patterns.
     */
    fun findCards(doc: Document, minCards: Int = 3): List<Element> {
        // 1. Collect content links that have an image nearby
        val contentLinks = doc.select("a[href]").filter { a ->
            val href = a.attr("abs:href")
            href.isNotBlank() &&
            !href.contains("#") &&
            !href.contains("javascript:") &&
            !href.contains("facebook.com") &&
            !href.contains("twitter.com") &&
            !href.contains("instagram.com") &&
            a.selectFirst("img") != null
        }
        if (contentLinks.size < minCards) return findCardsByParent(doc, minCards)

        // 2. For each link, walk up to find the nearest structural container
        //    that shares tag+parent with at least 2 siblings
        //    Limit to first 200 links to avoid slowdown on huge pages
        val candidates = mutableListOf<Element>()
        for (link in contentLinks.take(200)) {
            var el = link.parent()
            var depth = 0
            while (el != null && el != doc.body() && depth < 5) {
                val tag = el.tagName()
                // Count siblings with same tag under same parent
                val allChildren = el.parent()?.children() ?: emptyList()
                var sameTagCount = 0
                for (child in allChildren) {
                    if (child.tagName() == tag) sameTagCount++
                }
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

        // 3. Group by (tag, parent_tag) and pick largest group
        if (candidates.isEmpty()) return findCardsByParent(doc, minCards)

        val groups = candidates.groupBy { Pair(it.tagName(), it.parent()?.tagName() ?: "") }
        val bestGroup = groups.maxByOrNull { it.value.size }
        val cards = bestGroup?.value?.distinct()?.filter { card ->
            card.select("img").any { img ->
                val w = img.attr("width").toIntOrNull() ?: 0
                val h = img.attr("height").toIntOrNull() ?: 0
                (w == 0 && h == 0) || w >= 50 || h >= 50
            }
        } ?: emptyList()

        Log.d(TAG, "Found ${cards.size} card(s) from ${contentLinks.size} content links")
        return cards
    }

    /**
     * Fallback: find parent containers of content links grouped by structure.
     */
    private fun findCardsByParent(doc: Document, minCards: Int): List<Element> {
        // Try table rows first — many WordPress stream sites use <tr> episode listings
        val tableRows = doc.select("table tbody tr").filter { tr ->
            tr.select("a[href]").size >= 2 && tr.selectFirst("img") != null
        }
        if (tableRows.size >= minCards) {
            Log.d(TAG, "Found ${tableRows.size} table row card(s)")
            return tableRows
        }


        val links = doc.select("a[href]").filter { a ->
            val href = a.attr("abs:href")
            href.isNotBlank() &&
            !href.contains("#") &&
            !href.contains("javascript:") &&
            a.selectFirst("img") != null
        }
        if (links.size < minCards) return emptyList()

        val grouped = links.groupBy { it.parent()?.cssSelector() ?: "" }
        val bestGroup = grouped.maxByOrNull { it.value.size }
        val parents = bestGroup?.value?.mapNotNull { it.parent() }?.distinct() ?: emptyList()

        Log.d(TAG, "Fallback by parent: found ${parents.size} containers")
        return parents
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

        val link = card.selectFirst("a") ?: return card.text().trim().takeIf { it.length > 3 }
        val text = link.text().trim()
        if (text.isNotBlank() && text.length > 3) return text
        val titleAttr = link.attr("title").trim()
        if (titleAttr.isNotBlank()) return titleAttr

        // Last resort: longest non-empty text in card
        return card.text().trim().takeIf { it.length > 3 }
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

        val best = thumbs.maxByOrNull { it.second }?.first
        if (best != null) return best

        val firstImg = images.firstOrNull() ?: return ""
        return firstImg.attr("data-src").ifBlank {
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
        }
    }

    private fun findBackgroundImage(card: Element): String {
        val bgPattern = Regex("""background-image\s*:\s*url\(['"]?(.*?)['"]?\)""", RegexOption.IGNORE_CASE)
        val elements = card.select("[style*=background-image]")
        for (el in elements) {
            val match = bgPattern.find(el.attr("style"))
            val url = match?.groupValues?.get(1)?.trim()?.ifBlank { null }
            if (url != null) return url
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

                val title = link.text().trim().ifBlank {
                    link.selectFirst("img")?.attr("alt")?.trim() ?: ""
                }.ifBlank {
                    url.substringAfterLast("/").replace("-", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }

                val img = link.selectFirst("img")
                val thumb = img?.let {
                    it.attr("data-src").ifBlank {
                        it.attr("data-lazy-src").ifBlank {
                            it.attr("data-lazy").ifBlank {
                                it.attr("data-original").ifBlank {
                                    it.attr("abs:src")
                                }
                            }
                        }
                    }
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
}
