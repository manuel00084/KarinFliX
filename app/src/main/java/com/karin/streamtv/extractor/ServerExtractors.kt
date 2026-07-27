package com.karin.streamtv.extractor

import android.util.Base64
import android.util.Log
import com.karin.streamtv.util.Http
import okhttp3.Request
import org.jsoup.Jsoup

object StreamTapeServerExtractor : ServerExtractor {
    override val serverName = "Streamtape"
    override val priority = 100

    override fun extract(html: String, embedUrl: String, context: Any?): String? {
        val patterns = listOf(
            Regex("""var\s+vt\s*=\s*["']([^"']+)["']"""),
            Regex("""(?:robotlink|tokten|token)\s*[=:]\s*["']([^"']+)["']"""),
            Regex("""document\.getElementById\(["']robotlink["']\)\.innerHTML\s*=\s*["']([^"']+)["']"""),
            Regex("""innerHTML\s*=\s*["']([^"']*get_video[^"']*)["']"""),
            Regex("""\b(?:url|link|href)\s*[=:]\s*["']([^"']*get_video[^"']*)["']"""),
            Regex("""["']([^"']*\/get_video\.php[^"']*)["']"""),
            Regex("""(?:video_url|download_url|stream_url)\s*[=:]\s*["'](https?://[^"']+)["']"""),
            Regex("""["'](https?://[^"'\s]*streamtape[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)["']"""),
            Regex("""['"]([^'"]+)['"]\s*\+\s*['"]([^'"]+)['"]"""),
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            var token = m.groupValues[1]
            if (token.isNotBlank()) {
                val right = m.groupValues.getOrElse(2) { "" }
                if (right.isNotBlank()) token += right
                val base = "https://streamtape.com"
                val fullUrl = if (token.startsWith("http")) token else base + token
                Log.d("StreamTapeExtractor", "Extracted: $fullUrl")
                return fullUrl
            }
        }
        return null
    }
}

object DoodStreamServerExtractor : ServerExtractor {
    override val serverName = "DoodStream"
    override val priority = 95
    private val client = Http.client

