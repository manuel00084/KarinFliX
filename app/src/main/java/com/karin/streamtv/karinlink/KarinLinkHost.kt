package com.karin.streamtv.karinlink

import android.content.Context
import android.util.Log
import com.karin.streamtv.karinlink.protocol.Capability
import com.karin.streamtv.karinlink.protocol.Envelope
import com.karin.streamtv.karinlink.protocol.PeerInfo
import com.karin.streamtv.karinlink.protocol.PeerRegistry
import com.karin.streamtv.karinlink.protocol.str
import com.karin.streamtv.karinlink.queue.QueueCommands
import com.karin.streamtv.karinlink.queue.QueueHub
import com.karin.streamtv.karinlink.queue.QueueProtocol
import kotlinx.serialization.json.JsonObject

/**
 * Mantiene el servidor Karin Link encendido a nivel de aplicación para que
 * el control remoto funcione sin tener abierta la pantalla de Karin Link
 * (p. ej. mientras se ve un capítulo).
 *
 * Se arranca en [com.karin.streamtv.KarinTVApplication.onCreate] y vive
 * mientras el proceso viva. Solo atiende comandos `remote.*`; el resto
 * (`sync`, etc.) lo siguen gestionando las actividades abiertas.
 */
object KarinLinkHost {

    private const val TAG = "KarinLinkHost"

    @Volatile var isRunning = false
        private set

    private var discovery: DiscoveryManager? = null
    private var appContext: Context? = null

    /**
     * One identity for the whole feature.
     *
     * This used to read a device id from its own `karin_link` prefs, while
     * [com.karin.streamtv.karinlink.protocol.PeerRegistry] keeps a different one
     * in `karin_link_v2`. Two ids for one device means every signature is
     * derived from the wrong pair and no handshake can ever succeed, so the
     * registry is now the single source.
     */
    private val peerRegistry by lazy { PeerRegistry(appContext!!) }

    private val localPeerInfo: PeerInfo by lazy {
        PeerInfo(
            deviceId = peerRegistry.deviceId,
            deviceName = peerRegistry.deviceName,
            appVersion = com.karin.streamtv.BuildConfig.VERSION_NAME,
            capabilities = setOf(Capability.PLAY, Capability.REMOTE)
        )
    }

    private val hostListener: (Envelope) -> Unit = { env ->
        when {
            RemoteProtocol.isRemote(env.t) -> {
                appContext?.let { RemoteControlHub.handleJson(it, env.t, env.d) }
            }
            // Capítulo compartido para reproducir en este equipo.
            env.t == "sync" -> {
                appContext?.let { handleSync(it, env.d) }
            }
            // Cola de reproducción: se atiende siempre, haya o no una pantalla
            // abierta, porque mandar un vídeo con la app en segundo plano es el
            // caso normal.
            QueueProtocol.isQueue(env.t) -> {
                appContext?.let { QueueCommands.handle(it, env.from, env.t, env.d) }
            }
        }
    }

    /**
     * Auto-reproduce un capítulo enviado por otro equipo, aunque la pantalla
     * de Karin Link no esté abierta. Si sí lo está, ella lo gestiona.
     */
    private fun handleSync(app: Context, data: JsonObject) {
        val embed = data.str("embedUrl")
        if (embed.isBlank()) return
        val current = com.karin.streamtv.util.AppActivityHolder.current()
        if (current is KarinLinkActivity) return
        try {
            val intent = android.content.Intent(
                app,
                com.karin.streamtv.ui.EmbedWebViewActivity::class.java,
            ).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("embed_url", embed)
                putExtra("video_title", data.str("episodeTitle"))
                putExtra("episode_url", data.str("episodeUrl"))
                putExtra("episode_number", 0)
            }
            app.startActivity(intent)
            Log.i(TAG, "Auto-playing shared episode: ${data.str("episodeTitle")}")
        } catch (e: Exception) {
            Log.w(TAG, "handleSync failed: ${e.message}")
        }
    }

    @Synchronized
    fun start(ctx: Context) {
        if (isRunning) return
        try {
            val app = ctx.applicationContext
            appContext = app
            // La cola vive en el proceso, no en una pantalla: tiene que seguir
            // funcionando con el reproductor delante y la app en segundo plano.
            QueueHub.attach(app)
            if (!LinkServer.configure(peerRegistry, localPeerInfo)) {
                Log.w(TAG, "Host start failed: identity mismatch")
                return
            }
            val port = LinkServer.start(0)
            if (port <= 0) {
                Log.w(TAG, "LinkServer did not bind")
                return
            }
            // El host es el camino que sobrevive a que se cierre la pantalla,
            // así que también es quien expone las carpetas compartidas.
            LinkServer.filesProvider = { FsStore.config(app) }
        LinkServer.uploadsProvider = { QueueHub.uploadStore }
            LinkServer.addListener(hostListener)
            discovery = DiscoveryManager(app, peerRegistry.deviceId).also {
                it.registerService(port, localPeerInfo)
                it.startDiscovery()
            }
            isRunning = true
            Log.i(TAG, "Host started on port $port as ${localPeerInfo.deviceName}")
        } catch (e: Exception) {
            Log.w(TAG, "Host start failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        LinkServer.removeListener(hostListener)
        // No se detiene LinkServer aquí: otras pantallas pueden estar usándolo.
        try {
            discovery?.destroy()
        } catch (_: Exception) {
        }
        discovery = null
        Log.i(TAG, "Host stopped")
    }
}
