package com.karin.streamtv.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object WatchHistory {

    private const val PREFS_NAME = "karin_watch_history"
    private const val KEY_HISTORY = "watch_history"

    private var prefs: SharedPreferences? = null

    data class HistoryEntry(
        val animeId: String,
        val episodeNumber: Int,
        val title: String,
        val siteName: String,
        val thumbnailUrl: String,
        val timestamp: Long = System.currentTimeMillis(),
        val episodeUrl: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun addEntry(entry: HistoryEntry) {
        val entries = getHistory().toMutableList()
        entries.removeAll { it.animeId == entry.animeId && it.episodeNumber == entry.episodeNumber }
        entries.add(0, entry)
        if (entries.size > 100) {
            entries.subList(100, entries.size).clear()
        }
        saveHistory(entries)
    }

    fun getHistory(): List<HistoryEntry> {
        val json = prefs?.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(
                    animeId = obj.optString("animeId", ""),
                    episodeNumber = obj.optInt("episodeNumber", 0),
                    title = obj.optString("title", ""),
                    siteName = obj.optString("siteName", ""),
                    thumbnailUrl = obj.optString("thumbnailUrl", ""),
                    timestamp = obj.optLong("timestamp", 0L),
                    episodeUrl = obj.optString("episodeUrl", ""),
                    positionMs = obj.optLong("positionMs", 0L),
                    durationMs = obj.optLong("durationMs", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getRecentEntries(limit: Int = 30): List<HistoryEntry> {
        return getHistory().take(limit)
    }

    fun getContinueWatching(limit: Int = 6): List<HistoryEntry> {
        return getHistory()
            .filter { it.positionMs > 0 && it.durationMs > 0 && it.positionMs < it.durationMs * 0.95 }
            .distinctBy { it.animeId }
            .take(limit)
    }

    fun wasRecentlyWatched(animeId: String, episodeNumber: Int): Boolean {
        return getHistory().any { it.animeId == animeId && it.episodeNumber == episodeNumber }
    }

    fun clearHistory() {
        prefs?.edit()?.remove(KEY_HISTORY)?.apply()
    }

    private fun saveHistory(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("animeId", entry.animeId)
                put("episodeNumber", entry.episodeNumber)
                put("title", entry.title)
                put("siteName", entry.siteName)
                put("thumbnailUrl", entry.thumbnailUrl)
                put("timestamp", entry.timestamp)
                put("episodeUrl", entry.episodeUrl)
                put("positionMs", entry.positionMs)
                put("durationMs", entry.durationMs)
            })
        }
        prefs?.edit()?.putString(KEY_HISTORY, array.toString())?.apply()
    }
}
