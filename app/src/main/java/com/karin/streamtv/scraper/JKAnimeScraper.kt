package com.karin.streamtv.scraper

import com.karin.streamtv.model.Episode

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
            episodeExtractor = { card ->
                card.selectFirst("span.badge.badge-primary")?.text()?.trim()
                    ?: DynamicParser.findEpisodeNum(card)
            }
        )
    }
}
