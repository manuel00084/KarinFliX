package com.karin.streamtv.scraper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

class ServerRedirectTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Test
    fun `follow voe redirect chain`() {
        val url = "https://voe.sx/e/hhlktnqcbych"
        var current = url
        repeat(5) {
            val req = Request.Builder()
                .url(current)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept-Language", "es-ES,es;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                println(">>> ${resp.code} -> ${resp.request.url}  location=${resp.header("Location")}")
                if (resp.isRedirect) {
                    val loc = resp.header("Location")
                    if (loc == null) return
                    current = if (loc.startsWith("http")) loc else resp.request.url.newBuilder().encodedPath(loc).build().toString()
                } else {
                    val body = resp.body?.string().orEmpty()
                    println(">>> FINAL LENGTH: ${body.length}")
                    println(body.take(2000))
                    return
                }
            }
        }
    }
}
