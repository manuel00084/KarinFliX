package com.karin.streamtv.karinlink.queue

import android.content.Context
import android.util.Log
import com.karin.streamtv.karinlink.protocol.str
import kotlinx.serialization.json.JsonObject

/**
 * Traduce los mensajes de la cola en llamadas a [QueueHub].
 *
 * Separar el reparto de la ejecución permite que el TV ignore un mensaje
 * incompleto sin tocar la cola, y deja en un solo sitio las reglas de qué
 * mensaje hace qué.
 */
object QueueCommands {

    private const val TAG = "QueueCommands"

    fun handle(ctx: Context, from: String, type: String, data: JsonObject) {
        QueueHub.attach(ctx)
        try {
            when (type) {
                QueueProtocol.PLAY -> {
                    val item = QueueProtocol.itemFrom(data) ?: return ignore(type)
                    Log.i(TAG, "Play now from $from: ${item.title}")
                    QueueHub.playNow(item)
                }

                QueueProtocol.ADD -> {
                    val item = QueueProtocol.itemFrom(data) ?: return ignore(type)
                    Log.i(TAG, "Enqueue from $from: ${item.title}")
                    QueueHub.add(item)
                }

                QueueProtocol.SKIP -> QueueHub.skip()

                QueueProtocol.REMOVE -> {
                    val id = data.str("id")
                    if (id.isBlank()) return ignore(type)
                    QueueHub.remove(id)
                }

                QueueProtocol.CLEAR -> QueueHub.clear()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not handle '$type' from $from: ${e.message}")
        }
    }

    private fun ignore(type: String) {
        Log.w(TAG, "Ignored a '$type' with nothing playable in it")
    }
}
