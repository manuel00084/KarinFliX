package com.karin.streamtv.karinlink.upload

import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El disco donde caen los vídeos enviados por `/push`.
 *
 * Aquí no hay red ni sockets: el riesgo está en lo que se escribe, en dónde y
 * en qué condiciones, y eso se puede comprobar en una JVM normal.
 */
class UploadStoreTest {

    private fun store(dir: File = tmpDir()) = UploadStore(dir)

    private fun tmpDir() =
        File(System.getProperty("java.io.tmpdir"), "kl-up-${UUID.randomUUID()}")

    @Test
    fun `stores what was sent`() {
        val s = store()
        val body = ByteArray(4096) { (it % 251).toByte() }

        val file = s.save("corto.mp4", ByteArrayInputStream(body), body.size.toLong())

        assertEquals(body.size.toLong(), file.length())
        assertEquals(body.toList(), file.readBytes().toList())
    }

    @Test
    fun `keeps the extension and invents the name`() {
        val s = store()
        val file = s.save("mi pelicula.mp4", ByteArrayInputStream(ByteArray(3)), 3)

        // El nombre del disco es un id: el móvil no elige la ruta, solo sugere
        // un nombre que la interfaz pueda enseñar.
        assertTrue(file.name.endsWith(".mp4"))
        assertNotEquals("mi pelicula.mp4", file.name)
        assertTrue(file.parentFile!!.canonicalFile == s.dir.canonicalFile)
    }

    @Test
    fun `a traversal in the name cannot escape`() {
        val s = store()
        val file = s.save("../../etc/passwd", ByteArrayInputStream(ByteArray(1)), 1)
        assertTrue(file.parentFile!!.canonicalFile == s.dir.canonicalFile)
    }

    @Test
    fun `a windows path in the name cannot escape`() {
        val s = store()
        val file = s.save("""C:\Windows\System32\evil.mp4""", ByteArrayInputStream(ByteArray(1)), 1)
        assertTrue(file.parentFile!!.canonicalFile == s.dir.canonicalFile)
    }

    @Test
    fun `an empty name still produces something usable`() {
        val s = store()
        val file = s.save("   ", ByteArrayInputStream(ByteArray(1)), 1)
        assertTrue(file.exists())
        assertTrue(file.name.isNotBlank())
    }

    @Test
    fun `a cut upload is an error and leaves nothing behind`() {
        val s = store()
        // Se declara más de lo que llega: es una conexión cortada. El fichero a
        // medias no puede quedarse, porque el reproductor lo abriría y se pararía
        // a la mitad sin decir nada.
        try {
            s.save("roto.mp4", ByteArrayInputStream(ByteArray(10)), 4096)
            throw AssertionError("a short upload should not be accepted")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("expected"))
        }
        assertEquals(0, s.dir.listFiles()!!.size)
    }

    @Test
    fun `a body longer than declared stops at the declared length`() {
        val s = store()
        // Si el cliente dice 4 y manda 10, se guardan 4 y no se espera a que
        // cierre la conexión: leer hasta EOF colgaba el hilo del servidor.
        val file = s.save("corto.mp4", ByteArrayInputStream(ByteArray(10) { 7 }), 4)
        assertEquals(4L, file.length())
    }

    @Test
    fun `an oversized body is refused and nothing is kept`() {
        val s = store()
        try {
            s.save("largo.mp4", ByteArrayInputStream(ByteArray(64)), 1L shl 40)
            throw AssertionError("should have refused an oversized upload")
        } catch (e: UploadStore.TooLarge) {
            assertTrue(e.message!!.contains("limit"))
        }
        assertEquals(0, s.dir.listFiles()!!.size)
    }

    @Test
    fun `a body that outgrows the limit is cut while copying`() {
        // Con un tope pequeño se comprueba el corte a mitad de copia, que es
        // donde un cuerpo que miente con su Content-Length intentaría llenar el
        // disco.
        val s = UploadStore(tmpDir(), limit = 1024)
        try {
            s.save("gigante.mp4", ByteArrayInputStream(ByteArray(64 * 1024)), 64 * 1024)
            throw AssertionError("should have been refused")
        } catch (e: UploadStore.TooLarge) {
            // Da igual si se viola la longitud declarada o el tope real: lo que
            // importa es que no se escriba nada.
            assertTrue(e.message!!.contains("1024"))
        }
        assertEquals(0, s.dir.listFiles()!!.size)
    }

    @Test
    fun `a body that lies about being bigger is refused up front`() {
        val s = UploadStore(tmpDir(), limit = 1024)
        try {
            s.save("gigante.mp4", ByteArrayInputStream(ByteArray(16)), 4096)
            throw AssertionError("should have been refused")
        } catch (e: UploadStore.TooLarge) {
            assertTrue(e.message!!.contains("limit"))
        }
        assertEquals(0, s.dir.listFiles()!!.size)
    }

    @Test
    fun `deletes only what belongs to the store`() {
        val s = store()
        val outside = File(System.getProperty("java.io.tmpdir"), "kl-not-mine-${UUID.randomUUID()}")
        outside.writeText("no me toques")

        s.delete(outside.absolutePath)
        s.delete("file://${outside.absolutePath}")
        // Un `videoUrl` viene de la red: con un traversal no puede borrar fuera.
        s.delete("file://${s.dir.absolutePath}/../${outside.name}")

        assertTrue("a path outside the store must survive", outside.exists())
        outside.delete()
    }

    @Test
    fun `deletes a file it stored`() {
        val s = store()
        val file = s.save("x.mp4", ByteArrayInputStream(ByteArray(2)), 2)
        s.delete(s.urlOf(file))
        assertFalse(file.exists())
    }

    @Test
    fun `deleting twice is not a problem`() {
        val s = store()
        val file = s.save("x.mp4", ByteArrayInputStream(ByteArray(2)), 2)
        s.delete(s.urlOf(file))
        s.delete(s.urlOf(file))
        assertFalse(file.exists())
    }

    @Test
    fun `a restart clears what the last session left`() {
        val s = store()
        s.save("a.mp4", ByteArrayInputStream(ByteArray(1)), 1)
        s.save("b.mp4", ByteArrayInputStream(ByteArray(1)), 1)

        assertEquals(2, s.clearStale())
        assertEquals(0, s.dir.listFiles()!!.size)
    }

    @Test
    fun `names are trimmed and shortened`() {
        assertEquals("corto.mp4", UploadStore.sanitize("  corto.mp4 "))
        assertEquals(120, UploadStore.sanitize("a".repeat(400)).length)
        // Un nombre con salto de línea no puede romper cabeceras ni logs.
        assertFalse(UploadStore.sanitize("a\nb.mp4").contains("\n"))
    }

    @Test
    fun `a file url is what the player can open`() {
        val s = store()
        val file = s.save("x.mp4", ByteArrayInputStream(ByteArray(1)), 1)
        assertTrue(s.urlOf(file).startsWith("file://"))
        assertTrue(s.urlOf(file).endsWith(file.name))
    }
}
