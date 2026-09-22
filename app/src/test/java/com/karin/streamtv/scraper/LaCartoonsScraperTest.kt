package com.karin.streamtv.scraper

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LaCartoonsScraperTest {

    private fun doc(html: String) = Jsoup.parse(html, "https://www.lacartoons.com")

    private fun resource(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: throw AssertionError("resource not found: $name")
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `real serie html yields 12 episodes`() {
        val d = Jsoup.parse(resource("lc_serie.html"), "https://www.lacartoons.com/serie/1")
        val eps = LaCartoonsScraper.fetchSeriesEpisodes(d, "https://www.lacartoons.com/serie/1")
        assertEquals(12, eps.size)
        assertEquals("https://www.lacartoons.com/serie/capitulo/1?t=1", eps[0].url)
        assertEquals("1", eps[0].episodeNum)
        assertEquals("2 Perros Tontos", LaCartoonsScraper.fetchSeriesTitle(d))
        assertTrue(LaCartoonsScraper.fetchSeriesDescription(d).length > 20)
    }

    private val dirHtml = """
        <div class="conjuntos-series">
          <a href="/serie/1">
            <div class="serie serie-Activa">
              <img src="/rails/active_storage/poster1.jpg" />
              <div class="informacion-serie"><div>
                <p class="nombre-serie">2 Perros Tontos</p>
                <span class="marcador marcadorSeries marcador-cartoon">Cartoon Network</span>
                <span class="marcador marcador-ano">1993</span>
                <span class="valoracion">7<span class="fa fa-star"></span></span>
              </div></div>
            </div>
          </a>
          <a href="/serie/2">
            <div class="serie serie-Activa">
              <img src="/rails/active_storage/poster2.jpg" />
              <div class="informacion-serie"><div>
                <p class="nombre-serie">Caballeros del Zodiaco</p>
                <span class="marcador marcador-ano">1986</span>
              </div></div>
            </div>
          </a>
        </div>""".trimIndent()

    private val serieHtml = """
        <h2 class="text-center subtitulo-serie-seccion ">2 Perros Tontos
          <span class="marcador marcador-cartoon">Cartoon Network</span>
        </h2>
        <div class="imagen-serie"><img src="/rails/active_storage/cover1.jpg" /></div>
        <div class="informacion-serie-seccion">
          <p>Episodios:<span>12</span></p>
          <p>Reseña:<br><span>Dos perros tontos era un dibujo animado sobre un par de perros.</span></p>
        </div>
        <div class="contenedor-episondios">
          <h4 class="accordion" data-temporada-id="1"><span></span> Temporada 1</h4>
          <div class="episodio-panel"><ul class="listas-de-episodion">
            <li><a class="active" href="/serie/capitulo/1?t=1"><span>Capitulo 1-</span> El problema de la puerta</a></li>
            <li><a class="" href="/serie/capitulo/2?t=1"><span>Capitulo 2-</span> Hojuela de maíz</a></li>
            <li><a class="" href="/serie/capitulo/3?t=1"><span>Capitulo 3-</span> Bufet en Las Vegas</a></li>
          </ul></div>
        </div>
        <aside class="temporada-recomendaciones">
          <a href="/serie/380"><div class="serie"><p class="nombre-serie">Ciudad de Perros</p></div></a>
        </aside>""".trimIndent()

    @Test
    fun `directory cards parse to series`() {
        val series = LaCartoonsScraper.parseDirectory(doc(dirHtml))
        assertEquals(2, series.size)
        assertEquals("2 Perros Tontos", series[0].title)
        assertEquals("https://www.lacartoons.com/serie/1", series[0].url)
        assertTrue("poster resolved absolute", series[0].thumbnailUrl.startsWith("https://www.lacartoons.com/rails/"))
        assertEquals("Caballeros del Zodiaco", series[1].title)
    }

    @Test
    fun `series page yields episodes excluding recommendations`() {
        val eps = LaCartoonsScraper.fetchSeriesEpisodes(doc(serieHtml), "https://www.lacartoons.com/serie/1")
        assertEquals(3, eps.size)
        assertTrue("no recommendation links", eps.none { it.url.contains("/serie/380") })
        assertEquals("https://www.lacartoons.com/serie/capitulo/1?t=1", eps[0].url)
        assertEquals("1", eps[0].episodeNum)
        assertTrue("title kept", eps[2].title.contains("Bufet"))
        assertTrue("poster attached", eps[0].thumbnailUrl.startsWith("https://www.lacartoons.com/rails/"))
    }

    @Test
    fun `multi season prefixes season`() {
        val html = """
            <div class="contenedor-episondios">
              <h4 data-temporada-id="1">Temporada 1</h4>
              <div><ul class="listas-de-episodion">
                <li><a href="/serie/capitulo/1?t=1"><span>Capitulo 1-</span> Uno</a></li>
              </ul></div>
              <h4 data-temporada-id="2">Temporada 2</h4>
              <div><ul class="listas-de-episodion">
                <li><a href="/serie/capitulo/20?t=2"><span>Capitulo 1-</span> Dos</a></li>
              </ul></div>
            </div>""".trimIndent()
        val eps = LaCartoonsScraper.fetchSeriesEpisodes(doc(html), "https://www.lacartoons.com/serie/9")
        assertEquals(2, eps.size)
        assertFalse(eps[0].title.startsWith("T"))
        assertTrue("season 2 prefixed", eps[1].title.startsWith("T2"))
        assertTrue("season param kept", eps[1].url.contains("?t=2"))
    }

    @Test
    fun `series title and description extracted`() {
        val d = doc(serieHtml)
        assertEquals("2 Perros Tontos", LaCartoonsScraper.fetchSeriesTitle(d))
        assertTrue(LaCartoonsScraper.fetchSeriesDescription(d).startsWith("Dos perros tontos"))
    }

    @Test
    fun `dynamic parser routes lacartoons directory`() {
        val eps = DynamicParser.parseDynamic(doc(dirHtml), "LaCartoons", minCards = 1)
        assertTrue("dir parsed (got ${eps.size})", eps.size >= 2)
        assertTrue(eps.all { it.url.contains("/serie/") })
    }
}
