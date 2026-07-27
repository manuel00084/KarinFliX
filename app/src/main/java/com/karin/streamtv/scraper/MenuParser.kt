package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.MenuSection
import com.karin.streamtv.model.SiteMenuItem
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Extracts site navigation menus from homepage HTML using heuristics.
 * Works without hardcoded selectors — adapts to any site structure.
 */
object MenuParser {

    private const val TAG = "MenuParser"

    /**
     * Known section keywords (Spanish/English) mapped to MenuSection.
     */
    private val sectionKeywords = mapOf(
        "nuevos episodios" to MenuSection.NEW_EPISODES,
        "nuevos" to MenuSection.NEW_EPISODES,
        "recientes" to MenuSection.NEW_EPISODES,
        "latest" to MenuSection.NEW_EPISODES,
        "directorio" to MenuSection.DIRECTORY,
        "animes" to MenuSection.DIRECTORY,
        "lista" to MenuSection.DIRECTORY,
        "listado" to MenuSection.DIRECTORY,
        "catalogo" to MenuSection.DIRECTORY,
        "directory" to MenuSection.DIRECTORY,
        "generos" to MenuSection.GENRES,
        "genero" to MenuSection.GENRES,
        "categorias" to MenuSection.GENRES,
        "categoria" to MenuSection.GENRES,
        "genres" to MenuSection.GENRES,
        "calendario" to MenuSection.SCHEDULE,
        "horario" to MenuSection.SCHEDULE,
        "schedule" to MenuSection.SCHEDULE,
        "populares" to MenuSection.POPULAR,
        "popular" to MenuSection.POPULAR,
        "tendencia" to MenuSection.POPULAR,
        "trending" to MenuSection.POPULAR,
        "peliculas" to MenuSection.MOVIES,
        "pelicula" to MenuSection.MOVIES,
        "movies" to MenuSection.MOVIES,
        "series" to MenuSection.SERIES,
        "anime" to MenuSection.ANIME,
        "donghua" to MenuSection.DONGHUA,
        "dorama" to MenuSection.DORAMA,
        "temporada" to MenuSection.SEASONAL,
        "seasonal" to MenuSection.SEASONAL,
        "estrenos" to MenuSection.SEASONAL
    )

    /**
     * Excluded link keywords (social media, login, etc.)
     */
    private val excluded = setOf(
        "facebook", "twitter", "instagram", "youtube", "tiktok",
        "login", "register", "signup", "sign in", "sign up",
        "newsletter", "rss", "feed", "wp-", "cdn.", "google.",
        "telegram", "discord", "patreon", "paypal", "donar"
    )

    /**
     * Extract navigation menu items from a document.
     * Returns items sorted by section priority.
     */
    fun extractMenu(doc: Document): List<SiteMenuItem> {
        val items = mutableSetOf<SiteMenuItem>()

        // Find navigation containers
        val containers = findNavContainers(doc)

        for (container in containers) {
            container.select("a[href]").forEach { link ->
                val item = parseLink(link, doc.baseUri())
                if (item != null) items.add(item)
            }
        }

        // Also check header and any .menu elements
        doc.select("header a[href], .menu a[href], .nav a[href], [role='navigation'] a[href]").forEach { link ->
            val item = parseLink(link, doc.baseUri())
            if (item != null) items.add(item)
        }

        val sorted = items.sortedByDescending { it.section.ordinal }
        Log.d(TAG, "Extracted ${sorted.size} menu items")
        return sorted
    }

    private fun findNavContainers(doc: Document): List<Element> {
        val containers = mutableListOf<Element>()

        // Try common navigation patterns
        val selectors = listOf(
            "nav ul", "nav ol",
            "nav",
            ".menu", ".navigation", ".navbar",
            "header ul",
            "#menu", "#navigation", "#navbar",
            "[role='navigation']",
            ".nav-menu", ".main-menu", ".primary-menu",
            "div:has(> ul > li > a[href])"  // any div with direct ul > li > a structure
        )

        for (sel in selectors) {
            val found = doc.select(sel)
            if (found.isNotEmpty()) {
                containers.addAll(found)
                // Stop after finding a good nav pattern (usually just 1 nav)
                if (found.size <= 3) break
            }
        }

        return containers.distinct()
    }

    private fun parseLink(link: Element, baseUrl: String): SiteMenuItem? {
        val href = link.attr("abs:href").ifBlank { return null }

        // Skip external links, anchors, javascript, excluded keywords
        val lower = href.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("#")) return null
        if (excluded.any { lower.contains(it) }) return null

        val text = link.text().trim().ifBlank {
            link.attr("title").trim().ifBlank { return null }
        }
        if (text.length > 30 || text.length < 2) return null

        val section = detectSection(text, href)

        // Skip "home", "inicio" type links at root
        if (section == MenuSection.OTHER && isHomeLink(text, href, baseUrl)) return null

        return SiteMenuItem(text, href, section)
    }

    private fun detectSection(text: String, href: String): MenuSection {
        val combined = "$text $href".lowercase()

        // Direct keyword match (priority: most specific first)
        for ((keyword, section) in sectionKeywords.entries) {
            if (combined.contains(keyword)) return section
        }

        return MenuSection.OTHER
    }

    private fun isHomeLink(text: String, href: String, baseUrl: String): Boolean {
        val lowerText = text.lowercase()
        if (lowerText in setOf("inicio", "home", "inici", "main", "homepage")) return true
        if (href.trimEnd('/') == baseUrl.trimEnd('/')) return true
        return false
    }
}
