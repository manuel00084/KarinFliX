package com.karin.streamtv.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object AniSkipService {

    private const val TAG = "AniSkipService"
    private const val ANISKIP_BASE = "https://api.aniskip.com/v2"
    private const val ANILIST_BASE = "https://graphql.anilist.co"

    data class SkipInterval(
        val startTime: Double,
        val endTime: Double,
        val skipType: String
    )

    data class SkipResult(
        val opening: SkipInterval?,
        val ending: SkipInterval?,
        val malId: Int?
    )

    private val malIdCache = ConcurrentHashMap<String, Int>()
    private val skipCache = ConcurrentHashMap<String, SkipResult>()

    suspend fun getSkipTimes(title: String, episodeNumber: Int): SkipResult {
        val cacheKey = "${title.lowercase().trim()}|${episodeNumber}"
        skipCache[cacheKey]?.let { return it }

        val malId = resolveMalId(title)
        if (malId == null) {
            Log.d(TAG, "Could not resolve MAL ID for: $title")
            val empty = SkipResult(null, null, null)
            skipCache[cacheKey] = empty
            return empty
        }

        val result = fetchSkipTimes(malId, episodeNumber)
        skipCache[cacheKey] = result
        return result
    }

    private suspend fun resolveMalId(title: String): Int? {
        val cleanTitle = title
            .replace(Regex("\\s*\\(.*?\\)"), "")
            .replace(Regex("\\s*\\[.*?\\]"), "")
            .replace(Regex("Episodio\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Capitulo\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Temporada\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\b(sub|dub|español|latino|castellano|hd|sd|fhd|4k|1080p|720p|480p)\\b"), "")
            .replace(Regex("\\s*[-–—]\\s*$"), "")
            .trim()

        if (cleanTitle.isBlank()) return null

        val cacheKey = cleanTitle.lowercase()
        malIdCache[cacheKey]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val dollar = "$"
                val query = JSONObject().apply {
                    put("query", "query(${dollar}search: String) { Media(search: ${dollar}search, type: ANIME) { idMal title { romaji english } } }")
                    put("variables", JSONObject().put("search", cleanTitle))
                }

                val conn = URL(ANILIST_BASE).openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                }

                conn.outputStream.write(query.toString().toByteArray())
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(response)
                val media = json.optJSONObject("data")?.optJSONObject("Media")
                val malId = media?.optInt("idMal", -1)?.takeIf { it > 0 }

                if (malId != null) {
                    malIdCache[cacheKey] = malId
                    val titles = media.optJSONObject("title")
                    Log.d(TAG, "Resolved '$cleanTitle' → MAL $malId (${titles?.opt("romaji")})")
                }

                malId
            } catch (e: Exception) {
                Log.w(TAG, "AniList lookup failed for '$cleanTitle': ${e.message}")
                null
            }
        }
    }

    private suspend fun fetchSkipTimes(malId: Int, episodeNumber: Int): SkipResult {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$ANISKIP_BASE/skip-times/$malId/$episodeNumber?types=op&types=ed")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(response)
                val found = json.optBoolean("found", false)
                if (!found) {
                    Log.d(TAG, "No skip times for MAL $malId ep $episodeNumber")
                    return@withContext SkipResult(null, null, malId)
                }

                val results = json.optJSONArray("results") ?: JSONArray()
                var opening: SkipInterval? = null
                var ending: SkipInterval? = null

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val type = item.optString("skipType", "")
                    val interval = item.optJSONObject("interval") ?: continue
                    val start = interval.optDouble("startTime", -1.0)
                    val end = interval.optDouble("endTime", -1.0)

                    if (start < 0 || end < 0) continue

                    when (type) {
                        "op" -> opening = SkipInterval(start, end, "op")
                        "ed" -> ending = SkipInterval(start, end, "ed")
                    }
                }

                Log.d(TAG, "MAL $malId ep $episodeNumber: op=${opening != null} ed=${ending != null}")
                SkipResult(opening, ending, malId)

            } catch (e: Exception) {
                Log.w(TAG, "AniSkip fetch failed for MAL $malId ep $episodeNumber: ${e.message}")
                SkipResult(null, null, malId)
            }
        }
    }

    fun clearCache() {
        skipCache.clear()
        malIdCache.clear()
    }
}
