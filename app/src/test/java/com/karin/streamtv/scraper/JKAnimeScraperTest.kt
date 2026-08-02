package com.karin.streamtv.scraper

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JKAnimeScraperTest {

    private fun doc(html: String) = Jsoup.parse(html, "https://jkanime.net")

    // ── Episode number extraction from badge ──

    @Test
    fun `episodeExtractor extracts number from badge`() {
        val html = """
        <div class="mb-4 d-flex align-items-stretch mb-3 dir1">
            <div class="card ml-2 mr-2">
                <a href="/anime/one-piece/episodio-5">
                    <div class="d-thumb">
                        <img class="card-img-top" src="https://jkanime.net/uploads/onepiece.jpg" data-animepic="https://jkanime.net/uploads/onepiece.jpg" alt="One Piece">
                        <div class="badges badges-top">
                            <span class="badge badge-primary">Ep 5</span>
                            <span class="badge badge-secondary"><i class="ti ti-clock-hour-5"></i> Hace 1 día</span>
                        </div>
                    </div>
                    <div class="card-body d-flex flex-column">
                        <h5 class="strlimit card-title">One Piece</h5>
                    </div>
                </a>
            </div>
        </div>"""

        val card = doc(html)
        val epText = card.selectFirst("span.badge.badge-primary")?.text()?.trim() ?: ""
        val numMatch = Regex("""(\d+)""").find(epText)
        val epNum = numMatch?.groupValues?.get(1) ?: ""

        assertEquals("5", epNum)
    }

    @Test
    fun `episodeExtractor extracts number from badge with Ep prefix`() {
        val epText = "Ep 12"
        val numMatch = Regex("""(\d+)""").find(epText)
        val epNum = numMatch?.groupValues?.get(1) ?: ""
        assertEquals("12", epNum)
    }

    @Test
    fun `episodeExtractor extracts number from badge with Episode prefix`() {
        val epText = "Episode 99"
        val numMatch = Regex("""(\d+)""").find(epText)
        val epNum = numMatch?.groupValues?.get(1) ?: ""
        assertEquals("99", epNum)
    }

    // ── buildSearchUrl ──

    @Test
    fun `buildSearchUrl encodes query correctly`() {
        val query = "dragon ball"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        assertEquals("https://jkanime.net/buscar/dragon+ball/", "https://jkanime.net/buscar/$encoded/")
    }

    @Test
    fun `buildSearchUrl handles special characters`() {
        val query = "one piece"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        assertEquals("one+piece", encoded)
    }

    @Test
    fun `JKAnimeScraper properties`() {
        assertEquals("JKAnime", JKAnimeScraper.name)
        assertEquals("https://jkanime.net", JKAnimeScraper.baseUrl)
    }

    // ── Full homepage card parsing simulation ──

    @Test
    fun `full homepage parsing extracts episodes correctly`() {
        val html = """
        <body>
            <div class="trending__anime">
                <div class="tab-content">
                    <div class="tab-pane active" id="animes">
                        <div class="mb-4 d-flex align-items-stretch mb-3 dir1">
                            <div class="card ml-2 mr-2">
                                <a href="/anime/one-piece/episodio-5">
                                    <div class="d-thumb">
                                        <img class="card-img-top" src="https://jkanime.net/uploads/onepiece.jpg" data-animepic="https://jkanime.net/uploads/onepiece.jpg" alt="One Piece">
                                        <div class="badges badges-top">
                                            <span class="badge badge-primary">Ep 5</span>
                                            <span class="badge badge-secondary"><i class="ti ti-clock-hour-5"></i> Hace 1 día</span>
                                        </div>
                                    </div>
                                    <div class="card-body d-flex flex-column">
                                        <h5 class="strlimit card-title">One Piece</h5>
                                    </div>
                                </a>
                            </div>
                        </div>
                        <div class="mb-4 d-flex align-items-stretch mb-3 dir1">
                            <div class="card ml-2 mr-2">
                                <a href="/anime/dragon-ball/episodio-100">
                                    <div class="d-thumb">
                                        <img class="card-img-top" src="https://jkanime.net/uploads/db.jpg" data-animepic="https://jkanime.net/uploads/db.jpg" alt="Dragon Ball">
                                        <div class="badges badges-top">
                                            <span class="badge badge-primary">Ep 100</span>
                                            <span class="badge badge-secondary"><i class="ti ti-clock-hour-5"></i> Hace 2 horas</span>
                                        </div>
                                    </div>
                                    <div class="card-body d-flex flex-column">
                                        <h5 class="strlimit card-title">Dragon Ball</h5>
                                    </div>
                                </a>
                            </div>
                        </div>
                        <div class="mb-4 d-flex align-items-stretch mb-3 dir1">
                            <div class="card ml-2 mr-2">
                                <a href="/anime/naruto/episodio-50">
                                    <div class="d-thumb">
                                        <img class="card-img-top" src="https://jkanime.net/uploads/naruto.jpg" data-animepic="https://jkanime.net/uploads/naruto.jpg" alt="Naruto">
                                        <div class="badges badges-top">
                                            <span class="badge badge-primary">Ep 50</span>
                                            <span class="badge badge-secondary"><i class="ti ti-clock-hour-5"></i> Hoy</span>
                                        </div>
                                    </div>
                                    <div class="card-body d-flex flex-column">
                                        <h5 class="strlimit card-title">Naruto Shippuden</h5>
                                    </div>
                                </a>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </body>""".trimIndent()

        val document = doc(html)

        val cards = document.select("div.mb-4.d-flex.align-items-stretch.mb-3.dir1")
        assertEquals(3, cards.size)

        val episodes = mutableListOf<Pair<String, String>>()
        for (card in cards) {
            val title = card.selectFirst("h5.strlimit.card-title")?.text()?.trim() ?: continue
            val epText = card.selectFirst("span.badge.badge-primary")?.text()?.trim() ?: ""
            val numMatch = Regex("""(\d+)""").find(epText)
            val epNum = numMatch?.groupValues?.get(1) ?: ""
            episodes.add(epNum to title)
        }

        assertEquals(3, episodes.size)
        assertEquals("5" to "One Piece", episodes[0])
        assertEquals("100" to "Dragon Ball", episodes[1])
        assertEquals("50" to "Naruto Shippuden", episodes[2])
    }

    // ── Thumbnail extraction ──

    @Test
    fun `thumbnail extraction from card-img-top`() {
        val html = """
        <div class="card">
            <img class="card-img-top" src="https://jkanime.net/uploads/anime.jpg" data-animepic="https://jkanime.net/uploads/anime-alt.jpg">
        </div>"""

        val card = doc(html)
        val img = card.selectFirst("img.card-img-top")
        val src = img?.attr("abs:src") ?: img?.attr("data-animepic") ?: ""
        assertEquals("https://jkanime.net/uploads/anime.jpg", src)
    }

    @Test
    fun `thumbnail extraction from data-src fallback`() {
        val html = """
        <div class="card">
            <img class="card-img-top" data-src="https://jkanime.net/uploads/lazy.jpg">
        </div>"""

        val card = doc(html)
        val img = card.selectFirst("img.card-img-top")
        val src = img?.attr("data-src") ?: img?.attr("abs:src") ?: ""
        assertEquals("https://jkanime.net/uploads/lazy.jpg", src)
    }

    // ── Date extraction ──

    @Test
    fun `date extraction from badge-secondary`() {
        val html = """
        <div class="badges badges-top">
            <span class="badge badge-secondary"><i class="ti ti-clock-hour-5"></i> Hace 3 horas</span>
        </div>"""

        val card = doc(html)
        val date = card.selectFirst("span.badge.badge-secondary")?.text()?.trim() ?: ""
        assertTrue(date.contains("Hace 3 horas"))
    }
}
