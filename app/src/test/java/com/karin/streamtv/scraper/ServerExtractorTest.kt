package com.karin.streamtv.scraper

import android.util.Base64
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServerExtractorTest {

    private fun doc(html: String) = Jsoup.parse(html, "https://latanime.org")

    // ── extractDataPlayerServers (via extractServersFromDoc) ──

    @Test
    fun `extractServersFromDoc extracts data-player servers`() {
        val mp4Url = "https://www.mp4upload.com/embed-0eill84h6nux.html"
        val mp4Encoded = Base64.encodeToString(mp4Url.toByteArray(), Base64.DEFAULT).trim()

        val voeUrl = "https://voe.sx/e/abc123"
        val voeEncoded = Base64.encodeToString(voeUrl.toByteArray(), Base64.DEFAULT).trim()

        val html = """
        <body>
            <ul class="cap_repro">
                <a class="play-video repro-item cap" data-player="$mp4Encoded">mp4upload</a>
                <a class="play-video repro-item cap" data-player="$voeEncoded">voe</a>
            </ul>
        </body>""".trimIndent()

        val document = doc(html)
        val servers = ServerExtractor.extractServersFromDoc(document, "https://latanime.org/ver/test-episodio-1")

        assertEquals(2, servers.size)
        val names = servers.map { it.name }
        assertTrue("Should contain mp4upload", names.contains("mp4upload"))
        assertTrue("Should contain voe", names.contains("voe"))
    }

    @Test
    fun `extractServersFromDoc decodes mp4upload URL correctly`() {
        val mp4Url = "https://www.mp4upload.com/embed-0eill84h6nux.html"
        val mp4Encoded = Base64.encodeToString(mp4Url.toByteArray(), Base64.DEFAULT).trim()

        val html = """
        <body>
            <a class="play-video" data-player="$mp4Encoded">mp4upload</a>
        </body>""".trimIndent()

        val document = doc(html)
        val servers = ServerExtractor.extractServersFromDoc(document)

        assertEquals(1, servers.size)
        assertEquals(mp4Url, servers[0].serverUrl)
    }

    @Test
    fun `extractServersFromDoc extracts all 8 LatAnime servers`() {
        val servers_data = listOf(
            "dsvplay" to "https://dsvplay.com/embed/test1",
            "byse" to "https://byse.to/embed/test2",
            "hexload" to "https://hexload.com/embed/test3",
            "savefiles" to "https://savefiles.com/embed/test4",
            "mega" to "https://mega.nz/embed/test5",
            "mixdrop" to "https://mixdrop.co/embed/test6",
            "voe" to "https://voe.sx/e/test7",
            "mp4upload" to "https://mp4upload.com/embed/test8"
        )

        val links = servers_data.joinToString("\n") { (name, url) ->
            val encoded = Base64.encodeToString(url.toByteArray(), Base64.DEFAULT).trim()
            """<a class="play-video repro-item cap" data-player="$encoded">$name</a>"""
        }

        val html = """
        <body>
            <ul class="cap_repro">
                $links
            </ul>
        </body>""".trimIndent()

        val document = doc(html)
        val servers = ServerExtractor.extractServersFromDoc(document)

        assertEquals(8, servers.size)
        val urls = servers.map { it.serverUrl }
        assertTrue(urls.contains("https://dsvplay.com/embed/test1"))
        assertTrue(urls.contains("https://byse.to/embed/test2"))
        assertTrue(urls.contains("https://hexload.com/embed/test3"))
        assertTrue(urls.contains("https://savefiles.com/embed/test4"))
        assertTrue(urls.contains("https://mega.nz/embed/test5"))
        assertTrue(urls.contains("https://mixdrop.co/embed/test6"))
        assertTrue(urls.contains("https://voe.sx/e/test7"))
        assertTrue(urls.contains("https://mp4upload.com/embed/test8"))
    }

    @Test
    fun `extractServersFromDoc deduplicates same URL`() {
        val url = "https://dsvplay.com/embed/same"
        val encoded = Base64.encodeToString(url.toByteArray(), Base64.DEFAULT).trim()

        val html = """
        <body>
            <a class="play-video" data-player="$encoded">dsvplay</a>
            <a class="play-video" data-player="$encoded">dsvplay duplicate</a>
        </body>""".trimIndent()

        val document = doc(html)
        val servers = ServerExtractor.extractServersFromDoc(document)
        assertEquals(1, servers.size)
    }

    // ── isAdUrl ──

    @Test
    fun `isAdUrl detects doubleclick`() {
        assertTrue(ServerExtractor.isAdUrl("https://doubleclick.net/ad"))
    }

    @Test
    fun `isAdUrl detects googlesyndication`() {
        assertTrue(ServerExtractor.isAdUrl("https://pagead2.googlesyndication.com"))
    }

    @Test
    fun `isAdUrl detects popads`() {
        assertTrue(ServerExtractor.isAdUrl("https://popads.net/script"))
    }

    @Test
    fun `isAdUrl detects taboola`() {
        assertTrue(ServerExtractor.isAdUrl("https://cdn.taboola.com/tracker"))
    }

    @Test
    fun `isAdUrl detects outbrain`() {
        assertTrue(ServerExtractor.isAdUrl("https://widgets.outbrain.com/outbrain.js"))
    }

    @Test
    fun `isAdUrl rejects normal video URLs`() {
        assertFalse(ServerExtractor.isAdUrl("https://dsvplay.com/embed/video123"))
        assertFalse(ServerExtractor.isAdUrl("https://voe.sx/e/abc123"))
        assertFalse(ServerExtractor.isAdUrl("https://mp4upload.com/embed/xyz"))
        assertFalse(ServerExtractor.isAdUrl("https://latanime.org/ver/episode-1"))
    }

    // ── extractServersFromDoc with iframes ──

    @Test
    fun `extractServersFromDoc extracts from iframes`() {
        val html = """
        <body>
            <iframe src="https://dsvplay.com/embed/video123"></iframe>
            <iframe src="https://voe.sx/e/abc456"></iframe>
        </body>""".trimIndent()

        val document = doc(html)
        val servers = ServerExtractor.extractServersFromDoc(document)

        assertEquals(2, servers.size)
        val names = servers.map { it.name }
        assertTrue(names.contains("DsvPlay"))
        assertTrue(names.contains("VOE"))
    }

    @Test
    fun `extractServersFromDoc filters ad iframes`() {
        val html = """
        <body>
            <iframe src="https://pagead2.googlesyndication.com/pagead/show_ads.js"></iframe>
            <iframe src="https://dsvplay.com/embed/video123"></iframe>
        </body>""".trimIndent()

        val document = doc(html)
        val servers = ServerExtractor.extractServersFromDoc(document)

        assertEquals(1, servers.size)
        assertEquals("DsvPlay", servers[0].name)
    }

    // ── extractServersFromDoc empty page ──

    @Test
    fun `extractServersFromDoc returns empty for empty page`() {
        val html = "<body><p>No servers here</p></body>"
        val document = doc(html)
        val servers = ServerExtractor.extractServersFromDoc(document)
        assertTrue(servers.isEmpty())
    }

    // ── extractServerTabs fallback ──

    @Test
    fun `extractServersFromDoc falls back to server tabs`() {
        val html = """
        <body>
            <ul class="nav-tabs">
                <li><a class="option" href="#">DsvPlay</a></li>
                <li><a class="option" href="#">VOE</a></li>
                <li><a class="option" href="#">Mp4Upload</a></li>
            </ul>
        </body>""".trimIndent()

        val document = doc(html)
        val servers = ServerExtractor.extractServersFromDoc(document, "https://latanime.org/ver/test")

        assertTrue(servers.isNotEmpty())
        val names = servers.map { it.name }
        assertTrue(names.contains("DsvPlay"))
        assertTrue(names.contains("VOE"))
        assertTrue(names.contains("Mp4Upload"))
    }
}
