package com.karin.streamtv.scraper

import com.karin.streamtv.model.Episode

interface BaseScraper {
    val name: String
    val baseUrl: String
    suspend fun getLatestEpisodes(): List<Episode>
    suspend fun search(query: String): List<Episode>
}
