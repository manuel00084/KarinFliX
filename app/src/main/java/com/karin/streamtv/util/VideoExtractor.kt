package com.karin.streamtv.util

import android.util.Base64
import android.util.Log
import com.karin.streamtv.extractor.AtobExtractor
import com.karin.streamtv.extractor.DoodStreamServerExtractor
import com.karin.streamtv.extractor.DsvPlayServerExtractor
import com.karin.streamtv.extractor.FileMoonServerExtractor
import com.karin.streamtv.extractor.MixDropServerExtractor
import com.karin.streamtv.extractor.RegexExtractor
import com.karin.streamtv.extractor.SaveFilesServerExtractor
import com.karin.streamtv.extractor.StreamTapeServerExtractor
import com.karin.streamtv.extractor.StreamWishServerExtractor
import com.karin.streamtv.extractor.VoeServerExtractor
import com.karin.streamtv.model.VideoInfo
import com.karin.streamtv.model.VideoServer
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.model.VideoType
import com.karin.streamtv.scraper.ScrapingEngine
import com.karin.streamtv.util.AdBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup

object VideoExtractor {

    private const val TAG = "VideoExtractor"

    private val client = Http.client

    private val VIDEO_EXTENSIONS = setOf("mp4", "m3u8", "mpd", "webm", "ts")

