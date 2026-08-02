package com.karin.streamtv.scraper

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CalendarParserTest {

    private fun doc(html: String) = Jsoup.parse(html, "https://latanime.org")

    @Test
    fun `parse finds all 7 day tab panes`() {
        val html = """
        <body>
            <div id="lunes-tap-pane">
                <li class="col mb-3">
                    <a href="/anime/one-piece">
                        <img data-src="https://latanime.org/uploads/op.jpg" width="200" height="300">
                        <span class="badge">Próximo - 1122</span>
                        <h3>One Piece</h3>
                    </a>
                </li>
            </div>
            <div id="martes-tap-pane">
                <li class="col mb-3">
                    <a href="/anime/naruto">
                        <img data-src="https://latanime.org/uploads/naruto.jpg" width="200" height="300">
                        <span class="badge">Próximo - 500</span>
                        <h3>Naruto</h3>
                    </a>
                </li>
            </div>
            <div id="miercoles-tap-pane"></div>
            <div id="jueves-tap-pane"></div>
            <div id="viernes-tap-pane"></div>
            <div id="sabado-tap-pane"></div>
            <div id="domingo-tap-pane"></div>
        </body>""".trimIndent()

        val document = doc(html)
        val days = CalendarParser.parse(document)

        assertEquals(7, days.size)
        assertEquals("Lunes", days[0].name)
        assertEquals("Martes", days[1].name)
        assertEquals("Miercoles", days[2].name)
        assertEquals("Jueves", days[3].name)
        assertEquals("Viernes", days[4].name)
        assertEquals("Sabado", days[5].name)
        assertEquals("Domingo", days[6].name)
    }

    @Test
    fun `parse extracts items from Lunes pane`() {
        val html = """
        <body>
            <div id="lunes-tap-pane">
                <li class="col mb-3">
                    <a href="/anime/one-piece">
                        <img data-src="https://latanime.org/uploads/op.jpg" width="200" height="300">
                        <span class="badge">Próximo - 1122</span>
                        <h3>One Piece</h3>
                    </a>
                </li>
                <li class="col mb-3">
                    <a href="/anime/bleach">
                        <img data-src="https://latanime.org/uploads/bleach.jpg" width="200" height="300">
                        <span class="badge">Próximo - 370</span>
                        <h3>Bleach</h3>
                    </a>
                </li>
            </div>
            <div id="martes-tap-pane"></div>
            <div id="miercoles-tap-pane"></div>
            <div id="jueves-tap-pane"></div>
            <div id="viernes-tap-pane"></div>
            <div id="sabado-tap-pane"></div>
            <div id="domingo-tap-pane"></div>
        </body>""".trimIndent()

        val document = doc(html)
        val days = CalendarParser.parse(document)

        assertEquals(2, days[0].items.size)
        assertEquals("One Piece", days[0].items[0].title)
        assertEquals("https://latanime.org/anime/one-piece", days[0].items[0].url)
        assertEquals("https://latanime.org/uploads/op.jpg", days[0].items[0].thumbnailUrl)
        assertEquals("Próximo - 1122", days[0].items[0].nextEpisode)
        assertEquals("Bleach", days[0].items[1].title)
    }

    @Test
    fun `parse handles relative URLs by resolving with baseUri`() {
        val html = """
        <body>
            <div id="lunes-tap-pane">
                <li class="col mb-3">
                    <a href="/anime/test-series">
                        <img data-src="https://latanime.org/uploads/test.jpg" width="200" height="300">
                        <h3>Test Series</h3>
                    </a>
                </li>
            </div>
            <div id="martes-tap-pane"></div>
            <div id="miercoles-tap-pane"></div>
            <div id="jueves-tap-pane"></div>
            <div id="viernes-tap-pane"></div>
            <div id="sabado-tap-pane"></div>
            <div id="domingo-tap-pane"></div>
        </body>""".trimIndent()

        val document = doc(html)
        val days = CalendarParser.parse(document)

        assertEquals(1, days[0].items.size)
        assertEquals("https://latanime.org/anime/test-series", days[0].items[0].url)
        assertEquals("https://latanime.org/uploads/test.jpg", days[0].items[0].thumbnailUrl)
    }

    @Test
    fun `parse skips items without title or href`() {
        val html = """
        <body>
            <div id="lunes-tap-pane">
                <li class="col mb-3">
                    <a href="">
                        <h3></h3>
                    </a>
                </li>
                <li class="col mb-3">
                    <a href="/anime/valid">
                        <h3>Valid Series</h3>
                    </a>
                </li>
            </div>
            <div id="martes-tap-pane"></div>
            <div id="miercoles-tap-pane"></div>
            <div id="jueves-tap-pane"></div>
            <div id="viernes-tap-pane"></div>
            <div id="sabado-tap-pane"></div>
            <div id="domingo-tap-pane"></div>
        </body>""".trimIndent()

        val document = doc(html)
        val days = CalendarParser.parse(document)

        assertEquals(1, days[0].items.size)
        assertEquals("Valid Series", days[0].items[0].title)
    }

    @Test
    fun `parse handles empty pane`() {
        val html = """
        <body>
            <div id="lunes-tap-pane"></div>
            <div id="martes-tap-pane"></div>
            <div id="miercoles-tap-pane"></div>
            <div id="jueves-tap-pane"></div>
            <div id="viernes-tap-pane"></div>
            <div id="sabado-tap-pane"></div>
            <div id="domingo-tap-pane"></div>
        </body>""".trimIndent()

        val document = doc(html)
        val days = CalendarParser.parse(document)

        assertEquals(7, days.size)
        for (day in days) {
            assertTrue(day.items.isEmpty())
        }
    }

    @Test
    fun `parse extracts title from h3 element`() {
        val html = """
        <body>
            <div id="viernes-tap-pane">
                <li class="col mb-3">
                    <a href="/anime/jujutsu-kaisen">
                        <img data-src="https://latanime.org/uploads/jjk.jpg" width="200" height="300">
                        <span class="badge">Próximo - 24</span>
                        <h3>Jujutsu Kaisen</h3>
                    </a>
                </li>
            </div>
            <div id="lunes-tap-pane"></div>
            <div id="martes-tap-pane"></div>
            <div id="miercoles-tap-pane"></div>
            <div id="jueves-tap-pane"></div>
            <div id="sabado-tap-pane"></div>
            <div id="domingo-tap-pane"></div>
        </body>""".trimIndent()

        val document = doc(html)
        val days = CalendarParser.parse(document)

        val viernes = days.first { it.name == "Viernes" }
        assertEquals(1, viernes.items.size)
        assertEquals("Jujutsu Kaisen", viernes.items[0].title)
        assertEquals("Próximo - 24", viernes.items[0].nextEpisode)
    }
}
