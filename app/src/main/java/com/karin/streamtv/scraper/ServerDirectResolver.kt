package com.karin.streamtv.scraper

import android.util.Base64
import android.util.Log
import com.karin.streamtv.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves a direct playback URL for video hosters using plain HTTP (OkHttp),
 * without relying on WebView/JS execution. Used for servers whose players crash
 * on old WebView engines (ES2020 syntax: dsvplay/dood, byse, voe, mega).
 */
object ServerDirectResolver {

    private const val TAG = "ServerDirectResolver"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client get() = Http.client

    data class ResolvedVideo(
        val url: String,
        val referer: String,
        val extraHeaders: Map<String, String> = emptyMap(),
        val needsMegaDecrypt: Boolean = false,
        val megaKey: ByteArray? = null,
        val megaCtrStart: Long = 0L,
        val displayName: String = ""
    )

    suspend fun resolve(url: String, referer: String = ""): ResolvedVideo? {
        return withContext(Dispatchers.IO) {
            try {
                val lower = url.lowercase()
                val result = when {
                    lower.contains("dsvplay") || lower.contains("playmogo") ||
                            lower.contains("doodstream") || lower.contains("dooood") ||
                            lower.contains("d0000d") || lower.contains("dood") -> resolveDood(url, referer)
                    lower.contains("voe") || lower.contains("jessicachoosemake") -> resolveVoe(url, referer)
                    lower.contains("byse") || lower.contains("bysekoze") -> resolveByse(url)
                    lower.contains("mega") -> resolveMega(url)
                    else -> null
                }
                if (result != null) {
                    Log.i(TAG, "RESOLVED ${lower.substringBefore(".")} -> ${result.url.takeLast(90)}")
                } else {
                    Log.w(TAG, "NOT resolved via HTTP: ${url.takeLast(80)}")
                }
                result
            } catch (e: Exception) {
                Log.w(TAG, "resolve error for ${url.takeLast(80)}: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    /**
     * Resolves a video URL using pre-fetched HTML (e.g., from CloudflareInterceptor.solveWithWebView).
     * Used when the URL is behind Cloudflare and HTTP fetch returns a challenge page instead of the real content.
     */
    suspend fun resolveFromHtml(url: String, html: String, referer: String = ""): ResolvedVideo? {
        return try {
            val lower = url.lowercase()
            when {
                lower.contains("voe") || lower.contains("jessicachoosemake") -> resolveVoeFromHtml(html, url, referer)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveFromHtml error for ${url.takeLast(80)}: ${e.message}")
            null
        }
    }

    /**
     * True when [url] can be played directly through ExoPlayer via the HTTP
     * resolver (dood/dsvplay, voe, byse, mega). False means playback falls
     * back to the WebView (hidden extractor or visible embed).
     */
    fun usesHttpResolver(url: String): Boolean {
        return com.karin.streamtv.model.VideoServer.detectServer(url).httpResolvable
    }

    // region --- HTTP helpers ---

    private suspend fun getString(
        url: String,
        referer: String? = null,
        cookie: String? = null,
        acceptJson: Boolean = false
    ): Pair<Int, String> {
        val result = tryOrdinary(url, referer, cookie, acceptJson, isPost = false, postBody = null)
        if (result != null) return result
        // Fallback: use ScrapingEngine's CF bypass
        return fetchViaCfBypass(url, referer) ?: (403 to "")
    }

    private suspend fun postJson(url: String, json: String): Pair<Int, String> {
        val result = tryOrdinary(
            url = url,
            referer = null,
            cookie = null,
            acceptJson = true,
            isPost = true,
            postBody = json
        )
        if (result != null) return result
        return fetchViaCfBypass(url, null) ?: (403 to "")
    }

    private suspend fun tryOrdinary(
        url: String,
        referer: String?,
        cookie: String?,
        acceptJson: Boolean,
        isPost: Boolean,
        postBody: String?
    ): Pair<Int, String>? {
        val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
        // If the host was already CF-locked, use the same UA that cf_clearance is bound to.
        val ua = ScrapingEngine.getLockedUa(host) ?: UA

        val rb = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "es-ES,es;q=0.9")
        if (acceptJson) {
            rb.header("Accept", "application/json, text/plain, */*")
        } else {
            rb.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        referer?.let { rb.header("Referer", it) }
        cookie?.let { rb.header("Cookie", it) }
        if (isPost && postBody != null) {
            rb.post(postBody.toRequestBody("application/json".toMediaType()))
        }
        client.newCall(rb.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (com.karin.streamtv.util.CloudflareInterceptor.isCloudflareChallenge(resp.code, body, resp.header("Server"))) {
                Log.w(TAG, "CF challenge for $url (code=${resp.code}) — will fallback to CF bypass")
                return null
            }
            return resp.code to body
        }
    }

    /**
     * Fallback: use ScrapingEngine's built-in Cloudflare bypass (WebView) to fetch the page.
     * Returns (statusCode, htmlBody) or null if bypass fails.
     */
    private suspend fun fetchViaCfBypass(url: String, referer: String? = null): Pair<Int, String>? {
        val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
        if (host.isBlank()) return null
        Log.i(TAG, "CF bypass fallback for host: $host")
        val cacheKey = "sdr_cfbypass::${host}::${url.hashCode()}"
        val doc = ScrapingEngine.fetch(url, "ServerDirectResolver", cacheKey, referer = referer)
        if (doc == null) return null
        val html = doc.body().html()
        if (html.isBlank()) return null
        if (com.karin.streamtv.util.CloudflareInterceptor.isCloudflareChallenge(200, html, null)) {
            Log.w(TAG, "CF bypass returned challenge page for $url")
            return null
        }
        Log.i(TAG, "CF bypass succeeded for $url (${html.length} chars)")
        return 200 to html
    }

    private fun b64urlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    private fun randomChars(n: Int): String {
        val rng = java.security.SecureRandom()
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (0 until n).map { chars[rng.nextInt(chars.length)] }.joinToString("")
    }
    // endregion

    // region --- DoodStream (dsvplay/playmogo/dooood/d0000d) ---
    // Flow: GET /e/{id} -> page contains `$.get('/pass_md5/{file_id}-188-93-{ts}-{hash}/{token}')`
    // GET /pass_md5/... -> body is the direct CDN URL; final = url + 10 random chars + ?token=...&expiry=...
    private suspend fun resolveDood(url: String, referer: String = ""): ResolvedVideo? {
        val host = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return null
        val effectiveRefer = referer.ifBlank { "$host/" }
        val (code, body) = getString(url, referer = effectiveRefer)
        if (code != 200) {
            Log.w(TAG, "dood embed HTTP $code")
            return null
        }
        val passPath = Regex("""\$\.get\('(/pass_md5/[^']+)'""").find(body)?.groupValues?.get(1) ?: run {
            Log.w(TAG, "dood pass_md5 path not found")
            return null
        }
        val token = passPath.substringAfterLast("/")
        val fileId = Regex("""\$\.cookie\('file_id',\s*'(\d+)'""").find(body)?.groupValues?.get(1).orEmpty()
        val (c2, direct) = getString(
            host + passPath,
            referer = "$host/",
            cookie = "file_id=$fileId; aff=233880"
        )
        if (c2 != 200 || !direct.startsWith("http")) {
            Log.w(TAG, "dood pass_md5 HTTP $c2")
            return null
        }
        val final = "$direct${randomChars(10)}?token=$token&expiry=${System.currentTimeMillis()}"
        return ResolvedVideo(
            url = final,
            referer = effectiveRefer,
            extraHeaders = mapOf("Referer" to effectiveRefer)
        )
    }
    // endregion

    // region --- VOE (voe.sx / mirror) ---
    // Flow: voe.sx -> JS redirect to mirror (jessicachoosemake.com...) -> page has
    // <script type="application/json">["...encoded..."]</script> -> decryptF7 -> {source: m3u8}
    private suspend fun resolveVoe(url: String, referer: String = ""): ResolvedVideo? {
        val (c1, body1) = getString(url, referer = referer.ifBlank { null })
        if (c1 != 200) return null
        return resolveVoeFromHtml(body1, url, referer)
    }

    private suspend fun resolveVoeFromHtml(html: String, url: String, referer: String = ""): ResolvedVideo? {
        val target = Regex("""location\.href\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
        val (c2, body2) = if (target != null) getString(target, referer = referer.ifBlank { url }) else {
            // Cloudflare bypass gave us the real page directly; use it as body2
            200 to html
        }
        if (c2 != 200) {
            Log.w(TAG, "voe page HTTP $c2")
            return null
        }
        val encoded = Regex("""<script[^>]*application/json[^>]*>([\s\S]*?)</script>""")
            .find(body2)?.groupValues?.get(1)?.trim()
            ?.substringAfter("[\"")?.substringBeforeLast("\"]") ?: run {
            Log.w(TAG, "voe application/json script not found")
            return null
        }
        val decrypted = decryptF7(encoded) ?: return null
        val json = try { JSONObject(decrypted) } catch (e: Exception) {
            Log.w(TAG, "voe bad decrypt json: ${e.message}")
            return null
        }
        val source = json.optString("source")
        if (source.isBlank() || !source.startsWith("http")) {
            Log.w(TAG, "voe no source in config")
            return null
        }
        val mirrorHost = Regex("""(https?://[^/]+)""").find(target ?: url)?.groupValues?.get(1) ?: "https://voe.sx"
        return ResolvedVideo(
            url = source,
            referer = mirrorHost,
            extraHeaders = mapOf("Referer" to mirrorHost, "Origin" to "https://voe.sx")
        )
    }

    private fun decryptF7(p8: String): String? {
        return try {
            val patterns = listOf("@\$", "^^", "~@", "%?", "*~", "!!", "#&")
            var v = p8.map { c ->
                when (c) {
                    in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                    in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                    else -> c
                }
            }.joinToString("")
            for (pat in patterns) v = v.replace(Regex(Regex.escape(pat)), "_")
            v = v.replace("_", "")
            val v4 = String(b64urlDecode(v))
            val v5 = v4.map { (it.code - 3).toChar() }.joinToString("")
            val v6 = v5.reversed()
            String(b64urlDecode(v6))
        } catch (e: Exception) {
            Log.w(TAG, "voe decryptF7 failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
    // endregion

    // region --- Byse (bysekoze.com / byse.to) ---
    // Flow: GET /api/videos/{id} -> playback {version, iv, payload, key_parts}
    // key = key_parts[version] + key_parts[31-version] (base64url) -> AES-256-GCM -> sources[].url (m3u8)
    private suspend fun resolveByse(url: String): ResolvedVideo? {
        val id = url.substringAfterLast("/").trim().substringBefore("?")
        if (id.isBlank() || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            Log.w(TAG, "byse bad id: $id")
            return null
        }
        val (c, body) = getString("https://bysekoze.com/api/videos/$id", acceptJson = true)
        if (c != 200) {
            Log.w(TAG, "byse api HTTP $c")
            return null
        }
        val playback = try { JSONObject(body).getJSONObject("playback") } catch (e: Exception) {
            Log.w(TAG, "byse no playback obj: ${e.message}")
            return null
        }
        val version = playback.optString("version").trim().toIntOrNull() ?: run {
            Log.w(TAG, "byse no version")
            return null
        }
        val iv = try { b64urlDecode(playback.getString("iv")) } catch (e: Exception) { return null }
        val payload = try { b64urlDecode(playback.getString("payload")) } catch (e: Exception) { return null }
        val partsArr = playback.optJSONArray("key_parts") ?: return null
        val parts = (0 until partsArr.length()).map { partsArr.getString(it) }
        val sel = listOf(version, 31 - version).filter { it in 1..parts.size }
        if (sel.size != 2) {
            Log.w(TAG, "byse invalid key selection $sel (version=$version, parts=${parts.size})")
            return null
        }
        val key = sel.flatMap { b64urlDecode(parts[it - 1]).toList() }.toByteArray()
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            String(c.doFinal(payload))
        } catch (e: Exception) {
            Log.w(TAG, "byse aes-gcm failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val sources = try { JSONObject(plain).getJSONArray("sources") } catch (e: Exception) {
            Log.w(TAG, "byse no sources in plaintext: ${e.message}")
            return null
        }
        val url = if (sources.length() > 0) sources.getJSONObject(0).optString("url") else ""
        if (url.isBlank() || !url.startsWith("http")) {
            Log.w(TAG, "byse no source url")
            return null
        }
        return ResolvedVideo(
            url = url,
            referer = "https://bysekoze.com/",
            extraHeaders = mapOf("Referer" to "https://bysekoze.com/", "Origin" to "https://bysekoze.com")
        )
    }
    // endregion

    // region --- Mega (mega.nz) ---
    // Flow: POST g.api.mega.co.nz/cs {"a":"g","g":1,"p":id} -> {g: direct url (AES-CTR encrypted), s, at}
    // Playback requires on-the-fly AES-128-CTR decryption via MegaDecryptingDataSource.
    private suspend fun resolveMega(url: String): ResolvedVideo? {
        val m = Regex("""(?:/#!|/file/)(.+?)(?:!|#)([^#!?]+)""").find(url) ?: run {
            Log.w(TAG, "mega url not #!id!key or /file/id#key format")
            return null
        }
        val id = m.groupValues[1].trimEnd('/')
        val keyB64 = m.groupValues[2]
        val (c, body) = postJson("https://g.api.mega.co.nz/cs?id=", """[{"a":"g","g":1,"p":"$id"}]""")
        if (c != 200) {
            Log.w(TAG, "mega api HTTP $c")
            return null
        }
        val json = try {
            val arr = org.json.JSONArray(body)
            arr.getJSONObject(0)
        } catch (e: Exception) {
            Log.w(TAG, "mega api bad response: $body")
            return null
        }
        val g = json.optString("g")
        if (g.isBlank()) {
            Log.w(TAG, "mega api no g (blocked/removed): $body")
            return null
        }
        val rawKey = b64urlDecode(keyB64)
        if (rawKey.size != 32) {
            Log.w(TAG, "mega key len ${rawKey.size} != 32")
            return null
        }
        val words = LongArray(8) { i ->
            (rawKey[i * 4].toLong() and 0xFF shl 24) or
                    (rawKey[i * 4 + 1].toLong() and 0xFF shl 16) or
                    (rawKey[i * 4 + 2].toLong() and 0xFF shl 8) or
                    (rawKey[i * 4 + 3].toLong() and 0xFF)
        }
        val k = IntArray(4) {
            (words[it].toInt() xor words[it + 4].toInt())
        }
        val keyBytes = ByteArray(16)
        for (i in 0 until 4) {
            keyBytes[i * 4] = ((k[i].toLong() ushr 24) and 0xFF).toByte()
            keyBytes[i * 4 + 1] = ((k[i].toLong() ushr 16) and 0xFF).toByte()
            keyBytes[i * 4 + 2] = ((k[i].toLong() ushr 8) and 0xFF).toByte()
            keyBytes[i * 4 + 3] = (k[i].toLong() and 0xFF).toByte()
        }
        val ctrStart = ((words[4].toLong() and 0xFFFFFFFFL) shl 32) or (words[5].toLong() and 0xFFFFFFFFL)
        return ResolvedVideo(
            url = g,
            referer = "https://mega.nz/",
            extraHeaders = mapOf("Referer" to "https://mega.nz/"),
            needsMegaDecrypt = true,
            megaKey = keyBytes,
            megaCtrStart = ctrStart
        )
    }
    // endregion
}
