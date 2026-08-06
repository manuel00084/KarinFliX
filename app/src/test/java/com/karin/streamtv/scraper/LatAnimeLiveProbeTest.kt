package com.karin.streamtv.scraper

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Live probe replicating the app's exact LatAnime path:
 *  1) fetch episode page -> extract data-player servers (Base64)
 *  2) resolve HTTP-resolvable servers (byse, voe, mega, dsvplay) exactly like ServerDirectResolver
 *  3) fetch WebView-only embeds (hexload, mixdrop, mp4upload) and check for video signals
 */
class LatAnimeLiveProbeTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private fun get(url: String, referer: String? = null, acceptJson: Boolean = false): Pair<Int, String> {
        val rb = Request.Builder().url(url).header("User-Agent", ua).header("Accept-Language", "es-ES,es;q=0.9")
        rb.header("Accept", if (acceptJson) "application/json, text/plain, */*" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (referer != null) rb.header("Referer", referer)
        return try {
            client.newCall(rb.build()).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            -1 to "ERR ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun postJson(url: String, json: String): Pair<Int, String> {
        val rb = Request.Builder().url(url)
            .header("User-Agent", ua).header("Accept-Language", "es-ES,es;q=0.9")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
        return try {
            client.newCall(rb.build()).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            -1 to "ERR ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun b64urlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.getDecoder().decode(t)
    }

    @Test
    fun probe() {
        // 1) episode page
        val (c0, html) = get("https://latanime.org/ver/rilakkuma-episodio-18")
        println("EPISODE status=$c0 len=${html.length}")
        if (c0 != 200) return
        val players = Regex("""data-player="([^"]+)"""").findAll(html).toList()
        println("data-player count=${players.size}")
        val urls = players.map { m ->
            String(Base64.getDecoder().decode(m.groupValues[1]))
        }.distinct()
        urls.forEach { println("  SERVER $it") }

        // 2) HTTP-resolvable
        for (u in urls) {
            when {
                u.contains("byse") -> probeByse(u)
                u.contains("voe") -> probeVoe(u)
                u.contains("mega") -> probeMega(u)
                u.contains("dsvplay") -> probeDood(u)
                u.contains("hexload") || u.contains("mixdrop") || u.contains("mp4upload") -> probeWebviewOnly(u)
            }
        }
    }

    private fun probeByse(url: String) {
        val id = url.substringAfterLast("/").trim().substringBefore("?")
        val (c, body) = get("https://bysekoze.com/api/videos/$id", acceptJson = true)
        println("BYSE api /api/videos/$id status=$c len=${body.length}")
        if (c != 200 || !body.contains("playback")) {
            println("  body=${body.take(250)}")
            return
        }
        fun grab(key: String): String {
            val v = Regex("\"$key\":\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            if (v == null) { println("  missing $key"); return "" }
            return v
        }
        try {
            val version = grab("version").trim().toIntOrNull() ?: return println("  no version")
            val iv = b64urlDecode(grab("iv"))
            val payload = b64urlDecode(grab("payload"))
            val kpm = Regex("\"key_parts\":\\s*\\[([\\s\\S]*?)\\]").find(body)!!.groupValues[1]
            val parts = Regex("\"([^\"]+)\"").findAll(kpm).toList().map { it.groupValues[1] }
            val sel = listOf(version, 31 - version).filter { it in 1..parts.size }
            println("  version=$version sel=$sel partSizes=${sel.map { b64urlDecode(parts[it - 1]).size }}")
            val key = sel.flatMap { b64urlDecode(parts[it - 1]).toList() }.toByteArray()
            val plain = try {
                val c2 = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                c2.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.GCMParameterSpec(128, iv))
                String(c2.doFinal(payload))
            } catch (e: Exception) {
                "DECRYPT ERR ${e.javaClass.simpleName}: ${e.message}"
            }
            val srcUrl = Regex("\"url\":\\s*\"([^\"]+)\"").find(plain)?.groupValues?.get(1).orEmpty()
            println("  plaintext head=${plain.take(200)}")
            println("  source[0]=$srcUrl")
        } catch (e: Exception) {
            println("  BYSE parse ERR ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun probeVoe(url: String) {
        val (c1, body1) = get(url, referer = "https://latanime.org/")
        val target = Regex("""location\.href\s*=\s*'([^']+)'""").find(body1)?.groupValues?.get(1)
        println("VOE $url status=$c1 len=${body1.length} redirect=$target")
        val target2 = target ?: url
        val (c2, body2) = get(target2, referer = url)
        println("VOE mirror $target2 status=$c2 len=${body2.length}")
        val encoded = Regex("""<script[^>]*application/json[^>]*>([\s\S]*?)</script>""")
            .find(body2)?.groupValues?.get(1)?.trim()
            ?.substringAfter("[\"")?.substringBeforeLast("\"]")
        println("VOE encoded len=${encoded?.length}")
        if (encoded != null) {
            val json = decryptF7(encoded)
            println("VOE decrypted=$json")
        } else {
            // cloudflare challenge?
            println("VOE body head: ${body2.take(300)}")
        }
    }

    private fun decryptF7(p8: String): String? {
        return try {
            val patterns = listOf("@\$", "^^", "~@", "%?", "*~", "!!", "#&")
            var v = p8.map { c ->
                when (c) {
                    in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                    in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                    else -> c
                }
            }.joinToString("")
            for (pat in patterns) v = v.replace(Regex(Regex.escape(pat)), "_")
            v = v.replace("_", "")
            val v4 = String(b64urlDecode(v))
            val v5 = v4.map { (it.code - 3).toChar() }.joinToString("")
            val v6 = v5.reversed()
            String(b64urlDecode(v6))
        } catch (e: Exception) {
            "DECRYPT ERR ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun probeMega(url: String) {
        val m = Regex("""(?:/#!|/file/)(.+?)(?:!|#)([^#!?]+)""").find(url) ?: return println("MEGA bad url: $url")
        val id = m.groupValues[1].trimEnd('/')
        val (c, body) = postJson("https://g.api.mega.co.nz/cs?id=", """[{"a":"g","g":1,"p":"$id"}]""")
        println("MEGA id=$id status=$c body=${body.take(200)}")
    }

    private fun probeDood(url: String) {
        val host = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return
        val (c, body) = get(url)
        println("DOOD $url status=$c len=${body.length}")
        val passPath = Regex("""\$\.get\('(/pass_md5/[^']+)'""").find(body)?.groupValues?.get(1)
        val fileId = Regex("""\$\\.cookie\('file_id',\s*'(\d+)'""").find(body)?.groupValues?.get(1).orEmpty()
        println("  passPath=$passPath fileId=$fileId")
        if (passPath != null) {
            val (c2, direct) = get(host + passPath, referer = "$host/", )
            println("  pass_md5 status=$c2 direct=${direct.take(120)}")
        }
    }

    private fun probeWebviewOnly(url: String) {
        val (c, body) = get(url, referer = "https://latanime.org/")
        val hasVideo = body.contains("<video") || body.contains(".m3u8") || body.contains(".mp4")
        val cf = body.contains("cloudflare") || body.contains("challenge") || body.length < 3000
        println("WEBVIEW ${url.take(50)} status=$c len=${body.length} videoSig=$hasVideo cfHint=$cf")
    }
}
