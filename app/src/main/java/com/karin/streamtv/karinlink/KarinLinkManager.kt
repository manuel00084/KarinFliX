package com.karin.streamtv.karinlink

import android.content.Context
import android.util.Log
import com.karin.streamtv.BuildConfig
import com.karin.streamtv.karinlink.protocol.Capability
import com.karin.streamtv.karinlink.protocol.DiscoveredPeer
import com.karin.streamtv.karinlink.protocol.Envelope
import com.karin.streamtv.karinlink.protocol.PeerInfo
import com.karin.streamtv.karinlink.protocol.PeerRegistry
import com.karin.streamtv.karinlink.protocol.long
import com.karin.streamtv.karinlink.protocol.str
import com.karin.streamtv.karinlink.queue.QueueHub
import com.karin.streamtv.karinlink.queue.QueueProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KarinLinkManager(private val context: Context) {

    companion object {
        private const val TAG = "KarinLinkManager"
        private const val PREFS_NAME = "karin_link"
        private const val KEY_REMOTE_FS = "remote_fs_enabled"
        private const val KEY_FS_TOKEN = "fs_token"

        private val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        @Volatile private var cachedToken: String? = null
    }

    /**
     * The one identity for the whole feature.
     *
     * Identity used to be read from `karin_link` prefs here and from
     * `karin_link_v2` inside the trust registry, so the same device had two ids.
     * Every signature is derived from the pair of ids, so a mismatch means no
     * handshake can ever succeed; the registry is now the only source and these
     * properties delegate to it.
     */
    val peerRegistry = PeerRegistry(context)

    val deviceId: String get() = peerRegistry.deviceId

    val deviceName: String get() = peerRegistry.deviceName

    var deviceNameMutable: String
        get() = peerRegistry.deviceName
        set(name) {
            peerRegistry.setDeviceName(name)
        }

    /** Alias to match KarinLinkConfigActivity expectation */
    fun setDeviceName(name: String) { deviceNameMutable = name }

    var isRemoteFileAccessEnabled: Boolean
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REMOTE_FS, false)
        set(v) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_REMOTE_FS, v)
                .apply()
        }

    /** Alias to match KarinLinkConfigActivity expectation */
    fun setRemoteFileAccess(enabled: Boolean) { isRemoteFileAccessEnabled = enabled }

    /** Carpetas que el usuario ha decidido compartir. */
    fun sharedFolders(): List<java.io.File> = FsStore.roots(context)

    fun shareFolder(dir: java.io.File): Boolean = FsStore.addRoot(context, dir)

    fun unshareFolder(dir: java.io.File) = FsStore.removeRoot(context, dir)

    val fsToken: String
        get() {
            cachedToken?.let { return it }
            val t = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FS_TOKEN, null)
            if (t != null) { cachedToken = t; return t }
            val newTok = generateFsToken()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FS_TOKEN, newTok)
                .apply()
            cachedToken = newTok
            return newTok
        }

    /** Alias */
    fun regenerateFsToken(): String {
        val tok = generateFsToken()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FS_TOKEN, tok)
            .apply()
        cachedToken = tok
        return tok
    }

    private fun generateFsToken(): String {
        val random = java.util.Random()
        return (1..32).map { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] }.joinToString("")
    }

    val discoveryManager = DiscoveryManager(context, deviceId)

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    private val _status = MutableStateFlow("Desconectado")
    val status: StateFlow<String> = _status

    /** Called on the receiving device when a peer sends us an episode to play. */
    var onPlaybackRequest: ((episodeTitle: String, episodeUrl: String, embedUrl: String, siteName: String) -> Unit)? = null

    private var serverRegistered = false
    private var pendingShare: JsonObject? = null

    /**
     * Lo último que se quiso mandar a la cola, por si la conexión no estaba.
     *
     * Se guarda el tipo con el payload porque "reproducir ahora" y "añadir" no
     * se pueden reenviar igual: si se perdiera el tipo, un vídeo enviado a la
     * cola podría acabar parando lo que estaba sonando.
     */
    private var pendingQueue: Pair<String, JsonObject>? = null

    val localPeerInfo: PeerInfo by lazy {
        PeerInfo(
            deviceId = deviceId,
            deviceName = deviceName,
            appVersion = BuildConfig.VERSION_NAME,
            capabilities = setOf(Capability.PLAY, Capability.REMOTE, Capability.PROXY)
        )
    }

    val linkClient = LinkClient(peerRegistry, localPeerInfo)

    /** The code this device is currently offering or expecting, if any. */
    val pendingPairingCode: String? get() = peerRegistry.pendingPin

    /**
     * Sets or clears the pairing code.
     *
     * Both ends need the same code, so each device stores the one the user
     * typed. Passing null cancels, which the user should be able to do without
     * hunting through settings once the pairing succeeded.
     */
    fun setPairingCode(code: String?) {
        peerRegistry.pendingPin = code?.takeIf { it.isNotBlank() }
        Log.i(TAG, if (peerRegistry.isAwaitingPairing) "Pairing code set" else "Pairing code cleared")
    }

    fun start() {
        if (_isEnabled.value) return
        _isEnabled.value = true
        _status.value = "Buscando dispositivos..."

        // Host server: allows this device to accept peers (mirror episodes and files).
        if (!LinkServer.configure(peerRegistry, localPeerInfo)) {
            _status.value = "Error de identidad"
            return
        }
        val port = LinkServer.start(0)
        if (port > 0) {
            Log.i(TAG, "LinkServer bound on $port")
            LinkServer.filesProvider = { FsStore.config(context) }
        LinkServer.uploadsProvider = { QueueHub.uploadStore }
            LinkServer.addListener(serverListener)
            LinkServer.addPeerListener(peerListener)
        }

        discoveryManager.startDiscovery()

        linkClient.setHandler(object : LinkClient.MessageHandler {
            override fun onPeerConnected(deviceId: String, deviceName: String) {
                Log.i(TAG, "Peer connected: $deviceName")
                _status.value = "Conectado a $deviceName"
                pendingShare?.let { broadcast("sync", it) }
            pendingQueue?.let { (type, payload) -> broadcast(type, payload) }
            }

            override fun onPeerDisconnected(deviceId: String) {
                Log.i(TAG, "Peer disconnected: $deviceId")
                _status.value = "Dispositivo desconectado"
            }

            override fun onRejected(reason: String) {
                Log.w(TAG, "Pairing refused: $reason")
                _status.value = when (reason) {
                    "not_paired" -> "Empareja este equipo con el otro"
                    "protocol_mismatch" -> "Versiones incompatibles"
                    else -> "Emparejamiento rechazado"
                }
            }

            override fun onSyncCommand(deviceId: String, data: JsonObject) {
                val embedUrl = data.str("embedUrl")
                if (embedUrl.isNotBlank()) {
                    onPlaybackRequest?.invoke(
                        data.str("episodeTitle"),
                        data.str("episodeUrl"),
                        embedUrl,
                        data.str("siteName")
                    )
                }
            }

            override fun onRemoteCommand(deviceId: String, type: String, data: JsonObject) {
                RemoteControlHub.handleJson(context, type, data)
            }
        })

        // Advertise on the LAN so other KarinFLiX devices can discover us.
        registerNsd(port)

        Log.i(TAG, "KARIN Link started - Device: $deviceName ($deviceId)")
    }

    /**
     * A peer that just authenticated gets whatever is already queued to share.
     *
     * This used to hang off the old `hello` message, which the typed protocol no
     * longer sends, so without this a device that connected after the user hit
     * share would sit idle.
     */
    private val peerListener: (PeerInfo?) -> Unit = { peer ->
        // Null means the peer dropped, which the message path already reports.
        if (peer != null) pendingShare?.let { LinkServer.broadcast("sync", it) }
    }

    private val serverListener: (Envelope) -> Unit = { env ->
        val data = env.d
        when (env.t) {
            "sync" -> {
                // Auto-play when a peer shares an episode to this device.
                if (env.from != deviceId) {
                    val embedUrl = data.str("embedUrl")
                    if (embedUrl.isNotBlank()) {
                        onPlaybackRequest?.invoke(
                            data.str("episodeTitle"),
                            data.str("episodeUrl"),
                            embedUrl,
                            data.str("siteName")
                        )
                    }
                }
            }
            "play" -> Log.i(TAG, "Peer play: ${data.str("episodeUrl")}")
            "pause" -> Log.i(TAG, "Peer pause @ ${data.long("positionMs")}")
            "seek" -> Log.i(TAG, "Peer seek @ ${data.long("positionMs")}")
        }
    }

    fun stop() {
        _isEnabled.value = false
        _status.value = "Desconectado"
        LinkServer.removeListener(serverListener)
        LinkServer.removePeerListener(peerListener)
        // El host de la app mantiene el servidor para el control remoto;
        // solo se detiene si nadie lo usa.
        if (!KarinLinkHost.isRunning) {
            LinkServer.stop()
        }
        unregisterNsd()
        discoveryManager.destroy()
        linkClient.shutdown()
    }

    fun connectToDevice(device: DiscoveredPeer) {
        _status.value = "Conectando a ${device.displayName}..."
        linkClient.connect(device.host, device.port)
    }

    fun shareEpisode(
        title: String,
        episodeTitle: String,
        episodeUrl: String,
        siteName: String,
        embedUrl: String = ""
    ) {
        val payload = buildJsonObject {
            put("deviceId", deviceId)
            put("episodeTitle", episodeTitle)
            put("episodeUrl", episodeUrl)
            put("embedUrl", embedUrl)
            put("siteName", siteName)
            put("title", title)
            put("positionMs", 0L)
            put("durationMs", 0L)
            put("isPlaying", false)
        }
        pendingShare = payload
        broadcast("sync", payload)
    }

    /** Sends a message to the peer connected via the outgoing client link. */
    private fun broadcast(type: String, payload: JsonObject): Boolean =
        linkClient.send(type, payload)

    /**
     * Encola o reproduce ahora un vídeo en el otro equipo.
     *
     * Se guarda como pendiente además de enviarlo porque la conexión suele
     *Aspettar un momento: si el vídeo se perdiera por llegar medio segundo
     * antes de terminar el handshake, el usuario no vería nada y no sabría por
     * qué.
     */
    fun shareToQueue(
        playNow: Boolean,
        itemTitle: String,
        videoUrl: String = "",
        embedUrl: String = "",
        episodeUrl: String = "",
        siteName: String = "",
        localFile: Boolean = false,
    ): Boolean {
        val payload = buildJsonObject {
            put("title", itemTitle)
            put("videoUrl", videoUrl)
            put("embedUrl", embedUrl)
            put("episodeUrl", episodeUrl)
            put("siteName", siteName)
            put("localFile", localFile)
        }
        val type = if (playNow) QueueProtocol.PLAY else QueueProtocol.ADD
        pendingQueue = type to payload
        return broadcast(type, payload)
    }

    /**
     * Sube un vídeo local al otro equipo y devuelve dónde lo ha dejado.
     *
     * Bloquea mientras sube, así que va fuera del hilo principal. Es el camino
     * largo, para cuando no hay un enlace directo que reproducir.
     */
    fun uploadVideo(
        device: DiscoveredPeer,
        token: String,
        name: String,
        title: String,
        length: Long,
        open: () -> java.io.InputStream,
    ): String? = linkClient.uploadVideo(device.host, device.port, token, name, title, open, length)
    private fun registerNsd(port: Int) {
        try {
            discoveryManager.registerService(port, discoveryManager.localPeerInfo(deviceId, deviceName))
            serverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "NSD register failed: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        discoveryManager.unregisterService()
        serverRegistered = false
    }
}