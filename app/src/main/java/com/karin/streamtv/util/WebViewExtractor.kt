package com.karin.streamtv.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object WebViewExtractor {
    private const val TAG = "WebViewExtractor"
    private const val TIMEOUT_MS = 30_000L
    private var webView: WebView? = null
    private val initDeferred = CompletableDeferred<Unit>()
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(context.applicationContext)
                wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                wv.layout(0, 0, 1, 1)
                val settings = wv.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                webView = wv
                initialized = true
                initDeferred.complete(Unit)
                Log.d(TAG, "WebView initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init WebView: ${e.message}")
                initDeferred.completeExceptionally(e)
            }
        }
    }

    suspend fun extractVideoUrl(embedUrl: String): String? {
        if (!initialized) {
            try {
                kotlinx.coroutines.withTimeout(5000L) {
                    initDeferred.await()
                }
            } catch (_: Exception) {
                Log.w(TAG, "WebView not initialized, skipping")
                return null
            }
        }
        val wv = webView ?: run {
            Log.w(TAG, "WebView is null")
            return null
        }

        Log.d(TAG, "Starting WebView extraction for $embedUrl")
        var finalResult: String? = null
        val finished = AtomicBoolean(false)
        val pageLoadStart = System.currentTimeMillis()
        var loadCount = 0
        var clickAttempted = false
        var navigatedToDLink = false
        val capturedUrls = mutableListOf<String>()

        val host = try { java.net.URI(embedUrl).host } catch (_: Exception) { "" }

        return suspendCancellableCoroutine<String?> { cont ->
            cont.invokeOnCancellation {
                finished.set(true)
                Log.d(TAG, "Extraction cancelled for $embedUrl")
            }

            wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        loadCount++
                        Log.d(TAG, "Page started #$loadCount: ${url?.take(80)}")
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        Log.d(TAG, "Override URL: ${url.take(120)}")
                        if (url.contains(".m3u8") || url.contains(".mp4") || url.contains(".webm")) {
                            finalResult = url
                            Log.d(TAG, "Captured video URL from override: ${url.take(120)}")
                            if (!finished.getAndSet(true)) cont.resume(url)
                            return true
                        }
                        // Capture non-video URLs too — may be the final file URL without extension
                        if (capturedUrls.size < 50) capturedUrls.add(url)
                        Log.d(TAG, "Captured URL #${capturedUrls.size}: ${url.take(80)}")
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val elapsed = System.currentTimeMillis() - pageLoadStart
                        Log.d(TAG, "Page finished #$loadCount: ${url?.take(80)} elapsed=${elapsed}ms")

                        if (finished.get()) return

                        // If we've navigated away from the original embed page, grab the URL
                        if (clickAttempted && url != null && url != embedUrl) {
                            finalResult = url
                            Log.d(TAG, "Captured URL after navigation: ${url.take(120)}")
                            if (!finished.getAndSet(true)) cont.resume(url)
                            return
                        }

                        // On first page load (after Cloudflare challenge resolves), find and click /d/ links
                        if (elapsed > 2000 && (url?.contains(host) == true) && !clickAttempted) {
                            // Look for video URLs in the page
                            view?.evaluateJavascript("""
                                (function(){
                                    var results = [];
                                    var html = document.documentElement.outerHTML || '';
                                    
                                    // Dump HTML for debugging (truncated)
                                    var dump = html.substring(0, 3000);
                                    
                                    var m3u8 = html.match(/https?:\/\/[^"'\s<>]+?\.m3u8[^"'\s<>]*/g);
                                    if(m3u8) m3u8.forEach(function(m){ results.push(m); });
                                    var mp4 = html.match(/https?:\/\/[^"'\s<>]+?\.mp4[^"'\s<>]*/g);
                                    if(mp4) mp4.forEach(function(m){ results.push(m); });
                                    var links = [];
                                    var anchors = document.querySelectorAll('a');
                                    anchors.forEach(function(a){
                                        if(a.href && a.href.match(/\.(mp4|m3u8|webm|ts)/)) links.push(a.href);
                                        if(a.href && (a.href.match(/\\/d\\//) || a.hasAttribute('download') || a.href.match(/\\/download/))) links.push(a.href);
                                    });
                                    var videos = document.querySelectorAll('video, video source, iframe');
                                    videos.forEach(function(v){ if(v.src) links.push(v.src); });
                                    
                                    // Also check all script src
                                    document.querySelectorAll('script').forEach(function(s){ if(s.src) links.push(s.src); });
                                    
                                    return JSON.stringify({dump:dump, urls:results, links:links});
                                })();
                            """.trimIndent()) { json ->
                                Log.d(TAG, "JS eval result length: ${json?.length ?: 0}")
                                if (json != null && json != "null" && json.length > 10) {
                                    try {
                                        val obj = org.json.JSONObject(json)
                                        val dump = obj.optString("dump", "")
                                        if (dump.isNotEmpty()) Log.d(TAG, "Page HTML dump (3k): ${dump.take(1500)}")
                                        val urls = obj.optJSONArray("urls")
                                        if (urls != null) {
                                            for (i in 0 until urls.length()) {
                                                val u = urls.getString(i)
                                                if (u.contains(".m3u8") || u.contains(".mp4")) {
                                                    finalResult = u
                                                    Log.d(TAG, "Found video URL: ${u.take(120)}")
                                                    break
                                                }
                                            }
                                        }
                                        val links = obj.optJSONArray("links")
                                        if (links != null && finalResult == null) {
                                            for (i in 0 until links.length()) {
                                                val u = links.getString(i)
                                                Log.d(TAG, "Found link: ${u.take(120)}")
                                                if (u.contains(".m3u8") || u.contains(".mp4") || u.contains("/d/")) {
                                                    finalResult = u
                                                    Log.d(TAG, "Found from links: ${u.take(120)}")
                                                    break
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "JS eval parse error: ${e.message}")
                                    }
                                }
                            }

                            // Click /d/ link for file hosts (savefiles, etc.)
                            view?.evaluateJavascript("""
                                (function(){
                                    // Try /d/ links first, then download links
                                    var link = document.querySelector('a[href*="/d/"]');
                                    if(!link) link = document.querySelector('a[download]');
                                    if(!link) link = document.querySelector('a[href*="/download"]');
                                    if(!link) link = document.querySelector('.download-link a, .download a, .btn-download');
                                    if(link) {
                                        var absoluteHref = link.href;
                                        link.click();
                                        return JSON.stringify({action:'clicked',href:absoluteHref});
                                    }
                                    return JSON.stringify({action:'none'});
                                })();
                            """.trimIndent()) { clickResult ->
                                Log.d(TAG, "Click result: $clickResult")
                                clickAttempted = true
                                // If we got a /d/ URL from the JS eval, also try to navigate directly
                                if (finalResult == null && clickResult?.contains("/d/") == true) {
                                    try {
                                        val json = org.json.JSONObject(clickResult)
                                        val href = json.optString("href", "")
                                        if (href.isNotBlank() && href != embedUrl) {
                                            Log.d(TAG, "Navigating directly to: ${href.take(120)}")
                                            view?.stopLoading()
                                            view?.loadUrl(href)
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }

                        // Savefiles fallback: try /d/ URL directly (embed page has no links)
                        if (finalResult == null && !navigatedToDLink && host.contains("savefiles") && embedUrl.contains("/e/")) {
                            val dUrl = embedUrl.replace("/e/", "/d/")
                            Log.d(TAG, "SaveFiles: navigating to /d/ URL: ${dUrl.take(120)}")
                            navigatedToDLink = true
                            view?.stopLoading()
                            view?.loadUrl(dUrl)
                        }

                        // After navigating to /d/ URL, capture whatever URL we end up on
                        if (navigatedToDLink && url != null && url != embedUrl) {
                            finalResult = url
                            Log.d(TAG, "Captured URL from /d/ navigation: ${url.take(120)}")
                            if (!finished.getAndSet(true)) cont.resume(url)
                            return
                        }

                        if (finalResult != null) {
                            if (!finished.getAndSet(true)) {
                                Log.d(TAG, "Resuming with result: ${finalResult.take(80)}")
                                cont.resume(finalResult)
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                        Log.w(TAG, "Error: ${error?.description} url=${request?.url?.toString()?.take(60)}")
                    }
                }

                wv.loadUrl(embedUrl)

                Handler(Looper.getMainLooper()).postDelayed({
                    if (!finished.getAndSet(true)) {
                        Log.w(TAG, "Timeout (${TIMEOUT_MS}ms)")
                        cont.resume(finalResult)
                    }
                }, TIMEOUT_MS)
        }
    }

    fun destroy() {
        Handler(Looper.getMainLooper()).post {
            webView?.destroy()
            webView = null
            initialized = false
            Log.d(TAG, "WebView destroyed")
        }
    }
}
