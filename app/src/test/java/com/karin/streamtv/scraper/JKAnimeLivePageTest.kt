package com.karin.streamtv.scraper

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JKAnimeLivePageTest {

    private fun resource(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: throw AssertionError("resource not found: $name")
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `homepage live html yields cards`() {
        val doc = Jsoup.parse(resource("jk_home.html"), "https://jkanime.net")
        val cards = doc.select("div.trending__anime div.tab-pane#animes div.mb-4.d-flex.align-items-stretch.mb-3.dir1")
        assertTrue("home has dir1 cards", cards.size > 20)
        val withBadge = cards.select("span.badge.badge-primary").size
        assertTrue("cards carry Ep badges", withBadge > 20)
        val first = cards.first()
        val href = first?.selectFirst("a")?.attr("abs:href").orEmpty()
        assertTrue("card links to episode", href.contains("jkanime.net") && href.trim('/').split('/').size >= 4)
    }

    @Test
    fun `homepage live html parses via DynamicParser fallback`() {
        val doc = Jsoup.parse(resource("jk_home.html"), "https://jkanime.net")
        val eps = DynamicParser.parseDynamic(doc, "JKAnime", minCards = 1)
        assertTrue("dynamic parsing extracts episodes", eps.size >= 20)
        val first = eps.first()
        assertTrue("has title", first.title.isNotBlank())
        assertTrue("has url", first.url.contains("jkanime.net"))
    }

    @Test
    fun `directorio live html parses via parseDynamic embedded json`() {
        val doc = Jsoup.parse(resource("jk_directorio.html"), "https://jkanime.net/directorio/")
        assertTrue("var animes marker present", doc.toString().contains("var animes = {"))
        val eps = DynamicParser.parseDynamic(doc, "JKAnime", minCards = 1)
        assertTrue("dir cards extracted", eps.size >= 10)
        val first = eps.first()
        assertTrue("has title", first.title.isNotBlank())
        assertTrue("has url", first.url.contains("jkanime.net"))
    }

    @Test
    fun `buscar live html parses via anime_item cards`() {
        val doc = Jsoup.parse(resource("jk_buscar.html"), "https://jkanime.net/buscar/one+piece/")
        val eps = DynamicParser.parseDynamic(doc, "JKAnime", minCards = 1)
        assertTrue("buscar cards extracted", eps.size >= 5)
        val first = eps.first()
        assertTrue("has title", first.title.isNotBlank())
        assertTrue("has url", first.url.contains("jkanime.net"))
        assertTrue("has thumb", first.thumbnailUrl.isNotBlank())
    }

    @Test
    fun `directorio filtrado live html parses via embedded json`() {
        val doc = Jsoup.parse(resource("jk_dir_filtro.html"), "https://jkanime.net/directorio?filtro=nombre&tipo=TV")
        assertTrue("filtro var animes present", doc.toString().contains("var animes = {"))
        val eps = DynamicParser.parseDynamic(doc, "JKAnime", minCards = 1)
        assertTrue("filtro results extracted", eps.size >= 10)
        val first = eps.first()
        assertTrue("has title", first.title.isNotBlank())
        assertTrue("has url", first.url.contains("jkanime.net"))
    }

    @Test
    fun `serie live html exposes ajax id and csrf token`() {
        val doc = Jsoup.parse(resource("jk_serie.html"), "https://jkanime.net/one-piece/")
        val html = doc.toString()
        val id = Regex("""ajax/episodes/(\d+)/""").find(html)?.groupValues?.get(1)
            ?: Regex("""anime_checks\('[^']+',\s*'(\d+)'""").find(html)?.groupValues?.get(1)
        val token = Regex("""<meta name="csrf-token" content="([^"]+)""")
            .find(html)?.groupValues?.get(1)?.trim()
        assertNotNull("series id extracted", id)
        assertTrue("series id is numeric", id!!.toIntOrNull() != null)
        assertNotNull("csrf token present", token)
        assertFalse("token not blank", token!!.isBlank())
        val slashEps = Regex("""https://jkanime\.net/[a-z0-9-]+/\d+/""").findAll(html).count()
        assertTrue("series page links episode URLs", slashEps > 0)
    }

    @Test
    fun `episode live html carries var servers json`() {
        val doc = Jsoup.parse(resource("jk_ep.html"), "https://jkanime.net/azur-lane-bisoku-zenshin-ni/11/")
        val html = doc.toString()
        val match = Regex("""var\s+servers\s*=\s*(\[[^\]\n]*\]);""", RegexOption.IGNORE_CASE).find(html)
        assertNotNull("var servers JSON found", match)
        val arr = org.json.JSONArray(match!!.groupValues[1])
        assertTrue("server array non-empty", arr.length() > 0)
        val first = arr.getJSONObject(0)
        assertTrue("has remote base64", first.optString("remote").isNotBlank())
        assertTrue("has server name", first.optString("server").isNotBlank())
    }
}