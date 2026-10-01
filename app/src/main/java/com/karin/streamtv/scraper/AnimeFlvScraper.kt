package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import com.karin.streamtv.util.HtmlClean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * AnimeFLV mirror (vww.animeflv.one).
 *
 * Estructura verificada en vivo:
 * - Home últimos episodios: `div.ul.hm article.li figure.i a[href*="/ver/"]`
 *   con `u` = "Episodio N", `span` = título de la serie,
 *   `img[data-src]` = portada (cdn portada webp).
 * - Búsqueda/directorio: `GET /animes?buscar=q` con resultados en
 *   `div.ul.x6 article.li` (`figure.i a` + `h3.h a`, `img[data-src]`, `u` = tipo).
 * - Ficha de serie: `/anime/slug` con lista JS `var eps` + `data-sl`.
 *   URLs tipo ver-slug-num.
 * - Episodio reproducible: `/ver/slug-num`.
 *
 * Los servidores se extraen con el [ServerExtractor] genérico (iframes).
 */
object AnimeFlvScraper : GenericScraper() {
    override val name = "AnimeFLV"
    override val baseUrl = "https://vww.animeflv.one"

    override fun buildSearchUrl(query: String): String =
        "$baseUrl/animes?buscar=${java.net.URLEncoder.encode(query, "UTF-8")}"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        val eps = parseLatestCards(doc)
        if (eps.isNotEmpty()) return eps
        return DynamicParser.parseDynamic(doc, name)
    }

    override suspend fun search(query: String): List<Episode> {
        if (query.isBlank()) return emptyList()
        val searchUrl = buildSearchUrl(query)
        Log.d("AnimeFLV", "Searching: $searchUrl")
        val doc = withContext(Dispatchers.IO) {
            engine.fetch(searchUrl, name, "${name}::search::${query.lowercase().take(50)}")
        } ?: return emptyList()
        val eps = parseDirectoryCards(doc)
        if (eps.isNotEmpty()) return eps
        return DynamicParser.parseDynamic(doc, name)
    }

    /** Home: `div.ul.hm article.li` → episodios reproducibles (/ver/). */
    fun parseLatestCards(doc: Document): List<Episode> {
        val out = mutableListOf<Episode>()
        doc.select("div.ul.hm article.li, div.ul.hm.listado article.li").forEach { card ->
            try {
                val link = card.selectFirst("figure.i a[href]") ?: return@forEach
                val url = HtmlClean.resolveUrl(doc.baseUri(), link.attr("href"))
                if (url.isBlank() || !url.contains("/ver/")) return@forEach
                val series = HtmlClean.clean(
                    link.selectFirst("span")?.text().orEmpty()
                        .ifBlank { link.attr("title").removePrefix("Ver ").substringBefore(" episodio") }
                )
                if (series.isBlank()) return@forEach
                val epRaw = link.selectFirst("u")?.text().orEmpty().trim()
                val epNum = Regex("""(\d+)""").find(epRaw)?.groupValues?.get(1).orEmpty()
                val title = if (epNum.isNotBlank()) "$series Episodio $epNum" else series
                val thumb = extractThumb(link, doc.baseUri())
                out.add(Episode(title, url, thumb, "", name, epNum))
            } catch (e: Exception) {
                Log.w("AnimeFLV", "latest card failed: ${e.message}")
            }
        }
        Log.d("AnimeFLV", "parseLatestCards → ${out.size}")
        return out.distinctBy { it.url }
    }

    /** Directorio/búsqueda: `div.ul.x6 article.li` → fichas de serie (/anime/). */
    fun parseDirectoryCards(doc: Document): List<Episode> {
        val out = mutableListOf<Episode>()
        doc.select("div.ul.x6 article.li, div.ul article.li").forEach { card ->
            try {
                val link = card.selectFirst("figure.i a[href]")
                    ?: card.selectFirst("h3.h a[href]")
                    ?: return@forEach
                val url = HtmlClean.resolveUrl(doc.baseUri(), link.attr("href"))
                if (url.isBlank() || !url.contains("/anime/")) return@forEach
                val title = HtmlClean.clean(
                    card.selectFirst("h3.h a")?.text().orEmpty()
                        .ifBlank { link.attr("title").removePrefix("Ver Anime ").substringBefore(" Online") }
                        .ifBlank { link.selectFirst("img")?.attr("alt").orEmpty() }
                )
                if (title.isBlank()) return@forEach
                val thumb = extractThumb(card, doc.baseUri())
                val type = HtmlClean.clean(card.selectFirst("figure.i u")?.text().orEmpty())
                out.add(Episode(title, url, thumb, type, name, ""))
            } catch (e: Exception) {
                Log.w("AnimeFLV", "directory card failed: ${e.message}")
            }
        }
        // Si el home (div.ul.hm) se coló en /animes (cabecera "Nuevos episodios"),
        // parseLatestCards lo cubre; aquí solo devolvemos fichas /anime/.
        Log.d("AnimeFLV", "parseDirectoryCards → ${out.size}")
        return out.distinctBy { it.url }
    }

    /**
     * Ficha `/anime/<slug>`: la lista de capítulos vive en
     * `var eps = [["220","0",""],...]` y el slug en `*[data-sl]`.
     * Se construye `./ver/<slug>-<num>(-<cod>)?`.
     */
    fun fetchSeriesEpisodes(doc: Document, seriesUrl: String, siteName: String = name): List<Episode> {
        val html = doc.toString()
        val slug = doc.selectFirst("[data-sl]")?.attr("data-sl")?.trim()
            .orEmpty()
            .ifBlank {
                try { java.net.URI(seriesUrl).path.trim('/').substringAfterLast('/') }
                catch (_: Exception) { "" }
            }
        if (slug.isBlank()) {
            Log.w("AnimeFLV", "fetchSeriesEpisodes: sin slug para $seriesUrl")
            return emptyList()
        }
        val epsRaw = Regex("""var\s+eps\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val arr = try { org.json.JSONArray(epsRaw) } catch (e: Exception) {
            Log.w("AnimeFLV", "eps JSON parse failed: ${e.message}")
            return emptyList()
        }
        val seriesTitle = HtmlClean.clean(
            doc.selectFirst(".info-t .ti h1")?.text().orEmpty()
                .ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
                .ifBlank { doc.title().substringBefore(" Online").substringAfter("Ver ").trim() }
        ).ifBlank { slug.replace("-", " ") }
        val cover = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?.let { HtmlClean.resolveUrl(doc.baseUri(), it) }
            .orEmpty()
            .ifBlank {
                doc.selectFirst(".info-l .i img")?.let {
                    HtmlClean.resolveUrl(doc.baseUri(), it.attr("abs:src").ifBlank { it.attr("data-src") })
                }.orEmpty()
            }
        val origin = try {
            val uri = java.net.URI(seriesUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) { baseUrl }
        val out = mutableListOf<Episode>()
        for (i in 0 until arr.length()) {
            try {
                val row = arr.optJSONArray(i) ?: continue
                val num = row.optString(0).trim()
                if (num.isBlank()) continue
                val cod = row.optString(2, "").trim()
                val url = "$origin/ver/$slug-$num" + if (cod.isNotEmpty()) "-$cod" else ""
                out.add(
                    Episode(
                        title = "$seriesTitle Episodio $num",
                        url = url,
                        thumbnailUrl = cover,
                        date = "",
                        siteName = siteName,
                        episodeNum = num
                    )
                )
            } catch (_: Exception) { }
        }
        Log.d("AnimeFLV", "Fetched ${out.size} episodes for '$slug'")
        return out.distinctBy { it.url }
    }

    private fun extractThumb(scope: org.jsoup.nodes.Element, base: String): String {
        val img = scope.selectFirst("img[data-src]") ?: scope.selectFirst("img[src]")
        ?: return ""
        val raw = img.attr("data-src").ifBlank { img.attr("abs:src").ifBlank { img.attr("src") } }
        if (raw.isBlank() || raw.contains("episode.png") || raw.contains("anime.png")) {
            // Placeholder: si hay data-src real úsalo, si no descarta.
            val real = img.attr("data-src")
            if (real.isBlank() || real.contains("episode.png") || real.contains("anime.png")) return ""
            return HtmlClean.resolveUrl(base, real)
        }
        return HtmlClean.resolveUrl(base, raw)
    }
}

class AnimeFlvScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = AnimeFlvScraper
}