    // Precompiled regex patterns used by extractSources / extractFromHtml (not extractDirectVideoUrl)
    private object P {
        val playerOrLoadVideo = Regex("""(?:var\s+player\s*=|loadVideo\s*\(\s*)["']?(https?://[^"'\s)]+)["']?""")
        val sourcesBracket = Regex("""(?:sources|videos|files)\s*[=:]\s*\[([^\]]+)\]""")
        val quotedUrl = Regex("""["'](https?://[^"']+)["']""")
        val quotedVideoExt = Regex("""["'](https?://[^"']+\.(?:mp4|m3u8|mpd|webm)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
        val bareVideoUrl = Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8|mpd|webm)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
        val jsPlayerUrl = Regex("""(?:var\s+player\s*=|loadVideo\s*\(\s*)["']?(https?://[^"'\s)]+)["']?""")
        val jsVideoExt = Regex("""["'](https?://[^"']+(?:mp4|m3u8|webm)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val jsSrcEq = Regex("""(?:src|url|file)\s*[:=]\s*["'](https?://[^"']+)["']""")
        val jsEmbedEq = Regex("""(?:embed|iframe)\s*[:=]\s*["'](https?://[^"']+)["']""")
        val mundoSplit = Regex("""['"]([^'"]*)['"]\.split\('\|'\)""")
    }

    suspend fun extractFromPage(url: String): List<VideoInfo> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoInfo>()

        val directVideo = checkDirectVideoUrl(url)
        if (directVideo != null) {
            videos.add(directVideo)
            return@withContext videos
        }

        val doc = ScrapingEngine.fetch(url, "VideoExtractor", url)
        if (doc != null) {
            videos.addAll(extractFromHtml(doc.html(), url))
        }

        return@withContext videos
    }

    suspend fun extractSourcesFromPage(url: String, siteName: String = "VideoExtractor"): List<VideoSource> = withContext(Dispatchers.IO) {
        val sources = mutableListOf<VideoSource>()

        val directVideo = checkDirectVideoUrl(url)
        if (directVideo != null) {
            sources.add(VideoSource(name = "Directo", serverUrl = url, quality = "HD"))
            return@withContext sources
        }

        val doc = ScrapingEngine.fetch(url, siteName, url)
        if (doc != null) {
            sources.addAll(extractSources(doc.html(), url))

            // If the only source is Toroplay (trembed), try to extract real servers from the trembed page
            val toroplaySources = sources.filter { VideoServer.detectServer(it.serverUrl) == VideoServer.TOROPLAY_EMBED }
            if (sources.size == toroplaySources.size) {
                for (ts in toroplaySources) {
                    val trembedSources = extractFromTrembedPage(ts.serverUrl, siteName)
                    if (trembedSources.isNotEmpty()) {
                        sources.remove(ts)
                        sources.addAll(trembedSources)
                    }
                }
            }
        }

        return@withContext sources.sortedByDescending { it.speedRating }
    }

    private suspend fun extractFromTrembedPage(trembedUrl: String, siteName: String): List<VideoSource> {
        try {
            val host = try { java.net.URL(trembedUrl).host } catch (_: Exception) { "" }
            val doc = ScrapingEngine.fetch(trembedUrl, siteName, trembedUrl) ?: return emptyList()
            val html = doc.html()
            val subSources = mutableListOf<VideoSource>()

            // Toroplay often has server data in script vars or data attributes
            // Pattern 1: data-* attributes on server buttons
            doc.select("[data-src], [data-url], [data-embed], [data-video], [data-id]").forEach { el ->
                val value = el.attr("data-src").ifBlank { el.attr("data-url").ifBlank { el.attr("data-embed").ifBlank { el.attr("data-video").ifBlank { el.attr("data-id") } } } }
                if (value.isNotBlank()) {
                    val resolved = resolveUrl(value, trembedUrl) ?: return@forEach
                    if (subSources.any { it.serverUrl == resolved }) return@forEach
                    val server = VideoServer.detectServer(resolved)
                    val name = el.text().trim().ifBlank { el.attr("title").ifBlank { server.displayName } }
                    subSources.add(VideoSource.create(server, resolved, name))
                }
            }

            // Pattern 2: iframes inside the toroplay page
            if (subSources.isEmpty()) {
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                    if (src.isNotBlank() && src != trembedUrl && !src.contains("trembed=")) {
                        val resolved = resolveUrl(src, trembedUrl) ?: return@forEach
                        if (subSources.any { it.serverUrl == resolved }) return@forEach
                        val server = VideoServer.detectServer(resolved)
                        val label = iframe.attr("title").ifBlank { iframe.attr("name").ifBlank { server.displayName } }
                        subSources.add(VideoSource.create(server, resolved, label))
                    }
                }
            }

            // Pattern 3: <a> links to known video servers
            if (subSources.isEmpty()) {
                doc.select("a[href]").forEach { link ->
                    val href = link.attr("abs:href")
                    if (href.isBlank() || href == trembedUrl) return@forEach
                    val server = VideoServer.detectServer(href)
                    if (server != VideoServer.GENERIC && server != VideoServer.TOROPLAY_EMBED) {
                        val name = link.text().trim().ifBlank { server.displayName }
                        subSources.add(VideoSource.create(server, href, name))
                    }
                }
            }

            // Pattern 4: script variables with video URLs
            if (subSources.isEmpty()) {
                doc.select("script").forEach { script ->
                    val text = script.html()
                    val urlPattern = Regex("""["'](https?://[^"']*?(?:streamtape|doodstream|dood|voe|filemoon|mixdrop|mega)[^"']*)["']""", RegexOption.IGNORE_CASE)
                    urlPattern.findAll(text).forEach { match ->
                        val videoUrl = match.groupValues[1]
                        val resolved = resolveUrl(videoUrl, trembedUrl) ?: return@forEach
                        if (subSources.any { it.serverUrl == resolved }) return@forEach
                        val server = VideoServer.detectServer(resolved)
                        subSources.add(VideoSource.create(server, resolved))
                    }
                }
            }

            return subSources
        } catch (e: Exception) {
            Log.e(TAG, "extractFromTrembedPage error: ${e.message}")
            return emptyList()
        }
    }

    suspend fun extractDirectVideoUrl(embedUrl: String, context: android.content.Context? = null): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "extractDirectVideoUrl: $embedUrl")

        val cached = ExtractionCache.get(embedUrl)
        if (cached != null) {
            ExtractionLogger.logServerResult(true, cached.url, method = "Cache")
            return@withContext cached.url
        }

        try {
            val server = VideoServer.detectServer(embedUrl)
            Log.d(TAG, "Detected server: ${server.displayName}")

            val embedHost = try { java.net.URL(embedUrl).host } catch (_: Exception) { "" }
            val embedOrigin = "https://$embedHost"
            ExtractionLogger.logServerStart(server.displayName, embedHost, embedUrl)

            val request = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "$embedOrigin/")
                .header("Origin", embedOrigin)
                .build()

