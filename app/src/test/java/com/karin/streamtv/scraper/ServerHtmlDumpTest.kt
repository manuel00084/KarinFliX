package com.karin.streamtv.scraper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

class ServerHtmlDumpTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Test
    fun `dump short server responses`() {
        val targets = listOf(
            "voe" to "https://voe.sx/e/hhlktnqcbych",
            "byse" to "https://bysekoze.com/e/9kq0rhx63sdx",
            "savefiles" to "https://savefiles.com/e/uoytpft435rq",
            "mega" to "https://mega.nz/embed/#!m1JyQTJT!5Uf6XA0_ZO5E-sf-OUbfQH4chrZLqDlNQocD0cWQebw",
            "mixdrop" to "https://mixdrop.top/e/gjno8x9pcw7w979",
            "hexload" to "https://hexload.com/embed-f5a5k08e57uj",
            "dsvplay" to "https://dsvplay.com/e/e8u7hezzzdgr"
        )
        for ((name, url) in targets) {
            println("#".repeat(70))
            println("### $name ($url)")
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()
                client.newCall(req).execute().use { resp ->
                    println("### HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    println("### LENGTH: ${body.length}")
                    println(body.take(2500))
                }
            } catch (e: Exception) {
                println("### ERROR: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
