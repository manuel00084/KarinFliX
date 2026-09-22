package com.karin.streamtv.karinlink

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class KarinLinkManager(private val context: Context) {

    companion object {
        private const val TAG = "KarinLinkManager"
        private const val PREFS_NAME = "karin_link"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_REMOTE_FS = "remote_fs_enabled"
        private const val KEY_FS_TOKEN = "fs_token"

        private val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        @Volatile private var cachedToken: String? = null
    }

    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    val deviceName: String by lazy {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_NAME, null)
            ?: Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: "KarinFLiX-$deviceId"
    }

    var deviceNameMutable: String
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_NAME, null)
            ?: Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: "KarinFLiX-$deviceId"
        set(name) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DEVICE_NAME, name)
                .apply()
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

    val discoveryManager = DiscoveryManager(context)
    val linkClient = LinkClient()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    private val _status = MutableStateFlow("Desconectado")
    val status: StateFlow<String> = _status

    /** Called on the receiving device when a peer sends us an episode to play. */
    var onPlaybackRequest: ((episodeTitle: String, episodeUrl: String, embedUrl: String, siteName: String) -> Unit)? = null

    private var serverRegistered = false
    private var pendingShareJson: org.json.JSONObject? = null

    fun start() {
        if (_isEnabled.value) return
        _isEnabled.value = true
        _status.value = "Buscando dispositivos..."

        // Host server: allows this device to accept peers (mirror episodes and files).
        val port = LinkServer.start(0)
        if (port > 0) {
            Log.i(TAG, "LinkServer bound on $port")
            LinkServer.addListener(serverListener)
        }

        discoveryManager.startDiscovery()

        linkClient.setHandler(object : LinkClient.MessageHandler {
            override fun onPeerConnected(deviceId: String, deviceName: String) {
                Log.i(TAG, "Peer connected: $deviceName")
                _status.value = "Conectado a $deviceName"
                pendingShareJson?.let {
                    broadcast("sync", it)
                }
            }

            override fun onPeerDisconnected(deviceId: String) {
                Log.i(TAG, "Peer disconnected: $deviceId")
                _status.value = "Dispositivo desconectado"
            }

            override fun onSyncCommand(deviceId: String, command: String, data: org.json.JSONObject) {
                val embedUrl = data.optString("embedUrl")
                if (embedUrl.isNotBlank()) {
                    onPlaybackRequest?.invoke(
                        data.optString("episodeTitle", ""),
                        data.optString("episodeUrl", ""),
                        embedUrl,
                        data.optString("siteName", "")
                    )
                }
            }

            override fun onPlayCommand(deviceId: String, episodeUrl: String, positionMs: Long) {
                Log.i(TAG, "Play command from $deviceId: $episodeUrl @ ${positionMs}ms")
            }

            override fun onPauseCommand(deviceId: String, positionMs: Long) {
                Log.i(TAG, "Pause command from $deviceId @ ${positionMs}ms")
            }

            override fun onSeekCommand(deviceId: String, positionMs: Long) {
                Log.i(TAG, "Seek command from $deviceId @ ${positionMs}ms")
            }
        })

        // Advertise on the LAN so other KarinFLiX devices can discover us.
        registerNsd(port)

        Log.i(TAG, "KARIN Link started - Device: $deviceName ($deviceId)")
    }

    private val serverListener: (from: String?, type: String, data: org.json.JSONObject) -> Unit = { from, type, data ->
        when (type) {
            "hello" -> {
                // A new peer connected to us: push the pending share so they can play it.
                val ep = pendingShareJson
                if (ep != null) {
                    LinkServer.broadcast("sync", ep)
                }
            }
            "sync" -> {
                // Auto-play when a peer shares an episode to this device.
                if (from != deviceId) {
                    val embedUrl = data.optString("embedUrl")
                    if (embedUrl.isNotBlank()) {
                        onPlaybackRequest?.invoke(
                            data.optString("episodeTitle", ""),
                            data.optString("episodeUrl", ""),
                            embedUrl,
                            data.optString("siteName", "")
                        )
                    }
                }
            }
            "play" -> Log.i(TAG, "Peer play: ${data.optString("episodeUrl")}")
            "pause" -> Log.i(TAG, "Peer pause @ ${data.optLong("positionMs")}")
            "seek" -> Log.i(TAG, "Peer seek @ ${data.optLong("positionMs")}")
        }
    }

    fun stop() {
        _isEnabled.value = false
        _status.value = "Desconectado"
        LinkServer.removeListener(serverListener)
        // El host de la app mantiene el servidor para el control remoto;
        // solo se detiene si nadie lo usa.
        if (!KarinLinkHost.isRunning) {
            LinkServer.stop()
        }
        unregisterNsd()
        discoveryManager.destroy()
        linkClient.shutdown()
    }

    fun connectToDevice(device: DiscoveryManager.DiscoveredDevice) {
        _status.value = "Conectando a ${device.displayName}..."
        linkClient.connect(device.host, device.port, deviceId, deviceName)
    }

    fun shareEpisode(
        title: String,
        episodeTitle: String,
        episodeUrl: String,
        siteName: String,
        embedUrl: String = ""
    ) {
        val payload = org.json.JSONObject().apply {
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
        pendingShareJson = payload
        broadcast("sync", payload)
    }

    /** Sends a message to the peer connected via the outgoing client link. */
    private fun broadcast(type: String, payload: org.json.JSONObject) {
        linkClient.sendJson(type, payload)
    }

    private fun registerNsd(port: Int) {
        try {
            discoveryManager.registerService(port, deviceId, deviceName)
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