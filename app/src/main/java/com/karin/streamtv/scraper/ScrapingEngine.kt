package com.karin.streamtv.scraper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import com.karin.streamtv.util.Http
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

object ScrapingEngine {

    private const val TAG = "ScrapingEngine"
    private var appContext: Context? = null
    private var cacheDir: File? = null

    // region --- Settings ---
    var minRequestIntervalMs: Long = 200L
    var maxRetries: Int = 2
    var memCacheMaxSize: Int = 60
    var memCacheTtlMs: Long = 15 * 60 * 1000L
    var diskCacheTtlMs: Long = 6 * 60 * 60 * 1000L
    var staleCacheMaxTtlMs: Long = 14L * 24 * 60 * 60 * 1000L
    var diskCacheMaxBytes: Long = 20L * 1024 * 1024
    var maxConcurrentRequests: Int = 8
    var circuitBreakerThreshold: Int = 3
    var circuitBreakerCooldownMs: Long = 5 * 60 * 1000L
    // endregion

    // region --- User-Agent rotation (2026 modern browsers) ---
    private data class BrowserProfile(
        val ua: String,
        val secChUa: String,
        val secChUaPlatform: String,
        val secChUaMobile: String
    )

    private val browserProfiles = listOf(
        BrowserProfile(
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            secChUaPlatform = "\"Windows\"",
            secChUaMobile = "?0"
        ),
        BrowserProfile(
            ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            secChUaPlatform = "\"macOS\"",
            secChUaMobile = "?0"
        ),
        BrowserProfile(
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"130\", \"Chromium\";v=\"130\", \"Not_A Brand\";v=\"24\"",
            secChUaPlatform = "\"Windows\"",
            secChUaMobile = "?0"
        ),
        BrowserProfile(
            ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            secChUa = "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            secChUaPlatform = "\"Linux\"",
            secChUaMobile = "?0"
        ),
        BrowserProfile(
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            secChUa = "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            secChUaPlatform = "\"Windows\"",
            secChUaMobile = "?0"
        ),
        BrowserProfile(
            ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
            secChUa = "",
            secChUaPlatform = "\"macOS\"",
            secChUaMobile = "?0"
        )
    )
    private val userAgentIndex = AtomicInteger(0)

    // Hosts that needed a Cloudflare bypass. Their cf_clearance cookie is bound to a specific
    // User-Agent, so every request to these hosts must reuse the UA the challenge was solved with
    // (fixed Chrome/131 desktop profile) instead of rotating. Key: host.
    private val cfLockedHosts = ConcurrentHashMap<String, String>()

    /** Returns the UA string that must be used for an already-CF-locked [host], or null if unlocked. */
    fun getLockedUa(host: String): String? = cfLockedHosts[host].also {
        if (it != null) Log.d(TAG, "Using CF-locked UA for host=$host")
    }

    // Fixed mobile Android Chrome/131 profile shared by the CF WebView and every retry to a
    // CF-protected host. Cloudflare binds cf_clearance to the exact UA string, so both must match.
    private val cfLockedProfile = BrowserProfile(
        ua = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        secChUa = "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        secChUaPlatform = "\"Android\"",
        secChUaMobile = "?1"
    )

    // Serialize the WebView CF bypass per host so concurrent fetches share a single solve.
    private val cfBypassMutexes = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val cfResolvedHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun hostOf(url: String): String {
        return try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
    }

    private fun nextBrowserProfile(): BrowserProfile {
        val idx = (userAgentIndex.getAndIncrement() and Int.MAX_VALUE) % browserProfiles.size
        return browserProfiles[idx]
    }
    // endregion

    // region --- In-Memory Cache ---
    private data class MemCacheEntry(val html: String, val timestamp: Long)

