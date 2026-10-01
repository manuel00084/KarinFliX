package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Scraper genérico para páginas temporales añadidas por el usuario.
 *
 * No hay parser específico: usa [ScrapingEngine] + [DynamicParser]
 * (portada, búsqueda y paginación) y [ServerExtractor] para servidores.
 * Vive solo en memoria: se registra en [ScraperRegistry] al crear el
 * sitio temporal y se retira al descartarlo o cerrar la app.
 */
class TempScraper(
    override val name: String,
    override val baseUrl: String
) : GenericScraper() {

    private val tag = "TempScraper/$name"

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = withContext(Dispatchers.IO) {
            ScrapingEngine.fetch(baseUrl, name, "${name}::${baseUrl.hashCode()}", forceFresh = false)
        } ?: return emptyList()
        var episodes = DynamicParser.parseDynamic(doc, name)
        if (episodes.isEmpty()) {
            episodes = DynamicParser.parseEpisodeLinks(doc, name)
        }
        Log.d(tag, "getLatestEpisodes → ${episodes.size}")
        return episodes
    }

    override suspend fun search(query: String): List<Episode> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        // Patrones de búsqueda más comunes en WordPress/streaming.
        val candidates = listOf(
            "$baseUrl/?s=$q",
            "$baseUrl/search/$q",
            "$baseUrl/buscar/$q",
            "$baseUrl/search.html?query=$q"
        )
        for (url in candidates) {
            try {
                val doc = withContext(Dispatchers.IO) {
                    ScrapingEngine.fetch(url, name, "${name}::search::${url.takeLast(60)}")
                } ?: continue
                val results = DynamicParser.parseDynamic(doc, name)
                if (results.isNotEmpty()) {
                    Log.d(tag, "search '$query' → ${results.size} via $url")
                    return results
                }
                val links = DynamicParser.parseEpisodeLinks(doc, name)
                if (links.isNotEmpty()) {
                    Log.d(tag, "search '$query' → ${links.size} (links) via $url")
                    return links
                }
            } catch (e: Exception) {
                Log.w(tag, "search candidate failed $url: ${e.message}")
            }
        }
        Log.d(tag, "search '$query' → 0 resultados")
        return emptyList()
    }
}
