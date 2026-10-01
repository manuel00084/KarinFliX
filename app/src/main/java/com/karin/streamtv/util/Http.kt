package com.karin.streamtv.util

import android.content.Context
import android.util.Log
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Http {

    private const val TAG = "Http"
    private var httpCache: Cache? = null
    private var persistentCookieStore: PersistentCookieStore? = null

    fun initCache(cacheDir: File) {
        val dir = File(cacheDir, "http_cache")
        httpCache = Cache(dir, 10L * 1024 * 1024)
    }

    fun initCookies(context: Context) {
        persistentCookieStore = PersistentCookieStore(context)
    }

    fun getPersistentCookieStore(): PersistentCookieStore? = persistentCookieStore

    val client: OkHttpClient
        get() = _client

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            persistentCookieStore?.saveFromResponse(url, cookies)
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return persistentCookieStore?.loadForRequest(url) ?: emptyList()
        }
    }

    private val _client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        httpCache?.let { builder.cache(it) }
        builder.build()
    }

    class PersistentCookieStore(context: Context) {

        private val prefs = context.getSharedPreferences("karin_flix_cookies", Context.MODE_PRIVATE)
        private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

        init {
            loadAll()
        }

        fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val existing = cookieStore[host]?.toMutableList() ?: mutableListOf()
            for (cookie in cookies) {
                existing.removeAll { it.name == cookie.name && it.path == cookie.path }
                existing.add(cookie)
            }
            cookieStore[host] = existing
            persistToDisk(host, existing)
        }

        fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val cookies = cookieStore[host] ?: return emptyList()
            return cookies.filter { cookie ->
                if (cookie.hasExpired()) return@filter false
                if (cookie.path != "/" && !url.encodedPath.startsWith(cookie.path)) return@filter false
                if (cookie.secure && url.scheme != "https") return@filter false
                true
            }
        }

        private fun persistToDisk(host: String, cookies: List<Cookie>) {
            try {
                val jsonArray = JSONArray()
                for (cookie in cookies) {
                    jsonArray.put(JSONObject().apply {
                        put("name", cookie.name)
                        put("value", cookie.value)
                        put("domain", cookie.domain)
                        put("path", cookie.path)
                        put("expires", cookie.expiresAt)
                        put("secure", cookie.secure)
                        put("httpOnly", cookie.httpOnly)
                    })
                }
                val key = "cookies_${host.replace(".", "_")}"
                prefs.edit().putString(key, jsonArray.toString()).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist cookies for $host: ${e.message}")
            }
        }

        private fun loadAll() {
            try {
                val all = prefs.all
                for ((key, value) in all) {
                    if (!key.startsWith("cookies_")) continue
                    val host = key.removePrefix("cookies_").replace("_", ".")
                    val json = value as? String ?: continue
                    val array = JSONArray(json)
                    val cookies = mutableListOf<Cookie>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val builder = Cookie.Builder()
                            .name(obj.getString("name"))
                            .value(obj.getString("value"))
                            .domain(obj.getString("domain"))
                            .path(obj.getString("path"))
                        if (obj.has("expires") && obj.getLong("expires") > 0) {
                            builder.expiresAt(obj.getLong("expires"))
                        }
                        if (obj.optBoolean("secure", false)) builder.secure()
                        if (obj.optBoolean("httpOnly", false)) builder.httpOnly()
                    cookies.add(builder.build())
                }
                cookieStore[host] = cookies.filter { !it.hasExpired() }

                val validCookies = cookieStore[host]
                if (validCookies.isNullOrEmpty()) {
                    prefs.edit().remove(key).apply()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted cookies: ${e.message}")
        }
        }

        private fun Cookie.hasExpired(): Boolean {
            return expiresAt > 0 && System.currentTimeMillis() > expiresAt
        }

        /**
         * Stores a raw `name=value; name2=value2` cookie header (as returned by
         * WebView's CookieManager after a sign-in flow) for a given host. Used to
         * hand a session captured in the WebView over to the OkHttp scraper client.
         */
        fun saveFromHeader(host: String, cookieHeader: String) {
            val names = mutableSetOf<String>()
            val cookies = mutableListOf<Cookie>()
            val now = System.currentTimeMillis()
            for (section in cookieHeader.split(";")) {
                val eq = section.indexOf('=')
                if (eq <= 0) continue
                val name = section.substring(0, eq).trim()
                val value = section.substring(eq + 1).trim()
                if (name.isEmpty() || value.isEmpty() || !names.add(name)) continue
                try {
                    cookies.add(
                        Cookie.Builder()
                            .name(name)
                            .value(value)
                            .domain(host)
                            .path("/")
                            .expiresAt(now + 365L * 24 * 60 * 60 * 1000)
                            .httpOnly()
                            .build()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid cookie $name: ${e.message}")
                }
            }
            if (cookies.isEmpty()) return
            // OkHttp only delivers cookies that match the request host.
            saveFromResponse("https://$host".toHttpUrl(), cookies)
        }

        /** Returns the names of the cookies currently stored for [host]. */
        fun getCookieNames(host: String): Set<String> =
            (cookieStore[host] ?: emptyList()).map { it.name }.toSet()

        /** Returns the value of a single cookie ([name]) stored for [host], or null. */
        fun getCookieValue(host: String, name: String): String? =
            (cookieStore[host] ?: emptyList()).firstOrNull { it.name == name }?.value

        /** Removes all stored cookies for [host] (used when signing out). */
        fun clearHost(host: String) {
            cookieStore.remove(host)
            prefs.edit().remove("cookies_${host.replace(".", "_")}").apply()
        }
    }
}
