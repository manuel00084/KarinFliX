package com.karin.streamtv.karinlink.queue

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.karin.streamtv.karinlink.upload.UploadStore
import com.karin.streamtv.player.ExoPlayerActivity
import com.karin.streamtv.util.AppActivityHolder
import java.io.File
import java.util.UUID

/**
 * Une la cola de [PlaybackQueue] con el reproductor de verdad.
 *
 * Es el mismo patrón que [com.karin.streamtv.karinlink.RemoteControlHub]: un
 * objeto de proceso al que apuntan tanto los mensajes que llegan por la red como
 * el reproductor cuando termina un vídeo. La diferencia es que aquí el
 * reproductor necesita saber *qué* elemento de la cola dejó de sonar, y para eso
 * cada arranque lleva [EXTRA_QUEUE_ID].
 */
object QueueHub {

    private const val TAG = "QueueHub"

    /** Clave con la que se marca qué elemento de la cola está sonando. */
    const val EXTRA_QUEUE_ID = "karin_queue_id"

    /** Notificado al cambiar la cola, para que la pantalla de la TV se refresque. */
    fun interface Listener {
        fun onQueueChanged(queue: PlaybackQueue)
    }

    private val queue = PlaybackQueue { UUID.randomUUID().toString() }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    @Volatile
    private var appContext: Context? = null

    /**
     * Dónde viven los vídeos subidos, si esta build los admite.
     *
     * Va por setter y no por [attach] para que el borrado al terminar use
     * siempre el mismo directorio que se usó al escribir, aunque la app se haya
     * reiniciado entre medias.
     */
    @Volatile
    var uploadStore: UploadStore? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
        if (uploadStore == null) {
            uploadStore = UploadStore(
                File(context.applicationContext.filesDir, "karin_uploads"),
            ).also {
                // Lo que quedó de una sesión anterior ya no lo va a reproducir
                // nadie, y ocupa espacio de verdad.
                val removed = it.clearStale()
                if (removed > 0) Log.i(TAG, "Removed $removed leftovers from a previous session")
            }
        }
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** La cola, para pintarla o mandarla a otro equipo. */
    fun current(): PlaybackQueue = queue

    // ── Operaciones, cada una ejecuta lo que la cola decida ─────────

    /** Lo que suena ahora se para y se sustituye por este. */
    fun playNow(item: QueueItem): QueueAction = execute(queue.playNow(item))

    /** Entra detrás de lo que haya, sin tocar al que suena. */
    fun add(item: QueueItem): QueueAction = execute(queue.add(item))

    fun skip(): QueueAction = execute(queue.skip())

    fun remove(id: String): QueueAction = execute(queue.remove(id))

    fun clear(): QueueAction = execute(queue.clear())

    /**
     * El reproductor terminó el elemento [id].
     *
     * Solo avanza si el id sigue siendo el primero: si mientras sonaba alguien
     * quitó ese elemento, o llegó otro que lo sustituyó, el aviso llega tarde y
     * no debe arrancar nada.
     */
    fun onPlaybackFinished(id: String?) {
        if (id.isNullOrBlank()) return
        val head = queue.current
        if (head == null || head.id != id) {
            Log.i(TAG, "Ignoring the end of a stale queue item: $id")
            return
        }
        execute(queue.completeCurrent())
    }

    private fun execute(action: QueueAction): QueueAction {
        val ctx = appContext
        if (ctx != null) main().post { perform(ctx, action) }
        if (action !is QueueAction.None) notifyChanged()
        return action
    }

    private fun perform(ctx: Context, action: QueueAction) {
        try {
            when (action) {
                is QueueAction.None -> Unit
                is QueueAction.Start -> startItem(ctx, action.item)
                is QueueAction.StopAndStart -> {
                    stopCurrent()
                    startItem(ctx, action.next)
                }
                is QueueAction.Stop -> {
                    stopCurrent()
                    deleteWhenLocal(action.item)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply $action: ${e.message}")
        }
    }

    /**
     * Arranca un elemento.
     *
     * Un enlace directo va directo al reproductor: antes solo existía el camino
     * del embed, que abría un WebView, y para un enlace ya extraído eso sobra.
     */
    private fun startItem(ctx: Context, item: QueueItem) {
        val target: Class<*> = if (item.embedUrl.isBlank()) {
            ExoPlayerActivity::class.java
        } else {
            com.karin.streamtv.ui.EmbedWebViewActivity::class.java
        }

        val intent = Intent(ctx, target).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_QUEUE_ID, item.id)
            putExtra("video_title", item.title)
            if (target == ExoPlayerActivity::class.java) {
                putExtra("video_url", item.videoUrl)
                if (item.episodeUrl.isNotBlank()) putExtra("referer", item.episodeUrl)
            } else {
                putExtra("embed_url", item.embedUrl)
                putExtra("episode_url", item.episodeUrl)
                putExtra("site_name", item.siteName)
                putExtra("episode_number", 0)
            }
        }
        ctx.startActivity(intent)
        Log.i(TAG, "Queue: playing ${item.title}")
    }

    /**
     * Para lo que esté sonando.
     *
     * Se pide a la Activity en primer plano que se cierre en vez de matarla a
     * la fuerza, para que el reproductor suelte el decoder y la pantalla.
     */
    private fun stopCurrent() {
        val current = AppActivityHolder.current()
        if (current is ExoPlayerActivity || current is com.karin.streamtv.ui.EmbedWebViewActivity) {
            current.runOnUiThread { current.finish() }
        }
    }

    /**
     * Borra el fichero si llegó subido desde otro equipo.
     *
     * La regla acordada es que estos vídeos no se quedan: se reproducen y se van,
     * para no llenar la TV con copias de lo que ya está en el móvil.
     */
    private fun deleteWhenLocal(item: QueueItem) {
        if (!item.localFile) return
        val store = uploadStore
        if (store != null) {
            store.delete(item.videoUrl)
            return
        }
        // Sin almacén se borra lo que se pueda, pero nunca fuera de él: un
        // `videoUrl` viene de la red y no puede convertirse en una orden de
        // borrar cualquier fichero del disco.
        val path = item.videoUrl.removePrefix("file://")
        if (path.isBlank()) return
        runCatching { java.io.File(path).delete() }
            .onSuccess { if (it) Log.i(TAG, "Queue: deleted $path") }
            .onFailure { Log.w(TAG, "Queue: could not delete $path: ${it.message}") }
    }

    private fun notifyChanged() {
        main().post { listeners.forEach { it.onQueueChanged(queue) } }
    }

    private fun main() = android.os.Handler(android.os.Looper.getMainLooper())

    /** Si el reproductor actual es un elemento de la cola, su id. */
    fun idOf(activity: Activity?): String? = activity?.intent?.getStringExtra(EXTRA_QUEUE_ID)
}
