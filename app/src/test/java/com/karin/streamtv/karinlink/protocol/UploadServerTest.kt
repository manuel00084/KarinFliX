package com.karin.streamtv.karinlink.protocol

import com.karin.streamtv.karinlink.FsConfig
import com.karin.streamtv.karinlink.LinkServer
import com.karin.streamtv.karinlink.upload.UploadStore
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Socket
import java.net.URLEncoder

/**
 * La subida de un vídeo, probada por HTTP real contra el servidor.
 *
 * [UploadStoreTest] ya comprueba qué se escribe en disco; aquí lo que importa es
 * el otro lado de la puerta: que se exija el token, que no se acepte un método
 * que no sea POST, que no se acepte un cuerpo sin longitud declarada y que lo
 * que vuelve sea una URL que el reproductor pueda abrir. `/push` es el único
 * endpoint que escribe, así que un descuido aquí es escritura no autorizada.
 */
class UploadServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val registry = PeerRegistry(InMemoryTrustStore(), { "Server" })
    private val info = PeerInfo(registry.deviceId, "Server", "1.0")
    private val token = "T0ken-de-prueba-1234567890ab"

    private lateinit var store: UploadStore
    private var port = 0

    @After
    fun tearDown() {
        LinkServer.filesProvider = null
        LinkServer.uploadsProvider = null
        LinkServer.stop()
    }

    private fun start(enabled: Boolean = true) {
        LinkServer.stop()
        assertTrue(LinkServer.configure(registry, info))
        store = UploadStore(tmp.newFolder("uploads"))
        LinkServer.filesProvider = { FsConfig(enabled, token, listOf(tmp.newFolder("shared"))) }
        LinkServer.uploadsProvider = { store }
        port = LinkServer.start(0)
        assertTrue("server did not bind", port > 0)
    }

    private fun post(
        target: String,
        body: ByteArray? = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
        method: String = "POST",
    ): Reply {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 15_000
        val all = HashMap(headers)
        // Solo se añade si el propio test no lo puso: algunos casos mienten a
        // propósito y una cabecera duplicada haría que se enviara la última.
        if (body != null && all.keys.none { it.equals("content-length", true) }) {
            all["Content-Length"] = body.size.toString()
        }
        val request = buildString {
            append("$method $target HTTP/1.1\r\n")
            append("Host: 127.0.0.1:$port\r\n")
            for ((k, v) in all) append("$k: $v\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            if (body != null) write(body)
            flush()
        }
        val raw = socket.getInputStream().readBytes()
        socket.close()

        val text = String(raw, Charsets.UTF_8)
        val head = text.substringBefore("\r\n\r\n")
        val bodyText = text.substringAfter("\r\n\r\n", "")
        return Reply(head.lines().firstOrNull().orEmpty(), head, bodyText)
    }

    private class Reply(val status: String, val head: String, val text: String)

    private fun bodyOf(video: ByteArray) = ByteArrayInputStream(video)

    @Test
    fun `a video is stored and its url comes back`() {
        start()
        val video = ByteArray(2048) { (it % 253).toByte() }

        val reply = post(
            target = "/push?t=${enc(token)}&name=${enc("mi video.mp4")}",
            body = video,
        )

        assertTrue("expected 200, got ${reply.status}", reply.status.contains("200"))
        assertTrue("no url in the answer: ${reply.text}", reply.text.contains("file://"))
        val stored = store.dir.listFiles()!!.single()
        assertEquals(video.size.toLong(), stored.length())
        assertEquals(video.toList(), stored.readBytes().toList())
    }

    @Test
    fun `the answer is valid json`() {
        start()
        val reply = post(target = "/push?t=${enc(token)}&name=x.mp4", body = ByteArray(16))

        val json = kotlinx.serialization.json.Json.parseToJsonElement(reply.text).jsonObject
        assertTrue(json.str("url").startsWith("file://"))
        assertTrue(json.str("name").isNotBlank())
    }

    @Test
    fun `without a token nothing is written`() {
        start()
        val reply = post(target = "/push?name=x.mp4", body = ByteArray(64))

        assertTrue("expected 401, got ${reply.status}", reply.status.contains("401"))
        assertEquals(0, store.dir.listFiles()!!.size)
    }

    @Test
    fun `with the wrong token nothing is written`() {
        start()
        val reply = post(target = "/push?t=inventado&name=x.mp4", body = ByteArray(64))

        assertTrue("expected 401, got ${reply.status}", reply.status.contains("401"))
        assertEquals(0, store.dir.listFiles()!!.size)
    }

    @Test
    fun `a disabled access refuses the upload`() {
        start(enabled = false)
        val reply = post(target = "/push?t=${enc(token)}&name=x.mp4", body = ByteArray(64))

        assertTrue("expected 404, got ${reply.status}", reply.status.contains("404"))
        assertEquals(0, store.dir.listFiles()!!.size)
    }

    @Test
    fun `a get cannot write anything`() {
        start()
        val reply = post(target = "/push?t=${enc(token)}", body = null, method = "GET")

        assertTrue("expected 405, got ${reply.status}", reply.status.contains("405"))
        assertEquals(0, store.dir.listFiles()!!.size)
    }

    @Test
    fun `a body without a length is refused`() {
        start()
        val reply = post(
            target = "/push?t=${enc(token)}&name=x.mp4",
            body = null,
            headers = mapOf("Transfer-Encoding" to "chunked"),
        )

        // Sin Content-Length no hay forma de acotar la escritura, y eso en un
        // socket de una TV no compensa.
        assertTrue(
            "expected 411 or 405, got ${reply.status}",
            reply.status.contains("411") || reply.status.contains("405"),
        )
    }

    @Test
    fun `an impossible length is refused before reading anything`() {
        start()
        val reply = post(
            target = "/push?t=${enc(token)}&name=x.mp4",
            body = ByteArray(16),
            headers = mapOf("Content-Length" to "999999999999"),
        )

        assertTrue("expected 413, got ${reply.status}", reply.status.contains("413"))
        assertEquals(0, store.dir.listFiles()!!.size)
    }

    @Test
    fun `a name trying to escape stays inside the store`() {
        start()
        post(
            target = "/push?t=${enc(token)}&name=${enc("../../evitado.mp4")}",
            body = ByteArray(8),
        )

        val stored = store.dir.listFiles()!!.single()
        assertTrue(stored.parentFile!!.canonicalFile == store.dir.canonicalFile)
        assertFalse(File(tmp.root, "evitado.mp4").exists())
    }

    @Test
    fun `a hostile title cannot break the answer`() {
        start()
        val reply = post(
            target = "/push?t=${enc(token)}&name=x.mp4&title=${enc("\"}}\" {\"inyectado\":1")}",
            body = ByteArray(8),
        )

        assertTrue(reply.status.contains("200"))
        // Lo que vuelve tiene que seguir siendo un JSON con los campos previstos.
        val json = kotlinx.serialization.json.Json.parseToJsonElement(reply.text).jsonObject
        assertTrue(json.str("title").contains("inyectado"))
        assertFalse(json.containsKey("inyectado"))
    }

    @Test
    fun `an upload works without any shared folder`() {
        // Una TV que no comparte ninguna carpeta y solo usa KARIN Link para
        // pasarle vídeos es el caso más normal de este camino: atarlo a que
        // haya carpetas lo hacía fallar justo donde hace falta.
        LinkServer.stop()
        assertTrue(LinkServer.configure(registry, info))
        store = UploadStore(tmp.newFolder("uploads"))
        LinkServer.filesProvider = { FsConfig(true, token, emptyList()) }
        LinkServer.uploadsProvider = { store }
        port = LinkServer.start(0)

        val reply = post(target = "/push?t=${enc(token)}&name=x.mp4", body = ByteArray(32))

        assertTrue("expected 200, got ${reply.status}", reply.status.contains("200"))
        assertEquals(1, store.dir.listFiles()!!.size)
    }

    @Test
    fun `reading still works after an upload`() {
        // /push no puede dejar el servidor en un estado raro para /fs: un fallo
        // al escribir no debe volver la carpeta compartida ilegible.
        val media = tmp.newFolder("media")
        LinkServer.stop()
        assertTrue(LinkServer.configure(registry, info))
        store = UploadStore(tmp.newFolder("uploads"))
        LinkServer.filesProvider = { FsConfig(true, token, listOf(media)) }
        LinkServer.uploadsProvider = { store }
        port = LinkServer.start(0)

        File(media, "notas.txt").writeText("hola")
        post(target = "/push?t=${enc(token)}&name=x.mp4", body = ByteArray(128))
        val listing = post(target = "/fs?t=${enc(token)}", method = "GET", body = null)

        assertTrue(listing.status.contains("200"))
        assertTrue(listing.text.contains("notas.txt"))
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

private fun kotlinx.serialization.json.JsonObject.str(key: String) =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
