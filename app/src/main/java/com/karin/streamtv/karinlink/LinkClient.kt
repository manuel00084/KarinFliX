package com.karin.streamtv.karinlink

import android.util.Log
import com.karin.streamtv.karinlink.protocol.Envelope
import com.karin.streamtv.karinlink.protocol.LinkProtocol
import com.karin.streamtv.karinlink.protocol.LinkSession
import com.karin.streamtv.karinlink.protocol.PeerInfo
import com.karin.streamtv.karinlink.protocol.PeerRegistry
import com.karin.streamtv.karinlink.protocol.str
import com.karin.streamtv.karinlink.queue.QueueProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The initiating half of KARIN Link: opens a WebSocket and runs a
 * [LinkSession] over it.
 *
 * OkHttp owns the socket and already does RFC 6455 framing, including the
 * masking a client is required to apply, so there is no codec here on purpose.
 * What this class adds is the protocol: a signed `hello` on open, refusal of
 * anything that fails verification, and no more sending once the session is
 * known to be untrusted.
 */
class LinkClient(
    private val registry: PeerRegistry,
    private val localInfo: PeerInfo
) {

    companion object {
        private const val TAG = "LinkClient"
    }

    private var webSocket: WebSocket? = null
    private var session: LinkSession? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    interface MessageHandler {
        fun onPeerConnected(deviceId: String, deviceName: String)
        fun onPeerDisconnected(deviceId: String)
        fun onRejected(reason: String)
        fun onSyncCommand(deviceId: String, data: JsonObject)
        fun onRemoteCommand(deviceId: String, type: String, data: JsonObject)
    }

    private var handler: MessageHandler? = null

    fun setHandler(handler: MessageHandler) {
        this.handler = handler
    }

    val isConnected: Boolean get() = session?.isReady == true

    /** True once a pairing code is pending, so the user can be asked for it. */
    val isAwaitingPairing: Boolean get() = registry.isAwaitingPairing

    fun connect(host: String, port: Int) {
        if (localInfo.deviceId != registry.deviceId) {
            Log.e(TAG, "Refusing to connect as '${localInfo.deviceId}': trust store is '${registry.deviceId}'")
            return
        }
        disconnect()

        val url = "ws://$host:$port${LinkProtocol.WS_PATH}"
        val fresh = LinkSession(localInfo, registry)
        session = fresh

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to $host:$port")
                // The server's Sec-WebSocket-Accept is checked by OkHttp, so
                // reaching this point means the handshake was well formed.
                fresh.beginHandshake()?.let { hello ->
                    webSocket.send(hello.encode())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                for (outcome in fresh.receive(text)) {
                    when (outcome) {
                        is LinkSession.Outcome.Paired -> {
                            if (!outcome.alreadyKnown) {
                                Log.i(TAG, "Paired with ${outcome.remote.deviceName}")
                            }
                            handler?.onPeerConnected(outcome.remote.deviceId, outcome.remote.deviceName)
                        }

                        is LinkSession.Outcome.Respond -> webSocket.send(outcome.envelope.encode())

                        is LinkSession.Outcome.Deliver -> dispatch(outcome.envelope)

                        is LinkSession.Outcome.Reject -> {
                            Log.w(TAG, "Refused ${outcome.reason}: ${outcome.detail}")
                            handler?.onRejected(outcome.reason)
                            webSocket.close(1008, outcome.reason)
                        }

                        is LinkSession.Outcome.Drop ->
                            Log.d(TAG, "Ignored a message: ${outcome.detail}")

                        is LinkSession.Outcome.Malformed -> {
                            Log.w(TAG, "Malformed message: ${outcome.detail}")
                            webSocket.close(1002, "protocol error")
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Connection closed: $reason")
                forgetPeer()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed: ${t.message}")
                forgetPeer()
            }
        })
    }

    private fun dispatch(env: Envelope) {
        val handler = handler ?: return
        if (RemoteProtocol.isRemote(env.t)) {
            handler.onRemoteCommand(env.from, env.t, env.d)
            return
        }
        when (env.t) {
            "sync" -> handler.onSyncCommand(env.from, env.d)
            "play", "pause", "seek" ->
                Log.i(TAG, "Player command '${env.t}' from ${env.from} is not handled in v2 yet")
        }
    }

    private fun forgetPeer() {
        session?.remoteId?.let { handler?.onPeerDisconnected(it) }
        session = null
        webSocket = null
    }

    /**
     * Sends a signed message.
     *
     * @return false when the session is not ready or has no key, in which case
     *   nothing was sent. The old `sendJson` happily sent an unsigned frame.
     */
    fun send(type: String, payload: JsonObject): Boolean {
        val socket = webSocket ?: return false
        val env = session?.send(type, payload) ?: return false
        return socket.send(env.encode())
    }

    fun sendEpisode(
        episodeTitle: String,
        episodeUrl: String,
        siteName: String,
        embedUrl: String = ""
    ): Boolean = send(
        "sync",
        buildJsonObject {
            put("episodeTitle", episodeTitle)
            put("episodeUrl", episodeUrl)
            put("siteName", siteName)
            put("embedUrl", embedUrl)
        }
    )

    // ── Remote control ────────────────────────────────────────────

    fun sendRemoteKey(keyCode: Int): Boolean =
        send(RemoteProtocol.KEY, buildJsonObject { put("keyCode", keyCode); put("action", "click") })

    fun sendRemoteText(text: String, submit: Boolean = false): Boolean =
        send(RemoteProtocol.TEXT, buildJsonObject { put("text", text); put("submit", submit) })

    fun sendMouseMove(x: Float, y: Float): Boolean =
        send(RemoteProtocol.MOUSE_MOVE, buildJsonObject { put("x", x); put("y", y) })

    fun sendMouseTap(x: Float, y: Float): Boolean =
        send(RemoteProtocol.MOUSE_TAP, buildJsonObject { put("x", x); put("y", y) })

    fun sendMouseScroll(dx: Float, dy: Float, x: Float, y: Float): Boolean =
        send(
            RemoteProtocol.MOUSE_SCROLL,
            buildJsonObject { put("dx", dx); put("dy", dy); put("x", x); put("y", y) }
        )

    fun sendMedia(cmd: String, value: Long = 0L): Boolean =
        send(RemoteProtocol.MEDIA, buildJsonObject { put("cmd", cmd); put("value", value) })

    fun disconnect() {
        webSocket?.close(1000, "User left")
        webSocket = null
        session = null
    }

    fun shutdown() = disconnect()

    // ── Subida de un vídeo local ───────────────────────────────────────────

    /**
     * Sends a local video to the other device so it can play it.
     *
     * Es el camino largo y por eso va aparte: el WebSocket es para mensajes
     * cortos, y un vídeo son cientos de megabytes. Aquí no hay firma por
     * mensaje, así que se comprueba el token, y el cuerpo se manda en
     * stream para no tener un fichero de dos gigabytes en memoria.
     *
     * Bloquea: se llama desde una corrutina fuera del hilo principal.
     *
     * @return la URL `file://` donde lo ha dejado el otro equipo.
     */
    fun uploadVideo(
        host: String,
        port: Int,
        token: String,
        name: String,
        title: String,
        open: () -> InputStream,
        length: Long,
    ): String? {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length
            // Se delega en el stream que abre el llamante, para que un
            // content:// del selector no tenga que copiarse antes.
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                }
            }
        }

        val url = StringBuilder("http://$host:$port")
            .append(LinkProtocol.PUSH_PATH)
            .append("?t=").append(URLEncoder.encode(token, "UTF-8"))
            .append("&name=").append(URLEncoder.encode(name, "UTF-8"))
            .append("&title=").append(URLEncoder.encode(title.take(200), "UTF-8"))
            .toString()

        val request = Request.Builder().url(url).post(body).build()
        // El cliente del WebSocket no tiene tiempo de escritura: el que se usa
        // aquí espera indefinidamente, porque un vídeo grande puede tardar
        // minutos en llegar entero por una red doméstica.
        return uploadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Upload refused with ${response.code}")
                return null
            }
            val stored = response.body?.string().orEmpty()
            val storedUrl = runCatching {
                Json.parseToJsonElement(stored).jsonObject.str("url")
            }.getOrDefault("")
            if (storedUrl.isBlank()) {
                Log.w(TAG, "Upload answered without a url")
                return null
            }
            storedUrl
        }
    }

    private val uploadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // ── Cola de reproducción ──────────────────────────────────────────────

    /**
     * Sustituye lo que esté sonando en el otro equipo por este vídeo.
     *
     * Es lo que se elige al mandar uno nuevo: el anterior se para y lo que
     * quedara en la cola se descarta, en vez de reanudarse más tarde.
     */
    fun sendQueuePlay(
        title: String,
        videoUrl: String,
        embedUrl: String = "",
        episodeUrl: String = "",
        siteName: String = "",
        itemId: String = "",
    ): Boolean = send(
        QueueProtocol.PLAY,
        queuePayload(title, videoUrl, embedUrl, episodeUrl, siteName, itemId),
    )

    /** Lo añade detrás de lo que haya, sin molestar al que está sonando. */
    fun sendQueueAdd(
        title: String,
        videoUrl: String,
        embedUrl: String = "",
        episodeUrl: String = "",
        siteName: String = "",
        itemId: String = "",
    ): Boolean = send(
        QueueProtocol.ADD,
        queuePayload(title, videoUrl, embedUrl, episodeUrl, siteName, itemId),
    )

    fun sendQueueSkip(): Boolean = send(QueueProtocol.SKIP, buildJsonObject { })

    fun sendQueueRemove(itemId: String): Boolean =
        send(QueueProtocol.REMOVE, buildJsonObject { put("id", itemId) })

    fun sendQueueClear(): Boolean = send(QueueProtocol.CLEAR, buildJsonObject { })

    private fun queuePayload(
        title: String,
        videoUrl: String,
        embedUrl: String,
        episodeUrl: String,
        siteName: String,
        itemId: String,
    ) = buildJsonObject {
        put("title", title)
        put("videoUrl", videoUrl)
        put("embedUrl", embedUrl)
        put("episodeUrl", episodeUrl)
        put("siteName", siteName)
        put("localFile", false)
        // Sin id, el otro equipo genera uno; mandarlo permite quitar después
        // ese mismo elemento desde este móvil.
        if (itemId.isNotBlank()) put("id", itemId)
    }
}