            val response = client.newBuilder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
                .newCall(request)
                .execute()

            val html = response.body?.string() ?: return@withContext null
            val finalUrl = response.request.url.toString()
            val responseContentType = response.header("Content-Type")
            response.close()

            Log.d(TAG, "Fetched ${html.length} chars from $finalUrl (server: ${server.displayName})")
            ExtractionLogger.logHttp("GET", embedUrl, 200, responseContentType, html.length, html.take(300))

            val actualServer = if (finalUrl != embedUrl) VideoServer.detectServer(finalUrl) else server

            // Phase 1: server-specific + MoonGetter in parallel
            ExtractionLogger.logServerSpecificStart(server.displayName)
            val phase1Result = try {
                coroutineScope {
                    val serverSpecificJob = async(Dispatchers.IO) {
                        val result = extractServerSpecific(html, embedUrl, finalUrl, server, actualServer, context)
                        if (result != null) Log.d(TAG, "Server-specific:${server.displayName} succeeded: ${result.take(120)}")
                        ExtractionLogger.logServerResult(result != null, result, method = "Server-specific:${server.displayName}")
                        result
                    }
                    val moonGetterJob = async(Dispatchers.IO) {
                        val result = MoonGetterExtractor.extractVideoUrl(embedUrl)
                        if (result != null) Log.d(TAG, "MoonGetter succeeded: ${result.take(120)}")
                        ExtractionLogger.logServerResult(result != null, result, method = "MoonGetter")
                        result
                    }
                    val r1 = serverSpecificJob.await()
                    val r2 = moonGetterJob.await()
                    r1 ?: r2
                }
            } catch (e: Exception) {
                Log.w(TAG, "Phase 1 failed: ${e.message}")
                null
            }

            if (phase1Result != null) {
                ExtractionCache.put(embedUrl, ExtractedVideo(phase1Result))
                return@withContext phase1Result
            }

