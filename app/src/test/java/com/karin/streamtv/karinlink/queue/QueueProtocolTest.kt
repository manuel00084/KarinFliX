package com.karin.streamtv.karinlink.queue

import com.karin.streamtv.karinlink.protocol.arr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que entra y sale por el WebSocket.
 *
 * Estos casos cubren el fallo caro: un mensaje mal leído que en vez de sonar
 * abre el reproductor con una URL vacía, y un id que cambia por el camino y
 * hace que "quitar" quite otra cosa.
 */
class QueueProtocolTest {

    private fun payload(
        title: String = "Episode 1",
        videoUrl: String = "https://example.com/a.m3u8",
        embedUrl: String = "",
        id: String = "",
        localFile: String = "false",
    ) = buildJsonObject {
        put("title", title)
        put("videoUrl", videoUrl)
        put("embedUrl", embedUrl)
        put("episodeUrl", "https://example.com/serie/ep-1")
        put("siteName", "Example")
        put("localFile", localFile)
        if (id.isNotBlank()) put("id", id)
    }

    @Test
    fun `reads a direct link`() {
        val item = QueueProtocol.itemFrom(payload())!!
        assertEquals("Episode 1", item.title)
        assertEquals("https://example.com/a.m3u8", item.videoUrl)
        assertEquals("Example", item.siteName)
        assertFalse(item.localFile)
        assertTrue(item.isPlayable)
    }

    @Test
    fun `an embed alone is enough to play`() {
        val item = QueueProtocol.itemFrom(payload(videoUrl = "", embedUrl = "https://example.com/e"))!!
        assertEquals("https://example.com/e", item.embedUrl)
        assertTrue(item.isPlayable)
    }

    @Test
    fun `an item with nothing to play is dropped`() {
        assertNull(QueueProtocol.itemFrom(payload(videoUrl = "", embedUrl = "")))
    }

    @Test
    fun `keeps the id the sender chose`() {
        assertEquals("abc-123", QueueProtocol.itemFrom(payload(id = "abc-123"))!!.id)
    }

    @Test
    fun `invents an id when the sender did not send one`() {
        // Sin id no se podría quitar el elemento después, así que se genera uno
        // en vez de dejar el hueco vacío.
        val first = QueueProtocol.itemFrom(payload())!!.id
        val second = QueueProtocol.itemFrom(payload())!!.id
        assertTrue(first.isNotBlank())
        assertNotEquals(first, second)
    }

    @Test
    fun `a local file is marked for deletion when it ends`() {
        val item = QueueProtocol.itemFrom(payload(localFile = "true"))!!
        assertTrue(item.localFile)
    }

    @Test
    fun `localFile is not confused by other text`() {
        assertTrue(QueueProtocol.itemFrom(payload(localFile = "TRUE"))!!.localFile)
        // Cualquier otra cosa cuenta como "no es un fichero local": ante la
        // duda, no se borra el fichero de nadie.
        assertFalse(QueueProtocol.itemFrom(payload(localFile = "yes"))!!.localFile)
    }

    @Test
    fun `localFile also works as a real boolean`() {
        // Es como lo manda itemsToJson y el cliente, así que leer solo el texto
        // dejaría los ficheros subidos sin borrar nunca.
        val item = QueueProtocol.itemFrom(
            buildJsonObject {
                put("title", "Subido")
                put("videoUrl", "file:///data/user/0/app/files/kl/a.mp4")
                put("localFile", true)
            },
        )!!
        assertTrue(item.localFile)
    }

    @Test
    fun `the queue survives a round trip`() {
        val items = listOf(
            QueueItem("id-1", "Uno", "https://example.com/1.m3u8", siteName = "A"),
            QueueItem("id-2", "Dos \"comillas\"", "https://example.com/2.m3u8", localFile = true),
        )

        val json = QueueProtocol.itemsToJson(items)
        val back = json.arr("items")
            .mapNotNull { it as? JsonObject }
            .mapNotNull { QueueProtocol.itemFrom(it) }

        assertEquals(2, back.size)
        assertEquals("id-1", back[0].id)
        assertEquals("Uno", back[0].title)
        // El título con comillas es justo el que revienta una construcción de
        // JSON a mano, así que la ida y la vuelta tienen que devolverlo igual.
        assertEquals("Dos \"comillas\"", back[1].title)
        assertTrue(back[1].localFile)
    }

    @Test
    fun `an empty queue is an empty list, not a missing field`() {
        val json = QueueProtocol.itemsToJson(emptyList())
        assertTrue(json.arr("items").isEmpty())
    }

    @Test
    fun `every queue message is recognised as queue`() {
        val types = listOf(
            QueueProtocol.PLAY, QueueProtocol.ADD, QueueProtocol.SKIP,
            QueueProtocol.REMOVE, QueueProtocol.CLEAR, QueueProtocol.STATE,
        )
        types.forEach { assertTrue(QueueProtocol.isQueue(it)) }
        // Un remote no debe acabar en la cola por accidente.
        assertFalse(QueueProtocol.isQueue("remote.key"))
    }

    @Test
    fun `play and add are different messages`() {
        // Si se fusionaran, mandar un vídeo de otra cosa descartaría la cola.
        assertNotEquals(QueueProtocol.PLAY, QueueProtocol.ADD)
    }
}