    private val memCache = object : LinkedHashMap<String, MemCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemCacheEntry>?): Boolean =
            size > memCacheMaxSize
    }

    private fun memGet(key: String): MemCacheEntry? = synchronized(memCache) { memCache[key] }
    private fun memPut(key: String, entry: MemCacheEntry) = synchronized(memCache) { memCache[key] = entry }
    private fun memRemove(key: String) = synchronized(memCache) { memCache.remove(key) }
    private fun memClear() = synchronized(memCache) { memCache.clear() }
    // endregion

    // region --- Disk Cache ---
    fun init(context: Context) {
        appContext = context.applicationContext
        cacheDir = context.getDir("scraper_cache", Context.MODE_PRIVATE).also { it.mkdirs() }
        Log.i(TAG, "Disk cache at: ${cacheDir?.absolutePath}")
    }

    private fun diskCacheFile(key: String): File? {
        val dir = cacheDir ?: return null
        val safeName = key.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(200)
        return File(dir, safeName)
    }

    private fun diskPut(key: String, html: String) {
        val file = diskCacheFile(key) ?: return
        try {
            file.writeText(html, Charsets.UTF_8)
            evictIfNeeded()
        } catch (e: Exception) {
            Log.w(TAG, "Disk cache write failed: ${e.message}")
        }
    }

    private fun diskGet(key: String, allowStale: Boolean = false): String? {
        val file = diskCacheFile(key) ?: return null
        if (!file.exists()) return null
        val ageMs = System.currentTimeMillis() - file.lastModified()
        if (!allowStale && ageMs > diskCacheTtlMs) {
            file.delete()
            return null
        }
        if (allowStale && ageMs > staleCacheMaxTtlMs) {
            file.delete()
            return null
        }
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun diskRemove(key: String) {
        diskCacheFile(key)?.delete()
    }

    private fun diskClear() {
        cacheDir?.listFiles()?.forEach { it.delete() }
    }

    private fun evictIfNeeded() {
        val dir = cacheDir ?: return
        val files = dir.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= diskCacheMaxBytes) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= diskCacheMaxBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                Log.i(TAG, "Disk cache evicted ${f.name}")
            }
        }
    }
    // endregion

    // region --- OkHttp Client ---
    private val httpClient = Http.client
    // endregion

    // region --- Concurrent Request Limiter ---
    private val concurrencySemaphore = Semaphore(maxConcurrentRequests)

    private suspend fun <T> withConcurrencyLimit(block: suspend () -> T): T {
        concurrencySemaphore.acquire()
        try {
            return block()
        } finally {
            concurrencySemaphore.release()
        }
    }
    // endregion

    // region --- Circuit Breaker ---
    private data class CircuitState(
        var consecutiveFails: Int = 0,
        var lastFailTime: Long = 0,
        var tripped: Boolean = false
    )

    private val circuitStates = ConcurrentHashMap<String, CircuitState>()

    private fun checkCircuitBreaker(siteName: String): Boolean {
        val state = circuitStates.computeIfAbsent(siteName) { CircuitState() }
        if (!state.tripped) return true

        if (System.currentTimeMillis() - state.lastFailTime > circuitBreakerCooldownMs) {
            state.tripped = false
            state.consecutiveFails = 0
            Log.i(TAG, "Circuit breaker reset for $siteName")
            return true
        }

        Log.w(TAG, "Circuit breaker OPEN for $siteName (${(System.currentTimeMillis() - state.lastFailTime) / 1000}s ago)")
        return false
    }

    private fun recordFailure(siteName: String) {
        val state = circuitStates.computeIfAbsent(siteName) { CircuitState() }
        state.consecutiveFails++
        state.lastFailTime = System.currentTimeMillis()
        if (state.consecutiveFails >= circuitBreakerThreshold) {
            state.tripped = true
            Log.e(TAG, "Circuit breaker TRIPPED for $siteName after ${state.consecutiveFails} failures")
        }
    }

    private fun recordSuccess(siteName: String) {
        val state = circuitStates[siteName]
        if (state != null) {
            state.consecutiveFails = 0
        }
    }
    // endregion

    // region --- Rate Limiting ---
    private val lastRequestTime = ConcurrentHashMap<String, Long>()

    private suspend fun enforceRateLimit(siteName: String) {
        val now = System.currentTimeMillis()
        val last = lastRequestTime[siteName] ?: 0L
        val elapsed = now - last
        if (elapsed < minRequestIntervalMs) {
            val wait = minRequestIntervalMs - elapsed
            Log.v(TAG, "Rate limit: waiting ${wait}ms before next request to $siteName")
            delay(wait)
        }
        lastRequestTime[siteName] = System.currentTimeMillis()
    }
    // endregion

    // region --- Logging ---
    data class ScrapeMetrics(
        val site: String,
        val url: String,
        val cached: Boolean,
        val source: String,
        val attempts: Int,
        val durationMs: Long,
        val success: Boolean
    )

    var onMetrics: ((ScrapeMetrics) -> Unit)? = null
    // endregion

    // region --- Public API ---

    suspend fun fetch(url: String, siteName: String, cacheKey: String? = null, forceFresh: Boolean = false, referer: String? = null): Document? {
        val key = cacheKey ?: buildCacheKey(url)
        val metricsStart = System.currentTimeMillis()

        if (!checkCircuitBreaker(siteName)) {
            val staleDoc = staleDocOrNull(key, url)
            if (staleDoc != null) {
                Log.w(TAG, "Circuit open, serving stale cache for $url ($siteName)")
                emitMetrics(siteName, url, true, "circuit_stale", 0, System.currentTimeMillis() - metricsStart, true)
                return staleDoc
            }
            emitMetrics(siteName, url, false, "circuit_open", 0, System.currentTimeMillis() - metricsStart, false)
            return null
        }

        if (!forceFresh) {
            val memEntry = memGet(key)
            if (memEntry != null && (System.currentTimeMillis() - memEntry.timestamp) < memCacheTtlMs) {
                val doc = safeParse(memEntry.html, url)
                if (doc != null) {
                    Log.d(TAG, "Memory cache HIT for $url ($siteName)")
                    emitMetrics(siteName, url, true, "mem", 1, System.currentTimeMillis() - metricsStart, true)
                    return doc
                }
            }

            val diskHtml = diskGet(key)
            if (diskHtml != null) {
                val doc = safeParse(diskHtml, url)
                if (doc != null) {
                    Log.d(TAG, "Disk cache HIT for $url ($siteName)")
                    memPut(key, MemCacheEntry(diskHtml, System.currentTimeMillis()))
                    emitMetrics(siteName, url, true, "disk", 1, System.currentTimeMillis() - metricsStart, true)
                    return doc
                }
            }
        }

        val networkResult = withConcurrencyLimit {
            var attempts = 0
            var cfBypassAttempted = false
            while (attempts < maxRetries) {
                attempts++
                 try {
                     enforceRateLimit(siteName)
                     Log.i(TAG, "Fetch [$attempts/$maxRetries] $url ($siteName)")
                      val response = executeRequest(url, referer)
                     val html = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                     val serverHeader = response.header("Server")
                     val statusCode = response.code
                     response.close()

                     if (html.isBlank()) throw IllegalStateException("Empty response body")

                     val cfBlocked = com.karin.streamtv.util.CloudflareInterceptor.isCloudflareChallenge(statusCode, html, serverHeader)

                    if (cfBlocked) {
                        Log.w(TAG, "CLOUDFLARE CHALLENGE detected [$statusCode] $url ($siteName)")
                        emitMetrics(siteName, url, false, "cf_detect", attempts, System.currentTimeMillis() - metricsStart, false)

                        if (!cfBypassAttempted) {
                            cfBypassAttempted = true
                            val host = hostOf(url)
                            cfLockedHosts[host] = cfLockedProfile.ua
                            val ctx = appContext
                            if (ctx != null) {
                                Log.i(TAG, "Attempting WebView CF bypass for $host")
                                val mutex = cfBypassMutexes.getOrPut(host) { kotlinx.coroutines.sync.Mutex() }
                                val webHtml = mutex.withLock {
                                    com.karin.streamtv.util.CloudflareInterceptor.solveWithWebView(ctx, url, cfLockedProfile.ua)
                                }
                                val doc = webHtml?.let { safeParse(it, url) }
                                if (doc != null && !doc.body().html().isBlank()) {
                                    cfResolvedHosts.add(host)
                                    Log.i(TAG, "CF bypass succeeded — got HTML (${webHtml!!.length} chars) for $url")
                                    if (!doc.body().html().isBlank()) {
                                        memPut(key, MemCacheEntry(webHtml!!, System.currentTimeMillis()))
                                        diskPut(key, webHtml!!)
                                    }
                                    recordSuccess(siteName)
                                    emitMetrics(siteName, url, true, "cf_webview", 1, System.currentTimeMillis() - metricsStart, true)
                                    return@withConcurrencyLimit doc
                                }
                            }
                        }

                        Log.w(TAG, "CF bypass failed — returning null for $url")
                        return@withConcurrencyLimit null
                    }

                    if (statusCode == 403 || statusCode == 503) {
                        val isMaint = html.contains("mantenimiento") || html.contains("maintenance") ||
                                html.contains("en mantenimiento") || html.contains("under construction")
                        if (isMaint) {
                            Log.w(TAG, "SITE UNDER MAINTENANCE [$statusCode] $url ($siteName)")
                            emitMetrics(siteName, url, false, "maintenance", attempts, System.currentTimeMillis() - metricsStart, false)
                            return@withConcurrencyLimit null
                        }
                        Log.w(TAG, "HTTP $statusCode from $url ($siteName)")
                        emitMetrics(siteName, url, false, "http_$statusCode", attempts, System.currentTimeMillis() - metricsStart, false)
                        return@withConcurrencyLimit null
                    }

                    val doc = safeParse(html, url)
                    if (doc == null) throw IllegalStateException("Failed to parse HTML")

                    if (doc.body().html().isBlank()) {
                        Log.w(TAG, "Parsed document has empty body for $url")
                    }

                    memPut(key, MemCacheEntry(html, System.currentTimeMillis()))
                    diskPut(key, html)

                    recordSuccess(siteName)
                    emitMetrics(siteName, url, false, "network", attempts, System.currentTimeMillis() - metricsStart, true)
                    return@withConcurrencyLimit doc
                } catch (e: Exception) {
                    Log.e(TAG, "Attempt $attempts failed for $url: ${e.javaClass.simpleName}: ${e.message}", e)
                    if (attempts < maxRetries) {
                        val backoff = (attempts * 2000L).coerceAtMost(8000L)
                        Log.d(TAG, "Backoff ${backoff}ms before retry")
                        delay(backoff)
                    } else {
                        recordFailure(siteName)
                    }
                }
            }

            Log.e(TAG, "All $maxRetries attempts failed for $url")
            emitMetrics(siteName, url, false, "network", attempts, System.currentTimeMillis() - metricsStart, false)
            null
        }

        if (networkResult == null) {
            val staleDoc = staleDocOrNull(key, url)
            if (staleDoc != null) {
                Log.w(TAG, "Network failed, serving stale disk cache for $url ($siteName)")
                emitMetrics(siteName, url, true, "stale_disk", 0, System.currentTimeMillis() - metricsStart, true)
                return staleDoc
            }
        }
        return networkResult
    }

    fun invalidate(key: String) {
        memRemove(key)
        diskRemove(key)
    }

    fun clearCache() {
        memClear()
        diskClear()
        Log.d(TAG, "All caches cleared")
    }

    fun resetCircuitBreaker(siteName: String) {
        circuitStates.remove(siteName)
        Log.i(TAG, "Circuit breaker manually reset for $siteName")
    }

    fun resetAllCircuitBreakers() {
        circuitStates.clear()
        Log.i(TAG, "All circuit breakers reset")
    }
    // endregion

    // region --- Internal ---

    private fun executeRequest(url: String, customReferer: String? = null): Response {
        val referer = customReferer ?: try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}/"
        } catch (_: Exception) { url }

        val host = hostOf(url)
        val profile = if (cfLockedHosts.containsKey(host)) cfLockedProfile else nextBrowserProfile()

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", profile.ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8,en-US;q=0.7")
            .header("Referer", referer)
            .header("Origin", referer.trimEnd('/'))
            .header("DNT", "1")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")

        if (profile.secChUa.isNotEmpty()) {
            requestBuilder
                .header("sec-ch-ua", profile.secChUa)
                .header("sec-ch-ua-mobile", profile.secChUaMobile)
                .header("sec-ch-ua-platform", profile.secChUaPlatform)
        }

        val request = requestBuilder.build()
        Log.d(TAG, "HTTP GET $url (UA: ${profile.ua.substringAfter("Chrome/").substringBefore(" ")})")
        val response = httpClient.newCall(request).execute()
        Log.d(TAG, "HTTP ${response.code} for ${url.takeLast(60)}")
        if (!response.isSuccessful && response.code != 403 && response.code != 503) {
            response.close()
            throw IllegalStateException("HTTP ${response.code} for $url")
        }
        return response
    }

    /**
     * POST form-encoded and return the raw body. Reuses the persistent cookie jar so the session
     * (e.g. Laravel CSRF) established by a previous GET on the same host is honored.
     * Returns null on 419/403/503/network errors so the caller can re-establish the session.
     */
    suspend fun postForm(url: String, form: Map<String, String>, csrfToken: String?, siteName: String): String? {
        return withConcurrencyLimit {
            var result: String? = null
            var attempts = 0
            while (attempts < maxRetries) {
                attempts++
                try {
                    enforceRateLimit(siteName)
                    Log.i(TAG, "POST [$attempts/$maxRetries] $url ($siteName)")

                    val referer = try {
                        val uri = java.net.URI(url)
                        "${uri.scheme}://${uri.host}/"
                    } catch (_: Exception) { url }
val profile = if (cfLockedHosts.containsKey(hostOf(url))) cfLockedProfile else nextBrowserProfile()

                    val formBody = FormBody.Builder().apply {
                        for ((k, v) in form) add(k, v)
                    }.build()

                    val requestBuilder = Request.Builder()
                        .url(url)
                        .header("User-Agent", profile.ua)
                        .header("Accept", "application/json, text/javascript, */*; q=0.01")
                        .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                        .header("Referer", referer)
                        .header("Origin", referer.trimEnd('/'))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "same-origin")
                        .post(formBody)
                    if (!csrfToken.isNullOrBlank()) {
                        requestBuilder.header("X-CSRF-TOKEN", csrfToken)
                    }
                    if (profile.secChUa.isNotEmpty()) {
                        requestBuilder
                            .header("sec-ch-ua", profile.secChUa)
                            .header("sec-ch-ua-mobile", profile.secChUaMobile)
                            .header("sec-ch-ua-platform", profile.secChUaPlatform)
                    }

                    val response = httpClient.newCall(requestBuilder.build()).execute()
                    val code = response.code
                    val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                    response.close()

                    when {
                        code == 419 || code == 403 || code == 503 -> {
                            Log.w(TAG, "POST $code for $url — session invalid ($siteName)")
                            return@withConcurrencyLimit null
                        }
                        body.isBlank() -> throw IllegalStateException("Empty POST response body")
                        else -> {
                            recordSuccess(siteName)
                            result = body
                            return@withConcurrencyLimit body
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "POST attempt $attempts failed for $url: ${e.javaClass.simpleName}: ${e.message}", e)
                    if (attempts < maxRetries) {
                        val backoff = (attempts * 1500L).coerceAtMost(6000L)
                        delay(backoff)
                    } else {
                        recordFailure(siteName)
                    }
                }
            }
            result
        }
    }

    private fun safeParse(html: String, baseUrl: String): Document? = try {
        Jsoup.parse(html, baseUrl)
    } catch (e: Exception) {
        Log.e(TAG, "Jsoup parse error: ${e.message}")
        null
    }

    private fun buildCacheKey(url: String): String = url.trimEnd('/')

    /** Read a stale disk entry (past fresh TTL) and parse it, if still within stale max TTL. */
    private fun staleDocOrNull(key: String, url: String): Document? {
        val html = diskGet(key, allowStale = true) ?: return null
        return safeParse(html, url)
    }

    private fun emitMetrics(site: String, url: String, cached: Boolean, source: String, attempts: Int, durationMs: Long, success: Boolean) {
        onMetrics?.invoke(ScrapeMetrics(site, url, cached, source, attempts, durationMs, success))
    }
    // endregion
}
