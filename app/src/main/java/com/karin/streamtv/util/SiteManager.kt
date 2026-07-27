package com.karin.streamtv.util

import android.content.Context
import android.content.SharedPreferences
import com.karin.streamtv.model.SiteConfig
import org.json.JSONArray
import org.json.JSONObject

class SiteManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("karin_flix_sites", Context.MODE_PRIVATE)
    private val sites = mutableListOf<SiteConfig>()

    private val defaultConfigs = listOf(
        SiteConfig(name = "Cuevana3", url = "https://www3.cuevana3.is", icon = "C3"),
        SiteConfig(name = "JKAnime", url = "https://jkanime.net", icon = "JA"),
        SiteConfig(name = "LatAnime", url = "https://latanime.org", icon = "LA"),
        SiteConfig(name = "DoramasYT", url = "https://www.doramasyt.com", icon = "DY"),
        SiteConfig(name = "MundoDonghua", url = "https://mundodonghua.com", icon = "MD"),
        SiteConfig(name = "RetroTVE", url = "https://retrotve.com", icon = "RT"),
        SiteConfig(name = "LaCartoons", url = "https://www.lacartoons.com", icon = "LC"),
        SiteConfig(name = "FrikiSeries", url = "https://www.frikiserie.com", icon = "FS"),
    )

    init {
        loadSites()
    }

    fun getSites(): List<SiteConfig> = sites.sortedBy { site ->
        val idx = defaultConfigs.indexOfFirst { it.url == site.url }
        if (idx >= 0) idx else Int.MAX_VALUE
    }

    fun getActiveSites(): List<SiteConfig> = sites.filter { it.isActive }

    fun addSite(site: SiteConfig) {
        sites.add(0, site)
        saveSites()
    }

    fun removeSite(id: String) {
        sites.removeAll { it.id == id }
        saveSites()
    }

    fun updateSite(site: SiteConfig) {
        val index = sites.indexOfFirst { it.id == site.id }
        if (index >= 0) {
            sites[index] = site
            saveSites()
        }
    }

    fun getSiteById(id: String): SiteConfig? = sites.find { it.id == id }

    fun touchLastVisited(id: String) {
        val index = sites.indexOfFirst { it.id == id }
        if (index >= 0) {
            sites[index] = sites[index].copy(lastVisited = System.currentTimeMillis())
            saveSites()
        }
    }

    private fun saveSites() {
        val jsonArray = JSONArray()
        sites.forEach { site ->
            jsonArray.put(JSONObject().apply {
                put("id", site.id)
                put("name", site.name)
                put("url", site.url)
                put("icon", site.icon)
                put("isActive", site.isActive)
                put("lastVisited", site.lastVisited)
            })
        }
        prefs.edit().putString("sites", jsonArray.toString()).apply()
    }

    private fun loadSites() {
        sites.clear()
        val json = prefs.getString("sites", null)
        if (json != null) {
            parseSites(json)
            ensureDefaults()
        } else {
            loadDefaults()
        }
    }

    private fun ensureDefaults() {
        val removedUrls = setOf(
            "https://www.pandrama.tv",
            "https://pandrama.tv",
            "https://pandrama.info",
            "https://pelisflix1.dev",
            "https://www.pelisplushd.la",
            "https://pelisplushd.la",
            "https://pelisplushd.mx",
            "https://pelisplushd.id",
            "https://www.pelisplushd.mx",
            "https://www.pelisplushd.id",
            "https://pelisplus.to",
            "https://pelisplus4k.info"
        )
        var changed = false
        val removed = sites.removeAll { it.url in removedUrls }
        if (removed) changed = true
        for ((pos, def) in defaultConfigs.withIndex()) {
            if (sites.none { it.url == def.url }) {
                sites.add(pos, def)
                changed = true
            }
        }
        if (changed) saveSites()
    }

    private fun parseSites(json: String) {
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                sites.add(
                    SiteConfig(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        icon = obj.optString("icon", "?"),
                        isActive = obj.optBoolean("isActive", true),
                        lastVisited = obj.optLong("lastVisited", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadDefaults() {
        sites.addAll(defaultConfigs)
        saveSites()
    }
}
