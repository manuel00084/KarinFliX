package com.karin.streamtv.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object DiskImageCache {

    private const val CACHE_DIR = "image_cache"
    private const val MAX_CACHE_SIZE = 50L * 1024 * 1024 // 50MB
    private const val MAX_ENTRIES = 500

    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, CACHE_DIR).also { it.mkdirs() }
        trimCache()
    }

    fun get(url: String): Bitmap? {
        val file = fileFor(url) ?: return null
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun getBytes(url: String): ByteArray? {
        val file = fileFor(url) ?: return null
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun put(url: String, data: ByteArray) {
        val file = fileFor(url) ?: return
        try {
            FileOutputStream(file).use { it.write(data) }
            file.setLastModified(System.currentTimeMillis())
            trimCache()
        } catch (_: Exception) {}
    }

    fun putBitmap(url: String, bitmap: Bitmap) {
        val file = fileFor(url) ?: return
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
            }
            file.setLastModified(System.currentTimeMillis())
            trimCache()
        } catch (_: Exception) {}
    }

    fun loadFromNetwork(url: String, maxWidth: Int = 400, maxHeight: Int = 220): Bitmap? {
        get(url)?.let { return it }

        val bytes = fetchBytes(url) ?: return null
        put(url, bytes)

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Ordered candidate URLs for a site's icon, high resolution first.
     * PNG/WebP sources are preferred over ICO (which Android decodes as a
     * small first frame and looks blurry when upscaled).
     */
    fun faviconCandidates(host: String): List<String> {
        val h = host.removePrefix("www.")
        return listOf(
            "https://$host/apple-touch-icon.png",
            "https://$host/android-chrome-512x512.png",
            "https://$host/android-chrome-192x192.png",
            "https://$host/favicon-192x192.png",
            "https://$host/favicon-128x128.png",
            "https://$host/favicon.ico",
            "https://www.google.com/s2/favicons?domain=$h&sz=128",
            "https://icons.duckduckgo.com/ip3/$h.ico"
        )
    }

    /**
     * Tries [candidates] in order and returns the bitmap with the most pixels,
     * stopping early once a crisp (>=128x128) icon is found. Falls back to the
     * largest available when none reach that size.
     */
    fun loadBestFavicon(candidates: List<String>, maxWidth: Int = 256, maxHeight: Int = 256): Bitmap? {
        var best: Bitmap? = null
        var bestPixels = 0
        for (url in candidates) {
            val bmp = loadFromNetwork(url, maxWidth, maxHeight) ?: continue
            val pixels = bmp.width * bmp.height
            if (pixels > bestPixels) {
                best = bmp
                bestPixels = pixels
            }
            if (minOf(bmp.width, bmp.height) >= 128 && pixels >= 128 * 128) break
        }
        return best
    }

    /**
     * Renders [logo] centered inside a dark rounded plate of [plateW]x[plateH],
     * so wide brand logos and small favicons both look uniform on a card.
     */
    fun renderLogoPlate(logo: Bitmap, plateW: Int, plateH: Int, cornerRadius: Int = 12): Bitmap {
        val out = Bitmap.createBitmap(plateW, plateH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rect = RectF(0f, 0f, plateW.toFloat(), plateH.toFloat())
        val r = cornerRadius.toFloat()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16161C.toInt() }
        c.drawRoundRect(rect, r, r, fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = 0xFF2E2E38.toInt()
        }
        c.drawRoundRect(rect, r, r, border)

        val pad = plateH * 0.16f
        val scale = minOf((plateW - pad * 2) / logo.width, (plateH - pad * 2) / logo.height)
        val w = logo.width * scale
        val h = logo.height * scale
        val left = (plateW - w) / 2f
        val top = (plateH - h) / 2f
        val dst = RectF(left, top, left + w, top + h)
        c.drawBitmap(logo, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun fetchBytes(url: String): ByteArray? {
        return try {
            val referer = try { "https://" + java.net.URL(url).host } catch (_: Exception) { "" }
            val conn = Http.client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Referer", referer)
                    .header("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .build()
            ).execute()
            if (!conn.isSuccessful) { conn.close(); return null }
            val bytes = conn.body?.bytes()
            conn.close()
            bytes
        } catch (_: Exception) { null }
    }

    fun clearCache() {
        cacheDir?.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(url: String): File? {
        val dir = cacheDir ?: return null
        val hash = md5(url)
        return File(dir, hash)
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun trimCache() {
        val dir = cacheDir ?: return
        val allFiles = dir.listFiles() ?: return
        val files = allFiles.sortedBy { it.lastModified() }

        var totalSize = files.sumOf { it.length() }
        var i = 0
        while (totalSize > MAX_CACHE_SIZE && i < files.size) {
            totalSize -= files[i].length()
            files[i].delete()
            i++
        }

        val remaining = dir.listFiles() ?: return
        if (remaining.size > MAX_ENTRIES) {
            remaining.sortedBy { it.lastModified() }.take(remaining.size - MAX_ENTRIES).forEach { it.delete() }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val (w, h) = options.outWidth to options.outHeight
        var inSampleSize = 1
        if (h > reqH || w > reqW) {
            val halfH = h / 2; val halfW = w / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
