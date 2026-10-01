package com.karin.streamtv.karinlink.protocol

import com.karin.streamtv.karinlink.LinkServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.nio.file.Files

/**
 * El endpoint de archivos, probado por HTTP real contra el servidor.
 *
 * [FsAccessTest] demuestra las reglas de rutas en aislamiento; esto
 * comprueba que el servidor las aplica de verdad y que además se niega a
 * escribir. Un endpoint que sirve el disco por la LAN es el sitio donde un
 * error de un carácter se convierte en una fuga de datos, y un test que solo
 * llama a funciones puras no lo habría detectado.
 */
class FileShareServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val registry = PeerRegistry(InMemoryTrustStore(), { "Server" })
    private val info = PeerInfo(registry.deviceId, "Server", "1.0")
    private val token = "T0ken-de-prueba-1234567890ab"

    private var port = 0

    @After
    fun tearDown() {
        LinkServer.filesProvider = null
        LinkServer.stop()
    }

    private fun start(roots: List<File>, enabled: Boolean = true) {
        LinkServer.stop()
        assertTrue(LinkServer.configure(registry, info))
        LinkServer.filesProvider = { com.karin.streamtv.karinlink.FsConfig(enabled, token, roots) }
        port = LinkServer.start(0)
        assertTrue("server did not bind", port > 0)
    }

    /**
     * A minimal HTTP client, so the test sees exactly what the server sends.
     *
 * Reads raw bytes rather than via a reader: a body can hold anything, and
 * decoding it as text would corrupt binary content and make a byte count
 * comparison meaningless.
     */
    private class Http(val port: Int, method: String = "GET", target: String, headers: Map<String, String> = emptyMap()) {
        val status: String
        val head: Map<String, String>
        val body: ByteArray

        init {
            val socket = Socket("127.0.0.1", port)
            socket.soTimeout = 10_000
            val request = buildString {
                append("$method $target HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                for ((k, v) in headers) append("$k: $v\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()

            val raw = socket.getInputStream().readBytes()
            socket.close()

            val split = indexOfHeaderEnd(raw)
            val headerText = String(raw, 0, if (split < 0) raw.size else split, Charsets.US_ASCII)
            val lines = headerText.split("\r\n")
            status = lines.firstOrNull().orEmpty()
            head = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) head[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            // Nothing is hidden from the test on purpose: a HEAD that leaks a
            // body has to be visible here, not papered over by the client.
            body = if (split < 0) ByteArray(0)
                   else raw.copyOfRange(split + 4, raw.size)
        }

        val text: String get() = String(body, Charsets.UTF_8)

        private fun indexOfHeaderEnd(raw: ByteArray): Int {
            val sep = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
            outer@ for (i in 0..raw.size - sep.size) {
                for (j in sep.indices) {
                    if (raw[i + j] != sep[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    @Test
    fun `a request without a token is refused`() {
        val media = tmp.newFolder("media")
        start(listOf(media))

        val res = Http(port, target = "/fs")

        assertTrue("expected 401, got ${res.status}", res.status.contains("401"))
    }

    @Test
    fun `a request with the wrong token is refused`() {
        val media = tmp.newFolder("media")
        start(listOf(media))

        val res = Http(port, target = "/fs?t=otro-token")

        assertTrue("expected 401, got ${res.status}", res.status.contains("401"))
    }

    @Test
    fun `a valid token lists the shared folders`() {
        val media = tmp.newFolder("media")
        File(media, "capa.mkv").writeText("x")
        start(listOf(media))

        val res = Http(port, target = "/fs?t=$token")

        assertTrue(res.status.contains("200"))
        assertTrue("the listing did not include the file: ${res.text}", res.text.contains("capa.mkv"))
    }

    @Test
    fun `a shared file is served with its content`() {
        val media = tmp.newFolder("media")
        val file = File(media, "capa.mkv")
        file.writeBytes(ByteArray(5000) { it.toByte() })
        start(listOf(media))

        val res = Http(port, target = "/fs?t=$token&path=${encode(file.path)}")

        assertTrue(res.status.contains("200"))
        assertEquals("5000", res.head["content-length"])
        assertEquals(Files.readAllBytes(file.toPath()).size, res.body.size)
    }

    @Test
    fun `a path outside the shared folders is refused`() {
        val media = tmp.newFolder("media")
        val privateDir = tmp.newFolder("private")
        val secret = File(privateDir, "secret.txt").apply { writeText("no leer") }
        start(listOf(media))

        val res = Http(port, target = "/fs?t=$token&path=${encode(secret.path)}")

        assertTrue("expected 403, got ${res.status}", res.status.contains("403"))
        assertFalse("the body leaked the file", res.text.contains("no leer"))
    }

    @Test
    fun `a traversal attempt is refused`() {
        val media = tmp.newFolder("media")
        start(listOf(media))

        val escape = File(media, "../../etc/passwd").path
        val res = Http(port, target = "/fs?t=$token&path=${encode(escape)}")

        assertTrue("expected 403, got ${res.status}", res.status.contains("403"))
        assertFalse("the body leaked /etc/passwd", res.text.contains("root:"))
    }

    @Test
    fun `writing is never allowed`() {
        val media = tmp.newFolder("media")
        start(listOf(media))

        for (method in listOf("PUT", "POST", "DELETE")) {
            val res = Http(port, method = method, target = "/fs?t=$token")
            assertTrue("$method should be refused, got ${res.status}", res.status.contains("405"))
        }
    }

    @Test
    fun `nothing is served while the feature is off`() {
        val media = tmp.newFolder("media")
        File(media, "capa.mkv").writeText("secreto")
        start(listOf(media), enabled = false)

        val res = Http(port, target = "/fs?t=$token")

        // 404 and not 401: off means the endpoint does not exist.
        assertTrue("expected 404, got ${res.status}", res.status.contains("404"))
        assertFalse("the body leaked the listing", res.text.contains("capa.mkv"))
    }

    @Test
    fun `a token with no shared folders serves nothing`() {
        start(emptyList())

        val res = Http(port, target = "/fs?t=$token")

        assertTrue("expected 404, got ${res.status}", res.status.contains("404"))
    }

    @Test
    fun `a HEAD sends the length without the bytes`() {
        val media = tmp.newFolder("media")
        val file = File(media, "capa.mkv").apply { writeBytes(ByteArray(4096)) }
        start(listOf(media))

        val res = Http(port, method = "HEAD", target = "/fs?t=$token&path=${encode(file.path)}")

        assertTrue(res.status.contains("200"))
        assertEquals("4096", res.head["content-length"])
        assertEquals(0, res.body.size)
    }

    @Test
    fun `the token may travel in a header instead of the query`() {
        val media = tmp.newFolder("media")
        start(listOf(media))

        val res = Http(port, target = "/fs", headers = mapOf("X-Karin-Token" to token))

        assertTrue("expected 200, got ${res.status}", res.status.contains("200"))
    }

    @Test
    fun `a path that does not exist answers 404 and not 403`() {
        val media = tmp.newFolder("media")
        start(listOf(media))

        val res = Http(port, target = "/fs?t=$token&path=${encode(File(media, "nada.mkv").path)}")

        assertTrue("expected 404, got ${res.status}", res.status.contains("404"))
    }

    @Test
    fun `asking for a shared folder itself lists it`() {
        val media = tmp.newFolder("media")
        File(media, "capa.mkv").writeText("x")
        start(listOf(media))

        val res = Http(port, target = "/fs?t=$token&path=${encode(media.path)}")

        assertTrue("expected 200, got ${res.status}", res.status.contains("200"))
        assertTrue("the root itself was not listed: ${res.text}", res.text.contains("capa.mkv"))
    }

    @Test
    fun `a subfolder is listed`() {
        val media = tmp.newFolder("media")
        val sub = File(media, "temporada 2").apply { mkdirs() }
        File(sub, "episodio.mkv").writeText("x")
        start(listOf(media))

        val res = Http(port, target = "/fs?t=$token&path=${encode(sub.path)}")

        assertTrue("expected 200, got ${res.status}", res.status.contains("200"))
        assertTrue(res.text.contains("episodio.mkv"))
    }

    @Test
    fun `a HEAD on a listing sends no body`() {
        val media = tmp.newFolder("media")
        File(media, "capa.mkv").writeText("x")
        start(listOf(media))

        val res = Http(port, method = "HEAD", target = "/fs?t=$token")

        assertTrue("expected 200, got ${res.status}", res.status.contains("200"))
        assertEquals(0, res.body.size)
    }
}
