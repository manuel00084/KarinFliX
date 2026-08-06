package com.karin.streamtv.karinlink

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Minimal RFC 6455 WebSocket + HTTP server. KarinFLiX devices open a
 * [LinkServer] so other KarinFLiX instances (discovered via the NSD
 * service registered on the same port) can connect over `ws://host:port/ws`
 * and exchange sync/play/pause/seek/history messages. Text frames received
 * from any peer are rebroadcast to the other connected clients so playback
 * state stays in sync across the room.
 *
 * Plain HTTP endpoints:
 *  - GET /ws        -> WebSocket upgrade (peer sync)
 *  - GET /room/<id> -> join-room info (200 with room members)
 *  - POST /room/<id>-> join the room with {"deviceId","deviceName"} payload
 */
object LinkServer {

    private const val TAG = "LinkServer"
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC11B67"

    internal class Peer(val socket: Socket) {
        val input: InputStream get() = socket.getInputStream()
        val output: OutputStream get() = socket.getOutputStream()
        var deviceId: String? = null
        var deviceName: String? = null
        var roomId: String? = null
        var closed = false
    }

    private val peers = CopyOnWriteArrayList<Peer>()
    private val rooms = ConcurrentHashMap<String, MutableList<Peer>>()
    private val roomByPeer = ConcurrentHashMap<Peer, String>()
    private val listeners = CopyOnWriteArrayList<(roomId: String?, from: String?, type: String, data: org.json.JSONObject) -> Unit>()

    private var serverSocket: ServerSocket? = null
    private var running = false
    private var acceptThread: Thread? = null

    val port: Int get() = serverSocket?.localPort ?: 0
    val isRunning: Boolean get() = running
    val connectedPeers: Int get() = peers.size

    fun addListener(listener: (roomId: String?, from: String?, type: String, data: org.json.JSONObject) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (roomId: String?, from: String?, type: String, data: org.json.JSONObject) -> Unit) {
        listeners.remove(listener)
    }

