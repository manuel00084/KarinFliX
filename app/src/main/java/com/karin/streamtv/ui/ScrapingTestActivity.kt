package com.karin.streamtv.ui

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.karin.streamtv.scraper.ScraperRegistry
import com.karin.streamtv.scraper.ScrapingEngine
import com.karin.streamtv.scraper.DynamicParser
import com.karin.streamtv.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScrapingTestActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scrollView = ScrollView(this)
        tvLog = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFF00FF00.toInt())
            setBackgroundColor(0xFF000000.toInt())
        }
        scrollView.addView(tvLog)
        setContentView(scrollView)

        runAllTests()
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            tvLog.append(msg + "\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        Log.d("ScrapingTest", msg)
    }

    private fun runAllTests() {
        lifecycleScope.launch {
            val sites = listOf(
                "Cuevana3" to "https://www3.cuevana3.is",
                "JKAnime" to "https://jkanime.net",
                "LatAnime" to "https://latanime.org",
                "DoramasYT" to "https://www.doramasyt.com",
                "MundoDonghua" to "https://mundodonghua.com",
                "RetroTVE" to "https://retrotve.com",
                "LaCartoons" to "https://www.lacartoons.com",
                "FrikiSeries" to "https://www.frikiserie.com"
            )

            appendLog("=== SCRAPING TEST START ===")
            appendLog("Sites to test: ${sites.size}")
            appendLog("")

            var totalSuccess = 0
            var totalFailed = 0
            val results = mutableListOf<String>()

            for ((name, url) in sites) {
                appendLog("--- [$name] $url ---")
                val startTime = System.currentTimeMillis()

                try {
                    val result = withContext(Dispatchers.IO) {
                        testSite(name, url)
                    }
                    val elapsed = System.currentTimeMillis() - startTime

                    if (result.episodeCount > 0) {
                        totalSuccess++
                        val status = "OK"
                        results.add("$status | $name | ${result.episodeCount} eps | ${elapsed}ms | src=${result.source}")
                        appendLog("  STATUS: $status | ${result.episodeCount} episodes found | ${elapsed}ms | source=${result.source}")
                        appendLog("  FIRST EP: ${result.firstTitle} -> ${result.firstUrl?.take(80)}")
                        if (result.firstThumb.isNotBlank()) {
                            appendLog("  THUMB: ${result.firstThumb.take(100)}")
                        }
                        appendLog("  NEXT PAGE: ${result.nextPage ?: "none"}")
                    } else {
                        totalFailed++
                        val status = "FAIL"
                        results.add("$status | $name | 0 eps | ${elapsed}ms | ${result.error ?: "empty"}")
                        appendLog("  STATUS: $status | 0 episodes | ${elapsed}ms")
                        appendLog("  ERROR: ${result.error ?: "no cards/selectors matched"}")
        appendLog("  HTML size: ${result.htmlSize} bytes")
        appendLog("  CF blocked: ${result.cfBlocked}")
        if (result.htmlSnippet.isNotBlank()) {
            appendLog("  HTML SNIPPET:\n${result.htmlSnippet}")
        }
                    }
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - startTime
                    totalFailed++
                    results.add("ERROR | $name | ${elapsed}ms | ${e.message}")
                    appendLog("  STATUS: ERROR | ${elapsed}ms | ${e.message}")
                }
                appendLog("")
            }

            appendLog("=== SUMMARY ===")
            for (r in results) {
                appendLog("  $r")
            }
            appendLog("")
            appendLog("PASSED: $totalSuccess / ${sites.size}")
            appendLog("FAILED: $totalFailed / ${sites.size}")
            appendLog("=== TEST COMPLETE ===")
        }
    }

    private data class SiteResult(
        val episodeCount: Int,
        val source: String,
        val firstTitle: String,
        val firstUrl: String?,
        val firstThumb: String,
        val nextPage: String?,
        val htmlSize: Int,
        val cfBlocked: Boolean,
        val error: String?,
        val htmlSnippet: String = ""
    )

    private suspend fun testSite(name: String, url: String): SiteResult {
        ScrapingEngine.clearCache()
        ScrapingEngine.resetCircuitBreaker(name)

        val doc = ScrapingEngine.fetch(url, name, "${name}::test", forceFresh = true)
            ?: return SiteResult(0, "fetch_failed", "", null, "", null, 0, false, "ScrapingEngine.fetch returned null")

        val htmlSize = doc.html().length
        val serverHeader = "unknown"

        val cfBlocked = doc.html().let { html ->
            html.contains("cf-browser-verification") ||
            html.contains("challenge-platform") ||
            html.contains("Just a moment") ||
            html.contains("Checking your browser")
        }

        if (cfBlocked) {
            return SiteResult(0, "cf_blocked", "", null, "", null, htmlSize, true, "Cloudflare challenge detected")
        }

        val scraper = ScraperRegistry.getScraper(name)
        if (scraper == null) {
            return SiteResult(0, "no_scraper", "", null, "", null, htmlSize, false, "No scraper registered for $name")
        }

        val episodes = scraper.getLatestEpisodes()

        val nextPage = DynamicParser.findNextPageUrl(doc, url)

        return if (episodes.isNotEmpty()) {
            SiteResult(
                episodeCount = episodes.size,
                source = "scraper",
                firstTitle = episodes[0].title,
                firstUrl = episodes[0].url,
                firstThumb = episodes[0].thumbnailUrl,
                nextPage = nextPage,
                htmlSize = htmlSize,
                cfBlocked = false,
                error = null
            )
        } else {
            val dynamicEps = DynamicParser.parseDynamic(doc, name)
            if (dynamicEps.isNotEmpty()) {
                SiteResult(
                    episodeCount = dynamicEps.size,
                    source = "dynamic_fallback",
                    firstTitle = dynamicEps[0].title,
                    firstUrl = dynamicEps[0].url,
                    firstThumb = dynamicEps[0].thumbnailUrl,
                    nextPage = nextPage,
                    htmlSize = htmlSize,
                    cfBlocked = false,
                    error = null
                )
            } else {
                val bodyHtml = doc.body()?.html() ?: ""
                val linkCount = doc.select("a[href]").size
                val imgCount = doc.select("img").size
                val aWithImg = doc.select("a[href] img").size
                val title = doc.title()
                val snippet = "title='$title' links=$linkCount imgs=$imgCount a+img=$aWithImg"
                val bodySnippet = bodyHtml.take(2000)
                SiteResult(0, "empty", "", null, "", nextPage, htmlSize, false, "Scraper+Dynamic both returned 0", "$snippet\n$bodySnippet")
            }
        }
    }
}