            // Phase 2: atob + Rhino + Regex in parallel
            Log.d(TAG, "Phase 1 failed, trying Phase 2 (atob + Rhino + Regex)...")
            val phase2Result = try {
                coroutineScope {
                    val atobJob = async(Dispatchers.IO) {
                        AtobExtractor.extract(html, embedUrl, context)
                    }
                    val rhinoJob = async(Dispatchers.IO) {
                        RhinoExtractor.extractVideoUrl(html, finalUrl)
                    }
                    val regexJob = async(Dispatchers.IO) {
                        RegexExtractor.extract(html, embedUrl, context)
                    }
                    atobJob.await() ?: rhinoJob.await() ?: regexJob.await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Phase 2 failed: ${e.message}")
                null
            }

            if (phase2Result != null) {
                ExtractionLogger.logServerResult(true, phase2Result, method = "Phase2")
                ExtractionCache.put(embedUrl, ExtractedVideo(phase2Result))
                return@withContext phase2Result
            }

            // Phase 3: candidate URLs + redirect check
            Log.d(TAG, "Phase 2 failed, trying Phase 3 (candidates + redirect)...")
            val candidates = RhinoExtractor.extractAllCandidateUrls(html)
            if (candidates.isNotEmpty()) {
                Log.d(TAG, "Fallback: ${candidates.size} candidates, using first: ${candidates[0].take(120)}")
                ExtractionLogger.logFallbackResult("candidateUrls", candidates.size, candidates[0])
                ExtractionCache.put(embedUrl, ExtractedVideo(candidates[0]))
                return@withContext candidates[0]
            }

            if (isVideoUrl(finalUrl)) {
                Log.d(TAG, "Final URL is a video: $finalUrl")
                ExtractionLogger.logServerResult(true, finalUrl, method = "Redirect-is-video")
                ExtractionCache.put(embedUrl, ExtractedVideo(finalUrl))
                return@withContext finalUrl
            }

            // Phase 4: WebView fallback
            Log.d(TAG, "All HTTP methods failed, trying WebView fallback for $embedUrl")
            ExtractionLogger.logServerResult(false, error = "All HTTP methods failed (html=${html.length} chars)", method = "All-exhausted")
            try {
                val webViewResult = WebViewExtractor.extractVideoUrl(embedUrl)
                if (webViewResult != null && isVideoUrl(webViewResult)) {
                    Log.d(TAG, "WebView fallback SUCCESS: ${webViewResult.take(120)}")
                    ExtractionLogger.logServerResult(true, webViewResult, method = "WebView-fallback")
                    ExtractionCache.put(embedUrl, ExtractedVideo(webViewResult))
                    return@withContext webViewResult
                }
                if (webViewResult != null) {
                    Log.w(TAG, "WebView returned non-video URL: ${webViewResult.take(120)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "WebView fallback failed: ${e.message}")
                ExtractionLogger.logServerResult(false, error = e.message, method = "WebView-fallback")
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "extractDirectVideoUrl error: ${e.message}")
            ExtractionLogger.logServerResult(false, error = e.message, method = "Exception")
            null
        }
    }

    private fun extractServerSpecific(html: String, embedUrl: String, finalUrl: String, server: VideoServer, actualServer: VideoServer, context: android.content.Context?): String? {
        val result = when (server) {
            VideoServer.STREAMTAPE -> StreamTapeServerExtractor.extract(html, embedUrl, context)
            VideoServer.DOODSTREAM -> DoodStreamServerExtractor.extract(html, finalUrl, context)
            VideoServer.VOE -> VoeServerExtractor.extract(html, finalUrl, context)
            VideoServer.STREAMSB -> RhinoExtractor.extractFromStreamSB(html)
            VideoServer.MIXDROP -> MixDropServerExtractor.extract(html, finalUrl, context)
            VideoServer.SAVEFILES -> SaveFilesServerExtractor.extract(html, finalUrl, context)
            VideoServer.MEGA -> MegaExtractor.extractVideoUrl(embedUrl, context)
            VideoServer.DSVPLAY -> DsvPlayServerExtractor.extract(html, finalUrl, context)
            VideoServer.STREAMWISH -> StreamWishServerExtractor.extract(html, finalUrl, context)
            VideoServer.FILEMOON -> FileMoonServerExtractor.extract(html, finalUrl, context)
            else -> null
        }
        if (result != null) return result

        if (actualServer != server && actualServer != VideoServer.GENERIC) {
            return when (actualServer) {
                VideoServer.DOODSTREAM -> DoodStreamServerExtractor.extract(html, finalUrl, null)
                VideoServer.VOE -> VoeServerExtractor.extract(html, finalUrl, null)
                VideoServer.SAVEFILES -> SaveFilesServerExtractor.extract(html, finalUrl, null)
                VideoServer.DSVPLAY -> DsvPlayServerExtractor.extract(html, finalUrl, null)
                VideoServer.STREAMWISH -> StreamWishServerExtractor.extract(html, finalUrl, null)
                VideoServer.FILEMOON -> FileMoonServerExtractor.extract(html, finalUrl, null)
                else -> null
            }
        }
        return null
    }

    private fun extractVoeUrl(html: String, finalUrl: String): String? = VoeServerExtractor.extract(html, finalUrl, null)
    private fun extractMixDropUrl(html: String, embedUrl: String): String? = MixDropServerExtractor.extract(html, embedUrl, null)
    private fun extractMegaUrl(embedUrl: String, context: android.content.Context? = null): String? = try { MegaExtractor.extractVideoUrl(embedUrl, context) } catch (_: Exception) { null }
    private fun extractSaveFilesUrl(html: String, embedUrl: String): String? = SaveFilesServerExtractor.extract(html, embedUrl, null)
    private fun extractDoodStreamUrl(html: String, embedUrl: String): String? = DoodStreamServerExtractor.extract(html, embedUrl, null)

    fun extractFromHtml(html: String, baseUrl: String): List<VideoInfo> {
        val videos = mutableListOf<VideoInfo>()
        val doc = Jsoup.parse(html, baseUrl)

        // 1. Extract direct video URLs from video/source tags
        doc.select("video[src], source[src]").forEach { element ->
            val src = element.attr("abs:src").ifBlank { element.attr("src") }
            if (src.isNotBlank() && isVideoUrl(src)) {
                val resolved = resolveUrl(src, baseUrl) ?: return@forEach
                if (!videos.any { it.url == resolved }) {
                    videos.add(VideoInfo(url = resolved, type = getVideoType(resolved)))
                }
            }
        }

        // 2. Extract video URLs from script tags
        doc.select("script").forEach { script ->
            val scriptContent = script.html()

            // Pattern: var player = "url"
            val playerUrls = P.playerOrLoadVideo.findAll(scriptContent)
            playerUrls.forEach { match ->
                val videoUrl = match.groupValues[1]
                val resolved = resolveUrl(videoUrl, baseUrl) ?: return@forEach
                if (!videos.any { it.url == resolved }) {
                    videos.add(VideoInfo(url = resolved, type = getVideoType(resolved)))
                }
            }

            // Pattern: sources = [...]
            val sourceUrls = P.sourcesBracket.findAll(scriptContent)
            sourceUrls.forEach { match ->
                val urls = P.quotedUrl.findAll(match.groupValues[1])
                urls.forEach { urlMatch ->
                    val videoUrl = urlMatch.groupValues[1]
                    val resolved = resolveUrl(videoUrl, baseUrl) ?: return@forEach
                    if (!videos.any { it.url == resolved }) {
                        videos.add(VideoInfo(url = resolved, type = getVideoType(resolved)))
                    }
                }
            }

            // Pattern: direct video URLs in script
            val videoUrlPattern = P.quotedVideoExt
            videoUrlPattern.findAll(scriptContent).forEach { match ->
                val videoUrl = match.groupValues[1]
                val resolved = resolveUrl(videoUrl, baseUrl) ?: return@forEach
                if (!videos.any { it.url == resolved }) {
                    videos.add(VideoInfo(url = resolved, type = getVideoType(resolved)))
                }
            }
        }

        // 3. Extract from data attributes
        doc.select("[data-src], [data-lazy], [data-original]").forEach { element ->
            val src = element.attr("data-src").ifBlank {
                element.attr("data-lazy").ifBlank {
                    element.attr("data-original")
                }
            }

            if (src.isNotBlank() && isVideoUrl(src)) {
                val resolved = resolveUrl(src, baseUrl) ?: return@forEach
                if (!videos.any { it.url == resolved }) {
                    videos.add(VideoInfo(url = resolved, type = getVideoType(resolved)))
                }
            }
        }

        // 4. Fallback: extract any video URL from page content
        val videoUrlPattern = P.bareVideoUrl
        videoUrlPattern.findAll(html).forEach { match ->
            val videoUrl = match.value
            val resolved = resolveUrl(videoUrl, baseUrl) ?: return@forEach
            if (!videos.any { it.url == resolved }) {
                videos.add(VideoInfo(url = resolved, type = getVideoType(resolved)))
            }
        }

        return videos
    }

    fun extractSources(html: String, baseUrl: String): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()
        val doc = Jsoup.parse(html, baseUrl)

        // 1. Extract iframes (most common pattern)
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank() && src != baseUrl) {
                val resolved = resolveUrl(src, baseUrl) ?: return@forEach
                if (sources.any { it.serverUrl == resolved }) return@forEach
                val server = VideoServer.detectFromIframeSrc(resolved)
                val label = iframe.attr("title").ifBlank { iframe.attr("name") }.ifBlank { server.displayName }
                sources.add(VideoSource.create(server, resolved, label))
            }
        }

        // 1a. MundoDonghua: extract servers from packed JS keyword arrays
        if (sources.isEmpty()) {
            sources.addAll(extractMundoDonghuaServers(html, baseUrl))
        }

        // 1b. Extract encrypted player tokens (DoramasYT pattern: button[data-player][data-usa-api])
        //     These use: player[data-key] + button[data-player] → reproductor URL → WebView
        if (sources.isEmpty()) {
            val playerBase = doc.selectFirst("[data-key]")?.attr("data-key") ?: ""
            if (playerBase.isNotBlank()) {
                doc.select("button[data-player][data-usa-api]").forEach { btn ->
                    val token = btn.attr("data-player")
                    if (token.isBlank()) return@forEach
                    val serverName = btn.text().trim().ifBlank { "Servidor" }
                    val reproductorUrl = "$playerBase$token"
                    val resolved = resolveUrl(reproductorUrl, baseUrl) ?: return@forEach
                    if (sources.any { it.serverUrl == resolved }) return@forEach
                    sources.add(VideoSource(
                        name = serverName, serverUrl = resolved,
                        supportsResolutionChange = false,
                        speedRating = 3,
                        isPreferred = serverName.lowercase() in listOf("mega", "voe", "filemoon", "doodstream")
                    ))
                }
            }
        }

        // 2. Extract Base64-encoded data-player/data-video/data-url (LatAnime, JKAnime, Cuevana3, etc.)
        //    These sites encode the embed URL in Base64 — check BEFORE generic data attrs
        val b64Attrs = listOf("data-player", "data-video", "data-url")
        for (attr in b64Attrs) {
            doc.select("[$attr]").forEach { element ->
                val encoded = element.attr(attr)
                if (encoded.isBlank()) return@forEach
                val decoded = tryDecodeBase64(encoded) ?: return@forEach
                val resolved = resolveUrl(decoded, baseUrl) ?: return@forEach
                if (resolved == baseUrl || sources.any { it.serverUrl == resolved }) return@forEach

                val server = VideoServer.detectFromIframeSrc(resolved)
                val displayName = element.text().trim().ifBlank { server.displayName }
                sources.add(VideoSource.create(server, resolved, displayName))
            }
        }

        // 3. Extract server buttons/links from data attributes
        //    Common attribute names across all Latin anime/donghua sites
        //    NOTE: data-player and data-video intentionally excluded (handled in step 2)
        if (sources.isEmpty()) {
            val dataAttrs = listOf(
                "data-url", "data-src", "data-server",
                "data-option", "data-file", "data-embed",
                "data-link", "data-href", "data-location", "data-source",
                "data-videosrc", "data-stream", "data-code", "data-id"
            )
            for (attr in dataAttrs) {
                doc.select("[$attr]").forEach { element ->
                    val value = element.attr(attr)
                    if (value.isBlank()) return@forEach
                    val resolved = resolveUrl(value, baseUrl) ?: return@forEach
                    if (resolved == baseUrl || sources.any { it.serverUrl == resolved }) return@forEach

                    val server = VideoServer.detectFromIframeSrc(resolved)
                    val displayName = element.text().trim().ifBlank {
                        element.attr("title").ifBlank { server.displayName }
                    }
                    if (displayName.isBlank()) return@forEach
                    sources.add(VideoSource.create(server, resolved, displayName))
                }
                if (sources.isNotEmpty()) break
            }
        }

        // 4. Extract <a> links pointing to known video servers
        //    Some sites show server options as clickable <a> tags
        doc.select("a[href]").forEach { link ->
            val href = link.attr("abs:href")
            if (href.isBlank() || href == baseUrl || sources.any { it.serverUrl == href }) return@forEach
            val server = VideoServer.detectServer(href)
            if (server != VideoServer.GENERIC) {
                val name = link.text().trim().ifBlank { link.attr("title").ifBlank { server.displayName } }
                sources.add(VideoSource.create(server, href, name))
            }
        }

        // 5. Extract video URLs from script tags
        doc.select("script").forEach { script ->
            val scriptContent = script.html()

            // Pattern: var player = "url", var video = [...]
            val jsPatterns = listOf(
                P.jsPlayerUrl,
                P.jsVideoExt,
                P.jsSrcEq,
                P.jsEmbedEq,
            )
            for (pattern in jsPatterns) {
                pattern.findAll(scriptContent).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    val resolved = resolveUrl(videoUrl, baseUrl) ?: return@forEach
                    if (sources.any { it.serverUrl == resolved }) return@forEach
                    val server = VideoServer.detectServer(resolved)
                    sources.add(VideoSource.create(server, resolved, isPreferred = true))
                }
            }

            // Pattern: sources/files/videos = [...]
            P.sourcesBracket.findAll(scriptContent).forEach { match ->
                P.quotedUrl.findAll(match.groupValues[1]).forEach { urlMatch ->
                    val videoUrl = urlMatch.groupValues[1]
                    val resolved = resolveUrl(videoUrl, baseUrl) ?: return@forEach
                    if (sources.any { it.serverUrl == resolved }) return@forEach
                    val server = VideoServer.detectServer(resolved)
                    sources.add(VideoSource.create(server, resolved, isPreferred = true))
                }
            }
        }

        return sources
            .distinctBy { it.serverUrl }
            .filter { !AdBlocker.shouldBlockRequest(it.serverUrl) }
            .sortedByDescending { it.speedRating }
    }

    private fun tryDecodeBase64(encoded: String): String? {
        return try {
            val cleaned = encoded.trim().replace(" ", "+").replace("-", "+").replace("_", "/")
            val padding = when (cleaned.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            val bytes = Base64.decode(cleaned + padding, Base64.DEFAULT)
            val decoded = String(bytes, Charsets.UTF_8)
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
        } catch (e: Exception) {
            null
        }
    }

    private fun checkDirectVideoUrl(url: String): VideoInfo? {
        val lower = url.lowercase().trim()
        return when {
            lower.endsWith(".mp4") -> VideoInfo(url, type = VideoType.MP4, isDirectUrl = true)
            lower.endsWith(".m3u8") -> VideoInfo(url, type = VideoType.M3U8, isDirectUrl = true)
            lower.endsWith(".mpd") -> VideoInfo(url, type = VideoType.DASH, isDirectUrl = true)
            lower.endsWith(".webm") -> VideoInfo(url, type = VideoType.MP4, isDirectUrl = true)
            "youtube.com/watch" in lower || "youtu.be/" in lower -> VideoInfo(url, type = VideoType.YOUTUBE)
            "twitch.tv" in lower -> VideoInfo(url, type = VideoType.EMBED)
            "dailymotion.com" in lower -> VideoInfo(url, type = VideoType.EMBED)
            "vimeo.com" in lower -> VideoInfo(url, type = VideoType.EMBED)
            else -> null
        }
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return VIDEO_EXTENSIONS.any { lower.contains(".$it") } ||
                lower.contains(".m3u8?") ||
                lower.contains(".mp4?") ||
                lower.contains(".mpd?")
    }

    private fun getVideoType(url: String): VideoType {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> VideoType.M3U8
            lower.contains(".mpd") -> VideoType.DASH
            lower.contains(".mp4") || lower.contains(".webm") -> VideoType.MP4
            lower.contains("youtube.com") || lower.contains("youtu.be") -> VideoType.YOUTUBE
            lower.contains("twitch.tv") -> VideoType.EMBED
            lower.contains("dailymotion.com") -> VideoType.EMBED
            lower.contains("vimeo.com") -> VideoType.EMBED
            else -> VideoType.DIRECT
        }
    }

    private fun resolveUrl(url: String, baseUrl: String): String? {
        if (url.isBlank()) return null
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed

        return try {
            val base = java.net.URL(baseUrl)
            java.net.URL(base, trimmed).toString()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun pingServer(serverUrl: String): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val domain = try {
                java.net.URL(serverUrl).host
            } catch (_: Exception) { return@withContext -1 }

            val request = Request.Builder()
                .url("https://$domain")
                .head()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Cache-Control", "no-cache")
                .build()

            val response = client.newBuilder()
                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
            response.close()
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        }
    }

    fun adjustSpeedWithPing(speedRating: Int, pingMs: Long): Int {
        if (pingMs < 0) return speedRating
        return when {
            pingMs < 200 -> minOf(speedRating + 1, 5)
            pingMs < 500 -> speedRating
            pingMs < 1500 -> maxOf(speedRating - 1, 1)
            else -> maxOf(speedRating - 2, 1)
        }
    }

    private fun extractMundoDonghuaServers(html: String, baseUrl: String): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()

        if (!html.contains("md-server-tab") && !html.contains("md-player-pane")) return sources

        Log.d(TAG, "MundoDonghua: detected server tabs")

        val doc = Jsoup.parse(html, baseUrl)

        val tabLabels = mutableMapOf<String, String>()
        doc.select("button.md-server-tab[data-target]").forEach { tab ->
            val target = tab.attr("data-target").trim()
            val label = tab.text().trim()
            if (target.isNotEmpty()) {
                tabLabels[target] = label.ifBlank { target }
            }
        }

        val keywordArrays = mutableListOf<List<String>>()
        val splitPattern = P.mundoSplit
        splitPattern.findAll(html).forEach { match ->
            val keywordStr = match.groupValues[1]
            val keywords = keywordStr.split("|")
            if (keywords.size > 10 && keywords.any { it.equals("https", ignoreCase = true) }) {
                keywordArrays.add(keywords)
            }
        }

        Log.d(TAG, "MundoDonghua: found ${keywordArrays.size} keyword arrays, ${tabLabels.size} tabs")

        for (keywords in keywordArrays) {
            val embedUrl = reconstructMundoDonghuaUrl(keywords) ?: continue
            if (sources.any { it.serverUrl == embedUrl }) continue

            val server = VideoServer.detectFromIframeSrc(embedUrl)
            val label = findServerLabel(tabLabels, embedUrl, keywords)

            sources.add(VideoSource.create(server, embedUrl, label))

            Log.d(TAG, "MundoDonghua: found server '$label' → ${embedUrl.take(120)}")
        }

        return sources
    }

    private fun findServerLabel(tabLabels: Map<String, String>, url: String, keywords: List<String>): String {
        val urlLower = url.lowercase()
        for ((target, label) in tabLabels) {
            if (urlLower.contains(target.lowercase())) return label
        }
        for ((target, label) in tabLabels) {
            if (keywords.any { it.lowercase().contains(target.lowercase()) }) return label
        }
        val server = VideoServer.detectFromIframeSrc(url)
        return server.displayName
    }

    private fun reconstructMundoDonghuaUrl(keywords: List<String>): String? {
        val httpsIdx = keywords.indexOfFirst { it.equals("https", ignoreCase = true) }
        if (httpsIdx < 0) return null

        val tlds = setOf("com", "sx", "xyz", "net", "org", "to", "cc", "pw", "io", "pro", "vip")
        val noise = setOf(
            "function", "iframe", "click", "buffer", "console", "append", "remove",
            "true", "false", "var", "tab", "play", "done", "http", "https", "src",
            "width", "height", "image", "thumbnail", "hls", "type", "file", "source",
            "redirector", "fmoon", "amagi", "vhide", "swish", "asura",
            "allowfullscreen", "frameborder", "log", "once", "buffered", "ApplyView",
            "trigger_fmoon", "trigger_amagi", "trigger_vhide", "trigger_swish"
        )

        if (httpsIdx - 2 >= 0 && httpsIdx - 1 >= 0) {
            val tldCandidate = keywords[httpsIdx - 2].lowercase()
            val domainCandidate = keywords[httpsIdx - 1].lowercase()

            if (tlds.contains(tldCandidate) && domainCandidate.length > 2 &&
                !noise.contains(domainCandidate) && !domainCandidate.contains(" ") &&
                domainCandidate.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {

                var videoId = ""
                for (j in httpsIdx - 3 downTo maxOf(0, httpsIdx - 6)) {
                    val kw = keywords[j]
                    if (kw.length > 6 && kw.all { it.isLetterOrDigit() || it == '_' || it == '-' } &&
                        !noise.contains(kw.lowercase()) && !tlds.contains(kw.lowercase())) {
                        videoId = kw
                        break
                    }
                }

                if (videoId.isEmpty()) return null

                val path = if (keywords.any { it.lowercase() == "v" && keywords.indexOf(it) == httpsIdx - 3 }) "/v/" else "/e/"

                return "https://$domainCandidate.$tldCandidate$path$videoId"
            }
        }

        val fullDomain = keywords.firstOrNull { kw ->
            kw.contains(".") && !kw.contains(" ") && !kw.contains("{") && !kw.contains("}") &&
                (kw.endsWith(".com") || kw.endsWith(".sx") || kw.endsWith(".xyz") ||
                    kw.endsWith(".net") || kw.endsWith(".pro") || kw.endsWith(".vip") ||
                    kw.endsWith(".to") || kw.endsWith(".pw") || kw.endsWith(".io") ||
                    kw.endsWith(".cc"))
        }
        if (fullDomain != null && fullDomain.startsWith("http")) {
            return fullDomain
        }

        return null
    }
}
