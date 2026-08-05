package com.karin.streamtv.scraper

import com.karin.streamtv.model.Episode

/**
 * MundoDonghua – Homepage section "Nuevos Episodios" lists recent donghua episodes.
 *
 * HTML structure:
 *   <section class="md-section">
 *     <h2 class="md-section-title">Nuevos Episodios</h2>
 *     <div class="md-episode-grid">
 *       <div class="md-card">
 *         <a href="{episode-url}">
 *           <div class="md-card-img">
 *             <img src="{thumb}" alt="{title}"/>
 *             <span class="md-card-badge donghua">HD</span>
 *           </div>
 *           <h3 class="md-card-title">{title}</h3>
 *           <span class="md-card-date"><i class="fas fa-calendar-alt"></i> {date}</span>
 *         </a>
 *       </div>
 *     </div>
 *   </section>
 *
 * We specifically target the "Nuevos Episodios" section, not "Episodios Limitados" or "Más contenido".
 */
object MundoDonghuaScraper : GenericScraper() {
    override val name = "MundoDonghua"
    override val baseUrl = "https://www.mundodonghua.com"

    override fun buildSearchUrl(query: String): String =
        "${baseUrl}/busquedas/?donghua=${java.net.URLEncoder.encode(query, "UTF-8")}"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()

        return parseEpisodeCards(
            doc = doc,
            cardSel = listOf(
                "section.md-section:has(h2:contains(Nuevos Episodios)) div.md-card",
                "div.md-episode-grid div.md-card",
                "div.md-card",
                "section.md-section div.md-card",
                "article",
                ".card"
            ),
            titleSel = listOf("h3.md-card-title", "h3", "h4", ".md-card-title", "[class*='title']"),
            thumbSel = listOf("div.md-card-img img", "img[src]", "img[data-src]"),
            thumbAttrs = listOf("abs:src", "src", "data-src", "data-lazy-src", "abs:data-lazy-src", "data-original"),
            dateSel = listOf("div.md-card-meta span:first-child", ".md-card-date", "[class*='date']", "time")
        )
    }
}

class MundoDonghuaScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = MundoDonghuaScraper
}
