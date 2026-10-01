package com.karin.streamtv.karinlink

import android.util.Log
import com.karin.streamtv.karinlink.protocol.Capability
import com.karin.streamtv.karinlink.protocol.Envelope
import com.karin.streamtv.karinlink.protocol.LinkProtocol
import com.karin.streamtv.karinlink.protocol.LinkSession
import com.karin.streamtv.karinlink.protocol.PeerInfo
import com.karin.streamtv.karinlink.protocol.PeerRegistry
import com.karin.streamtv.karinlink.protocol.RejectReason
import com.karin.streamtv.karinlink.protocol.WsFrameParser
import com.karin.streamtv.karinlink.protocol.WsOpcode
import com.karin.streamtv.karinlink.upload.UploadStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The accepting half of KARIN Link: an HTTP endpoint that upgrades to
 * WebSocket, then runs [LinkSession] for every connection.
 *
 * This class does framing and sockets. Every decision about whether a message is
 * allowed belongs to [LinkSession], which is unit tested; the previous version
 * kept those checks inline in the read loop, which is why none of them were
 * covered and why a peer that finished the handshake could then send anything.
 *
 * Two things changed for the better here:
 *  - A peer is added to [peers] only once it has been authenticated, so
 *    [broadcast] can no longer reach a stranger, and it can now reach anyone:
 *    the old `assignPeer` was never called from anywhere, so the peer list was
 *    permanently empty and every broadcast was a no-op.
 *  - [stop] leaves the listeners registered. Clearing them meant a restart
 *    silently stopped delivering messages, since the owner only registers on
 *    the way up.
 */
object LinkServer {

    private const val TAG = "LinkServer"
    private const val READ_BUFFER = 8 * 1024
    private const val HANDSHAKE_TIMEOUT_MS = 15_000

    /** One connected device. Guard writes with [writeLock]. */
    internal class Peer(
        val socket: Socket,
        /**
         * Created with the connection, not at the handshake. A peer that sends
         * before saying hello has to be judged by something, and a `lateinit`
         * here would throw instead of refusing it.
         */
        val session: LinkSession
    ) {
        val input: InputStream get() = socket.getInputStream()
        val output: OutputStream get() = socket.getOutputStream()

        /** A server must reject unmasked client frames; see WsFrameParser. */
        val parser = WsFrameParser(expectMasked = true)

        var deviceId: String? = null
            private set

        var deviceName: String? = null
            private set

        var closed = false
        val writeLock = Any()

        internal fun onPaired(remote: PeerInfo) {
            this.deviceId = remote.deviceId
            this.deviceName = remote.deviceName
        }
    }

    private val peers = CopyOnWriteArrayList<Peer>()

    /** Receives only messages that passed every check in [LinkSession]. */
    private val listeners = CopyOnWriteArrayList<(Envelope) -> Unit>()

    /** Told when a peer authenticates or drops, so the UI can follow sessions. */
    private val peerListeners = CopyOnWriteArrayList<(PeerInfo?) -> Unit>()

    private var serverSocket: ServerSocket? = null
    private var running = false

    /**
     * Identity and trust store. Required before [start]: without them there is
     * no way to verify a signature, so the server would have to accept
     * anonymous peers. Failing to bind is better than that.
     */
    private var registry: PeerRegistry? = null
    private var localInfo: PeerInfo? = null

    val port: Int get() = serverSocket?.localPort ?: 0
    val isRunning: Boolean get() = running
    val connectedPeers: Int get() = peers.size

    /**
     * Supplies the identity and trust store the server needs.
     *
     * @return false when called with a device id that does not match the
     *   registry's own, which would mean the two disagree on who this device is
     *   and every signature would fail.
     */
    /**
     * Supplies the shared folders on demand.
     *
     * A provider rather than a snapshot so the Ajustes switch takes effect
     * without restarting the server. Returning null means the feature is off,
     * and the endpoint then answers 404 so the toggle reveals nothing at all.
     */
    @Volatile
    var filesProvider: (() -> FsConfig?)? = null

/**
 * Dónde se guardan los vídeos que llegan por `/push`.
 *
 * [serveUpload] es la única parte del servidor que escribe en disco, así que el
 * destino se decide fuera y aquí solo se usa.
 */
var uploadsProvider: (() -> UploadStore?)? = null

