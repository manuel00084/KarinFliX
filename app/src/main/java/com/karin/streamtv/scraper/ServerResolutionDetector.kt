package com.karin.streamtv.scraper

import android.util.Log
import android.view.ViewGroup
import com.karin.streamtv.model.VideoServer
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.player.VideoExtractorHelper
import com.karin.streamtv.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Detects the real stream resolution(s) offered by a [VideoSource] when the
 * server-selection dialog opens. Resolution is derived from the resolved
 * stream URL: for HLS master playlists the #EXT-X-STREAM-INF RESOLUTION
 * heights are collected; a direct mp4/webm/mkv file is reported as fixed.
 *
 * HTTP-resolvable hosts (dood/byse/voe/mega) are resolved with
 * [ServerDirectResolver]; the rest fall back to a hidden WebView extraction
 * via [VideoExtractorHelper]. Returns a display label or null when the
 * resolution could not be determined.
 */
object ServerResolutionDetector {

    private const val TAG = "ServerResolutionDetector"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Resolves [source] and returns a label like "1080p · 720p · 480p", "1080p", "Fija" or null. */
    suspend fun detect(source: VideoSource, container: ViewGroup, referer: String = ""): String? {
        return try {
            val streamUrl = resolveStreamUrl(source, container, referer) ?: return null
            classify(streamUrl, source.serverUrl)
        } catch (e: Exception) {
            Log.w(TAG, "detect failed for ${source.serverUrl.takeLast(60)}: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun resolveStreamUrl(source: VideoSource, container: ViewGroup, referer: String): String? {
        val embedUrl = source.serverUrl
        if (ServerDirectResolver.usesHttpResolver(embedUrl)) {
            return ServerDirectResolver.resolve(embedUrl, referer)?.url
        }
        val extractor = VideoExtractorHelper(container)
        return try {
            extractor.extractSuspend(embedUrl, source.name, referer)
        } finally {
            extractor.destroy()
        }
    }

    private suspend fun classify(streamUrl: String, embedUrl: String): String? {
        val lower = streamUrl.lowercase()
        return when {
            lower.contains(".m3u8") -> parseHls(streamUrl, embedUrl)
            lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") -> "Fija"
            else -> null
        }
    }

    private suspend fun parseHls(m3u8Url: String, embedUrl: String): String? {
        val body = fetchManifest(m3u8Url, embedUrl) ?: return null
        if (!body.contains("#EXT-X-STREAM-INF")) {
            // Single media playlist -> one fixed rendition.
            return "Fija"
        }
        val heights = mutableListOf<Int>()
        Regex("""RESOLUTION\s*=\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .forEach { m -> m.groupValues[1].toIntOrNull()?.let { heights.add(it) } }
        if (heights.isEmpty()) return "Variable"
        return heights.distinct().sortedDescending().joinToString(" · ") { "${it}p" }
    }

    private suspend fun fetchManifest(url: String, embedUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val referer = try {
                    java.net.URI(embedUrl).host?.let { "https://$it" }
                } catch (_: Exception) { null }
                val rb = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "*/*")
                referer?.let { rb.header("Referer", it) }
                Http.client.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchManifest failed: ${e.message}")
                null
            }
        }
    }
}
