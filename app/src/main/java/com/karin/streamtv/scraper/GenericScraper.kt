package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

abstract class GenericScraper : BaseScraper {

    protected val engine get() = ScrapingEngine
    private val tag get() = "Scraper/${name}"

    /** Override in each site scraper if search URL differs from default. */
    protected open fun buildSearchUrl(query: String): String =
        "${baseUrl}/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"

    protected suspend fun fetchDocument(url: String? = null, forceFresh: Boolean = false): Document? {
        val target = url ?: baseUrl
        val cacheKey = "${name}::home"
        return withContext(Dispatchers.IO) {
            engine.fetch(target, name, cacheKey, forceFresh)
        }
    }

    override suspend fun search(query: String): List<Episode> {
        if (query.isBlank()) return emptyList()
        val searchUrl = buildSearchUrl(query)
        Log.d(tag, "Searching: $searchUrl")
        val doc = withContext(Dispatchers.IO) {
            engine.fetch(searchUrl, name, "${name}::search::${query.lowercase().take(50)}")
        }
        if (doc == null) return emptyList()
        // Use dynamic parser for search results (structure may differ from homepage)
        return DynamicParser.parseDynamic(doc, name)
    }

    /**
     * Parse episode cards with fallback selectors.
     * Each selector parameter accepts a list — the first selector that produces
     * results is used. This handles site HTML changes without breaking.
     *
     * @param cardSel   list of CSS selectors for each card container (tried in order)
     * @param titleSel  list of CSS selectors for the title element within a card
     * @param urlSel    list of CSS selectors for the anchor element within a card
     * @param thumbSel  list of CSS selectors for the thumbnail image within a card
     * @param dateSel   list of CSS selectors for the date element within a card
     */
    protected fun parseEpisodeCards(
        doc: Document,
        cardSel: List<String> = listOf("div.col-6.col-md-6.col-lg-3.mb-3", "div.mb-4.d-flex", "article", ".card", "div.post", "li"),
        titleSel: List<String> = listOf("h2.mt-3", "h3", "h4", ".title", ".card-title", "[class*='title']", "a"),
        urlSel: List<String> = listOf("a"),
        thumbSel: List<String> = listOf("img.lozad", "img[src]", "img[data-src]"),
        dateSel: List<String> = listOf("span.span-tiempo", ".date", ".fecha", "time", "[class*='date']", "[class*='fecha']"),
        titleAttr: String = "text",
        urlAttr: String = "abs:href",
        thumbAttrs: List<String> = listOf("data-src", "abs:src", "data-lazy-src", "abs:data-lazy-src", "data-lazy", "abs:data-lazy", "data-original", "abs:data-original", "data-echo", "abs:data-echo"),
        dateAttr: String = "text",
        episodeExtractor: ((Element) -> String)? = null,
        filter: ((Element) -> Boolean)? = null,
        epNumSelector: List<String> = listOf("span.badge", "[class*='episode']", "[class*='ep-']", "[class*='number']")
    ): List<Episode> {
        // Try card containers in order, pick first with matches
        var cards: MutableList<Element> = mutableListOf()
        var usedCardSel = ""
        for (sel in cardSel) {
            val found = doc.select(sel)
            if (found.isNotEmpty()) {
                cards = found
                usedCardSel = sel
                break
            }
        }
        if (cards.isEmpty()) {
            Log.w(tag, "No cards found with any selector. Falling back to dynamic parser...")
            return DynamicParser.parseDynamic(doc, name)
        }
        Log.d(tag, "Found ${cards.size} card(s) with selector '$usedCardSel' (tried ${cardSel.size})")

        val episodes = mutableListOf<Episode>()
        cards.forEachIndexed { i, card ->
            try {
                if (filter != null && !filter(card)) return@forEachIndexed

                // URL — try selectors in order
                val urlEl = firstMatch(card, urlSel) ?: card.selectFirst("a")
                val url = urlEl?.attr(urlAttr)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed

                // Title — try selectors in order
                val titleEl = firstMatch(card, titleSel)
                val title = if (titleAttr == "text") titleEl?.text()?.trim() else titleEl?.attr(titleAttr)?.trim()
                if (title.isNullOrBlank()) return@forEachIndexed

                // Thumbnail — try selectors in order, then try attrs in order, then background-image
                val thumbImg = thumbSel.firstNotNullOfOrNull { sel ->
                    card.selectFirst(sel)
                }
                val thumb = thumbImg?.let { img ->
                    thumbAttrs.firstNotNullOfOrNull { attr ->
                        img.attr(attr).ifBlank { null }
                    }
                } ?: findBackgroundImage(card)

                // Date — try selectors in order
                val date = dateSel.firstNotNullOfOrNull { sel ->
                    val el = card.selectFirst(sel)
                    if (dateAttr == "text") el?.text()?.trim() else el?.attr(dateAttr)?.trim()
                } ?: ""

                val epNum = episodeExtractor?.invoke(card) ?: run {
                    // Try selectors in order
                    val epEl = firstMatch(card, epNumSelector)
                    val epText = epEl?.text()?.trim() ?: ""
                    val numMatch = Regex("""(\d+)""").find(epText)
                    if (numMatch != null) numMatch.groupValues[1]
                    else DynamicParser.findEpisodeNum(card)
                }

                episodes.add(Episode(title, url, thumb, date, name, epNum))
            } catch (e: Exception) {
                Log.w(tag, "Failed to parse card #$i: ${e.message}")
            }
        }

        Log.i(tag, "Extracted ${episodes.size} episode(s)")
        return episodes
    }

    // Overload with single Strings for convenience
    protected fun parseEpisodeCards(
        doc: Document,
        cardSel: String,
        titleSel: String = "h2.mt-3",
        urlSel: String? = null,
        thumbSel: String? = null,
        dateSel: String? = null,
        titleAttr: String = "text",
        urlAttr: String = "abs:href",
        thumbAttr: String = "abs:src",
        dateAttr: String = "text",
        episodeExtractor: ((Element) -> String)? = null,
        filter: ((Element) -> Boolean)? = null,
        epNumSel: String? = null
    ): List<Episode> {
        return parseEpisodeCards(
            doc = doc,
            cardSel = listOf(cardSel),
            titleSel = listOf(titleSel),
            urlSel = if (urlSel != null) listOf(urlSel) else listOf("a"),
            thumbSel = if (thumbSel != null) listOf(thumbSel) else listOf("img.lozad", "img[src]", "img[data-src]"),
            dateSel = if (dateSel != null) listOf(dateSel) else listOf("span.span-tiempo", ".date", ".fecha", "time", "[class*='date']", "[class*='fecha']"),
            titleAttr = titleAttr,
            urlAttr = urlAttr,
            thumbAttrs = listOf(thumbAttr, "data-src", "data-lazy-src", "abs:src", "abs:data-src", "abs:data-lazy-src"),
            dateAttr = dateAttr,
            episodeExtractor = episodeExtractor,
            filter = filter,
            epNumSelector = if (epNumSel != null) listOf(epNumSel) else listOf("span.badge", "[class*='episode']", "[class*='ep-']", "[class*='number']")
        )
    }

    private fun firstMatch(card: Element, selectors: List<String>): Element? {
        for (sel in selectors) {
            val el = card.selectFirst(sel)
            if (el != null) return el
        }
        return null
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

    abstract override suspend fun getLatestEpisodes(): List<Episode>
}
