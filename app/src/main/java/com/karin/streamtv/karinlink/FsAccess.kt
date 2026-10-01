package com.karin.streamtv.karinlink

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Autorización del acceso remoto a archivos.
 *
 * Sirve archivos del propio dispositivo por HTTP, así que esto es la parte más
 * peligrosa de KARIN Link: un error aquí convierte la app en una puerta abierta
 * a la LAN. Por eso son funciones puras sin Android, para poder testear el
 * recorrido de rutas y la comparación del token sin montar un socket.
 */
object FsAccess {

    /**
     * Comparación en tiempo constante.
     *
     * Un `==` sobre el token devuelve el resultado en cuanto encuentra la
     * primera diferencia, lo que deja adivinarlo byte a byte midiendo el
     * tiempo de las peticiones. No es un ataque trivial, pero comparar mal
     * cuesta una línea y este es el peor sitio posible para ahorrar una.
     */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)

        // Se recorre el prefijo común y luego las dos colas por separado: el
        // número de iteraciones depende de las longitudes, que no son el
        // secreto, y no de en qué byte difieren, que sí lo es.
        val common = minOf(x.size, y.size)
        var diff = x.size xor y.size
        for (i in 0 until common) diff = diff or (x[i].toInt() xor y[i].toInt())
        for (i in common until x.size) diff = diff or x[i].toInt()
        for (i in common until y.size) diff = diff or y[i].toInt()
        return diff == 0
    }

    /**
     * Resuelve una ruta pedida contra las carpetas compartidas.
     *
     * @return el fichero real, o null si se sale de lo permitido.
     */
    fun resolve(requested: String, roots: List<File>): File? {
        if (requested.isBlank()) return null
        // Un NUL truncaría la ruta en la capa nativa y permitiría colar un
        // sufijo, así que se descarta antes de tocar el disco.
        if (requested.indexOf('\u0000') >= 0) return null

        val canonicalRoots = roots.mapNotNull { root ->
            runCatching { root.canonicalFile }.getOrNull()?.takeIf { it.isDirectory }
        }
        if (canonicalRoots.isEmpty()) return null

        // canonicalFile resuelve ".." y enlaces simbólicos, de modo que la
        // comprobación de contención se hace sobre la ruta real y no sobre la
        // que escribió el cliente.
        val target = runCatching { File(requested).canonicalFile }.getOrNull() ?: return null

        return canonicalRoots.firstOrNull { root -> isInside(target, root) }?.let { target }
    }

    /** Un fichero está permitido si está dentro de una raíz, no si es la raíz. */
    fun isInside(target: File, root: File): Boolean {
        val t = target.path
        val r = root.path
        if (t == r) return false
        return t.startsWith(if (r.endsWith(File.separator)) r else r + File.separator)
    }

    /**
     * Devuelve la carpeta compartida exacta que se pidió, si lo es.
     *
     * [resolve] se niega a devolver la raíz porque una raíz no es un fichero
     * servible, y con razón. Listarla es otra cosa: es lo primero que pide
     * cualquiera al abrir la carpeta, así que responderle 403 sería
     * desconcertante sin motivo. Por eso va aparte, y solo devuelve
     * directorios.
     */
    fun resolveSharedRoot(requested: String, roots: List<File>): File? {
        if (requested.isBlank()) return null
        if (requested.indexOf('\u0000') >= 0) return null

        val target = runCatching { File(requested).canonicalFile }.getOrNull() ?: return null
        if (!target.isDirectory) return null

        return roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .firstOrNull { it.path == target.path }
    }

    /**
     * Decidimos si el token vale.
     *
     * Deshabilitado o sin token esperado es un no, y el orden importa: aunque
     * el acceso esté apagado se compara igualmente, para que el tiempo de
     * respuesta no revele si la función está activa.
     */
    fun isAuthorized(enabled: Boolean, expected: String?, provided: String?): Boolean {
        val ok = constantTimeEquals(expected, provided)
        return enabled && expected != null && expected.isNotEmpty() && ok
    }

    /** Cabeceras para una respuesta, sin inventar el charset. */
    fun contentType(file: File): String = when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "json" -> "application/json"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    /**
     * Nombre de fichero seguro para la cabecera.
     *
     * Un nombre con CR o LF no solo rompe la cabecera: añade las que quiera al
     * principio de la respuesta, que es inyección de cabeceras y no un fallo de
     * formato. Por eso la copia entrecomillada se queda con lo imprimible y el
     * nombre real viaja percent-encoded en `filename*`, que es lo que los
     * reproductores y navegadores saben leer igual.
     */
    fun contentDisposition(name: String): String {
        val ascii = buildString {
            for (c in name) {
                if (c.code in 0x20..0x7E && c != '"' && c != '\\') append(c)
            }
        }.ifEmpty { "archivo" }

        val encoded = buildString {
            for (b in name.toByteArray(Charsets.UTF_8)) {
                val v = b.toInt() and 0xFF
                val c = v.toChar()
                if (v in 0x21..0x7E && c != '%' && c != '\'' && c != '"' && c != '\\') {
                    append(c)
                } else {
                    append('%')
                    append(v.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    /**
     * Una entrada del listado, como objeto JSON.
     *
     * El escapado lo pone la librería en vez de sustituir a mano las comillas:
     * un nombre con tabulador, salto de línea o byte de control deja el JSON
     * inválido si se monta con concatenación.
     */
    fun jsonEntry(name: String, directory: Boolean, size: Long): String =
        buildJsonObject {
            put("name", name)
            put("dir", directory)
            put("size", size)
        }.toString()
}
