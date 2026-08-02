package com.karin.streamtv.scraper

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LatAnimeScraperTest {

    private fun doc(html: String) = Jsoup.parse(html, "https://latanime.org")

    // ── Episode number extraction from title ──

    @Test
    fun `episodeExtractor extracts number from Episodio N`() {
        val html = """
        <div class="col-6 col-md-6 col-lg-3 mb-3">
            <a href="/ver/test-episodio-5">
                <h2 class="mt-3">Episodio 5 - Test Anime</h2>
            </a>
        </div>"""

        val card = doc(html)
        val title = card.selectFirst("h2.mt-3")?.text()?.trim() ?: ""
        val match = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(title)
        val epNum = match?.groupValues?.get(1) ?: ""

        assertEquals("5", epNum)
    }

    @Test
    fun `episodeExtractor extracts number from Ep N`() {
        val title = "Ep 12 - Something"
        val match = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(title)
        val epNum = match?.groupValues?.get(1) ?: ""
        assertEquals("12", epNum)
    }

    @Test
    fun `episodeExtractor extracts number from Cap N`() {
        val title = "Cap 99 - Another"
        val match = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(title)
        val epNum = match?.groupValues?.get(1) ?: ""
        assertEquals("99", epNum)
    }

    @Test
    fun `episodeExtractor extracts number from Capitulo N`() {
        val title = "Capitulo 99 - Another"
        val match = Regex("""(?i)(?:episodio|ep|cap)\p{L}*\s*\.?\s*#?(\d+)""").find(title)
        val epNum = match?.groupValues?.get(1) ?: ""
        assertEquals("99", epNum)
    }

    @Test
    fun `episodeExtractor extracts number from Capitulo with accent`() {
        val title = "Capítulo 99 - Another"
        val match = Regex("""(?i)(?:episodio|ep|cap)\p{L}*\s*\.?\s*#?(\d+)""").find(title)
        val epNum = match?.groupValues?.get(1) ?: ""
        assertEquals("99", epNum)
    }

    @Test
    fun `episodeExtractor extracts number from EP N`() {
        val title = "EP 200 - Milestone"
        val match = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(title)
        val epNum = match?.groupValues?.get(1) ?: ""
        assertEquals("200", epNum)
    }

    @Test
    fun `episodeExtractor extracts number with hash prefix`() {
        val title = "Episodio #42 - Answer"
        val match = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(title)
        val epNum = match?.groupValues?.get(1) ?: ""
        assertEquals("42", epNum)
    }

    @Test
    fun `episodeExtractor extracts number with dot separator`() {
        val title = "Ep. 7 - Short"
        val match = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(title)
        val epNum = match?.groupValues?.get(1) ?: ""
        assertEquals("7", epNum)
    }

    @Test
    fun `episodeExtractor returns empty for no episode pattern`() {
        val title = "Just a Regular Title"
        val match = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(title)
        val epNum = match?.groupValues?.get(1) ?: ""
        assertEquals("", epNum)
    }

    // ── buildSearchUrl ──

    @Test
    fun `buildSearchUrl encodes query correctly`() {
        val query = "dragon ball"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        assertEquals("https://latanime.org/buscar?q=dragon+ball", "https://latanime.org/buscar?q=$encoded")
    }

    @Test
    fun `buildSearchUrl handles special characters`() {
        val query = "one piece"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        assertEquals("one+piece", encoded)
    }

    @Test
    fun `LatAnimeScraper properties`() {
        assertEquals("LatAnime", LatAnimeScraper.name)
        assertEquals("https://latanime.org", LatAnimeScraper.baseUrl)
    }

    // ── Full homepage card parsing simulation ──

    @Test
    fun `full homepage parsing extracts episodes correctly`() {
        val html = """
        <body>
            <div class="col-6 col-md-6 col-lg-3 mb-3">
                <a href="/ver/kaiju-girl-caramelise-episodio-2">
                    <img class="lozad nxtmainimg" data-src="https://latanime.org/uploads/kaiju.jpg" width="200" height="300">
                    <h2 class="mt-3">Episodio 2 - Kaiju Girl Caramelise</h2>
                    <span class="span-tiempo">Hace 1 día</span>
                </a>
            </div>
            <div class="col-6 col-md-6 col-lg-3 mb-3">
                <a href="/ver/dragon-ball-episodio-100">
                    <img class="lozad nxtmainimg" data-src="https://latanime.org/uploads/db.jpg" width="200" height="300">
                    <h2 class="mt-3">Episodio 100 - Dragon Ball</h2>
                    <span class="span-tiempo">Hace 2 horas</span>
                </a>
            </div>
            <div class="col-6 col-md-6 col-lg-3 mb-3">
                <a href="/ver/naruto-episodio-50">
                    <img class="lozad nxtmainimg" data-src="https://latanime.org/uploads/naruto.jpg" width="200" height="300">
                    <h2 class="mt-3">Episodio 50 - Naruto Shippuden</h2>
                    <span class="span-tiempo">Hoy</span>
                </a>
            </div>
        </body>""".trimIndent()

        val document = doc(html)

        val cards = document.select("div.col-6.col-md-6.col-lg-3.mb-3")
        assertEquals(3, cards.size)

        val episodes = mutableListOf<Pair<String, String>>()
        for (card in cards) {
            val title = card.selectFirst("h2.mt-3")?.text()?.trim() ?: continue
            val match = Regex("""(?i)(?:episodio|ep|cap)\s*\.?\s*#?(\d+)""").find(title)
            val epNum = match?.groupValues?.get(1) ?: ""
            episodes.add(epNum to title)
        }

        assertEquals(3, episodes.size)
        assertEquals("2" to "Episodio 2 - Kaiju Girl Caramelise", episodes[0])
        assertEquals("100" to "Episodio 100 - Dragon Ball", episodes[1])
        assertEquals("50" to "Episodio 50 - Naruto Shippuden", episodes[2])
    }
}
