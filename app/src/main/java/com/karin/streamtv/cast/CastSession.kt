package com.karin.streamtv.cast

import android.net.Uri
import java.io.File

/**
 * Emisión en curso, a nivel de proceso.
 *
 * El servidor de ficheros locales y el receptor elegido tienen que sobrevivir
 * a la pantalla que los puso en marcha: si al cerrar "Emitir" se apagara el
 * servidor, el vídeo se cortaría en el segundo en que el usuario vuelve al
 * reproductor. Igual que [com.karin.streamtv.karinlink.queue.QueueHub], vive
 * aquí y no dentro de la Activity.
 */
object CastSession {

    var renderer: DlnaProtocol.Renderer? = null
        private set

    /** URL que acaba de recibir el receptor (directa o servida por nosotros). */
    var mediaUrl: String? = null
        private set

    var lastError: String? = null
        private set

    private var server: EphemeralMediaServer? = null

    val isEmitting: Boolean get() = renderer != null

    /**
     * Prepara la URL que el receptor debe reproducir. Un DLNA no abre
     * `/storage/...`, así que los archivos locales se sirven por HTTP.
     * Devuelve null si no hay nada reproducible.
     */
    fun prepare(source: String): String? {
        if (source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)
        ) {
            mediaUrl = source
            lastError = null
            return source
        }

        val file = fileOf(source) ?: run {
            lastError = "No se encontró el archivo"
            return null
        }
        val candidate = EphemeralMediaServer(file)
        val url = candidate.start()
        server?.stop()
        if (url == null) {
            candidate.stop()
            server = null
            mediaUrl = null
            lastError = "No se pudo preparar la red"
            return null
        }
        server = candidate
        mediaUrl = url
        lastError = null
        return url
    }

    fun mark(target: DlnaProtocol.Renderer?) {
        renderer = target
        if (target != null) lastError = null
    }

    fun stop() {
        renderer = null
        mediaUrl = null
        server?.stop()
        server = null
    }

    private fun fileOf(source: String): File? {
        val uri = Uri.parse(source)
        val path = if (uri.scheme == "file") uri.path ?: source else source
        if (path.isBlank()) return null
        return File(path).takeIf { it.isFile }
    }
}
