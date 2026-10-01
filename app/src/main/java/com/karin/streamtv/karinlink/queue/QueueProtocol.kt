package com.karin.streamtv.karinlink.queue

import com.karin.streamtv.karinlink.protocol.bool
import com.karin.streamtv.karinlink.protocol.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Los mensajes de la cola de reproducción, y el reparto entre lo que significa
 * "sustituir" y lo que significa "añadir detrás".
 *
 * La diferencia no es cosmética y por eso hay dos tipos y no uno con una
 * bandera: "reproducir ahora" para lo que suena y descarta lo pendiente, y
 * "añadir a la cola" para lo que entra detrás sin molestar. Confundirlos
 * descartaría justo los episodios que el usuario quería ver después.
 */
object QueueProtocol {

    /** Sustituye lo que suena por este vídeo. */
    const val PLAY = "queue.play"

    /** Lo pone detrás de lo que haya. */
    const val ADD = "queue.add"

    const val SKIP = "queue.skip"

    /** Quita un elemento por id. */
    const val REMOVE = "queue.remove"

    const val CLEAR = "queue.clear"

    /** Lo que tiene el otro equipo en su cola, para poder pintar el móvil. */
    const val STATE = "queue.state"

    fun isQueue(type: String): Boolean = type.startsWith("queue.")

    /**
     * Lee un elemento de la cola desde el mensaje.
     *
     * Devuelve null si no trae forma de reproducirlo: es preferible ignorar un
     * mensaje malformado a abrir el reproductor con una URL vacía y dejar al
     * usuario mirando un error.
     */
    fun itemFrom(data: JsonObject): QueueItem? {
        val item = QueueItem(
            // Si el otro equipo no mandó id, se genera uno aquí: quitar un
            // elemento sin id sería imposible, y solo quien añade lo quitará.
            id = data.str("id").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
            title = data.str("title"),
            videoUrl = data.str("videoUrl"),
            embedUrl = data.str("embedUrl"),
            episodeUrl = data.str("episodeUrl"),
            siteName = data.str("siteName"),
            localFile = readLocalFile(data),
        )
        return item.takeIf { it.isPlayable }
    }

    /**
     * Si el elemento es un fichero que llegó subido desde otro equipo.
     *
     * Acepta las dos formas porque el mismo campo se manda de dos maneras
     * según quién escriba: como booleano por [itemsToJson] y por el cliente, y
     * como texto por quien arme el JSON a mano. Leer solo una de las dos hacía
     * que el fichero no se borrara nunca, sin que nada fallara visiblemente.
     */
    private fun readLocalFile(data: JsonObject): Boolean =
        data.bool("localFile") || data.str("localFile").equals("true", ignoreCase = true)

    /** La cola, en el mismo formato que espera [itemFrom]. */
    fun itemsToJson(items: List<QueueItem>): JsonObject = buildJsonObject {
        putJsonArray("items") {
            for (item in items) {
                addJsonObject {
                    put("id", item.id)
                    put("title", item.title)
                    put("videoUrl", item.videoUrl)
                    put("embedUrl", item.embedUrl)
                    put("episodeUrl", item.episodeUrl)
                    put("siteName", item.siteName)
                    put("localFile", item.localFile)
                }
            }
        }
    }
}
