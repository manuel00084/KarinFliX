package com.karin.streamtv.karinlink.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La cola es lo que convierte "mandar un vídeo" en "mandar varios".
 *
 * Estos casos son los que describen las dos cosas que pidió el usuario: que un
 * envío nuevo pare lo que suena, y que lo que se añade detrás siga solo cuando
 * lo de delante acaba.
 */
class PlaybackQueueTest {

    private var counter = 0
    private val queue = PlaybackQueue { "id-${++counter}" }

    private fun item(title: String) = QueueItem(id = title, title = title, videoUrl = "http://x/$title")

    private fun started(action: QueueAction): QueueItem? =
        (action as? QueueAction.Start)?.item
            ?: (action as? QueueAction.StopAndStart)?.next

    private fun stopped(action: QueueAction): QueueItem? =
        (action as? QueueAction.Stop)?.item
            ?: (action as? QueueAction.StopAndStart)?.stopped

    // ── Reproducir ahora ───────────────────────────────────────────

    @Test
    fun `an empty queue just starts the item`() {
        val action = queue.playNow(item("a"))

        assertEquals("a", started(action)?.title)
        assertNull("nothing was playing to stop", stopped(action))
        assertTrue(queue.isPlaying)
    }

    @Test
    fun `a new video stops what was playing`() {
        queue.playNow(item("one-piece-1071"))

        val action = queue.playNow(item("nueva-temporada-1"))

        assertEquals("nueva-temporada-1", started(action)?.title)
        assertEquals("one-piece-1071", stopped(action)?.title)
    }

    @Test
    fun `a new video discards whatever was waiting`() {
        queue.playNow(item("a"))
        queue.add(item("b"))
        queue.add(item("c"))

        queue.playNow(item("d"))

        assertEquals(1, queue.size)
        assertEquals("d", queue.current?.title)
    }

    // ── Añadir a la cola ───────────────────────────────────────────

    @Test
    fun `adding behind a playing item does not disturb it`() {
        queue.playNow(item("one-piece-1071"))

        val action = queue.add(item("temporada-nueva-1"))

        assertTrue("adding should not touch the player", action is QueueAction.None)
        assertEquals("one-piece-1071", queue.current?.title)
        assertEquals(2, queue.size)
    }

    @Test
    fun `adding to an empty queue starts it`() {
        val action = queue.add(item("primero"))

        assertEquals("primero", started(action)?.title)
        assertTrue(queue.isPlaying)
    }

    @Test
    fun `the classic case plays the added series in order`() {
        // Viendo el último de una serie y añadiendo los primeros de otras dos.
        queue.playNow(item("one-piece-1071"))
        queue.add(item("temporada-nueva-1"))
        queue.add(item("otra-serie-1"))

        assertEquals(listOf("one-piece-1071", "temporada-nueva-1", "otra-serie-1"),
            queue.snapshot().map { it.title })

        val first = queue.completeCurrent()
        // Al terminar solo, no hay que parar nada: el primero ya acabó.
        assertTrue("a natural end should not ask to stop", first is QueueAction.Start)
        assertEquals("temporada-nueva-1", started(first)?.title)
        assertNull(stopped(first))

        val second = queue.completeCurrent()
        assertEquals("otra-serie-1", started(second)?.title)

        val third = queue.completeCurrent()
        assertTrue("the queue should be over", third is QueueAction.Stop)
        assertEquals("otra-serie-1", stopped(third)?.title)
        assertTrue(queue.isEmpty)
        assertFalse(queue.isPlaying)
    }

    @Test
    fun `finishing the last item stops the player`() {
        queue.playNow(item("solo"))

        val action = queue.completeCurrent()

        assertTrue(action is QueueAction.Stop)
        assertEquals("solo", stopped(action)?.title)
        assertFalse(queue.isPlaying)
    }

    @Test
    fun `finishing an empty queue is not an error`() {
        assertTrue(queue.completeCurrent() is QueueAction.None)
        assertFalse(queue.isPlaying)
    }

    // ── Saltar y quitar ────────────────────────────────────────────

