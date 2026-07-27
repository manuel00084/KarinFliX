package com.karin.streamtv.extractor

import android.util.Base64

object RegexExtractor : ServerExtractor {
    override val serverName = "Regex"
    override val priority = 40

    private val VIDEO_EXTENSIONS = setOf("mp4", "m3u8", "mpd", "webm", "ts")

    private object P {
        val srcWithBraces = Regex("""\.src\s*\(\s*\{[^}]*["']?src["']?\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        val fileEq = Regex("""\bfile\b\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val sourceEq = Regex("""\bsource\b\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val srcEq = Regex("""(?:^|[\s{,])src\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val urlEq = Regex("""\burl\b\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val videoEq = Regex("""\bvideo\b\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val innerHtmlEq = Regex("""innerHTML\s*=\s*["'](https?://[^"']*\.(?:mp4|m3u8|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val videoTagSrc = Regex("""<video[^>]*src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        val sourceTagSrc = Regex("""<source[^>]*src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        val sourcesArray = Regex("""(?:sources|videos|files)\s*[=:]\s*\[\s*\{[^}]*["']?(?:file|src|source|url)["']?\s*[:=]\s*["'](https?://[^"']+)["']""")
        val varSource = Regex("""var\s+source\s*=\s*['"](https?://[^"']+)['"]""", RegexOption.IGNORE_CASE)
        val anyVideoQuoted = Regex("""["'](https?://[^"']+\.(?:mp4|m3u8|mpd|webm)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
        val bareUrl = Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8|mpd|webm)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
    }

    private val patterns = listOf(
        P.srcWithBraces, P.fileEq, P.sourceEq, P.srcEq, P.urlEq, P.videoEq,
        P.innerHtmlEq, P.videoTagSrc, P.sourceTagSrc, P.sourcesArray, P.varSource, P.anyVideoQuoted
    )

    override fun extract(html: String, embedUrl: String, context: Any?): String? {
        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            var url = match.groupValues[1]
            if (!url.startsWith("http")) {
                try {
                    val decoded = String(Base64.decode(url, Base64.DEFAULT), Charsets.UTF_8)
                    if (decoded.startsWith("http")) url = decoded
                } catch (_: Exception) {}
            }
            if (url.startsWith("http") && isVideoUrl(url)) return url
        }
        return null
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return VIDEO_EXTENSIONS.any { lower.contains(".$it") }
    }
}
