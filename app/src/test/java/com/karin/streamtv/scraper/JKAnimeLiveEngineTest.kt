package com.karin.streamtv.scraper

import com.karin.streamtv.util.Http
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JKAnimeLiveEngineTest {

    @Before
    fun initHttpMock() {
        val ctx = RuntimeEnvironment.getApplication()
        val cacheDir = File(ctx.cacheDir, "http_cache_test")
        Http.initCache(cacheDir)
        Http.initCookies(ctx)
    }

    @Test
    fun `live getLatestEpisodes via engine returns episodes`() = runBlocking {
        val eps = JKAnimeScraper.getLatestEpisodes()
        assertTrue("live homepage yields episodes (got ${eps.size})", eps.size >= 20)
        val first = eps.first()
        assertTrue("has title", first.title.isNotBlank())
        assertTrue("has url", first.url.contains("jkanime.net"))
        assertTrue("has thumb", first.thumbnailUrl.isNotBlank())
    }

    @Test
    fun `live search via engine returns results`() = runBlocking {
        val eps = JKAnimeScraper.search("one piece")
        assertTrue("live search yields results (got ${eps.size})", eps.size >= 5)
        assertTrue("results link to jkanime", eps.all { it.url.contains("jkanime.net") })
    }

    @Test
    fun `live directory aired series yields episodes end to end`() = runBlocking {
        val dirDoc = ScrapingEngine.fetch("https://jkanime.net/directorio?p=4", "JKAnime", "jk-live-dir-p4")
        assertNotNull("dir page 4 fetched", dirDoc)
        val series = DynamicParser.parseDynamic(dirDoc!!, "JKAnime", minCards = 1)
        assertTrue("dir page 4 has series (got ${series.size})", series.isNotEmpty())
        var anyEpisodes = -1
        for (s in series.take(8)) {
            val doc = ScrapingEngine.fetch(s.url, "JKAnime", "jk-live-dir-p4-serie-${s.url.hashCode()}")
            if (doc == null) continue
            val eps = JKAnimeScraper.fetchSeriesEpisodes(doc, s.url, "JKAnime")
            if (eps.isNotEmpty()) { anyEpisodes = eps.size; break }
        }
        assertTrue("at least one page-4 series yields episodes (last=${anyEpisodes})", anyEpisodes >= 5)
    }

    @Test
    fun `live series episodes via engine POST`() = runBlocking {
        val doc = ScrapingEngine.fetch("https://jkanime.net/one-piece/", "JKAnime", "jk-live-auth")
        assertNotNull("series page fetched", doc)
        val eps = JKAnimeScraper.fetchSeriesEpisodes(doc!!, "https://jkanime.net/one-piece/", "JKAnime")
        assertTrue("episodes POSTed via fresh session (got ${eps.size})", eps.size >= 16)
        val first = eps.first()
        assertTrue("has number", first.episodeNum.toIntOrNull() != null)
        assertTrue("has url", first.url.contains("jkanime.net"))
    }
}