    @Test
    fun `skip moves on without waiting`() {
        queue.playNow(item("a"))
        queue.add(item("b"))
        queue.add(item("c"))

        val action = queue.skip()

        assertEquals("b", started(action)?.title)
        assertEquals("a", stopped(action)?.title)
        assertEquals(2, queue.size)
    }

    @Test
    fun `removing a waiting item leaves the player alone`() {
        queue.playNow(item("a"))
        queue.add(item("b"))

        val action = queue.remove("b")

        assertTrue(action is QueueAction.None)
        assertEquals("a", queue.current?.title)
        assertEquals(1, queue.size)
    }

    @Test
    fun `removing the playing item starts the next one`() {
        queue.playNow(item("a"))
        queue.add(item("b"))

        val action = queue.remove("a")

        assertEquals("b", started(action)?.title)
        assertEquals("a", stopped(action)?.title)
        assertEquals(1, queue.size)
    }

    @Test
    fun `removing the only item stops the player`() {
        queue.playNow(item("a"))

        val action = queue.remove("a")

        assertTrue(action is QueueAction.Stop)
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `removing something that is not there does nothing`() {
        queue.playNow(item("a"))
        assertTrue(queue.remove("nada") is QueueAction.None)
        assertEquals(1, queue.size)
        assertTrue(queue.isPlaying)
    }

    @Test
    fun `removing the same id twice is harmless`() {
        queue.playNow(item("a"))
        queue.add(item("b"))

        assertTrue(queue.remove("b") is QueueAction.None)
        assertTrue(queue.remove("b") is QueueAction.None)
        assertEquals("a", queue.current?.title)
    }

    @Test
    fun `clearing stops whatever was playing`() {
        queue.playNow(item("a"))
        queue.add(item("b"))

        val action = queue.clear()

        assertTrue(action is QueueAction.Stop)
        assertTrue(queue.isEmpty)
        assertFalse(queue.isPlaying)
    }

    @Test
    fun `clearing an empty queue is not an error`() {
        assertTrue(queue.clear() is QueueAction.None)
    }

    // ── Detalles que seolvidan hasta que rompen ───────────────────

    @Test
    fun `a long queue plays in order to the end`() {
        queue.playNow(item("0"))
        for (i in 1..50) queue.add(item("$i"))

        // Se consume desde el primero hasta que la cola se vacía.
        val played = mutableListOf(queue.current!!.title)
        while (queue.isPlaying) {
            val action = queue.completeCurrent()
            val next = started(action) ?: break
            played.add(next.title)
        }

        assertEquals((0..50).map { "$it" }, played)
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `enqueue assigns an id that can be used to remove it`() {
        queue.playNow(item("a"))
        queue.enqueue(title = "b", videoUrl = "http://x/b")
        val added = queue.current.let { queue.snapshot()[1] }

        val action = queue.remove(added.id)

        assertTrue(action is QueueAction.None)
        assertEquals(1, queue.size)
    }

    @Test
    fun `a snapshot is a copy and not a live view`() {
        queue.playNow(item("a"))
        val copy = ArrayList(queue.snapshot())
        copy.clear()

        assertEquals(1, queue.size)
    }

    @Test
    fun `an uploaded file is marked so it can be deleted afterwards`() {
        val action = queue.enqueue(
            title = "del movil",
            videoUrl = "file:///data/user/0/com.karintv.player/files/x.mp4",
            localFile = true,
        )
        assertTrue(queue.current!!.localFile)
        assertEquals("del movil", started(action)?.title)
    }

    @Test
    fun `an item with no way to play it is still queued`() {
        // Decide el reproductor, no la cola: puede ser un embed que se resuelve
        // al abrirlo. Lo que no debe hacer la cola es inventarse una fuente.
        val item = QueueItem(id = "x", title = "solo embed", videoUrl = "", embedUrl = "https://e")
        assertTrue(item.isPlayable)
        assertFalse(QueueItem(id = "y", title = "nada", videoUrl = "").isPlayable)
    }

    @Test
    fun `replaceAll rebuilds the queue from a peer's description`() {
        queue.playNow(item("a"))
        queue.replaceAll(listOf(item("b"), item("c")))

        assertEquals(listOf("b", "c"), queue.snapshot().map { it.title })
        assertTrue(queue.isPlaying)
    }
}
