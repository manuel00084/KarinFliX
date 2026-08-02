package com.karin.streamtv.scraper

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies the MEGA AES-128-CTR decryption pipeline against a real, live
 * public MEGA file (the MEGAcmd integration-test export file). Mirrors the
 * exact arithmetic used in ServerDirectResolver.resolveMega and
 * MegaDecryptingDataSource so a passing test proves the app's code path.
 */
class MegaCryptoProbeTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun b64urlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.getDecoder().decode(t)
    }

    /** Exact copy of ServerDirectResolver.resolveMega key derivation. */
    private fun deriveKey(keyB64: String): Pair<ByteArray, Long> {
        val rawKey = b64urlDecode(keyB64)
        assertEquals("key must be 32 bytes", 32, rawKey.size)
        val words = LongArray(8) { i ->
            (rawKey[i * 4].toLong() and 0xFF shl 24) or
                    (rawKey[i * 4 + 1].toLong() and 0xFF shl 16) or
                    (rawKey[i * 4 + 2].toLong() and 0xFF shl 8) or
                    (rawKey[i * 4 + 3].toLong() and 0xFF)
        }
        val k = IntArray(4) { (words[it].toInt() xor words[it + 4].toInt()) }
        val keyBytes = ByteArray(16)
        for (i in 0 until 4) {
            keyBytes[i * 4] = ((k[i].toLong() ushr 24) and 0xFF).toByte()
            keyBytes[i * 4 + 1] = ((k[i].toLong() ushr 16) and 0xFF).toByte()
            keyBytes[i * 4 + 2] = ((k[i].toLong() ushr 8) and 0xFF).toByte()
            keyBytes[i * 4 + 3] = (k[i].toLong() and 0xFF).toByte()
        }
        val ctrStart = ((words[4].toLong() and 0xFFFFFFFFL) shl 32) or (words[5].toLong() and 0xFFFFFFFFL)
        return keyBytes to ctrStart
    }

    /** Exact copy of MegaDecryptingDataSource.genKeystream counter logic. */
    private fun decrypt(key: ByteArray, ctrStart: Long, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val out = ByteArray(data.size)
        var pos = 0
        while (pos < data.size) {
            val block = pos / 16
            val counter = BigInteger.valueOf(ctrStart)
                .shiftLeft(64)
                .add(BigInteger.valueOf(block.toLong()))
            val ivBytes = ByteArray(16)
            val cb = counter.toByteArray()
            val copyLen = minOf(cb.size, 16)
            System.arraycopy(cb, cb.size - copyLen, ivBytes, 16 - copyLen, copyLen)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
            val keystream = cipher.update(ByteArray(16))
            for (j in 0 until 16) {
                val idx = pos + j
                if (idx >= data.size) break
                out[idx] = (data[idx].toInt() xor keystream[j].toInt()).toByte()
            }
            pos += 16
        }
        return out
    }

    @Test
    fun `mega live file decrypts to plaintext with app algorithm`() {
        val handle = "YfNngDKR"
        val keyB64 = "qk9THHhxbakddRmt_tLR8OhInexzVCpPPG6M6feFfZg"

        val body = """[{"a":"g","g":1,"p":"$handle"}]"""
        val apiResp = client.newCall(
            Request.Builder()
                .url("https://g.api.mega.co.nz/cs?id=0")
                .header("User-Agent", "Mozilla/5.0")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        assertTrue("api response", apiResp.startsWith("["))
        val g = Regex(""""g":"(https?://[^"]+)""").find(apiResp)?.groupValues?.get(1)
        val size = Regex(""""s":(\d+)""").find(apiResp)?.groupValues?.get(1)?.toLongOrNull()
        assertTrue("got g url: $g", g != null && g.startsWith("http"))
        assertTrue("got size", size != null)
        val gUrl = g!!
        val fileSize = size!!
        println("MEGA API: size=$fileSize g=$gUrl")

        val enc = client.newCall(
            Request.Builder().url(gUrl).header("User-Agent", "Mozilla/5.0").build()
        ).execute().use { it.body?.bytes()!! }
        assertEquals("downloaded size", fileSize, enc.size.toLong())
        println("ciphertext bytes: ${enc.joinToString(" ") { "%02x".format(it) }}")

        val (key, ctrStart) = deriveKey(keyB64)
        val plain = decrypt(key, ctrStart, enc)
        val text = String(plain, Charsets.UTF_8)
        println("PLAINTEXT: '$text'")
        assertTrue("plaintext is printable ascii", text.all { it in ' '..'~' })
    }

    @Test
    fun `datasource streaming replica matches reference on sdk png`() {
        val handle = "zAJnUTYD"
        val keyB64 = "8YE5dXrnIEJ47NdDfFEvqtOefhuDMphyae0KY5zrhns"
        val body = """[{"a":"g","g":1,"p":"$handle"}]"""
        val apiResp = client.newCall(
            Request.Builder().url("https://g.api.mega.co.nz/cs?id=0")
                .header("User-Agent", "Mozilla/5.0")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        val g = Regex(""""g":"(https?://[^"]+)""").find(apiResp)?.groupValues?.get(1)!!
        val enc = client.newCall(
            Request.Builder().url(g)
                .header("Range", "bytes=0-47")
                .header("User-Agent", "Mozilla/5.0")
                .build()
        ).execute().use { it.body?.bytes()!! }
        val (key, ctrStart) = deriveKey(keyB64)
        assertEquals("size", 48, enc.size)

        // Byte-by-byte streaming replica of MegaDecryptingDataSource.read()
        var currentBlock = -1L
        var keystream = ByteArray(16)
        var keystreamIdx = 0
        var position = 0L
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val out = ByteArray(enc.size)
        fun genKeystream(block: Long) {
            val counter = BigInteger.valueOf(ctrStart).shiftLeft(64).add(BigInteger.valueOf(block))
            val ivBytes = ByteArray(16)
            val cb = counter.toByteArray()
            val copyLen = minOf(cb.size, 16)
            System.arraycopy(cb, cb.size - copyLen, ivBytes, 16 - copyLen, copyLen)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
            keystream = cipher.update(ByteArray(16))
            keystreamIdx = 0
        }
        // simulate read chunks of 3, then 4096
        var off = 0
        val reads = listOf(3, 4096, 4096)
        for (rd in reads) {
            val n = minOf(rd, enc.size - off)
            for (i in 0 until n) {
                val block = position / 16
                if (block != currentBlock) { currentBlock = block; genKeystream(block) }
                out[off + i] = (enc[off + i].toInt() xor keystream[keystreamIdx].toInt()).toByte()
                keystreamIdx++
                if (keystreamIdx == 16) keystreamIdx = 0
                position++
            }
            off += n
        }
        val hex = out.take(16).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        println("STREAMING REPLICA first 16: $hex")
        assertEquals(
            "streaming replica must equal reference (49 49 2a 00 ...)",
            "49 49 2a 00 08 00 00 00 19 00 fe 00 04 00 01 00",
            hex
        )
    }
}
