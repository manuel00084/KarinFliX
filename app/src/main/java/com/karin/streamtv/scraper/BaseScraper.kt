package com.karin.streamtv.scraper

import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.EpisodeNavigation
import com.karin.streamtv.model.VideoSource

interface BaseScraper {
    val name: String
    val baseUrl: String
    suspend fun getLatestEpisodes(): List<Episode>
    suspend fun search(query: String): List<Episode>

    /**
     * Extract video servers from an episode page.
     * Default implementation uses generic ServerExtractor.
     * Override for site-specific logic.
     */
    suspend fun extractServers(episodeUrl: String): List<VideoSource> =
        ServerExtractor.extractServers(episodeUrl, name)

    /**
     * Extract prev/next episode navigation links from an episode page.
     * Scrapes the actual page HTML to find navigation links.
     * Default implementation uses generic selectors.
     */
    suspend fun scrapeEpisodeNavigation(episodeUrl: String): EpisodeNavigation =
        ServerExtractor.extractEpisodeNavigation(episodeUrl, name)
}
