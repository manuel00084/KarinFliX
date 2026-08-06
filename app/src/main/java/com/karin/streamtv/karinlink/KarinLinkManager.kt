package com.karin.streamtv.karinlink

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class KarinLinkManager(private val context: Context) {

    companion object {
        private const val TAG = "KarinLinkManager"
        private const val PREFS_NAME = "karin_link"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
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

    val discoveryManager = DiscoveryManager(context)
    val roomManager = RoomManager()
    val linkClient = LinkClient()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    private val _status = MutableStateFlow("Desconectado")
    val status: StateFlow<String> = _status

    /** Called on the receiving device when a peer wants us to play an episode. */
    var onPlaybackRequest: ((episodeTitle: String, episodeUrl: String, embedUrl: String, siteName: String) -> Unit)? = null

    private var serverRegistered = false
    private val localIp: String by lazy { discoverLocalIp() }
    private var pendingShareJson: org.json.JSONObject? = null

    fun start() {
        if (_isEnabled.value) return
        _isEnabled.value = true
        _status.value = "Buscando dispositivos..."

        // Host server: allows this device to accept peers (rooms + sync).
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
                shareHistory()
                pendingShareJson?.let {
                    broadcast("sync", it)
                }
            }

            override fun onPeerDisconnected(deviceId: String) {
                Log.i(TAG, "Peer disconnected: $deviceId")
                _status.value = "Dispositivo desconectado"
            }

            override fun onSyncCommand(deviceId: String, command: String, data: org.json.JSONObject) {
                applySync(data)
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

            override fun onHistoryCommand(deviceId: String, historyJson: String) {
                Log.i(TAG, "History received from $deviceId")
                mergeHistory(historyJson)
            }
        })

        // Advertise on the LAN so other KarinFLiX devices can discover us.
        registerNsd(port)

        Log.i(TAG, "KARIN Link started - Device: $deviceName ($deviceId)")
    }

    private val serverListener: (roomId: String?, from: String?, type: String, data: org.json.JSONObject) -> Unit = { roomId, from, type, data ->
        when (type) {
            "hello" -> {
                // A new peer joined our room: push the pending share so they can play it.
                val ep = pendingShareJson
                if (ep != null && roomId != null) {
                    LinkServer.broadcast(roomId, "sync", ep, skip = null)
                }
            }
            "sync" -> {
                applySync(data)
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
            "history" -> mergeHistory(data.optString("historyJson", "[]"))
            "play" -> Log.i(TAG, "Peer play: ${data.optString("episodeUrl")}")
            "pause" -> Log.i(TAG, "Peer pause @ ${data.optLong("positionMs")}")
            "seek" -> Log.i(TAG, "Peer seek @ ${data.optLong("positionMs")}")
        }
    }

    private fun applySync(data: org.json.JSONObject) {
        roomManager.updateSync(RoomManager.SyncState(
            episodeTitle = data.optString("episodeTitle", ""),
            episodeUrl = data.optString("episodeUrl", ""),
            siteName = data.optString("siteName", ""),
            positionMs = data.optLong("positionMs", 0),
            durationMs = data.optLong("durationMs", 0),
            isPlaying = data.optBoolean("isPlaying", false)
        ))
    }

    fun stop() {
        _isEnabled.value = false
        _status.value = "Desconectado"
        LinkServer.removeListener(serverListener)
        LinkServer.stop()
        unregisterNsd()
        discoveryManager.destroy()
        linkClient.shutdown()
        roomManager.leaveRoom()
    }

    fun connectToDevice(device: DiscoveryManager.DiscoveredDevice) {
        _status.value = "Conectando a ${device.displayName}..."
        linkClient.connect(device.host, device.port, deviceId, deviceName)
    }

    fun createRoom(name: String): RoomManager.Room {
        val room = roomManager.createRoom(name, deviceId, deviceName)
        // Ensure the host server is up so peers can join by room.
        val port = LinkServer.port
        if (port > 0 && !serverRegistered) registerNsd(port)
        return room
    }

    /** Connects to a remote room (used when joining via QR / deep link). */
    fun joinRoom(roomId: String, host: String? = null, port: Int? = null): RoomManager.Room? {
        val room = roomManager.joinRoom(roomId, deviceId, deviceName)
        if (host != null && port != null && port > 0) {
            _status.value = "Conectando a la sala $roomId..."
            linkClient.connect(host, port, deviceId, deviceName)
        }
        return room
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

    fun broadcastPlay(episodeUrl: String, positionMs: Long) {
        val payload = org.json.JSONObject().apply {
            put("deviceId", deviceId)
            put("episodeUrl", episodeUrl)
            put("positionMs", positionMs)
        }
        broadcast("play", payload)
    }

    fun broadcastPause(positionMs: Long) {
        val payload = org.json.JSONObject().apply {
            put("deviceId", deviceId)
            put("positionMs", positionMs)
        }
        broadcast("pause", payload)
    }

    fun broadcastSeek(positionMs: Long) {
        val payload = org.json.JSONObject().apply {
            put("deviceId", deviceId)
            put("positionMs", positionMs)
        }
        broadcast("seek", payload)
    }

    /** Sends via the host server (room members) or the outgoing client link. */
    private fun broadcast(type: String, payload: org.json.JSONObject) {
        val roomId = roomManager.currentRoom.value?.id
        if (roomId != null && LinkServer.isRunning) {
            LinkServer.broadcast(roomId, type, payload)
        } else {
            linkClient.sendJson(type, payload)
        }
    }

    fun shareHistory() {
        val entries = com.karin.streamtv.util.WatchHistory.getHistory()
        val array = org.json.JSONArray()
        entries.forEach { entry ->
            array.put(org.json.JSONObject().apply {
                put("animeId", entry.animeId)
                put("episodeNumber", entry.episodeNumber)
                put("title", entry.title)
                put("siteName", entry.siteName)
                put("thumbnailUrl", entry.thumbnailUrl)
                put("episodeUrl", entry.episodeUrl)
                put("positionMs", entry.positionMs)
                put("durationMs", entry.durationMs)
                put("timestamp", entry.timestamp)
            })
        }
        val roomId = roomManager.currentRoom.value?.id
        if (roomId != null && LinkServer.isRunning) {
            LinkServer.broadcast(roomId, "history", org.json.JSONObject().apply {
                put("historyJson", array.toString())
            })
        } else {
            linkClient.sendHistory(array.toString())
        }
    }

    private fun mergeHistory(historyJson: String) {
        if (historyJson.isBlank()) return
        val entries = com.karin.streamtv.util.WatchHistory.getHistory()
        val existingIds = entries.map { "${it.animeId}_${it.episodeNumber}" }.toSet()
        try {
            val array = org.json.JSONArray(historyJson)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val animeId = obj.optString("animeId", "")
                val epNum = obj.optInt("episodeNumber", 0)
                val key = "${animeId}_${epNum}"
                if (key !in existingIds) {
                    com.karin.streamtv.util.WatchHistory.addEntry(
                        com.karin.streamtv.util.WatchHistory.HistoryEntry(
                            animeId = animeId,
                            episodeNumber = epNum,
                            title = obj.optString("title", ""),
                            siteName = obj.optString("siteName", ""),
                            thumbnailUrl = obj.optString("thumbnailUrl", ""),
                            episodeUrl = obj.optString("episodeUrl", ""),
                            positionMs = obj.optLong("positionMs", 0),
                            durationMs = obj.optLong("durationMs", 0),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (_: Exception) {}
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

    fun localIpAddress(): String = localIp

    fun getJoinUrl(roomId: String): String {
        val port = LinkServer.port
        return if (port > 0) "karinflinx://room/$roomId?host=$localIp&port=$port"
        else "karinflinx://room/$roomId"
    }

    private fun discoverLocalIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.let { nis ->
                val list = nis.toList()
                val candidate = list.firstOrNull { iface ->
                    iface.isUp && !iface.isLoopback && iface.name != "rmnet_data0"
                }
                val all = list.flatMap { iface -> iface.inetAddresses.toList() }
                val addr = all.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                addr?.hostAddress ?: "127.0.0.1"
            } ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}
