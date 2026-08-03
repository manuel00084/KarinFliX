package com.karin.streamtv.util

import android.content.Context
import android.content.Intent
import com.karin.streamtv.model.PlaylistItem
import com.karin.streamtv.ui.EmbedWebViewActivity
import org.json.JSONArray
import org.json.JSONObject

object PlaylistQueue {

    fun toJson(items: List<PlaylistItem>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().apply {
                put("title", item.title)
                put("url", item.url)
                put("embedUrl", item.embedUrl)
                put("serverName", item.serverName)
                put("episodeNumber", item.episodeNumber)
            })
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<PlaylistItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val result = ArrayList<PlaylistItem>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    PlaylistItem(
                        title = obj.optString("title", ""),
                        url = obj.optString("url", ""),
                        embedUrl = obj.optString("embedUrl", obj.optString("url", "")),
                        serverName = obj.optString("serverName", ""),
                        episodeNumber = obj.optInt("episodeNumber", 0)
                    )
                )
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun buildIntent(context: Context, items: List<PlaylistItem>, index: Int, siteName: String): Intent {
        val item = items.getOrNull(index) ?: return Intent(context, EmbedWebViewActivity::class.java)
        return Intent(context, EmbedWebViewActivity::class.java).apply {
            putExtra("embed_url", item.embedUrl)
            putExtra("server_name", item.serverName)
            putExtra("video_title", item.title)
            putExtra("episode_url", item.url)
            putExtra("episode_number", item.episodeNumber)
            putExtra("site_name", siteName)
            putExtra("playlist_json", toJson(items))
            putExtra("playlist_index", index)
        }
    }
}
