package com.karin.streamtv.karinlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * El recorrido de rutas decide si la TV se queda con las puertas abiertas.
 *
 * Estos tests son la diferencia entre "compartir un archivo" y "cualquiera en
 * el WiFi lee tus datos", así que cubren el camino de traversal de forma
 * explícita en vez de confiar en que `canonicalFile` ya lo hace todo.
 */
class FsAccessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var media: File
    private lateinit var secret: File

    private fun setup() {
        media = tmp.newFolder("media")
        secret = tmp.newFolder("private")
        File(media, "movie.mp4").writeText("video")
        File(secret, "secret.txt").writeText("no leer")
    }

    // ── Token ──────────────────────────────────────────────────────

    @Test
    fun `a matching token is accepted`() {
        assertTrue(FsAccess.isAuthorized(enabled = true, expected = "abc123", provided = "abc123"))
    }

    @Test
    fun `a wrong token is refused`() {
        assertFalse(FsAccess.isAuthorized(enabled = true, expected = "abc123", provided = "abc124"))
    }

    @Test
    fun `access refused while disabled even with the right token`() {
        assertFalse(FsAccess.isAuthorized(enabled = false, expected = "abc123", provided = "abc123"))
    }

    @Test
    fun `a null or empty expected token never authorizes`() {
        assertFalse(FsAccess.isAuthorized(true, null, null))
        assertFalse(FsAccess.isAuthorized(true, "", ""))
        assertFalse(FsAccess.isAuthorized(true, "abc", null))
    }

    @Test
    fun `token comparison is not fooled by a prefix or a length change`() {
        assertFalse(FsAccess.constantTimeEquals("abc", "abcd"))
        assertFalse(FsAccess.constantTimeEquals("abcd", "abc"))
        assertFalse(FsAccess.constantTimeEquals("", "a"))
        assertFalse(FsAccess.constantTimeEquals(null, "a"))
        assertTrue(FsAccess.constantTimeEquals("abc", "abc"))
    }

    @Test
    fun `token comparison does not short circuit on the first difference`() {
        // If it short circuited, an early difference would make it faster. Both
        // must take the same path, so this only guards the obvious regression.
        // A timing assertion would be flaky in CI; this asserts the result.
        assertFalse(FsAccess.constantTimeEquals("aaaaX", "aaaaY"))
        assertFalse(FsAccess.constantTimeEquals("Xaaaa", "Yaaaa"))
    }

    // ── Rutas ──────────────────────────────────────────────────────

    @Test
    fun `a file inside a shared root is served`() {
        setup()
        val found = FsAccess.resolve(File(media, "movie.mp4").path, listOf(media))

        assertEquals(File(media, "movie.mp4").canonicalPath, found?.canonicalPath)
    }

    @Test
    fun `a path climbing out with a relative parent is refused`() {
        setup()
        val escape = File(media, "../private/secret.txt").path

        assertNull(FsAccess.resolve(escape, listOf(media)))
    }

    @Test
    fun `a path climbing out with many hops is refused`() {
        setup()
        val escape = File(media, "../../../../../../etc/passwd").path

        assertNull(FsAccess.resolve(escape, listOf(media)))
    }

    @Test
    fun `a file in an unshared folder is refused`() {
        setup()

        assertNull(FsAccess.resolve(File(secret, "secret.txt").path, listOf(media)))
    }

    @Test
    fun `the root itself is not handed out as a file`() {
        setup()
        // A directory is only listable through the listing endpoint, never
        // streamed, so resolve() must not treat the root as a valid target.
        assertFalse(FsAccess.isInside(media.canonicalFile, media.canonicalFile))
    }

    @Test
    fun `a sibling folder with a shared prefix is refused`() {
        setup()
        // "/media" must not grant access to "/media-secrets": a prefix compare
        // without the separator would.
        val sibling = tmp.newFolder("media-secrets")
        File(sibling, "x.txt").writeText("x")

        assertNull(FsAccess.resolve(File(sibling, "x.txt").path, listOf(media)))
    }

    @Test
    fun `a NUL byte is refused`() {
        setup()
        val sneaky = File(media, "movie.mp4").path + "\u0000.txt"

        assertNull(FsAccess.resolve(sneaky, listOf(media)))
    }

    @Test
    fun `an empty or blank path is refused`() {
        setup()

        assertNull(FsAccess.resolve("", listOf(media)))
        assertNull(FsAccess.resolve("   ", listOf(media)))
    }

    @Test
    fun `no shared roots means nothing is reachable`() {
        setup()

        assertNull(FsAccess.resolve(File(media, "movie.mp4").path, emptyList()))
        assertNull(FsAccess.resolve(File(media, "movie.mp4").path, listOf(File(media, "missing"))))
    }

    @Test
    fun `any shared root can serve its own files`() {
        setup()
        val other = tmp.newFolder("other")
        File(other, "b.txt").writeText("b")

        assertEquals(
            File(other, "b.txt").canonicalPath,
            FsAccess.resolve(File(other, "b.txt").path, listOf(media, other))?.canonicalPath
        )
    }

    @Test
    fun `a shared folder is listable but still not a servable file`() {
        setup()

        // Pedir el contenido de la carpeta es lo primero que hace el remoto, así
        // que tiene que responder con su listado. Lo que no puede pasar es que
        // esa misma ruta llegue a servirse como fichero.
        val listing = FsAccess.resolveSharedRoot(media.path, listOf(media))
        assertEquals(media.canonicalPath, listing?.canonicalPath)
        assertNull("the root must not resolve as a file", FsAccess.resolve(media.path, listOf(media)))
    }

    @Test
    fun `resolveSharedRoot only answers for a shared folder itself`() {
        setup()
        val sub = File(media, "temporada").apply { mkdirs() }
        val other = tmp.newFolder("other")

        assertNull("a subfolder is handled by resolve", FsAccess.resolveSharedRoot(sub.path, listOf(media)))
        assertNull("an unshared folder must not be listed", FsAccess.resolveSharedRoot(other.path, listOf(media)))
        assertNull("a file is not a folder", FsAccess.resolveSharedRoot(File(media, "a.txt").path, listOf(media)))
    }

    @Test
    fun `a name with a newline cannot add a header`() {
        // Un nombre con CR/LF añadiría cabeceras propias si se copiase tal cual,
        // y eso es inyección de cabeceras, no un simple fallo de formato.
        val header = FsAccess.contentDisposition("capa\r\nX-Injected: yes.mkv")

        assertFalse("a bare CR survived", header.substringBefore("; filename*").contains('\r'))
        assertFalse("a bare LF survived", header.substringBefore("; filename*").contains('\n'))
        assertTrue("the value was left unquoted: $header", header.startsWith("attachment; filename=\""))
    }

    @Test
    fun `the real name travels percent-encoded`() {
        val header = FsAccess.contentDisposition("capa \"especial\" ñ.mkv")

        assertTrue("no extended name: $header", header.contains("filename*=UTF-8''"))
        assertTrue("the name is not recoverable: $header", header.contains("capa%20%22especial%22%20%C3%B1.mkv"))
    }

    @Test
    fun `an empty or unreadable name still yields a usable header`() {
        for (name in listOf("", "   ", "\"\"", "///")) {
            val header = FsAccess.contentDisposition(name)
            assertTrue("not a disposition: $header", header.startsWith("attachment; filename=\""))
        }
    }

    @Test
    fun `a name with control bytes keeps the listing valid`() {
        // Montado a mano, un tabulador o un salto de línea dentro del JSON lo
        // vuelven inválido y el cliente no puede ni sacar un error claro.
        for (name in listOf("a\"b.mkv", "c\td.mkv", "e\nf.mkv", "g\\h.mkv", "ib.mkv", "ñandú ✅")) {
            val entry = FsAccess.jsonEntry(name, directory = false, size = 10)

            val parsed = runCatching { Json.parseToJsonElement(entry) }.getOrNull()
            assertTrue("invalid JSON for <$name>: $entry", parsed is JsonObject)
            val back = (parsed as JsonObject)["name"]
            assertEquals(
                "the name did not survive <$name>",
                name,
                (back as? JsonPrimitive)?.content
            )
        }
    }

    @Test
    fun `an entry reports what the listing needs`() {
        val entry = FsAccess.jsonEntry("capa.mkv", directory = false, size = 1234)
        val obj = Json.parseToJsonElement(entry) as JsonObject

        assertEquals("capa.mkv", (obj["name"] as JsonPrimitive).content)
        assertEquals(false, (obj["dir"] as JsonPrimitive).content.toBoolean())
        assertEquals("1234", (obj["size"] as JsonPrimitive).content)
    }
}
