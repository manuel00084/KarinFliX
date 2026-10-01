package com.karin.streamtv.util

import com.karin.streamtv.model.SiteConfig
import java.net.URI

/**
 * Lógica pura para páginas temporales: normalizar/validar URLs y
 * construir [SiteConfig] marcados como temporales (no se persisten).
 *
 * No toca Android: se puede probar con JUnit puro.
 */
object TempSiteHelper {

    fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        // Quita trailing slashes (pero conserva raíz simple).
        while (url.endsWith("/") && url.length > "https://x".length) {
            url = url.dropLast(1)
        }
        return url
    }

    fun isValidHttpUrl(raw: String): Boolean {
        val url = raw.trim()
        if (url.isBlank()) return false
        val normalized = normalizeUrl(url)
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
        return try {
            val uri = URI(normalized)
            val host = uri.host ?: return false
            // Host con al menos un punto o localhost/IP: evita "htp:/x".
            host.contains(".") || host == "localhost" || host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))
        } catch (_: Exception) {
            false
        }
    }

    fun deriveName(rawName: String, url: String): String {
        val name = rawName.trim()
        if (name.isNotBlank()) return name.take(32)
        return try {
            val host = URI(normalizeUrl(url)).host?.lowercase() ?: url
            host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }.take(32)
        } catch (_: Exception) {
            url.take(32)
        }
    }

    fun deriveIcon(name: String): String {
        val clean = name.trim().filter { it.isLetterOrDigit() }.uppercase()
        if (clean.isEmpty()) return "⏳"
        return clean.take(2)
    }

    fun buildTempSite(rawName: String, rawUrl: String): SiteConfig? {
        val url = normalizeUrl(rawUrl)
        if (!isValidHttpUrl(url)) return null
        val name = deriveName(rawName, url)
        if (name.isBlank()) return null
        return SiteConfig(
            name = name,
            url = url,
            icon = deriveIcon(name),
            isActive = true,
            isTemporary = true
        )
    }
}
