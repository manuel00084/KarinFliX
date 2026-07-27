package com.karin.streamtv.util

import android.util.Log
import java.util.LinkedHashMap

object ExtractionCache {

    private const val TAG = "ExtractionCache"
    private const val MAX_ENTRIES = 64
    private const val TTL_MS = 5 * 60 * 1000L

    private data class CacheEntry(val url: String, val headers: Map<String, String>?, val timestamp: Long)

    private val cache = object : LinkedHashMap<String, CacheEntry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun get(embedUrl: String): ExtractedVideo? {
        val entry = cache[embedUrl] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            cache.remove(embedUrl)
            Log.d(TAG, "Cache expired for: ${embedUrl.take(80)}")
            return null
        }
        Log.d(TAG, "Cache HIT: ${embedUrl.take(80)} -> ${entry.url.take(80)}")
        return ExtractedVideo(entry.url, entry.headers ?: emptyMap())
    }

    @Synchronized
    fun put(embedUrl: String, result: ExtractedVideo) {
        cache[embedUrl] = CacheEntry(result.url, result.headers, System.currentTimeMillis())
        Log.d(TAG, "Cached: ${embedUrl.take(80)} -> ${result.url.take(80)} (size=${cache.size})")
    }

    @Synchronized
    fun clear() {
        cache.clear()
        Log.d(TAG, "Cache cleared")
    }

    @Synchronized
    fun evictExpired(): Int {
        val now = System.currentTimeMillis()
        val iterator = cache.iterator()
        var evicted = 0
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (now - entry.timestamp > TTL_MS) {
                iterator.remove()
                evicted++
            }
        }
        if (evicted > 0) Log.d(TAG, "Evicted $evicted expired entries")
        return evicted
    }
}
