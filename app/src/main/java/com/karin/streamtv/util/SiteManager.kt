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
    /** Páginas temporales: solo memoria, nunca se persisten (ver saveSites). */
    private val tempSites = mutableListOf<SiteConfig>()

    private val defaultConfigs = listOf(
        SiteConfig(name = "JKAnime", url = "https://jkanime.net", icon = "JA"),
        SiteConfig(name = "LatAnime", url = "https://latanime.org", icon = "LA"),
        SiteConfig(name = "MundoDonghua", url = "https://www.mundodonghua.com", icon = "MD"),
        SiteConfig(name = "RetroTVE", url = "https://retrotve.com", icon = "RT"),
        SiteConfig(name = "LaCartoons", url = "https://www.lacartoons.com", icon = "LC"),
        SiteConfig(name = "DoramasYT", url = "https://www.doramasyt.com", icon = "DY"),
        SiteConfig(name = "FrikiSeries", url = "https://www.frikiserie.com", icon = "FS"),
        SiteConfig(name = "PeliPops", url = "https://pelispop.mov", icon = "PP"),
        SiteConfig(name = "CineCalidad", url = "https://cine-calidad.mx", icon = "CC"),
        SiteConfig(name = "AnimeFLV", url = "https://vww.animeflv.one", icon = "AF"),
        SiteConfig(name = "DonghuaLife", url = "https://donghualife.com", icon = "DL"),
        SiteConfig(name = "Pandrama", url = "https://www.pandrama.tv", icon = "PA"),
    )

    init {
        loadSites()
    }

    fun getSites(): List<SiteConfig> = (sites + tempSites).sortedBy { site ->
        if (site.isTemporary) Int.MAX_VALUE - 1
        else {
            val idx = defaultConfigs.indexOfFirst { it.url == site.url }
            if (idx >= 0) idx else Int.MAX_VALUE - 2
        }
    }

    fun getActiveSites(): List<SiteConfig> = (sites + tempSites).filter { it.isActive }

    fun getTemporarySites(): List<SiteConfig> = tempSites.toList()

    fun isTemporary(id: String): Boolean = tempSites.any { it.id == id }

    fun addSite(site: SiteConfig) {
        sites.add(0, site.copy(isTemporary = false))
        saveSites()
    }

    /**
     * Añade una página temporal (solo memoria). Devuelve null si la URL
     * ya existe (permanente o temporal).
     */
    fun addTemporarySite(site: SiteConfig): SiteConfig? {
        val url = site.url.trim().trimEnd('/')
        if ((sites + tempSites).any { it.url.trim().trimEnd('/').equals(url, ignoreCase = true) }) {
            return null
        }
        // Nombre único: evita colisiones con scrapers registrados.
        var name = site.name.ifBlank { "Temporal" }.take(32)
        var n = 2
        while ((sites + tempSites).any { it.name.equals(name, ignoreCase = true) }) {
            name = "${site.name.take(28)} $n"
            n++
        }
        val temp = site.copy(name = name, isActive = true, isTemporary = true)
        tempSites.add(0, temp)
        // No se llama a saveSites(): lo temporal no se persiste.
        return temp
    }

    /** Convierte una página temporal en permanente (la guarda en disco). */
    fun promoteTemporary(id: String): SiteConfig? {
        val idx = tempSites.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val temp = tempSites.removeAt(idx)
        val permanent = temp.copy(isTemporary = false)
        sites.add(0, permanent)
        saveSites()
        return permanent
    }

    /** Descarta una página temporal (o una permanente por id). */
    fun discardTemporary(id: String): Boolean {
        val removedTemp = tempSites.removeAll { it.id == id }
        if (removedTemp) return true
        val before = sites.size
        sites.removeAll { it.id == id }
        if (sites.size != before) {
            saveSites()
            return true
        }
        return false
    }

    fun clearTemporary() {
        if (tempSites.isNotEmpty()) tempSites.clear()
    }

    fun removeSite(id: String) {
        if (tempSites.removeAll { it.id == id }) return
        sites.removeAll { it.id == id }
        saveSites()
    }

    fun updateSite(site: SiteConfig) {
        val tempIdx = tempSites.indexOfFirst { it.id == site.id }
        if (tempIdx >= 0) {
            tempSites[tempIdx] = site.copy(isTemporary = true)
            return
        }
        val index = sites.indexOfFirst { it.id == site.id }
        if (index >= 0) {
            sites[index] = site.copy(isTemporary = false)
            saveSites()
        }
    }

    fun getSiteById(id: String): SiteConfig? =
        tempSites.find { it.id == id } ?: sites.find { it.id == id }

    fun getSiteByName(name: String): SiteConfig? =
        tempSites.find { it.name.equals(name, ignoreCase = true) }
            ?: sites.find { it.name.equals(name, ignoreCase = true) }

    fun touchLastVisited(id: String) {
        val tempIdx = tempSites.indexOfFirst { it.id == id }
        if (tempIdx >= 0) {
            tempSites[tempIdx] = tempSites[tempIdx].copy(lastVisited = System.currentTimeMillis())
            return // temporal: sin persistencia
        }
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
            "https://pelisplus4k.info",
            "https://www.youtube.com",
            "https://youtube.com",
            "https://m.youtube.com",
            "https://youtu.be",
            "https://www.mediasetinfinity.es/programas-tv/cuarto-milenio",
            "https://www.mediasetinfinity.es/programas-tv/cuarto-milenio/",
            "https://mediasetinfinity.es/programas-tv/cuarto-milenio"
        )
        var changed = false
        val removed = sites.removeAll { site ->
            site.url in removedUrls
        }
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
                try {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name", "")
                    val url = obj.optString("url", "")
                    // Entrada corrupta/incompleta: se salta sin abortar el resto.
                    if (name.isBlank() || url.isBlank()) continue
                    sites.add(
                        SiteConfig(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            name = name,
                            url = url,
                            icon = obj.optString("icon", "?"),
                            isActive = obj.optBoolean("isActive", true),
                            lastVisited = obj.optLong("lastVisited", 0L)
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
