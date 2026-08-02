package com.karin.streamtv.util

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object CloudflareInterceptor {

    private const val TAG = "CFInterceptor"
    private const val MAX_WAIT_SECONDS = 20L

    private val CF_MARKERS = listOf(
        "cf-browser-verification", "challenge-platform", "Just a moment",
        "Checking your browser", "cf-challenge", "Attention Required",
        "Verify you are human", "challenge-error-title", "challenge-error-text",
        "cf_chl", "turnstile"
    )

    private val CF_TURNSTILE_MARKERS = listOf(
        "cf-turnstile", "challenges.cloudflare.com/turnstile"
    )

    fun isCloudflareChallenge(statusCode: Int, html: String, serverHeader: String? = null): Boolean {
        if (statusCode == 403 || statusCode == 503) {
            if (serverHeader != null && serverHeader.contains("cloudflare", ignoreCase = true)) {
                return true
            }
            if (CF_MARKERS.any { html.contains(it, ignoreCase = true) }) {
                return true
            }
        }
        if (statusCode == 200 && CF_TURNSTILE_MARKERS.any { html.contains(it, ignoreCase = true) }) {
            return true
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveWithWebView(context: Context, url: String): Boolean {
        return try {
            suspendCancellableCoroutine { cont ->
                var solved = false
                var webViewRef: WebView? = null
                val latch = CountDownLatch(1)
                val handler = android.os.Handler(android.os.Looper.getMainLooper())

                val cleanup = {
                    handler.post {
                        val wv = webViewRef
                        if (wv != null) {
                            webViewRef = null
                            try {
                                wv.stopLoading()
                                wv.destroy()
                            } catch (_: Exception) {}
                        }
                    }
                }

                val webViewRunnable = Runnable {
                    if (!cont.isActive) return@Runnable

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)

                    webViewRef = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                super.onPageFinished(view, loadedUrl)
                                Log.d(TAG, "WebView page finished: $loadedUrl")
                                cookieManager.flush()
                                val cookies = cookieManager.getCookie(url)
                                if (!cookies.isNullOrBlank() && latch.count > 0) {
                                    Log.i(TAG, "Cookies found for $url")
                                    latch.countDown()
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?, errorCode: Int, description: String?, failingUrl: String?
                            ) {
                                super.onReceivedError(view, errorCode, description, failingUrl)
                                Log.w(TAG, "WebView error $errorCode: $description")
                                if (latch.count > 0) latch.countDown()
                            }
                        }

                        postDelayed({
                            cookieManager.flush()
                            if (latch.count > 0) latch.countDown()
                        }, 12000)

                        Log.i(TAG, "Loading URL in WebView: $url")
                        loadUrl(url)
                    }
                }

                handler.post(webViewRunnable)

                cont.invokeOnCancellation {
                    latch.countDown()
                    handler.removeCallbacks(webViewRunnable)
                    cleanup()
                }

                Thread {
                    latch.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
                    handler.post {
                        if (cont.isActive) {
                            val cookieManager = CookieManager.getInstance()
                            val cookies = cookieManager.getCookie(url)
                            if (!cookies.isNullOrBlank() && !solved) {
                                solved = true
                                saveCookiesToStore(context, url, cookies)
                                Log.i(TAG, "CF bypass SUCCESS for $url")
                            } else {
                                Log.w(TAG, "CF bypass TIMEOUT for $url")
                            }
                        }
                        cleanup()
                        if (cont.isActive) cont.resume(solved)
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "CF bypass exception: ${e.message}")
            false
        }
    }

    private fun saveCookiesToStore(context: Context, url: String, rawCookies: String) {
        try {
            val host = java.net.URI(url).host ?: return
            val httpUrl = url.toHttpUrlOrNull() ?: return
            val store = Http.getPersistentCookieStore() ?: return

            val cookieList = mutableListOf<Cookie>()
            rawCookies.split(";").forEach { pair ->
                val parts = pair.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    val builder = Cookie.Builder()
                        .name(parts[0].trim())
                        .value(parts[1].trim())
                        .domain(host)
                        .path("/")
                    if (url.startsWith("https")) builder.secure()
                    cookieList.add(builder.build())
                }
            }
            if (cookieList.isNotEmpty()) {
                store.saveFromResponse(httpUrl, cookieList)
                Log.d(TAG, "Saved ${cookieList.size} cookies for $host")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save CF cookies: ${e.message}")
        }
    }
}
