package com.karin.streamtv.cast

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket

/**
 * El servidor que pone un fichero local en la red para que un receptor DLNA
 * pueda bajarlo.
 *
 * Se prueba con HTTP real contra un socket: el fallo caro aqui es el rango.
 * Un receptor que pide "bytes=X-" y recibe 200 con todo no hace seek y en
 * muchas TVs ni siquiera arranca, y eso un test de funciones puras no lo
 * veria.
 */
class EphemeralMediaServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var server: EphemeralMediaServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun startServer(content: ByteArray): String {
        val file = tmp.newFile("video.mp4")
        file.writeBytes(content)
        val url = EphemeralMediaServer(file).also { server = it }.start()
        assertNotNull("no LAN address or port to bind", url)
        return url!!
    }

    /** La ruta no es fija: se genera en cada arranque. */
    private fun media() = server?.path ?: error("server not started")

    private data class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun rawRequest(url: String, path: String, method: String, headers: Map<String, String> = emptyMap()): Response {
        val port = java.net.URI(url).port
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 8000
            val out = socket.getOutputStream()
            val request = StringBuilder("$method $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> request.append("$k: $v\r\n") }
            request.append("\r\n")
            out.write(request.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val raw = socket.getInputStream().readBytes()
            val text = raw.toString(Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            val head = if (split >= 0) text.substring(0, split) else text
            val body = if (split >= 0) raw.copyOfRange(split + 4, raw.size) else ByteArray(0)
            val lines = head.split("\r\n")
            val status = lines[0].substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            return Response(status, headers, body)
        }
    }

    @Test
    fun `serves the whole file when no range is asked`() {
        val content = ByteArray(4096) { (it % 251).toByte() }
        val url = startServer(content)

        val response = rawRequest(url, media(), "GET")

        assertEquals(200, response.status)
        assertEquals(content.size.toString(), response.headers["content-length"])
        assertEquals("bytes", response.headers["accept-ranges"])
        assertArrayEquals(content, response.body)
    }

    @Test
    fun `answers 206 with the requested slice`() {
        val content = ByteArray(1000) { it.toByte() }
        val url = startServer(content)

        val response = rawRequest(url, media(), "GET", mapOf("Range" to "bytes=100-199"))

        assertEquals(206, response.status)
        assertEquals("bytes 100-199/1000", response.headers["content-range"])
        assertEquals("100", response.headers["content-length"])
        assertArrayEquals(content.copyOfRange(100, 200), response.body)
    }

    @Test
    fun `answers 206 for an open ended range`() {
        val content = ByteArray(500) { it.toByte() }
        val url = startServer(content)

        val response = rawRequest(url, media(), "GET", mapOf("Range" to "bytes=400-"))

        assertEquals(206, response.status)
        assertEquals("bytes 400-499/500", response.headers["content-range"])
        assertArrayEquals(content.copyOfRange(400, 500), response.body)
    }

    @Test
    fun `answers 206 for a suffix range`() {
        val content = ByteArray(500) { it.toByte() }
        val url = startServer(content)

        val response = rawRequest(url, media(), "GET", mapOf("Range" to "bytes=-100"))

        assertEquals(206, response.status)
        assertEquals("bytes 400-499/500", response.headers["content-range"])
        assertArrayEquals(content.copyOfRange(400, 500), response.body)
    }

    @Test
    fun `head sends the headers without the body`() {
        val content = ByteArray(64) { it.toByte() }
        val url = startServer(content)

        val response = rawRequest(url, media(), "HEAD", mapOf("Range" to "bytes=0-9"))

        assertEquals(206, response.status)
        assertEquals("10", response.headers["content-length"])
        assertEquals(0, response.body.size)
    }

    @Test
    fun `refuses any other path and any other method`() {
        val url = startServer(ByteArray(16) { it.toByte() })

        assertEquals(404, rawRequest(url, "/etc/passwd", "GET").status)
        // La ruta previsible tampoco sirve: se genera una nueva en cada arranque.
        assertEquals(404, rawRequest(url, "/media", "GET").status)
        assertEquals(405, rawRequest(url, media(), "POST").status)
    }

    @Test
    fun `the path is different on every server`() {
        val first = EphemeralMediaServer(tmp.newFile("a.mp4").apply { writeBytes(ByteArray(8)) })
        val second = EphemeralMediaServer(tmp.newFile("b.mp4").apply { writeBytes(ByteArray(8)) })
        val a = first.start()?.substringAfterLast('/')
        first.stop()
        val b = second.start()?.substringAfterLast('/')
        second.stop()

        assertNotNull(a)
        assertNotNull(b)
        assertTrue("path should be a random token, was $a", a!!.length >= 16)
        assertTrue("two servers must not share a path ($a vs $b)", a != b)
    }

    @Test
    fun `refuses a range that is out of bounds`() {
        val url = startServer(ByteArray(100) { it.toByte() })

        val response = rawRequest(url, media(), "GET", mapOf("Range" to "bytes=9000-9500"))

        assertEquals(416, response.status)
        assertEquals("bytes */100", response.headers["content-range"])
    }

    @Test
    fun `does not start for something that is not a file`() {
        val missing = File(tmp.root, "no-existe.mp4")
        val started = EphemeralMediaServer(missing).start()
        assertNull(started)
    }

    @Test
    fun `range parser accepts what receivers really send`() {
        assertEquals(100L to 199L, slice(EphemeralMediaServer.parseRange("bytes=100-199", 1000)))
        assertEquals(0L to 999L, slice(EphemeralMediaServer.parseRange("bytes=0-", 1000)))
        assertEquals(950L to 999L, slice(EphemeralMediaServer.parseRange("bytes=-50", 1000)))
        assertEquals(0L to 999L, slice(EphemeralMediaServer.parseRange("bytes=0-5000", 1000)))
        assertNull(EphemeralMediaServer.parseRange(null, 1000))
        assertNull(EphemeralMediaServer.parseRange("items=0-1", 1000))
        assertNull(EphemeralMediaServer.parseRange("bytes=abc-", 1000))
        assertNull(EphemeralMediaServer.parseRange("bytes=500-100", 1000))
    }

    @Test
    fun `content type follows the extension`() {
        assertEquals("video/mp4", EphemeralMediaServer.contentType("a.mp4"))
        assertEquals("application/vnd.apple.mpegurl", EphemeralMediaServer.contentType("a.m3u8"))
        assertEquals("video/x-matroska", EphemeralMediaServer.contentType("a.mkv"))
        assertTrue(EphemeralMediaServer.contentType("a.webm").startsWith("video/"))
    }

    private fun slice(range: Triple<Long, Long, Boolean>?) = range?.let { it.first to it.second }
}
