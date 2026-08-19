package com.karin.streamtv.karinlink

import android.util.Log
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Servidor HTTP local ultra-ligero que actúa como *proxy* para streams SMB.
 *
 * ExoPlayer no entiende el protocolo SMB, pero sí reproduce HTTP con soporte
 * Range. Este proxy abre un socket TCP en un puerto efímero, y cuando llega
 * una petición GET /file?ref=<ref> abre el stream SMB correspondiente y lo
 * reenvía al cliente con cabeceras Range/Accept-Ranges para permitir seek.
 *
 * El proxy se inicia bajo demanda (la primera vez que se necesita reproducir
 * un archivo SMB) y se detiene cuando ya no hay conexiones activas.
 */
object SmbHttpProxy {

    private const val TAG = "SmbHttpProxy"

    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var boundPort = 0

    val isRunning: Boolean get() = running.get()
    val port: Int get() = boundPort

    /**
     * Inicia el proxy si no lo está ya. Devuelve el puerto.
     */
    @Synchronized
    fun startIfNotRunning(): Int {
        if (running.get()) return boundPort
        try {
            serverSocket = ServerSocket(0)
            boundPort = serverSocket?.localPort ?: 0
            running.set(true)
            serverThread = thread(name = "SmbHttpProxy") { acceptLoop() }
            Log.i(TAG, "SmbHttpProxy started on port $boundPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy: ${e.message}")
        }
        return boundPort
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                thread(name = "SmbHttpProxyConn", isDaemon = true) {
                    handle(socket)
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Accept failed: ${e.message}")
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                respondError(output, 400, "Bad Request")
                return
            }
            val params = parseQuery(parts[1].substringAfter('?', ""))
            val refStr = params["ref"] ?: ""
            if (refStr.isEmpty()) {
                respondError(output, 404, "Not Found")
                return
            }

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            val ref = parseSmbRef(refStr)
            val smbResult = SmbClient.openStream(ref)
            if (smbResult is SmbClient.SmbResult.Error) {
                respondError(output, 500, smbResult.message)
                return
            }
            val stream = (smbResult as SmbClient.SmbResult.Stream).stream
            val fileLen = smbResult.length

            val rangeHeader = headers["range"]
            var start = 0L
            var end = fileLen - 1
            var status = "200 OK"
            var contentRange: String? = null

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val spec = rangeHeader.removePrefix("bytes=").trim()
                val dash = spec.indexOf('-')
                val startSpec = if (dash > 0) spec.substring(0, dash).toLongOrNull() else null
                if (startSpec != null) {
                    if (startSpec >= fileLen) {
                        respondRangeError(output, fileLen)
                        return
                    }
                    start = startSpec
                    val endSpec = if (dash >= 0) spec.substring(dash + 1).toLongOrNull() else null
                    end = endSpec?.coerceIn(start, fileLen - 1) ?: (fileLen - 1)
                    status = "206 Partial Content"
                    contentRange = "bytes $start-$end/$fileLen"
                } else if (dash == 0) {
                    val suffix = spec.substring(1).toLongOrNull() ?: 0L
                    start = (fileLen - suffix).coerceAtLeast(0L)
                    end = fileLen - 1
                    status = "206 Partial Content"
                    contentRange = "bytes $start-$end/$fileLen"
                }
            }

            try {
                val header = buildString {
                    append("HTTP/1.1 $status\r\n")
                    append("Content-Type: application/octet-stream\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: ${end - start + 1}\r\n")
                    if (contentRange != null) append("Content-Range: $contentRange\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                output.write(header.toByteArray(Charsets.UTF_8))
                output.flush()
                stream.use { s ->
                    var skipped = 0L
                    while (skipped < start) {
                        val n = s.skip(start - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                    val buf = ByteArray(64 * 1024)
                    var remaining = end - start + 1
                    while (remaining > 0) {
                        if (socket.isClosed) break
                        val n = s.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        output.write(buf, 0, n)
                        output.flush()
                        remaining -= n
                    }
                }
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connection error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Obtiene una URL HTTP local que proxya el archivo SMB referenciado.
     * Útil para pasar a ExoPlayer.
     */
    fun proxyUrl(ref: SmbRef): String {
        val port = startIfNotRunning()
        val serialized = serializeRef(ref)
        return "http://127.0.0.1:$port/file?ref=${android.net.Uri.encode(serialized)}"
    }

    private fun serializeRef(ref: SmbRef): String =
        "${ref.host}|${ref.port}|${ref.share}|${ref.remotePath}|${ref.user ?: ""}|${ref.password ?: ""}|${ref.domain ?: ""}"

    private fun parseSmbRef(str: String): SmbRef {
        val parts = str.split("|")
        return SmbRef(
            deviceName = parts.getOrElse(0) { "" },
            host = parts.getOrElse(0) { "127.0.0.1" },
            port = parts.getOrElse(1) { "445" }.toIntOrNull() ?: 445,
            share = parts.getOrElse(2) { "" },
            remotePath = parts.getOrElse(3) { "/" },
            user = parts.getOrElse(4) { "" }.takeIf { it.isNotBlank() },
            password = parts.getOrElse(5) { "" }.takeIf { it.isNotBlank() },
            domain = parts.getOrElse(6) { "" }.takeIf { it.isNotBlank() }
        )
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null
            else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun respondError(output: java.io.OutputStream, code: Int, msg: String) {
        val body = "{\"error\":\"$msg\"}".toByteArray(Charsets.UTF_8)
        val resp = "HTTP/1.1 $code ${statusText(code)}\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        try {
            output.write(resp.toByteArray(Charsets.UTF_8))
            output.write(body)
            output.flush()
        } catch (_: Exception) {}
    }

    private fun respondRangeError(output: java.io.OutputStream, fileLen: Long) {
        val resp = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                "Content-Range: bytes */$fileLen\r\n" +
                "Content-Length: 0\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        try {
            output.write(resp.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (_: Exception) {}
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    /**
     * Detiene el proxy. Se llama en onDestroy.
     */
    @Synchronized
    fun stop() {
        if (!running.get()) return
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        boundPort = 0
        serverThread = null
        Log.i(TAG, "SmbHttpProxy stopped")
    }
}
