package com.karin.streamtv.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object EpisodeProgress {

    private const val PREFS_NAME = "karin_episode_progress"
    private const val KEY_WATCHED = "watched_episodes"
    private const val KEY_LAST_POSITION = "last_positions"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun markWatched(animeId: String, episodeNumber: Int) {
        val watched = getWatchedEpisodes(animeId).toMutableSet()
        watched.add(episodeNumber)
        val json = JSONArray(watched.toList()).toString()
        prefs?.edit()?.putString(KEY_WATCHED + "_" + animeId, json)?.apply()
    }

    fun isWatched(animeId: String, episodeNumber: Int): Boolean {
        return getWatchedEpisodes(animeId).contains(episodeNumber)
    }

    fun getWatchedEpisodes(animeId: String): Set<Int> {
        val json = prefs?.getString(KEY_WATCHED + "_" + animeId, null) ?: return emptySet()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getInt(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun getWatchedCount(animeId: String): Int {
        return getWatchedEpisodes(animeId).size
    }

    fun getLastWatchedEpisode(animeId: String): Int {
        return prefs?.getInt("last_" + animeId, 0) ?: 0
    }

    fun setLastWatchedEpisode(animeId: String, episodeNumber: Int) {
        prefs?.edit()?.putInt("last_" + animeId, episodeNumber)?.apply()
    }

    fun saveLastPosition(animeId: String, episodeNumber: Int, positionMs: Long) {
        val key = KEY_LAST_POSITION + "_" + animeId + "_" + episodeNumber
        prefs?.edit()?.putLong(key, positionMs)?.apply()
    }

    fun saveLastPosition(animeId: String, episodeNumber: Int, positionMs: Long, durationMs: Long) {
        saveLastPosition(animeId, episodeNumber, positionMs)
        val dkey = "duration_" + animeId + "_" + episodeNumber
        prefs?.edit()?.putLong(dkey, durationMs)?.apply()
    }

    fun getLastPosition(animeId: String, episodeNumber: Int): Long {
        val key = KEY_LAST_POSITION + "_" + animeId + "_" + episodeNumber
        return prefs?.getLong(key, 0L) ?: 0L
    }

    fun getDuration(animeId: String, episodeNumber: Int): Long {
        val key = "duration_" + animeId + "_" + episodeNumber
        return prefs?.getLong(key, 0L) ?: 0L
    }

    fun clearProgress(animeId: String) {
        prefs?.edit()?.remove(KEY_WATCHED + "_" + animeId)?.apply()
        prefs?.edit()?.remove("last_" + animeId)?.apply()
    }

    fun getProgressPercent(animeId: String, totalEpisodes: Int): Int {
        if (totalEpisodes <= 0) return 0
        val watched = getWatchedCount(animeId)
        return ((watched * 100) / totalEpisodes).coerceIn(0, 100)
    }

    fun getRecentAnime(): List<String> {
        val json = prefs?.getString("recent_anime_list", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addToRecent(animeId: String) {
        val recent = getRecentAnime().toMutableList()
        recent.remove(animeId)
        recent.add(0, animeId)
        if (recent.size > 20) recent.removeLast()

        val array = JSONArray()
        recent.forEach { array.put(it) }
        prefs?.edit()?.putString("recent_anime_list", array.toString())?.apply()
    }

    fun generateAnimeId(url: String): String {
        return url.lowercase()
            .replace(Regex("""https?://"""), "")
            .replace(Regex("""/+$"""), "")
            .replace(Regex("""[^a-z0-9]+"""), "_")
    }
}
