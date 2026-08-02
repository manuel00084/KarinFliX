package com.karin.streamtv.karinlink

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LinkServer {

    companion object {
        private const val TAG = "LinkServer"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    interface MessageHandler {
        fun onPeerConnected(deviceId: String, deviceName: String)
        fun onPeerDisconnected(deviceId: String)
        fun onSyncCommand(deviceId: String, command: String, data: JSONObject)
        fun onPlayCommand(deviceId: String, episodeUrl: String, positionMs: Long)
        fun onPauseCommand(deviceId: String, positionMs: Long)
        fun onSeekCommand(deviceId: String, positionMs: Long)
        fun onHistoryCommand(deviceId: String, historyJson: String)
    }

    private var handler: MessageHandler? = null

    fun setHandler(handler: MessageHandler) {
        this.handler = handler
    }

    fun connect(host: String, port: Int, deviceId: String, deviceName: String) {
        val request = Request.Builder()
            .url("ws://$host:$port/ws")
            .header("X-Device-Id", deviceId)
            .header("X-Device-Name", deviceName)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to $host:$port")
                sendJson("hello", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("deviceName", deviceName)
                    put("appVersion", "1.0")
                })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    val data = json.optJSONObject("data") ?: JSONObject()
                    handleMessage(type, data)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.i(TAG, "Connection closing: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Connection closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed: ${t.message}")
            }
        })
    }

    private fun handleMessage(type: String, data: JSONObject) {
        val deviceId = data.optString("deviceId", "unknown")
        when (type) {
            "hello" -> handler?.onPeerConnected(deviceId, data.optString("deviceName", "Unknown"))
            "play" -> handler?.onPlayCommand(deviceId, data.optString("episodeUrl"), data.optLong("positionMs"))
            "pause" -> handler?.onPauseCommand(deviceId, data.optLong("positionMs"))
            "seek" -> handler?.onSeekCommand(deviceId, data.optLong("positionMs"))
            "sync" -> handler?.onSyncCommand(deviceId, "sync", data)
            "history" -> handler?.onHistoryCommand(deviceId, data.optString("historyJson", "[]"))
            "leave" -> handler?.onPeerDisconnected(deviceId)
        }
    }

    fun sendJson(type: String, data: JSONObject) {
        val message = JSONObject().apply {
            put("type", type)
            put("data", data)
        }
        webSocket?.send(message.toString())
    }

    fun broadcastPlay(episodeUrl: String, positionMs: Long, deviceId: String) {
        sendJson("play", JSONObject().apply {
            put("deviceId", deviceId)
            put("episodeUrl", episodeUrl)
            put("positionMs", positionMs)
        })
    }

    fun broadcastPause(positionMs: Long, deviceId: String) {
        sendJson("pause", JSONObject().apply {
            put("deviceId", deviceId)
            put("positionMs", positionMs)
        })
    }

    fun broadcastSeek(positionMs: Long, deviceId: String) {
        sendJson("seek", JSONObject().apply {
            put("deviceId", deviceId)
            put("positionMs", positionMs)
        })
    }

    fun sendHistory(historyJson: String) {
        sendJson("history", JSONObject().apply {
            put("historyJson", historyJson)
        })
    }

    fun disconnect() {
        sendJson("leave", JSONObject())
        webSocket?.close(1000, "User left")
        webSocket = null
    }

    fun shutdown() {
        disconnect()
    }
}
