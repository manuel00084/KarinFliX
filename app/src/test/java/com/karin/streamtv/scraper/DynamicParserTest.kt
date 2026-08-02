package com.karin.streamtv.scraper

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DynamicParserTest {

    private fun doc(html: String) = Jsoup.parse(html, "https://latanime.org")

    // ── findTitle ──

    @Test
    fun `findTitle returns h2 text`() {
        val card = doc("<div><h2>Episodio 5 - Dragon Ball</h2></div>")
        assertEquals("Episodio 5 - Dragon Ball", DynamicParser.findTitle(card))
    }

    @Test
    fun `findTitle returns h3 text when no h2`() {
        val card = doc("<div><h3>Naruto Shippuden</h3></div>")
        assertEquals("Naruto Shippuden", DynamicParser.findTitle(card))
    }

    @Test
    fun `findTitle falls back to title class element`() {
        val card = doc("""<div><span class="title-text">One Piece</span></div>""")
        assertEquals("One Piece", DynamicParser.findTitle(card))
    }

    @Test
    fun `findTitle falls back to bold element`() {
        val card = doc("<div><b>Bleach Thousand Year</b></div>")
        assertEquals("Bleach Thousand Year", DynamicParser.findTitle(card))
    }

    @Test
    fun `findTitle returns null for empty card`() {
        val card = doc("<div></div>")
        assertNull(DynamicParser.findTitle(card))
    }

    // ── findThumbnail ──

    @Test
    fun `findThumbnail returns data-src from img`() {
        val card = doc("""<div><img data-src="https://latanime.org/img/cover.jpg" class="lozad"></div>""")
        assertEquals("https://latanime.org/img/cover.jpg", DynamicParser.findThumbnail(card))
    }

    @Test
    fun `findThumbnail prefers data-src over src`() {
        val card = doc("""<div><img data-src="https://latanime.org/lazy.jpg" src="https://latanime.org/placeholder.png"></div>""")
        assertEquals("https://latanime.org/lazy.jpg", DynamicParser.findThumbnail(card))
    }

    @Test
    fun `findThumbnail returns abs src when no data-src`() {
        val card = doc("""<div><img src="/img/direct.jpg"></div>""")
        assertEquals("https://latanime.org/img/direct.jpg", DynamicParser.findThumbnail(card))
    }

    @Test
    fun `findThumbnail returns empty for no images`() {
        val card = doc("<div><p>No images here</p></div>")
        assertEquals("", DynamicParser.findThumbnail(card))
    }

    @Test
    fun `findThumbnail extracts from background-image style`() {
        val card = doc("""<div style="background-image: url('https://latanime.org/bg.jpg')"><p>text</p></div>""")
        assertEquals("https://latanime.org/bg.jpg", DynamicParser.findThumbnail(card))
    }

    // ── findUrl ──

    @Test
    fun `findUrl returns href from anchor`() {
        val card = doc("""<div><a href="/ver/one-piece-episodio-1">Link</a></div>""")
        assertEquals("https://latanime.org/ver/one-piece-episodio-1", DynamicParser.findUrl(card))
    }

    @Test
    fun `findUrl skips anchor-only links`() {
        val card = doc("""<div><a href="#section">Jump</a><a href="/anime/naruto">Watch</a></div>""")
        assertEquals("https://latanime.org/anime/naruto", DynamicParser.findUrl(card))
    }

    @Test
    fun `findUrl skips social media links`() {
        val card = doc("""<div><a href="https://facebook.com/page">FB</a><a href="/ver/episode">Watch</a></div>""")
        assertEquals("https://latanime.org/ver/episode", DynamicParser.findUrl(card))
    }

    @Test
    fun `findUrl returns null for no links`() {
        val card = doc("<div><p>Nothing</p></div>")
        assertNull(DynamicParser.findUrl(card))
    }

    // ── findEpisodeNum ──

    @Test
    fun `findEpisodeNum extracts from Episodio N`() {
        val card = doc("<div>Episodio 42</div>")
        assertEquals("42", DynamicParser.findEpisodeNum(card))
    }

    @Test
    fun `findEpisodeNum extracts from Ep N pattern`() {
        val card = doc("<div>Ep 7</div>")
        assertEquals("7", DynamicParser.findEpisodeNum(card))
    }

    @Test
    fun `findEpisodeNum extracts from Cap N pattern`() {
        val card = doc("<div>Capítulo 15</div>")
        assertEquals("15", DynamicParser.findEpisodeNum(card))
    }

    @Test
    fun `findEpisodeNum extracts from #N pattern`() {
        val card = doc("<div>Episode #99</div>")
        assertEquals("99", DynamicParser.findEpisodeNum(card))
    }

    @Test
    fun `findEpisodeNum returns empty for no number`() {
        val card = doc("<div>Just some text</div>")
        assertEquals("", DynamicParser.findEpisodeNum(card))
    }

    // ── isEpisodeUrl ──

    @Test
    fun `isEpisodeUrl matches ver pattern`() {
        assertTrue(DynamicParser.isEpisodeUrl("https://latanime.org/ver/one-piece-episodio-1"))
    }

    @Test
    fun `isEpisodeUrl matches -episodio- pattern`() {
        assertTrue(DynamicParser.isEpisodeUrl("https://latanime.org/anime/one-piece-episodio-5"))
    }

    @Test
    fun `isEpisodeUrl matches episode pattern`() {
        assertTrue(DynamicParser.isEpisodeUrl("https://example.com/episode/123"))
    }

    @Test
    fun `isEpisodeUrl does not match series page`() {
        assertFalse(DynamicParser.isEpisodeUrl("https://latanime.org/anime/one-piece"))
    }

    @Test
    fun `isEpisodeUrl does not match homepage`() {
        assertFalse(DynamicParser.isEpisodeUrl("https://latanime.org/"))
    }

    // ── findDate ──

    @Test
    fun `findDate extracts relative time`() {
        val card = doc("""<div><span class="span-tiempo">Hace 3 horas</span></div>""")
        assertEquals("Hace 3 horas", DynamicParser.findDate(card))
    }

    @Test
    fun `findDate extracts from time element text`() {
        val card = doc("""<div><time datetime="2025-07-30">30 jul</time></div>""")
        assertEquals("30 jul", DynamicParser.findDate(card))
    }

    @Test
    fun `findDate extracts from datetime attr when no text`() {
        val card = doc("""<div><time datetime="2025-07-30"></time></div>""")
        assertEquals("2025-07-30", DynamicParser.findDate(card))
    }

    @Test
    fun `findDate returns empty for no date`() {
        val card = doc("<div><p>No date here</p></div>")
        assertEquals("", DynamicParser.findDate(card))
    }

    // ── parseDynamic with LatAnime homepage HTML ──

    @Test
    fun `parseDynamic parses LatAnime homepage cards`() {
        val html = """
        <body>
            <div class="col-6 col-md-6 col-lg-3 mb-3">
                <a href="/ver/kaiju-girl-caramelise-episodio-2">
                    <img class="lozad nxtmainimg" data-src="https://latanime.org/uploads/cover1.jpg" width="200" height="300">
                    <h2 class="mt-3">Episodio 2 - Kaiju Girl</h2>
                    <span class="span-tiempo">Hace 1 día</span>
                </a>
            </div>
            <div class="col-6 col-md-6 col-lg-3 mb-3">
                <a href="/ver/dragon-ball-episodio-100">
                    <img class="lozad nxtmainimg" data-src="https://latanime.org/uploads/cover2.jpg" width="200" height="300">
                    <h2 class="mt-3">Episodio 100 - Dragon Ball</h2>
                    <span class="span-tiempo">Hace 2 horas</span>
                </a>
            </div>
            <div class="col-6 col-md-6 col-lg-3 mb-3">
                <a href="/ver/naruto-episodio-50">
                    <img class="lozad nxtmainimg" data-src="https://latanime.org/uploads/cover3.jpg" width="200" height="300">
                    <h2 class="mt-3">Episodio 50 - Naruto Shippuden</h2>
                    <span class="span-tiempo">Hoy</span>
                </a>
            </div>
        </body>""".trimIndent()

        val document = doc(html)
        val episodes = DynamicParser.parseDynamic(document, "LatAnime")

        assertEquals(3, episodes.size)
        assertEquals("Episodio 2 - Kaiju Girl", episodes[0].title)
        assertEquals("https://latanime.org/ver/kaiju-girl-caramelise-episodio-2", episodes[0].url)
        assertEquals("https://latanime.org/uploads/cover1.jpg", episodes[0].thumbnailUrl)
        assertEquals("Hace 1 día", episodes[0].date)
        assertEquals("2", episodes[0].episodeNum)
    }

    // ── parseDynamic with LatAnime directory HTML ──

    @Test
    fun `parseDynamic parses LatAnime directory series cards`() {
        val html = """
        <body>
            <div class="col-md-4 col-lg-3 col-xl-2 col-6 my-3">
                <a href="/anime/one-piece">
                    <div class="series">
                        <img class="lozad" data-src="https://latanime.org/uploads/onepiece.jpg" width="200" height="300">
                        <div class="seriedetails">
                            <h3>One Piece</h3>
                        </div>
                    </div>
                </a>
            </div>
            <div class="col-md-4 col-lg-3 col-xl-2 col-6 my-3">
                <a href="/anime/naruto">
                    <div class="series">
                        <img class="lozad" data-src="https://latanime.org/uploads/naruto.jpg" width="200" height="300">
                        <div class="seriedetails">
                            <h3>Naruto</h3>
                        </div>
                    </div>
                </a>
            </div>
            <div class="col-md-4 col-lg-3 col-xl-2 col-6 my-3">
                <a href="/anime/bleach">
                    <div class="series">
                        <img class="lozad" data-src="https://latanime.org/uploads/bleach.jpg" width="200" height="300">
                        <div class="seriedetails">
                            <h3>Bleach</h3>
                        </div>
                    </div>
                </a>
            </div>
        </body>""".trimIndent()

        val document = doc(html)
        val episodes = DynamicParser.parseDynamic(document, "LatAnime")

        assertTrue(episodes.size >= 3)
        val titles = episodes.map { it.title }
        assertTrue(titles.contains("One Piece"))
        assertTrue(titles.contains("Naruto"))
        assertTrue(titles.contains("Bleach"))
    }

    // ── parseSeriesPage with LatAnime series HTML ──

    @Test
    fun `parseSeriesPage extracts title from h2`() {
        val html = """
        <body>
            <h2>Kaiju Girl Caramelise</h2>
            <div class="serieimgficha">
                <img class="img-fluid2" src="https://latanime.org/uploads/kaiju-cover.jpg">
            </div>
            <div class="col-lg-9">
                <p>Una historia sobre una chica que se transforma en kaiju cuando se emociona demasiado. Una mezcla única de romance y acción.</p>
            </div>
        </body>""".trimIndent()

        val document = doc(html)
        val series = DynamicParser.parseSeriesPage(document, "https://latanime.org", "LatAnime")

        assertEquals("Kaiju Girl Caramelise", series.title)
        assertEquals("https://latanime.org/uploads/kaiju-cover.jpg", series.coverUrl)
        assertTrue(series.description.contains("Una historia sobre una chica"))
    }

    @Test
    fun `parseSeriesPage extracts episodes from cap-layout links`() {
        val html = """
        <body>
            <h2>Test Anime</h2>
            <a href="/ver/test-anime-episodio-1">
                <div class="cap-layout">
                    <img class="lozad" data-src="https://latanime.org/uploads/ep1.jpg" alt="Episodio 1" width="200" height="100">
                </div>
            </a>
            <a href="/ver/test-anime-episodio-2">
                <div class="cap-layout">
                    <img class="lozad" data-src="https://latanime.org/uploads/ep2.jpg" alt="Episodio 2" width="200" height="100">
                </div>
            </a>
            <a href="/ver/test-anime-episodio-3">
                <div class="cap-layout">
                    <img class="lozad" data-src="https://latanime.org/uploads/ep3.jpg" alt="Episodio 3" width="200" height="100">
                </div>
            </a>
        </body>""".trimIndent()

        val document = doc(html)
        val series = DynamicParser.parseSeriesPage(document, "https://latanime.org", "LatAnime")

        assertEquals(3, series.episodes.size)
        assertEquals("1", series.episodes[0].episodeNum)
        assertEquals("2", series.episodes[1].episodeNum)
        assertEquals("3", series.episodes[2].episodeNum)
    }

    @Test
    fun `parseSeriesPage extracts episode numbers from title text`() {
        val html = """
        <body>
            <h2>Test Anime</h2>
            <a href="/ver/test-anime-episodio-15">
                <div class="cap-layout">
                    <h3>Episodio 15</h3>
                </div>
            </a>
        </body>""".trimIndent()

        val document = doc(html)
        val series = DynamicParser.parseSeriesPage(document, "https://latanime.org", "LatAnime")

        assertEquals(1, series.episodes.size)
        assertEquals("15", series.episodes[0].episodeNum)
    }

    // ── findCards ──

    @Test
    fun `findCards finds LatAnime div series cards`() {
        val html = """
        <body>
            <div class="series"><a href="/anime/a"><img src="/img/a.jpg" width="100" height="100"><h3>A</h3></a></div>
            <div class="series"><a href="/anime/b"><img src="/img/b.jpg" width="100" height="100"><h3>B</h3></a></div>
            <div class="series"><a href="/anime/c"><img src="/img/c.jpg" width="100" height="100"><h3>C</h3></a></div>
        </body>""".trimIndent()

        val document = doc(html)
        val cards = DynamicParser.findCards(document, 3)
        assertEquals(3, cards.size)
    }

    @Test
    fun `findCards returns empty when insufficient cards`() {
        val html = """
        <body>
            <p>Just some text</p>
            <p>More text</p>
        </body>""".trimIndent()

        val document = doc(html)
        val cards = DynamicParser.findCards(document, 3)
        assertTrue(cards.isEmpty())
    }

    // ── findNextPageUrl ──

    @Test
    fun `findNextPageUrl finds rel next link`() {
        val html = """
        <body>
            <a rel="next" href="/animes?p=2">Next</a>
        </body>""".trimIndent()

        val document = doc(html)
        val nextUrl = DynamicParser.findNextPageUrl(document, "https://latanime.org/animes?p=1")
        assertEquals("https://latanime.org/animes?p=2", nextUrl)
    }

    @Test
    fun `findNextPageUrl returns null when no next`() {
        val html = "<body><p>No pagination</p></body>"
        val document = doc(html)
        val nextUrl = DynamicParser.findNextPageUrl(document, "https://latanime.org/animes")
        assertNull(nextUrl)
    }
}
