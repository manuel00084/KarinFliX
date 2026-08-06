package com.karin.streamtv.util

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object CloudflareInterceptor {

    private const val TAG = "CFInterceptor"
    var maxWaitSeconds: Long = 20L

    // Cap global de WebViews simultáneos (patrón JobManager de Kodi): un WebView
    // offscreen de 720x1280 por bypass es caro en RAM/compositor en TV/box baratas.
    // Varios bypasses a la vez (p.ej. 8 requests del engine) hundían el renderer.
    private val webViewPermits = Semaphore(2, true)

    private val CF_MARKERS = listOf(
        "cf-browser-verification", "Just a moment",
        "Checking your browser", "cf-challenge", "Attention Required",
        "Verify you are human", "challenge-error-title", "challenge-error-text",
        "cf_chl"
    )

    // Actual Turnstile challenge widget (not just the script URL or JS variable names).
    // The HTML element <div class="cf-turnstile" data-sitekey="..."> is present on a real
    // challenge page; mere <script src=".../turnstile/..."> is loaded by many legitimate pages.
    private val CF_TURNSTILE_MARKERS = listOf(
        """class="cf-turnstile"""",
        """class='cf-turnstile'"""
    )

    // Managed/captcha challenges are frequently served with HTTP 200 (JS auto-submit page)
    // rather than 403/503, so we detect these distinctive markers regardless of status code.
    // NOTE: markers that also appear in legitimate pages (script URLs, analytics beacons,
    // JS variable names like "turnstileToken") are deliberately excluded to avoid false
    // positives on pages that merely *load* Cloudflare scripts.
    private val CF_200_CHALLENGE_MARKERS = listOf(
        "Please wait while your request is being verified",
        "One moment, please",
        "Checking your browser before accessing",
        "cf-browser-verification",
        "cf_chl_",
        "Verify you are human",
        """action="/cdn-cgi/l/chk_js""",
        "Enable JavaScript"
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
        if (CF_200_CHALLENGE_MARKERS.any { html.contains(it, ignoreCase = true) }) {
            return true
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveWithWebView(context: Context, url: String, userAgent: String? = null): String? {
        val permitted = withContext(Dispatchers.IO) {
            try {
                webViewPermits.tryAcquire(maxWaitSeconds, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
        }
        if (!permitted) {
            Log.w(TAG, "CF bypass capacity full, dropping $url")
            return null
        }
        return try {
            solveWithWebViewLocked(context, url, userAgent)
        } finally {
            webViewPermits.release()
        }
    }

    private suspend fun solveWithWebViewLocked(context: Context, url: String, userAgent: String?): String? {
        return try {
            suspendCancellableCoroutine { cont ->
                var htmlResult: String? = null
                var webViewRef: WebView? = null
                var attachedWm: android.view.WindowManager? = null
                var attachedView: android.view.View? = null
                val latch = CountDownLatch(1)
                val handler = android.os.Handler(android.os.Looper.getMainLooper())

                val cleanup = {
                    handler.post {
                        val wv = webViewRef
                        webViewRef = null
                        try {
                            wv?.stopLoading()
                            wv?.destroy()
                        } catch (_: Exception) {}
                        try {
                            attachedWm?.let { wm -> attachedView?.let { wm.removeViewImmediate(it) } }
                        } catch (_: Exception) {}
                        attachedWm = null
                        attachedView = null
                    }
                }

                val webViewRunnable = Runnable {
                    if (!cont.isActive) return@Runnable

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)

                    // Cloudflare binds cf_clearance to the exact UA string and also fingerprints TLS,
                    // so a plain OkHttp retry is rejected even with the cookie. The attached WebView
                    // passes the challenge and has the real HTML, so we return it directly.
                    val hostActivity = AppActivityHolder.current()
                    val webContext: Context = hostActivity ?: context
                    val wm = webContext.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager

                    val parent = android.widget.FrameLayout(webContext).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                        x = -9999f
                        y = -9999f
                        clipChildren = false
                        visibility = android.view.View.INVISIBLE
                        alpha = 0f
                    }

                    webViewRef = WebView(webContext).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(720, 1280)
                        setBackgroundColor(0x00000000)
                        visibility = android.view.View.INVISIBLE
                        alpha = 0f
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.setSupportMultipleWindows(false)
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = userAgent ?: "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                super.onPageFinished(view, loadedUrl)
                                Log.d(TAG, "WebView page finished: $loadedUrl")
                                cookieManager.flush()
                                val stillChallenge = loadedUrl.isNullOrBlank() ||
                                    loadedUrl.contains("/cdn-cgi/") ||
                                    loadedUrl.contains("challenge-platform")
                                if (stillChallenge || htmlResult != null) return
                                if (loadedUrl.isNullOrBlank()) return
                                val pageCookies = cookieManager.getCookie(loadedUrl)
                                if (!pageCookies.isNullOrBlank()) {
                                    saveCookiesToStore(context, loadedUrl, pageCookies)
                                }
                                Log.i(TAG, "Reached real page $loadedUrl — extracting HTML")
                                view?.evaluateJavascript(
                                    "(function(){return document.documentElement.outerHTML;})()",
                                    { value ->
                                        try {
                                            val decoded = org.json.JSONTokener(value ?: "null").nextValue() as? String
                                            htmlResult = decoded?.takeIf { it.isNotBlank() }
                                        } catch (_: Exception) { htmlResult = null }
                                        if (htmlResult != null) {
                                            Log.i(TAG, "Extracted HTML (${htmlResult!!.length} chars)")
                                        }
                                        latch.countDown()
                                    }
                                )
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
                        }, 20000)
                    }

                    parent.addView(webViewRef!!, android.view.ViewGroup.LayoutParams(720, 1280))

                    if (wm != null && hostActivity != null) {
                        try {
                            val lp = android.view.WindowManager.LayoutParams(
                                1,
                                1,
                                android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                android.graphics.PixelFormat.TRANSLUCENT
                            )
                            lp.x = -9999
                            lp.y = -9999
                            wm.addView(parent, lp)
                            attachedWm = wm
                            attachedView = parent
                            Log.i(TAG, "CF WebView attached to window (${hostActivity.javaClass.simpleName})")
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not attach CF WebView: ${e.message}")
                            attachedWm = null
                            attachedView = null
                        }
                    } else {
                        Log.w(TAG, "No foreground activity; running headless CF WebView")
                    }

                    Log.i(TAG, "Loading URL in WebView: $url")
                    webViewRef?.loadUrl(url)
                }

                handler.post(webViewRunnable)

                cont.invokeOnCancellation {
                    latch.countDown()
                    handler.removeCallbacks(webViewRunnable)
                    cleanup()
                }

                Thread {
                    latch.await(maxWaitSeconds, TimeUnit.SECONDS)
                    handler.post {
                        if (cont.isActive) {
                            if (htmlResult != null) {
                                Log.i(TAG, "CF bypass SUCCESS for $url")
                            } else {
                                Log.w(TAG, "CF bypass TIMEOUT for $url")
                            }
                        }
                        cleanup()
                        if (cont.isActive) cont.resume(htmlResult)
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "CF bypass exception: ${e.message}")
            null
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