    override fun extract(html: String, embedUrl: String, context: Any?): String? {
        val passMatch = Regex("""(https?://[^"'\s]+/pass_md5/[^"'\s]+)""").find(html)
        if (passMatch != null) {
            val passUrl = passMatch.groupValues[1]
            try {
                val embedHost = try { java.net.URL(embedUrl).host } catch (_: Exception) { "" }
                val request = Request.Builder()
                    .url(passUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                    .header("Referer", embedUrl)
                    .header("Origin", "https://$embedHost")
                    .build()
                val response = client.newBuilder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(false)
                    .build()
                    .newCall(request)
                    .execute()
                val body = response.body?.string()?.trim() ?: ""
                response.close()
                if (body.startsWith("http")) {
                    val token = java.util.UUID.randomUUID().toString().replace("-", "")
                    return "$body$token"
                }
                try {
                    val decoded = Base64.decode(body, Base64.DEFAULT)
                    val decodedStr = String(decoded, Charsets.UTF_8)
                    if (decodedStr.startsWith("http")) {
                        val token = java.util.UUID.randomUUID().toString().replace("-", "")
                        return "$decodedStr$token"
                    }
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w("DoodStreamExtractor", "pass_md5 failed: ${e.message}")
            }
        }
        val altPatterns = listOf(
            Regex("""(?:file|video|source|stream)\s*[=:]\s*["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']"""),
            Regex("""(?:file|video|source|stream)\s*[=:]\s*["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']"""),
            Regex("""["'](https?://[^"'\s]*dood[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)["']"""),
        )
        for (p in altPatterns) {
            val match = p.find(html) ?: continue
            val url = match.groupValues[1]
            if (url.isNotBlank()) return url
        }
        return null
    }
}

object VoeServerExtractor : ServerExtractor {
    override val serverName = "VOE"
    override val priority = 85
    private val client = Http.client

    private data class VoeDecrypted(val source: String? = null, val directAccessUrl: String? = null)

    override fun extract(html: String, embedUrl: String, context: Any?): String? {
        val redirectPatterns = listOf(
            Regex("""window\.location\.href\s*=\s*['"]?(https?://[^'"\s;]+)"""),
            Regex("""document\.location\s*=\s*['"]?(https?://[^'"\s;]+)"""),
            Regex("""location\.href\s*=\s*['"]?(https?://[^'"\s;]+)"""),
            Regex("""window\.location\s*=\s*['"]?(https?://[^'"\s;]+)"""),
            Regex("""window\.location\.replace\s*\(\s*['"]?(https?://[^'"\s;]+)['"]?\s*\)"""),
        )
        for (pattern in redirectPatterns) {
            val match = pattern.find(html) ?: continue
            val redirectUrl = match.groupValues[1]
            if (redirectUrl.isBlank()) continue
            val redirected = followVoeRedirect(redirectUrl, embedUrl)
            if (redirected != null) return redirected
        }
        return null
    }

    private fun followVoeRedirect(redirectUrl: String, originalUrl: String): String? {
        try {
            val request = Request.Builder()
                .url(redirectUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                .header("Referer", originalUrl)
                .build()
            val response = client.newBuilder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
                .newCall(request)
                .execute()
            val html = response.body?.string() ?: ""
            val finalUrl = response.request.url.toString()
            response.close()

            try {
                val doc = Jsoup.parse(html, finalUrl)
                val scriptTag = doc.selectFirst("script[type=application/json]")
                if (scriptTag != null) {
                    val rawData = scriptTag.data()?.trim() ?: ""
                    if (rawData.isNotEmpty()) {
                        var encoded = rawData
                        if (encoded.startsWith("[\"") && encoded.endsWith("\"]")) {
                            encoded = encoded.substring(2, encoded.length - 2)
                        }
                        val decoded = decryptVoeJson(encoded)
                        if (decoded != null) {
                            var videoUrl = decoded.source ?: decoded.directAccessUrl
                            if (videoUrl != null) {
                                videoUrl = Regex("""\.m(3u)?$""").replace(videoUrl, ".m3u8")
                                return videoUrl
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("VOEExtractor", "decrypt failed: ${e.message}")
            }

            val videoPatterns = listOf(
                Regex("""sources?\s*[=:]\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
                Regex("""(?:file|src|source|video)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
            )
            for (p in videoPatterns) {
                val m = p.find(html) ?: continue
                val url = m.groupValues[1]
                if (url.isNotBlank() && url.startsWith("http") && !url.contains("test-videos")) return url
            }
        } catch (e: Exception) {
            Log.w("VOEExtractor", "redirect follow failed: ${e.message}")
        }
        return null
    }

    private fun decryptVoeJson(encoded: String): VoeDecrypted? {
        return try {
            val step1 = rot13(encoded)
            val step2 = step1.replace("@$", "_").replace("^^", "_").replace("~@", "_")
                .replace("%?", "_").replace("*~", "_").replace("!!", "_").replace("#&", "_")
            val step3 = step2.replace("_", "")
            val step4 = String(Base64.decode(step3, Base64.DEFAULT))
            val step5 = step4.map { (it.code - 3).toChar() }.joinToString("")
            val step6 = step5.reversed()
            val step7 = String(Base64.decode(step6, Base64.DEFAULT))
            val json = org.json.JSONObject(step7)
            VoeDecrypted(
                source = json.optString("source", ""),
                directAccessUrl = json.optString("direct_access_url", "")
            )
        } catch (e: Exception) { null }
    }

    private fun rot13(input: String): String = input.map { c ->
        when (c) {
            in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> c
        }
    }.joinToString("")
}

object MixDropServerExtractor : ServerExtractor {
    override val serverName = "MixDrop"
    override val priority = 50

    private val packedEval = Regex("""eval\(function\(p,a,c,k,e,(?:r|d).*?\)\s*\{.*?\}\s*\(.*?\)\s*\)""")
    private val packedJs = Regex("""\('([^']+)',\s*(\d+),\s*(\d+),\s*'([^']+)'\.split\('\|'\)""")
    private val wurl = Regex("""wurl\s*=\s*["']([^"']+)["']""")

    override fun extract(html: String, embedUrl: String, context: Any?): String? {
        val packedMatch = packedEval.find(html) ?: return null
        val unpacked = unpackJs(packedMatch.value) ?: return null
        val wurlMatch = wurl.find(unpacked) ?: return null
        var url = wurlMatch.groupValues[1]
        if (url.startsWith("//")) url = "https:$url"
        return url
    }

    private fun unpackJs(packed: String): String? {
        val paramsMatch = packedJs.find(packed) ?: return null
        val p = paramsMatch.groupValues[1]
        val a = paramsMatch.groupValues[2].toInt()
        val c = paramsMatch.groupValues[3].toInt()
        val dict = paramsMatch.groupValues[4].split("|")
        var result = p
        for (i in 0 until c) {
            val encoded = encodePackedNum(i, a)
            val word = if (i < dict.size) dict[i] else ""
            if (word.isNotEmpty() && encoded.isNotEmpty()) {
                result = result.replace(Regex("\\b${Regex.escape(encoded)}\\b"), word)
            }
        }
        return result
    }

    private fun encodePackedNum(c: Int, a: Int): String {
        if (c < a) return if (c > 35) (c + 29).toChar().toString() else Integer.toString(c, 36)
        return encodePackedNum(c / a, a) + encodePackedNum(c % a, a)
    }
}

object SaveFilesServerExtractor : ServerExtractor {
    override val serverName = "SaveFiles"
    override val priority = 55
    private val client = Http.client

    private val jwFile = Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*"([^"]+)"\s*""")
    private val bareUrl = Regex("""https?://[^\s"']+\.(?:mp4|m3u8|webm)(?:\?[^\s"']*)?""")

    override fun extract(html: String, embedUrl: String, context: Any?): String? {
        val code = try {
            val path = java.net.URL(embedUrl).path
            path.removeSuffix(".html").substringAfterLast("/").substringAfterLast("-")
        } catch (_: Exception) { null } ?: return null
        if (code.isBlank()) return null

        val formBody = okhttp3.FormBody.Builder()
            .add("op", "embed")
            .add("file_code", code)
            .add("auto", "1")
            .add("referer", embedUrl)
            .build()

        val dlUrl = try {
            val u = java.net.URL(embedUrl)
            "${u.protocol}://${u.host}/dl"
        } catch (_: Exception) { null } ?: return null

        val request = Request.Builder()
            .url(dlUrl)
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .header("Referer", embedUrl)
            .build()

        val response = client.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
            .newCall(request)
            .execute()

        val responseHtml = response.body?.string() ?: ""
        response.close()

        val match = jwFile.find(responseHtml)
        if (match != null) {
            var videoUrl = match.groupValues[1]
            if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
            return videoUrl
        }

        val directMatch = bareUrl.find(responseHtml)
        if (directMatch != null) return directMatch.value

        return null
    }
}

object AtobExtractor : ServerExtractor {
    override val serverName = "atob"
    override val priority = 60

    override fun extract(html: String, embedUrl: String, context: Any?): String? {
        val atobPattern = Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""")
        val videoRe = Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|m3u8|mpd|webm)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
        for (m in atobPattern.findAll(html)) {
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                val videoMatch = videoRe.find(decoded)
                if (videoMatch != null) return videoMatch.value
                if (decoded.startsWith("http")) return decoded
            } catch (_: Exception) {}
        }
        return null
    }
}

object DsvPlayServerExtractor : ServerExtractor {
    override val serverName = "DsvPlay"
    override val priority = 80
    private val videoRe = Regex("""(?:file|source|src|video|url)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val packedEval = Regex("""eval\(function\(p,a,c,k,e,d.*?\)\s*\{.*?\}\s*\(.*?\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
    private val packedSplit = Regex("""'([^']+)'\.split\('\|'\)""")

    override fun extract(html: String, embedUrl: String, context: Any?): String? {
        val directMatch = videoRe.find(html)
        if (directMatch != null) return directMatch.groupValues[1]

        val unpacked = unpackPackedJs(html) ?: return null
        val unpackedMatch = videoRe.find(unpacked)
        return unpackedMatch?.groupValues?.get(1)
    }

    private fun unpackPackedJs(html: String): String? {
        val packed = packedEval.find(html)?.value ?: return null
        val params = packedSplit.find(packed) ?: return null
        val dict = params.groupValues[1].split("|")
        val base = Regex("""\('([^']+)',\s*(\d+),\s*(\d+)""").find(packed) ?: return null
        val p = base.groupValues[1]
        val a = base.groupValues[2].toIntOrNull() ?: return null
        val c = base.groupValues[3].toIntOrNull() ?: return null

        var result = p
        for (i in 0 until c) {
            val encoded = encodeBaseN(i, a)
            val word = dict.getOrElse(i) { "" }
            if (word.isNotEmpty() && encoded.isNotEmpty()) {
                result = result.replace(Regex("\\b${Regex.escape(encoded)}\\b"), word)
            }
        }
        return result
    }

    private fun encodeBaseN(c: Int, a: Int): String {
        if (c < a) return if (c > 35) (c + 29).toChar().toString() else Integer.toString(c, 36)
        return encodeBaseN(c / a, a) + encodeBaseN(c % a, a)
    }
}

object StreamWishServerExtractor : ServerExtractor {
    override val serverName = "StreamWish"
    override val priority = 60
    private val videoRe = Regex("""(?:file|source|src|video)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val packedEval = Regex("""eval\(function\(p,a,c,k,e,d.*?\)\s*\{.*?\}\s*\(.*?\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
    private val packedSplit = Regex("""'([^']+)'\.split\('\|'\)""")

    override fun extract(html: String, embedUrl: String, context: Any?): String? {
        val directMatch = videoRe.find(html)
        if (directMatch != null) return directMatch.groupValues[1]

        val unpacked = unpackPackedJs(html) ?: return null
        val unpackedMatch = videoRe.find(unpacked)
        return unpackedMatch?.groupValues?.get(1)
    }

    private fun unpackPackedJs(html: String): String? {
        val packed = packedEval.find(html)?.value ?: return null
        val params = packedSplit.find(packed) ?: return null
        val dict = params.groupValues[1].split("|")
        val base = Regex("""\('([^']+)',\s*(\d+),\s*(\d+)""").find(packed) ?: return null
        val p = base.groupValues[1]
        val a = base.groupValues[2].toIntOrNull() ?: return null
        val c = base.groupValues[3].toIntOrNull() ?: return null

        var result = p
        for (i in 0 until c) {
            val encoded = encodeBaseN(i, a)
            val word = dict.getOrElse(i) { "" }
            if (word.isNotEmpty() && encoded.isNotEmpty()) {
                result = result.replace(Regex("\\b${Regex.escape(encoded)}\\b"), word)
            }
        }
        return result
    }

    private fun encodeBaseN(c: Int, a: Int): String {
        if (c < a) return if (c > 35) (c + 29).toChar().toString() else Integer.toString(c, 36)
        return encodeBaseN(c / a, a) + encodeBaseN(c % a, a)
    }
}

object FileMoonServerExtractor : ServerExtractor {
    override val serverName = "FileMoon"
    override val priority = 92
    private val videoRe = Regex("""(?:file|source|src|video)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val packedEval = Regex("""eval\(function\(p,a,c,k,e,d.*?\)\s*\{.*?\}\s*\(.*?\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
    private val packedSplit = Regex("""'([^']+)'\.split\('\|'\)""")

    override fun extract(html: String, embedUrl: String, context: Any?): String? {
        val directMatch = videoRe.find(html)
        if (directMatch != null) return directMatch.groupValues[1]

        val unpacked = unpackPackedJs(html) ?: return null
        val unpackedMatch = videoRe.find(unpacked)
        return unpackedMatch?.groupValues?.get(1)
    }

    private fun unpackPackedJs(html: String): String? {
        val packed = packedEval.find(html)?.value ?: return null
        val params = packedSplit.find(packed) ?: return null
        val dict = params.groupValues[1].split("|")
        val base = Regex("""\('([^']+)',\s*(\d+),\s*(\d+)""").find(packed) ?: return null
        val p = base.groupValues[1]
        val a = base.groupValues[2].toIntOrNull() ?: return null
        val c = base.groupValues[3].toIntOrNull() ?: return null

        var result = p
        for (i in 0 until c) {
            val encoded = encodeBaseN(i, a)
            val word = dict.getOrElse(i) { "" }
            if (word.isNotEmpty() && encoded.isNotEmpty()) {
                result = result.replace(Regex("\\b${Regex.escape(encoded)}\\b"), word)
            }
        }
        return result
    }

    private fun encodeBaseN(c: Int, a: Int): String {
        if (c < a) return if (c > 35) (c + 29).toChar().toString() else Integer.toString(c, 36)
        return encodeBaseN(c / a, a) + encodeBaseN(c % a, a)
    }
}
