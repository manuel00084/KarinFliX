package com.karin.streamtv.scraper

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.math.min

class ServerExtractionProbeTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private fun get(url: String, referer: String? = null, withCookies: Boolean = false): Triple<Int, String, String> {
        val rb = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "es-ES,es;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (referer != null) rb.header("Referer", referer)
        client.newCall(rb.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val cookies = resp.headers("Set-Cookie").joinToString("; ")
            return Triple(resp.code, body, cookies)
        }
    }

    private fun printInteresting(title: String, body: String) {
        println("=".repeat(60))
        println("## $title (len=${body.length})")
        // search key fragments
        listOf("pass_md5", "file_id", "md5", "player", "m3u8", "mp4", "wurl", "furl", "vfile", "api", "config", "token", "signature", "time=", "window.", "src=", "iframe", "hls", "stream", "dood", "source")
            .forEach { frag ->
                val i = body.indexOf(frag)
                if (i >= 0) {
                    val s = body.substring(maxOf(0, i - 80), min(body.length, i + 220)).replace("\n", " ")
                    println("  [$frag]: ...$s...")
                }
            }
    }

    private fun dump(name: String, body: String) {
        val dir = System.getProperty("java.io.tmpdir")
        java.io.File(dir, name).writeText(body)
        println("DUMPED $name len=${body.length} to $dir")
    }

    @Test
    fun `probe dood dsvplay`() {
        val (code, body, cookies) = get("https://dsvplay.com/e/e8u7hezzzdgr")
        println("dsvplay status=$code cookies=[$cookies]")
        dump("probe_dsvplay.html", body)

        val passPath = Regex("""\$\.get\('(/pass_md5/[^']+)'""").find(body)?.groupValues?.get(1)
        val token = Regex("""token=([A-Za-z0-9]+)['\"]""").find(body)?.groupValues?.get(1)
        val fileId = Regex("""\$\\.cookie\('file_id',\s*'(\d+)'""").find(body)?.groupValues?.get(1)
        println("passPath=$passPath token=$token fileId=$fileId")

        if (passPath != null) {
            for (host in listOf("https://dsvplay.com", "https://playmogo.com")) {
                try {
                    val rb = Request.Builder().url(host + passPath)
                        .header("User-Agent", ua)
                        .header("Referer", "https://dsvplay.com/")
                        .header("Cookie", "file_id=$fileId; aff=233880; lang=1")
                    client.newCall(rb.build()).execute().use { resp ->
                        val b = resp.body?.string().orEmpty()
                        println("PASSMD5 [$host] status=${resp.code} len=${b.length} body=[${b.take(300)}]")
                    }
                } catch (e: Exception) {
                    println("PASSMD5 [$host] ERROR ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    @Test
    fun `probe byse`() {
        val (code, body, _) = get("https://bysekoze.com/e/9kq0rhx63sdx")
        println("byse status=$code")
        dump("probe_byse.html", body)
        // fetch the JS bundle
        val bundle = Regex("""<script[^>]*src="(/assets/[^"]+\.js)"[^>]*>""").find(body)?.groupValues?.get(1)
        println("byse bundle=$bundle")
        if (bundle != null) {
            val (c2, b2, _) = get("https://bysekoze.com$bundle")
            println("bundle status=$c2 len=${b2.length}")
            dump("probe_byse_bundle.js", b2)
        }
    }

    @Test
    fun `probe byse api`() {
        val id = "9kq0rhx63sdx"
        val urls = listOf(
            "/api/videos/$id/watch/settings",
            "/api/videos/$id/settings",
            "/api/videos/$id",
            "/api/videos/$id/watch",
            "/api/videos/$id/embed",
            "/api/videos/$id/watch/view",
            "/api/videos/$id/sources",
            "/api/videos/$id/playback",
            "/api/videos/stream/$id",
            "/api/$id"
        )
        for (u in urls) {
            try {
                val (code, b, _) = get("https://bysekoze.com$u")
                println("API $u status=$code len=${b.length}")
                if (code == 200) dump("probe_byse_api_${u.replace("/", "_").replace(":", "")}.json", b)
                println("  body=${b.take(400)}")
            } catch (e: Exception) { println("API $u ERROR ${e.javaClass.simpleName}") }
        }
    }

    private fun b64urlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return java.util.Base64.getDecoder().decode(t)
    }

    private fun qa(version: String, total: Int): List<Int> {
        val n = version.trim().toIntOrNull() ?: return emptyList()
        val o = n
        val a = 31 - n
        return if (o < 1 || a < 1 || o > total || a > total) emptyList() else listOf(o, a)
    }

    @Test
    fun `probe byse decrypt2`() {
        val body = java.io.File(System.getProperty("java.io.tmpdir"), "probe_byse_api__api_videos_9kq0rhx63sdx.json").readText()
        fun grab(key: String): String =
            Regex("""\"$key\":\s*\"([^\"]+)\"""").find(body)?.groupValues?.get(1)
                ?: error("missing $key")
        val version = grab("version")
        val iv = b64urlDecode(grab("iv"))
        val payload = b64urlDecode(grab("payload"))
        val kpm = Regex("""\"key_parts\":\s*\[([\s\S]*?)\]""").find(body)!!.groupValues[1]
        val parts = Regex("""\"([^\"]+)\"""").findAll(kpm).toList().map { it.groupValues[1] }
        val sel = qa(version, parts.size)
        println("version=$version sel=$sel partSizes=${sel.map { b64urlDecode(parts[it - 1]).size }}")
        val key = sel.flatMap { b64urlDecode(parts[it - 1]).toList() }.toByteArray()
        println("keyLen=${key.size}")
        val plain = try {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
            String(c.doFinal(payload))
        } catch (e: Exception) {
            "DECRYPT ERROR ${e.javaClass.simpleName}: ${e.message}"
        }
        println("plain=$plain")
    }

    @Test
    fun `probe byse decrypt`() {
        val c = java.io.File(System.getProperty("java.io.tmpdir"), "probe_byse_bundle.js").readText()
        for (frag in listOf("api/", "/e/", "fetch(", "axios", "/video", "/source", "m3u8", "playerToken", "wurl", "/file/", "backend", "BASE_URL", "baseUrl")) {
            val ms = Regex(".{0,80}${Regex.escape(frag)}.{0,120}").findAll(c).toList()
            if (ms.isNotEmpty()) {
                println("=== $frag (${ms.size}) ===")
                for (k in 0 until min(3, ms.size)) {
                    println("  " + ms[k].value.replace("\n", " ").replace("\r", " "))
                }
            }
        }
        // fetch videoPages bundle chunks
        val chunkNames = Regex("""import\(\"\./(videoPagesBundle-[^\"]+\.js)\"\)""").findAll(c).toList().map { it.groupValues[1] }.distinct()
        println("chunks=$chunkNames")
        for (ch in chunkNames) {
            val (code, b, _) = get("https://bysekoze.com/assets/$ch")
            println("chunk $ch status=$code len=${b.length}")
            dump("probe_byse_$ch", b)
            val ops = Regex("""operation\s*:\s*[\"']([^\"']+)[\"']""").findAll(b).toList().map { it.groupValues[1] }.distinct()
            val ops2 = Regex("""operation[\"']?\s*,\s*[\"']([^\"']+)[\"']""").findAll(b).toList().map { it.groupValues[1] }.distinct()
            println("  ops=$ops $ops2")
            for (frag in listOf("m3u8", "hls", "playerToken", "wurl", "watch", "embed", "source", "src", "master")) {
                val ms = Regex(".{0,70}${Regex.escape(frag)}.{0,100}").findAll(b).toList()
                if (ms.isNotEmpty()) {
                    println("  [$frag] ${ms.size}")
                    for (k in 0 until min(2, ms.size)) println("    " + ms[k].value.replace("\n", " ").replace("\r", " "))
                }
            }
        }
    }


    private fun rot13(input: String): String = input.map { c ->
        when (c) {
            in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> c
        }
    }.joinToString("")

    private fun b64decode(s: String): String =
        String(java.util.Base64.getDecoder().decode(s), Charsets.UTF_8)

    private fun decryptF7(p8: String): String? {
        try {
            val patterns = listOf("@\$", "^^", "~@", "%?", "*~", "!!", "#&")
            var v = rot13(p8)
            for (pat in patterns) v = v.replace(Regex(Regex.escape(pat)), "_")
            v = v.replace("_", "")
            val v4 = b64decode(v)
            val v5 = v4.map { (it.code - 3).toChar() }.joinToString("")
            val v6 = v5.reversed()
            val json = b64decode(v6)
            return json
        } catch (e: Exception) {
            println("decryptF7 error: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    @Test
    fun `probe voe decrypt`() {
        val html = java.io.File(System.getProperty("java.io.tmpdir"), "probe_jessica.html").readText()
        val m = Regex("""<script[^>]*application/json[^>]*>([\s\S]*?)</script>""").find(html)
        val encoded = m?.groupValues?.get(1)?.trim()
            ?.substringAfter("[\"")?.substringBeforeLast("\"]")
        println("encoded len=${encoded?.length}")
        val json = encoded?.let { decryptF7(it) }
        println("decrypted: $json")
    }

    @Test
    fun `probe voe`() {
        val jar = okhttp3.CookieJar.NO_COOKIES
        val c = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(object : okhttp3.CookieJar {
                val store = mutableMapOf<String, MutableList<okhttp3.Cookie>>()
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                    store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
                }
                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                    return store[url.host] ?: emptyList()
                }
            })
            .build()

        fun getC(u: String): Triple<Int, String, String> {
            val rb = Request.Builder().url(u).header("User-Agent", ua)
                .header("Accept-Language", "es-ES,es;q=0.9")
                .header("Referer", "https://voe.sx/")
            c.newCall(rb.build()).execute().use { resp ->
                return Triple(resp.code, resp.body?.string().orEmpty(), resp.headers("Set-Cookie").joinToString("; "))
            }
        }

        val (code1, body1, _) = getC("https://voe.sx/e/hhlktnqcbych")
        println("voe.e status=$code1 len=${body1.length}")
        val m = Regex("""location\.href\s*=\s*'([^']+)'""").find(body1)
        println("voe redirect target=${m?.groupValues?.get(1)}")
        val target = m?.groupValues?.get(1) ?: return
        val (code2, body2, _) = getC(target)
        println("jessica status=$code2 len=${body2.length}")
        dump("probe_jessica.html", body2)
        println("jessica head: ${body2.take(2500)}")

        // try the config js
        val id = target.substringAfterLast("/")
        for (suffix in listOf("/e/$id.js?w=1680&h=945", "/e/$id.js?w=1280&h=720", "/e/$id.js", "/$id.js")) {
            try {
                val (c3, b3, _) = getC("https://jessicachoosemake.com$suffix")
                println("js $suffix status=$c3 len=${b3.length}")
                if (c3 == 200) dump("probe_jessica_${suffix.replace("/", "_").replace("?", "_")}.js", b3)
            } catch (e: Exception) { println("js $suffix ERROR ${e.javaClass.simpleName}") }
        }

        for (u in listOf(
            "https://jessicachoosemake.com/js/loader.a40897e.js",
            "https://provisionamendsale.com/0e/d5/91/0ed591400877d316744c6353cd338f08.js"
        )) {
            try {
                val rb = Request.Builder().url(u)
                    .header("User-Agent", ua)
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .header("Referer", "https://jessicachoosemake.com/e/hhlktnqcbych")
                c.newCall(rb.build()).execute().use { resp ->
                    val b = resp.body?.string().orEmpty()
                    println("loader2 $u status=${resp.code} len=${b.length}")
                    if (resp.code == 200) dump("probe_loader2_${u.substringAfter("//").replace("/", "_").replace("?", "_")}.js", b)
                }
            } catch (e: Exception) { println("loader2 $u ERROR ${e.javaClass.simpleName}") }
        }
    }

    @Test
    fun `probe mega api`() {
        val known = listOf(
            "m1JyQTJT" to "5Uf6XA0_ZO5E-sf-OUbfQH4chrZLqDlNQocD0cWQebw",
            "Wt0F1bpR" to "WlCGMYlOS_K23Q4wH5isOK7Oin6qsa2uyEAG3C5G3ro",
            "8soDySiQ" to "Z6I57NiZ-K5CeE8E95ZKZyL1aSpdoo3eoDA_tE0Dlsc"
        )
        for ((id, key) in known) {
            val body = """[{"a":"g","g":1,"p":"$id"}]"""
            try {
                val rb = Request.Builder()
                    .url("https://g.api.mega.co.nz/cs?id=&ak=")
                    .header("User-Agent", ua)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                client.newCall(rb.build()).execute().use { resp ->
                    val b = resp.body?.string().orEmpty()
                    println("mega [$id] -> ${resp.code} ${b.take(300)}")
                }
            } catch (e: Exception) { println("mega [$id] ERROR ${e.message}") }
        }
        val raw = b64urlDecode("5Uf6XA0_ZO5E-sf-OUbfQH4chrZLqDlNQocD0cWQebw")
        println("keyRawLen=${raw.size}")
    }

    @Test
    fun `probe mega`() {
        val (code, body, _) = get("https://mega.nz/embed/#!m1JyQTJT!5Uf6XA0_ZO5E-sf-OUbfQH4chrZLqDlNQocD0cWQebw")
        println("mega status=$code len=${body.length}")
        dump("probe_mega.html", body)
        printInteresting("mega body", body)
    }
}
