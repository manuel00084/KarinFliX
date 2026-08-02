package com.karin.streamtv.scraper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Integration test: extracts the real server URLs from a LatAnime episode
 * and tests each one to determine if the video file is reachable or if
 * playback is blocked by ads/interstitial.
 */
class ServerPlaybackTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    private fun fetch(url: String, referer: String = ""): String {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (referer.isNotBlank()) builder.header("Referer", referer)
        return client.newCall(builder.build()).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }.second
    }

    @Test
    fun `probe all LatAnime servers`() {
        val start = System.currentTimeMillis()
        // Real server URLs decoded from LatAnime episode page (2026-07-31)
        val servers = listOf(
            Triple("dsvplay", "https://dsvplay.com/e/e8u7hezzzdgr", "https://latanime.org"),
            Triple("byse", "https://bysekoze.com/e/9kq0rhx63sdx", "https://latanime.org"),
            Triple("hexload", "https://hexload.com/embed-f5a5k08e57uj", "https://latanime.org"),
            Triple("savefiles", "https://savefiles.com/e/uoytpft435rq", "https://latanime.org"),
            Triple("mega", "https://mega.nz/embed/#!m1JyQTJT!5Uf6XA0_ZO5E-sf-OUbfQH4chrZLqDlNQocD0cWQebw", "https://latanime.org"),
            Triple("mixdrop", "https://mixdrop.top/e/gjno8x9pcw7w979", "https://latanime.org"),
            Triple("voe", "https://voe.sx/e/hhlktnqcbych", "https://latanime.org")
        )

        println(">>> Testing ${servers.size} real LatAnime servers")
        for ((name, url, referer) in servers) {
            testServer(name, url, referer)
        }
        println(">>> Total test time: ${System.currentTimeMillis() - start}ms")
    }

    private fun testServer(name: String, url: String, episodeUrl: String) {
        println("=".repeat(70))
        println(">>> SERVER: $name")
        println(">>> URL: $url")
        try {
            val html = fetch(url, episodeUrl)
            println(">>> Response length: ${html.length} chars")

            // Detect ad-blockers / interstitial / anti-bot
            val lower = html.lowercase()
            val adSignals = listOf(
                "you need to enable javascript" to "JS_required",
                "adblock" to "adblock_message",
                "captcha" to "captcha",
                "please click" to "click_through",
                "click here to continue" to "click_continue",
                "verify you are human" to "human_verify",
                "access denied" to "access_denied",
                "cloudflare" to "cloudflare",
                "interstitial" to "interstitial",
                "cf-chl" to "cloudflare_challenge",
                "geetest" to "geetest",
                "recaptcha" to "recaptcha",
                "hcaptcha" to "hcaptcha"
            )
            val detected = adSignals.filter { it.first in lower }

            // Detect actual video file
            val videoSignals = listOf(
                Regex("""<video[^>]*src=["']([^"']+)["']"""),
                Regex("""source[^>]*src=["']([^"']+\.(?:m3u8|mp4|webm))["']"""),
                Regex("""https?://[^"'\s<>]+\.(?:m3u8|mp4|webm)(?:\?[^"'\s<>]*)?"""),
                Regex("""file["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4))["']"""),
                Regex("""src["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4|webm))["']""")
            )
            val videoMatches = videoSignals.flatMap { re -> re.findAll(lower).toList() }

            val videoCount = Regex("""<video""").findAll(lower).toList().size
            val iframeCount = Regex("""<iframe""").findAll(lower).toList().size

            println(">>> <video> elements: $videoCount, <iframe> elements: $iframeCount")
            println(">>> Video file signals found: ${videoMatches.size}")
            if (videoMatches.isNotEmpty()) {
                videoMatches.take(3).forEach { m ->
                    println(">>>   signal: ${m.value.take(120)}")
                }
            }
            println(">>> Ad/block signals: ${detected.map { it.second }.joinToString(", ").ifEmpty { "none" }}")

            // Classify result
            val hasVideo = videoMatches.isNotEmpty() || videoCount > 0
            val blocked = detected.isNotEmpty()

            when {
                hasVideo && !blocked -> println(">>> RESULT: OK - video file found, no blocking detected")
                hasVideo && blocked -> println(">>> RESULT: PARTIAL - video file present BUT blocking signals: ${detected.map { it.second }}")
                !hasVideo && blocked -> println(">>> RESULT: BLOCKED - no video found, ad/interstitial blocking detected: ${detected.map { it.second }}")
                !hasVideo && !blocked -> println(">>> RESULT: NO VIDEO - page loaded but no video file or blocking detected (JS-rendered player?)")
            }
        } catch (e: Exception) {
            println(">>> RESULT: ERROR - ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
