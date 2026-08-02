package com.karin.streamtv.scraper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

class JessicaChoosemakeTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Test
    fun `probe jessicachoosemake`() {
        val url = "https://jessicachoosemake.com/e/hhlktnqcbych"
        println("### URL: $url")
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Referer", "https://voe.sx/e/hhlktnqcbych")
                .header("Accept-Language", "es-ES,es;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                println("### HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                println("### LENGTH: ${body.length}")
                println(body.take(4000))
            }
        } catch (e: Exception) {
            println("### ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
