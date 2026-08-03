package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.Episode
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

object FrikiserieScraper : GenericScraper() {
    override val name = "FrikiSeries"
    override val baseUrl = "https://www.frikiserie.com"

    override fun buildSearchUrl(query: String): String = baseUrl

    override suspend fun search(query: String): List<Episode> {
        if (query.isBlank()) return emptyList()
        val doc = fetchDocument() ?: return emptyList()
        val all = parseNgState(doc)
        if (all.isEmpty()) return emptyList()
        val q = query.lowercase()
        return all.filter { it.title.lowercase().contains(q) }
    }

    override suspend fun getLatestEpisodes(): List<Episode> {
        val doc = fetchDocument() ?: return emptyList()
        return parseNgState(doc).take(24)
    }

    private fun parseNgState(doc: Document): List<Episode> {
        val script = doc.selectFirst("script#ng-state") ?: return emptyList()
        val raw = script.html().trim()
        if (raw.isEmpty()) return emptyList()
        return try {
            val json = JSONObject(raw)
            val arr = json.optJSONArray("data-info") ?: return emptyList()
            parseDataInfoArray(arr)
        } catch (e: Exception) {
            Log.w("Frikiserie", "Failed to parse ng-state: ${e.message}")
            emptyList()
        }
    }

    private fun parseDataInfoArray(arr: JSONArray): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                val titulo = obj.optString("titulo", "").ifBlank { continue }
                val slug = obj.optString("slug", "").ifBlank { continue }
                val imagen = obj.optString("imagen", "")
                val totalCaps = obj.optString("totalCapitulos", "")
                val year = obj.optInt("fecha", 0)

                val detailUrl = "$baseUrl/lista-series/$slug"

                val epNum = if (totalCaps.isNotBlank() && totalCaps != "0") {
                    try {
                        val n = totalCaps.toInt()
                        if (n > 0) "1-$n" else ""
                    } catch (_: NumberFormatException) { "" }
                } else ""

                val dateStr = if (year > 0) year.toString() else ""

                episodes.add(Episode(
                    title = titulo,
                    url = detailUrl,
                    thumbnailUrl = imagen,
                    date = dateStr,
                    siteName = name,
                    episodeNum = epNum
                ))
            } catch (e: Exception) {
                Log.w("Frikiserie", "Error parsing entry #$i: ${e.message}")
            }
        }
        return episodes
    }

}

class FrikiserieScraperProvider : ScraperProvider {
    override val scraper: BaseScraper = FrikiserieScraper
}