    /** Starts the TCP listener on [inport] (0 = ephemeral). Returns the bound port. */
    @Synchronized
    fun start(inPort: Int = 0): Int {
        if (running) return port
        return try {
            serverSocket = ServerSocket(inPort)
            running = true
            acceptThread = thread(name = "KarinLinkServer") {
                Log.i(TAG, "Server listening on port ${serverSocket?.localPort}")
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        thread(name = "KarinLinkPeer", isDaemon = true) { handle(socket) }
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "Accept failed: ${e.message}")
                        break
                    }
                }
            }
            serverSocket?.localPort ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}")
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            0
        }
    }

    @Synchronized
    fun stop() {
        running = false
        acceptThread?.interrupt()
        runCatching { serverSocket?.close() }
        serverSocket = null
        peers.forEach { runCatching { it.socket.close() } }
        peers.clear()
        rooms.clear()
        roomByPeer.clear()
        listeners.clear()
        Log.i(TAG, "Server stopped")
    }

    /** Assigns a connecting peer to [roomId] and updates its handshake info. */
    internal fun assignPeer(peer: Peer, roomId: String, deviceId: String?, deviceName: String?) {
        if (!peers.contains(peer)) peers.add(peer)
        peer.deviceId = deviceId
        peer.deviceName = deviceName
        val old = roomByPeer.put(peer, roomId)
        if (old != null && old != roomId) rooms[old]?.remove(peer)
        rooms.getOrPut(roomId) { CopyOnWriteArrayList() }.apply { if (!contains(peer)) add(peer) }
        peer.roomId = roomId
        Log.i(TAG, "Peer ${deviceName ?: "unknown"} joined room $roomId (${roomSize(roomId)} members)")
    }

    internal fun removePeer(peer: Peer) {
        if (peer.closed) return
        peer.closed = true
        peers.remove(peer)
        roomByPeer.remove(peer)?.let { rooms[it]?.remove(peer) }
        runCatching { peer.socket.close() }
        Log.i(TAG, "Peer left")
    }

    fun roomSize(roomId: String): Int = roomByPeer.values.count { it == roomId }
    fun roomPeers(roomId: String): List<String> = rooms[roomId]?.mapNotNull { it.deviceName ?: it.deviceId } ?: emptyList()

    /** Sends a raw JSON message to every peer assigned to [roomId] except [skip]. */
    internal fun broadcast(roomId: String?, type: String, data: org.json.JSONObject, skip: Peer? = null) {
        val msg = org.json.JSONObject().apply {
            put("type", type)
            put("data", data)
        }.toString()
        val targets = if (roomId != null) rooms[roomId]?.toList() ?: emptyList() else peers.toList()
        targets.forEach { p ->
            if (p !== skip && !p.closed) {
                try { sendFrame(p, 0x1, msg) } catch (e: Exception) { removePeer(p) }
            }
        }
    }

    private fun sendFrame(peer: Peer, opcode: Int, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            len < 126 -> header.write(len)
            len < 65536 -> {
                header.write(126)
                header.write((len ushr 8) and 0xFF)
                header.write(len and 0xFF)
            }
            else -> {
                header.write(127)
                var l = len.toLong()
                for (i in 7 downTo 0) header.write(((l ushr (8 * i)) and 0xFF).toInt())
            }
        }
        synchronized(peer) {
            peer.output.write(header.toByteArray())
            peer.output.write(bytes)
            peer.output.flush()
        }
    }

    private fun handle(socket: Socket) {
        val peer = Peer(socket)
        try {
            val requestLine = readLine(peer.input) ?: run { runCatching { socket.close() }; return }
            val parts = requestLine.split(" ")
            val path = if (parts.size >= 2) parts[1] else "/"
            val method = if (parts.isNotEmpty()) parts[0] else "GET"

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(peer.input) ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            when (method) {
                "GET" -> {
                    if (path == "/ws") {
                        upgradeWebSocket(peer, headers)
                    } else if (path.startsWith("/room/")) {
                        val roomId = path.removePrefix("/room/")
                        val members = roomPeers(roomId)
                        httpReply(peer, "200 OK", org.json.JSONObject().apply {
                            put("room", roomId)
                            put("members", org.json.JSONArray(members))
                            put("size", members.size)
                        }.toString(), "application/json")
                    } else {
                        httpReply(peer, "200 OK", "{\"status\":\"ok\"}", "application/json")
                    }
                    closePeer(peer)
                }
                "POST" -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(contentLength)
                    if (contentLength > 0) readExactly(peer.input, body)
                    val roomId = path.removePrefix("/room/").takeIf { it.isNotBlank() }
                    if (roomId != null) {
                        val json = try { org.json.JSONObject(String(body, Charsets.UTF_8)) } catch (_: Exception) { org.json.JSONObject() }
                        assignPeer(peer, roomId, json.optString("deviceId").ifBlank { null }, json.optString("deviceName").ifBlank { null })
                        httpReply(peer, "200 OK", "{\"joined\":true,\"room\":\"$roomId\"}", "application/json")
                    } else {
                        httpReply(peer, "404 Not Found", "{\"error\":\"room required\"}", "application/json")
                    }
                }
                else -> httpReply(peer, "405 Method Not Allowed", "", "text/plain")
            }
        } catch (e: EOFException) {
            removePeer(peer)
        } catch (e: Exception) {
            Log.w(TAG, "Peer handling error: ${e.message}")
            removePeer(peer)
        }
    }

    private fun upgradeWebSocket(peer: Peer, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"] ?: run {
            httpReply(peer, "400 Bad Request", "missing Sec-WebSocket-Key", "text/plain")
            closePeer(peer)
            return
        }
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII))
        )
        val resp = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n")
            append("\r\n")
        }
        peer.output.write(resp.toByteArray(Charsets.US_ASCII))
        peer.output.flush()

        peer.deviceId = headers["x-device-id"]
        peer.deviceName = headers["x-device-name"]

        // A room may be declared in the handshake header so we can route
        // broadcasts even before the peer's first "hello" frame arrives.
        val handshakeRoom = headers["x-room-id"]?.takeIf { it.isNotBlank() }
        if (handshakeRoom != null) assignPeer(peer, handshakeRoom, peer.deviceId, peer.deviceName)

        // WebSocket connects don't carry a room until the peer says "hello".
        readFramesLoop(peer)
    }

    private fun readFramesLoop(peer: Peer) {
        while (!peer.closed && running) {
            val opcode = readFrame(peer) ?: break
            when (opcode) {
                0x8 -> { // close
                    sendClose(peer)
                    removePeer(peer)
                    return
                }
                0x9 -> { // ping -> pong (frame already consumed)
                    sendFrame(peer, 0xA, "")
                }
                0x1, 0x0 -> { /* handled below */ }
                else -> { /* binary / continuation ignored */ }
            }
            if (opcode != 0x1 && opcode != 0x0) continue
        }
        removePeer(peer)
    }

    /** Reads one complete text frame (opcode 0x1/0x0) and dispatches it. Returns the opcode. */
    private fun readFrame(peer: Peer): Int? {
        val b0 = readByte(peer.input) ?: return null
        val b1 = readByte(peer.input) ?: return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            val hi = readByte(peer.input) ?: return null
            val lo = readByte(peer.input) ?: return null
            length = ((hi shl 8) or lo).toLong()
        } else if (length == 127L) {
            length = 0L
            for (i in 0 until 8) {
                val b = readByte(peer.input) ?: return null
                length = (length shl 8) or b.toLong()
            }
        }

        val mask = ByteArray(4)
        if (masked) {
            for (i in 0 until 4) {
                val b = readByte(peer.input) ?: return null
                mask[i] = b.toByte()
            }
        }

        if (length > 10 * 1024 * 1024) throw EOFException() // safety cap

        val body = ByteArray(length.toInt())
        if (length > 0) readExactly(peer.input, body)
        if (masked) {
            for (i in body.indices) body[i] = (body[i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        if (opcode != 0x1 && opcode != 0x0) return opcode

        val text = String(body, Charsets.UTF_8)
        val json = try { org.json.JSONObject(text) } catch (_: Exception) { return opcode }
        val type = json.optString("type", "")
        val data = json.optJSONObject("data") ?: org.json.JSONObject()

        if (type == "hello") {
            peer.deviceId = data.optString("deviceId").ifBlank { peer.deviceId }
            peer.deviceName = data.optString("deviceName").ifBlank { peer.deviceName }
            val helloRoom = data.optString("roomId")
            if (peer.roomId == null && helloRoom.isNotBlank()) {
                assignPeer(peer, helloRoom, peer.deviceId, peer.deviceName)
            }
        }

        val roomId = peer.roomId
        if (roomId != null) broadcast(roomId, type, data, skip = peer)
        listeners.forEach { runCatching { it(roomId, peer.deviceId, type, data) } }
        return opcode
    }

    private fun sendClose(peer: Peer) {
        try { sendFrame(peer, 0x8, "") } catch (_: Exception) {}
    }

    private fun httpReply(peer: Peer, status: String, body: String, contentType: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val resp = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            synchronized(peer) {
                peer.output.write(resp.toByteArray(Charsets.US_ASCII))
                peer.output.write(bytes)
                peer.output.flush()
            }
        } catch (_: Exception) {}
    }

    private fun closePeer(peer: Peer) {
        removePeer(peer)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = readByte(input) ?: return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun readByte(input: InputStream): Int? {
        return try { input.read().takeIf { it != -1 } } catch (_: Exception) { null }
    }

    private fun readExactly(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = try { input.read(buf, off, buf.size - off) } catch (_: Exception) { -1 }
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun skip(input: InputStream, n: Long) {
        var remaining = n
        val tmp = ByteArray(512)
        while (remaining > 0) {
            val toRead = minOf(remaining, tmp.size.toLong()).toInt()
            val read = try { input.read(tmp, 0, toRead) } catch (_: Exception) { -1 }
            if (read < 0) throw EOFException()
            remaining -= read
        }
    }
}
