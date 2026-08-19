package com.karin.streamtv.util

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Límite global de WebViews simultáneos en toda la app.
 *
 * Cada WebView (visible u offscreen) dispara un proceso renderer de Chromium aparte,
 * que en equipos con 1GB de RAM cuesta ~40-100MB. Cuando el bypass de Cloudflare y el
 * extractor de video corren a la vez pueden coexistir varios renderers. Este semáforo
 * los acota a [MAX_WEBVIEWS] simultáneos (patrón JobManager de Kodi).
 */
object WebViewPermits {

    const val MAX_WEBVIEWS = 2

    private val permits = Semaphore(MAX_WEBVIEWS, true)

    fun tryAcquire(timeoutSeconds: Long): Boolean =
        permits.tryAcquire(timeoutSeconds, TimeUnit.SECONDS)

    fun tryAcquire(timeout: Long, unit: TimeUnit): Boolean =
        permits.tryAcquire(timeout, unit)

    fun release() {
        permits.release()
    }
}