    fun configure(registry: PeerRegistry, info: PeerInfo): Boolean {
        if (info.deviceId != registry.deviceId) {
            Log.e(TAG, "Refusing to serve as '${info.deviceId}': the trust store is '${registry.deviceId}'")
            return false
        }
        this.registry = registry
        this.localInfo = info
        return true
    }

    /** Delivers authenticated inbound messages. */
    fun addListener(listener: (Envelope) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Envelope) -> Unit) {
        listeners.remove(listener)
    }

    /** Reports peers completing the handshake, and leaving. */
    fun addPeerListener(listener: (PeerInfo?) -> Unit) {
        peerListeners.add(listener)
    }

    fun removePeerListener(listener: (PeerInfo?) -> Unit) {
        peerListeners.remove(listener)
    }

    /**
     * Starts the TCP listener on [inPort] (0 = ephemeral).
     *
     * @return the bound port, or 0 when it could not bind or [configure] has
     *   not been called.
     */
    @Synchronized
    fun start(inPort: Int = 0): Int {
        if (running) return port

        val trust = registry
        val info = localInfo
        if (trust == null || info == null) {
            Log.e(TAG, "start() without configure(): refusing to accept unauthenticated peers")
            return 0
        }

        return try {
            serverSocket = ServerSocket(inPort)
            running = true
            thread(name = "KarinLinkServer", isDaemon = true) {
                while (running) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "Accept failed: ${e.message}")
                        break
                    } ?: break
                    thread(name = "KarinLinkPeer", isDaemon = true) { handle(socket, trust, info) }
                }
            }
            Log.i(TAG, "Listening on ${serverSocket?.localPort} as ${info.deviceName}")
            serverSocket?.localPort ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}")
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            0
        }
    }

    /**
     * Stops listening and drops every connection.
     *
     * Listeners are intentionally left in place: they belong to whoever called
     * [addListener], and that caller reuses them across restarts.
     */
    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        peers.toList().forEach { drop(it) }
        Log.i(TAG, "Server stopped")
    }

    /** Sends [payload] to every authenticated peer except [skip]. */
    internal fun broadcast(type: String, payload: JsonObject, skip: Peer? = null) {
        peers.toList().forEach { peer ->
            if (peer === skip || peer.closed) return@forEach
            try {
                // Signed per peer: each session has its own key, so one shared
                // signature would not verify for anybody but its author.
                val env = peer.session.sendSigned(type, payload) ?: return@forEach
                sendText(peer, env.encode())
            } catch (e: Exception) {
                Log.w(TAG, "Broadcast to ${peer.deviceName} failed: ${e.message}")
                drop(peer)
            }
        }
    }

    /** Sends one signed message to one peer. */
    internal fun send(peer: Peer, type: String, payload: JsonObject): Boolean {
        if (peer.closed) return false
        return try {
            val env = peer.session.send(type, payload) ?: return false
            sendText(peer, env.encode())
            true
        } catch (e: Exception) {
            Log.w(TAG, "Send to ${peer.deviceName} failed: ${e.message}")
            drop(peer)
            false
        }
    }

    // ── Connection handling ───────────────────────────────────────

    private fun handle(socket: Socket, trust: PeerRegistry, info: PeerInfo) {
        val peer = Peer(socket, LinkSession(info, trust))
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS

            val request = readRequest(peer.input) ?: return closeQuietly(peer)

            if (request.path == LinkProtocol.FS_PATH) {
                serveFiles(peer, request)
                return closeQuietly(peer)
            }
            if (request.path == LinkProtocol.PUSH_PATH) {
                serveUpload(peer, request)
                return closeQuietly(peer)
            }
            if (request.path != LinkProtocol.WS_PATH) {
                httpReply(peer, "404 Not Found", "no such endpoint")
                return closeQuietly(peer)
            }
            if (!upgrade(peer, request)) return closeQuietly(peer)

            // Past this point the peer must complete a signed handshake, so the
            // read timeout goes back to blocking indefinitely.
            socket.soTimeout = 0
            readLoop(peer)
        } catch (e: SocketException) {
            Log.d(TAG, "Peer disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Connection failed: ${e.message}")
        } finally {
            drop(peer)
        }
    }

    private fun readLoop(peer: Peer) {
        val buffer = ByteArray(READ_BUFFER)
        while (!peer.closed && running) {
            val read = peer.input.read(buffer)
            if (read < 0) break
            if (read == 0) continue

            val events = peer.parser.feed(buffer.copyOf(read))
            if (!dispatch(peer, events)) break
        }
    }

    /** @return false when the connection must be torn down. */
    private fun dispatch(peer: Peer, events: List<WsFrameParser.Event>): Boolean {
        for (event in events) {
            when (event) {
                is WsFrameParser.Event.Text -> if (!onText(peer, event.message)) return false

                is WsFrameParser.Event.Ping -> sendFrame(peer, WsOpcode.PONG, event.payload)

                is WsFrameParser.Event.Pong -> Unit

                is WsFrameParser.Event.Closed -> {
                    sendFrame(peer, WsOpcode.CLOSE, ByteArray(0))
                    return false
                }

                is WsFrameParser.Event.Binary ->
                    Log.w(TAG, "Ignoring binary frame from ${peer.deviceName}: not part of the protocol")

                is WsFrameParser.Event.ProtocolError -> {
                    // Masking and framing violations mean we can no longer trust
                    // the byte stream, so the session ends here.
                    Log.w(TAG, "Framing error from ${peer.deviceName}: ${event.reason}")
                    return false
                }
            }
        }
        return true
    }

    private fun onText(peer: Peer, text: String): Boolean {
        val session = peer.session
        for (outcome in session.receive(text)) {
            when (outcome) {
                is LinkSession.Outcome.Paired -> {
                    peer.onPaired(outcome.remote)
                    if (!peers.contains(peer)) peers.add(peer)
                    Log.i(TAG, "Paired with ${outcome.remote.deviceName} (${outcome.remote.deviceId})")
                    peerListeners.forEach { runCatching { it(outcome.remote) } }
                }

                is LinkSession.Outcome.Respond -> sendText(peer, outcome.envelope.encode())

                is LinkSession.Outcome.Deliver ->
                    listeners.forEach { runCatching { it(outcome.envelope) } }

                is LinkSession.Outcome.Reject -> {
                    Log.w(TAG, "Refused ${outcome.reason} from ${peer.deviceName}: ${outcome.detail}")
                    // Always say why before hanging up. Closing on its own left
                    // the peer unable to tell a refusal from a network fault, so
                    // the UI could only report a generic failure.
                    runCatching { sendText(peer, session.reject(outcome.reason).encode()) }
                    // A peer that cannot be authenticated has no business
                    // staying connected, whether it lacks a pairing or sent a
                    // forged signature. A version mismatch is worth leaving
                    // connected for, since the other device may be usable.
                    if (outcome.reason != RejectReason.PROTOCOL_MISMATCH) return false
                }

                is LinkSession.Outcome.Drop ->
                    Log.d(TAG, "Ignored '${text.take(80)}' from ${peer.deviceName}: ${outcome.detail}")

                is LinkSession.Outcome.Malformed -> {
                    Log.w(TAG, "Malformed message from ${peer.deviceName}: ${outcome.detail}")
                    return false
                }
            }
        }
        return true
    }

    /** Removes a peer and tells anyone tracking sessions. */
    internal fun drop(peer: Peer) {
        if (peer.closed) return
        peer.closed = true
        peers.remove(peer)
        val name = peer.deviceName
        runCatching { peer.socket.close() }
        if (name != null) {
            Log.i(TAG, "Peer $name left")
            peerListeners.forEach { runCatching { it(null) } }
        }
    }

    // ── Wire helpers ──────────────────────────────────────────────

    private fun upgrade(peer: Peer, request: Request): Boolean {
        val key = request.header("sec-websocket-key")
        if (key == null) {
            httpReply(peer, "400 Bad Request", "missing Sec-WebSocket-Key")
            return false
        }
        if (!request.header("upgrade").equals("websocket", ignoreCase = true)) {
            httpReply(peer, "426 Upgrade Required", "this endpoint speaks WebSocket only")
            return false
        }

        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ${WsFrameParser.acceptFor(key)}\r\n")
            append("\r\n")
        }
        return try {
            peer.output.write(response.toByteArray(Charsets.US_ASCII))
            peer.output.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Upgrade write failed: ${e.message}")
            false
        }
    }

    /**
     * Sirve una carpeta compartida o un archivo.
     *
     * A diferencia del WebSocket, aquí no hay handshake firmado: la petición es
     * HTTP y el otro equipo solo tiene el token. Por eso el orden importa —
     * primero que el acceso esté activo, luego el token, y solo después se
     * toca el disco.
     */
    private fun serveFiles(peer: Peer, request: Request) {
        val method = request.method
        if (method != "GET" && method != "HEAD") {
            // Nunca se escribe ni se borra nada por esta vía: el remoto solo
            // lee. Aceptar PUT aquí convertiría el token en acceso de escritura.
            httpReply(peer, "405 Method Not Allowed", "read only")
            return
        }
        val headOnly = method == "HEAD"

        val config = filesProvider?.invoke()
        if (config == null || !config.isServable) {
            httpReply(peer, "404 Not Found", "no such endpoint")
            return
        }

        val provided = request.param("t") ?: request.header("x-karin-token")
        if (!FsAccess.isAuthorized(config.enabled, config.token, provided)) {
            Log.w(TAG, "Rejected a file request with a bad token")
            httpReply(peer, "401 Unauthorized", "bad token")
            return
        }

        val requested = request.param("path")
        if (requested.isNullOrBlank()) {
            httpReplyJson(peer, "200 OK", listingOf(config.roots), headOnly)
            return
        }

        val target = FsAccess.resolve(requested, config.roots)
            // Pedir el contenido de una carpeta compartida es lo primero que va
            // a hacer el remoto, así que la raíz exacta se lista en vez de
            // rechazarse. Sigue sin llegar nunca a [streamFile], porque aquí solo
            // se llega cuando es un directorio.
            ?: FsAccess.resolveSharedRoot(requested, config.roots)
        if (target == null) {
            Log.w(TAG, "Refused a path outside the shared folders: $requested")
            httpReply(peer, "403 Forbidden", "path not shared")
            return
        }
        if (!target.exists()) {
            httpReply(peer, "404 Not Found", "no such file")
            return
        }
        if (target.isDirectory) {
            httpReplyJson(peer, "200 OK", listingOf(listOf(target)), headOnly)
            return
        }
        streamFile(peer, target, headOnly)
    }

    /**
     * Recibe un vídeo para reproducirlo aquí y borrarlo al terminar.
     *
     * El orden de las comprobaciones es el mismo que en [serveFiles] y por el
     * mismo motivo: primero que la vía esté abierta, luego el token, y solo
     * después se toca el disco. El cuerpo se escribe a un temporal y se renombra
     * al final, así que una subida a medias nunca deja un fichero que el
     * reproductor pueda abrir.
     */
    private fun serveUpload(peer: Peer, request: Request) {
        if (request.method != "POST") {
            httpReply(peer, "405 Method Not Allowed", "post only")
            return
        }

        val store = uploadsProvider?.invoke()
        val config = filesProvider?.invoke()
        if (store == null || config == null || !config.isWritable) {
            httpReply(peer, "404 Not Found", "no such endpoint")
            return
        }

        val provided = request.param("t") ?: request.header("x-karin-token")
        if (!FsAccess.isAuthorized(config.enabled, config.token, provided)) {
            Log.w(TAG, "Rejected an upload with a bad token")
            httpReply(peer, "401 Unauthorized", "bad token")
            return
        }

        val declared = request.header("content-length")?.toLongOrNull()
        if (declared == null || declared <= 0) {
            // Sin Content-Length no se puede acotar la escritura, y un cuerpo
            // infinito en un socket de una TV no es un riesgo que se compense.
            httpReply(peer, "411 Length Required", "send a content-length")
            return
        }
        if (declared > store.maxBytes) {
            httpReply(peer, "413 Payload Too Large", "too large")
            return
        }

        val label = request.param("name") ?: "video"
        val title = request.param("title") ?: label
        val saved = try {
            store.save(label, peer.input, declared)
        } catch (e: UploadStore.TooLarge) {
            httpReply(peer, "413 Payload Too Large", "too large")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed: ${e.message}")
            httpReply(peer, "500 Internal Server Error", "could not store the upload")
            return
        }

        Log.i(TAG, "Stored an upload of ${saved.length()} bytes: ${saved.name}")
        httpReplyJson(peer, "200 OK", buildString {
            append("{\"name\":")
            append(Json.encodeToString(JsonPrimitive(UploadStore.sanitize(label))))
            append(",\"title\":")
            append(Json.encodeToString(JsonPrimitive(title.take(200))))
            append(",\"url\":")
            append(Json.encodeToString(JsonPrimitive(store.urlOf(saved))))
            append("}")
        })
    }

    private fun listingOf(dirs: List<File>): String = buildJsonObject {
        put("entries", buildJsonArray {
            for (dir in dirs) {
                val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (child in children.sortedBy { it.name }) {
                    if (child.isHidden) continue
                    add(kotlinx.serialization.json.Json.parseToJsonElement(
                        FsAccess.jsonEntry(child.name, child.isDirectory, child.length())
                    ))
                }
            }
        })
    }.toString()

    private fun streamFile(peer: Peer, file: File, headOnly: Boolean) {
        val length = file.length()
        if (length < 0) {
            httpReply(peer, "404 Not Found", "no such file")
            return
        }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${FsAccess.contentType(file)}\r\n")
            append("Content-Length: $length\r\n")
            // El nombre va entrecomillado para que un archivo con comillas o
            // saltos de línea no rompa la cabecera.
            append("Content-Disposition: ${FsAccess.contentDisposition(file.name)}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        try {
            peer.output.write(header.toByteArray(Charsets.US_ASCII))
            if (headOnly) {
                peer.output.flush()
                return
            }
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    peer.output.write(buffer, 0, read)
                }
            }
            peer.output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "File transfer stopped: ${e.message}")
        }
    }

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val query: String
    ) {
        fun header(name: String): String? = headers[name.lowercase()]

        /** Reads a query parameter, percent-decoding it. */
        fun param(name: String): String? {
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                if (!key.equals(name, ignoreCase = true)) continue
                val raw = if (eq < 0) "" else pair.substring(eq + 1)
                return try {
                    java.net.URLDecoder.decode(raw, "UTF-8")
                } catch (e: Exception) {
                    return raw
                }
            }
            return null
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val headers = HashMap<String, String>()
        var count = 0
        while (count++ < MAX_HEADER_LINES) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val target = parts[1]
        return Request(
            parts[0].uppercase(),
            target.substringBefore('?'),
            headers,
            target.substringAfter('?', "")
        )
    }

    private fun sendText(peer: Peer, text: String) {
        sendFrame(peer, WsOpcode.TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /** A server never masks. See [WsFrameParser.encode]. */
    private fun sendFrame(peer: Peer, opcode: Int, payload: ByteArray) {
        if (peer.closed) return
        val frame = WRITER.encode(opcode, payload, mask = null)
        synchronized(peer.writeLock) {
            peer.output.write(frame)
            peer.output.flush()
        }
    }

    /**
     * A text response.
     *
     * [headOnly] announces a length and then stops: a HEAD has to describe the
     * body it declined to send, and a client that read one would desynchronise
     * from the stream it is parsing.
     */
    private fun httpReply(peer: Peer, status: String, body: String, headOnly: Boolean = false) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching {
            synchronized(peer.writeLock) {
                peer.output.write(response.toByteArray(Charsets.US_ASCII))
                if (!headOnly) peer.output.write(bytes)
                peer.output.flush()
            }
        }
    }

    private fun httpReplyJson(peer: Peer, status: String, body: String, headOnly: Boolean = false) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching {
            synchronized(peer.writeLock) {
                peer.output.write(response.toByteArray(Charsets.US_ASCII))
                if (!headOnly) peer.output.write(bytes)
                peer.output.flush()
            }
        }
    }

    private fun closeQuietly(peer: Peer) {
        peer.closed = true
        runCatching { peer.socket.close() }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return String(buffer.toByteArray(), Charsets.UTF_8).trimEnd('\r')
            buffer.write(b)
            if (buffer.size() > MAX_LINE_BYTES) return null
        }
    }

    /** Stateless: encoding never touches the reader's state. */
    private val WRITER = WsFrameParser()

    private const val MAX_HEADER_LINES = 64
    private const val MAX_LINE_BYTES = 8 * 1024
}
