package com.karin.streamtv.util

import android.util.Log
import com.karin.streamtv.model.Episode
import com.karin.streamtv.scraper.ScraperRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object SearchManager {

    private const val TAG = "SearchManager"

    data class SearchResult(
        val title: String,
        val url: String,
        val posterUrl: String = "",
        val site: String = "",
        val episodeNum: String = "",
        val date: String = "",
        val episode: Episode? = null
    )

    suspend fun searchAll(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val scrapers = ScraperRegistry.allSites
        Log.d(TAG, "Searching '${query}' across ${scrapers.size} sites")

        val allResults = coroutineScope {
            scrapers.map { scraper ->
                async {
                    try {
                        val episodes = scraper.search(query)
                        episodes.map { ep ->
                            SearchResult(
                                title = ep.title,
                                url = ep.url,
                                posterUrl = ep.thumbnailUrl,
                                site = ep.siteName.ifBlank { scraper.name },
                                episodeNum = ep.episodeNum,
                                date = ep.date,
                                episode = ep
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Search failed on ${scraper.name}: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        Log.d(TAG, "Total results: ${allResults.size}")
        allResults.sortedBy { it.site }
    }
}
