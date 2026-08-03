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

    fun start() {
        if (_isEnabled.value) return
        _isEnabled.value = true
        _status.value = "Buscando dispositivos..."

        discoveryManager.startDiscovery()

        linkClient.setHandler(object : LinkClient.MessageHandler {
            override fun onPeerConnected(deviceId: String, deviceName: String) {
                Log.i(TAG, "Peer connected: $deviceName")
                _status.value = "Conectado a $deviceName"
                shareHistory()
            }

            override fun onPeerDisconnected(deviceId: String) {
                Log.i(TAG, "Peer disconnected: $deviceId")
                _status.value = "Dispositivo desconectado"
            }

            override fun onSyncCommand(deviceId: String, command: String, data: org.json.JSONObject) {
                val episodeUrl = data.optString("episodeUrl", "")
                val positionMs = data.optLong("positionMs", 0)
                roomManager.updateSync(RoomManager.SyncState(
                    episodeTitle = data.optString("episodeTitle", ""),
                    episodeUrl = episodeUrl,
                    siteName = data.optString("siteName", ""),
                    positionMs = positionMs,
                    durationMs = data.optLong("durationMs", 0),
                    isPlaying = data.optBoolean("isPlaying", false)
                ))
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
                if (historyJson.isNotBlank()) {
                    val entries = com.karin.streamtv.util.WatchHistory.getHistory()
                    val existingIds = entries.map { "${it.animeId}_${it.episodeNumber}" }.toSet()
                    try {
                        val array = org.json.JSONArray(historyJson)
                        for (i in 0 until array.length()) {
                            val obj = array.optJSONObject(i)
                            if (obj != null) {
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
                        }
                    } catch (_: Exception) {}
                }
            }
        })

        Log.i(TAG, "KARIN Link started - Device: $deviceName ($deviceId)")
    }

    fun stop() {
        _isEnabled.value = false
        _status.value = "Desconectado"
        discoveryManager.destroy()
        linkClient.shutdown()
        roomManager.leaveRoom()
    }

    fun connectToDevice(device: DiscoveryManager.DiscoveredDevice) {
        _status.value = "Conectando a ${device.displayName}..."
        linkClient.connect(device.host, device.port, deviceId, deviceName)
    }

    fun createRoom(name: String): RoomManager.Room {
        return roomManager.createRoom(name, deviceId, deviceName)
    }

    fun shareEpisode(title: String, episodeTitle: String, episodeUrl: String, siteName: String) {
        linkClient.sendJson("sync", org.json.JSONObject().apply {
            put("deviceId", deviceId)
            put("episodeTitle", episodeTitle)
            put("episodeUrl", episodeUrl)
            put("siteName", siteName)
            put("title", title)
        })
    }

    fun broadcastPlay(episodeUrl: String, positionMs: Long) {
        linkClient.broadcastPlay(episodeUrl, positionMs, deviceId)
    }

    fun broadcastPause(positionMs: Long) {
        linkClient.broadcastPause(positionMs, deviceId)
    }

    fun broadcastSeek(positionMs: Long) {
        linkClient.broadcastSeek(positionMs, deviceId)
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
        linkClient.sendHistory(array.toString())
    }
}
