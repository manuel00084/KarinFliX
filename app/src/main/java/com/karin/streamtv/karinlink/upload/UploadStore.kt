package com.karin.streamtv.karinlink.upload

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Dónde caen los vídeos que un móvil envía a la TV porque no hay forma de
 * reproducirlos desde un enlace.
 *
 * Es un caso raro y descartable: se escriben en el almacenamiento interno de la
 * app, que no necesita permisos ni es visible en el explorador, y se borran en
 * cuanto el vídeo termina o se quita de la cola. El motivo de escribirlos aquí y
 * no en una carpeta compartida es que [com.karin.streamtv.karinlink.FsAccess] es
 * de solo lectura a propósito, y este canal es la única excepción.
 */
class UploadStore(val dir: File, private val limit: Long = MAX_BYTES) {

    /**
     * Tope de tamaño.
     *
     * Es un vídeo, no un documento, así que tiene que ser grande; pero poner un
     * límite sigue siendo mejor que acceptar un `Content-Length` enorme y
     * quedarse sin espacio a mitad de la escritura.
     */
    val maxBytes: Long get() = limit

    /**
     * Borra lo que hubiera de una sesión anterior.
     *
     * Un fichero a medias de una subida interrumpida no lo va a reproductions
     * nadie nunca, así que al arrancar se recoge lo que quedó.
     */
    fun clearStale(): Int {
        val files = dir.listFiles() ?: return 0
        var removed = 0
        for (file in files) if (file.delete()) removed++
        return removed
    }

    /**
     * Escribe el cuerpo de la petición en un fichero nuevo.
     *
     * Se escribe primero con otro nombre y se renombra al final: si la conexión
     * se cae a mitad, el reproductor nunca ve un fichero a medias, solo el
     * temporal, que [clearStale] se lleva.
     *
     * @return el fichero final, ya con nombre definitivo.
     * @throws java.io.IOException si no cabe, y en ese caso no queda nada a medias.
     */
    fun save(suggestedName: String, body: InputStream, declaredLength: Long): File {
        if (!dir.exists() && !dir.mkdirs()) {
            throw java.io.IOException("cannot create ${dir.path}")
        }
        if (declaredLength > maxBytes) {
            throw TooLarge("declared $declaredLength bytes, the limit is $maxBytes")
        }

        val safe = sanitize(suggestedName)
        val extension = safe.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            ?.let { ".$it" } ?: ""
        val id = UUID.randomUUID().toString()
        val final = File(dir, "$id$extension")
        val temp = File(dir, "$id$extension.part")

        var written = 0L
        try {
            temp.outputStream().buffered().use { out -> written = copy(body, out, declaredLength) }
            // Menos de lo declarado significa que la conexión se cortó: se
            // devuelve un error y el temporal se borra, en vez de dejar un
            // fichero que el reproductor abriría y se pararía a la mitad.
            if (written != declaredLength) {
                throw java.io.IOException("expected $declaredLength bytes, got $written")
            }
            if (written > maxBytes) throw TooLarge("sent $written bytes, the limit is $maxBytes")
            // El nombre que devuelve la interfaz es el que sugiere el móvil, y
            // el del disco es un id: el otro equipo nunca decide dónde escribe.
            if (!temp.renameTo(final)) {
                throw java.io.IOException("cannot store ${final.name}")
            }
            return final
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /**
     * Copia exactamente [expected] bytes.
     *
     * No se lee hasta EOF a propósito: el otro equipo manda `Content-Length` y
     * luego espera la respuesta, así que quien lee hasta el cierre se queda
     * esperando para siempre. Y si llegan de menos, es una conexión cortada y se
     * nota aquí.
     */
    private fun copy(body: InputStream, out: OutputStream, expected: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < expected) {
            val read = body.read(buffer, 0, minOf(buffer.size.toLong(), expected - total).toInt())
            if (read < 0) break
            total += read
            // Se corta en cuanto se pasa del tope, sin esperar a haber guardado
            // gigabytes para decidir que no caben.
            if (total > maxBytes) throw TooLarge("the upload is larger than $maxBytes bytes")
            out.write(buffer, 0, read)
        }
        out.flush()
        return total
    }

    /** `file://` URL para que el reproductor pueda abrirlo tal cual. */
    fun urlOf(file: File): String = "file://" + file.absolutePath

    /** Borra el fichero de un elemento de la cola. Silencioso si ya no está. */
    fun delete(urlOrPath: String) {
        val path = urlOrPath.removePrefix("file://")
        if (path.isBlank()) return
        val file = File(path)
        // Nunca fuera del directorio de subidas: un `videoUrl` viene de la red y
        // no puede convertirse en una orden de borrar cualquier cosa.
        if (file.parentFile?.canonicalFile != dir.canonicalFile) return
        file.delete()
    }

    class TooLarge(message: String) : java.io.IOException(message)

    companion object {
        /** 2 GiB: cabe un vídeo largo sin dejar que el campo se llene. */
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024

        /**
         * Deja solo el nombre del fichero, sin rutas ni caracteres raros.
         *
         * El nombre que manda el móvil es una pista para la interfaz, no una
         * instrucción: se limpia aquí para que un `../../` no llegue al disco.
         */
        fun sanitize(name: String): String {
            val base = name.trim()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9 ._-]"), "_")
                .trim()
                .removePrefix(".")
            return base.take(120).ifBlank { "video" }
        }
    }
}
