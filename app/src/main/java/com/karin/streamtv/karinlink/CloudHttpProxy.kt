package com.karin.streamtv.karinlink

import android.util.Log
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Servidor HTTP local ultra-ligero que actúa como *proxy* para streams de nube
 * (Google Drive, Dropbox). ExoPlayer simplemente recibe una URL http://127.0.0.1
 * y el proxy traduce la petición al proveedor añadiendo la cabecera de
 * autenticación (Bearer token) y reenviando Range para permitir seek.
 *
 * Se inicia bajo demanda la primera vez que se reproduce un archivo de nube y
 * se detiene cuando deja de haber conexiones activas.
 */
object CloudHttpProxy {

    private const val TAG = "CloudHttpProxy"

    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var boundPort = 0

    val isRunning: Boolean get() = running.get()
    val port: Int get() = boundPort

    @Synchronized
    fun startIfNotRunning(): Int {
        if (running.get()) return boundPort
        try {
            serverSocket = ServerSocket(0)
            boundPort = serverSocket?.localPort ?: 0
            running.set(true)
            serverThread = thread(name = "CloudHttpProxy") { acceptLoop() }
            Log.i(TAG, "CloudHttpProxy started on port $boundPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy: ${e.message}")
        }
        return boundPort
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                thread(name = "CloudHttpProxyConn", isDaemon = true) { handle(socket) }
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

            val ref = parseRef(refStr) ?: run {
                respondError(output, 400, "Ref inválido")
                return
            }
            val client = CloudClients.forProvider(ref.provider) ?: run {
                respondError(output, 500, "Proveedor no soportado")
                return
            }
            val token = CloudTokenStore.getToken(ref.provider)
            if (token.isNullOrBlank()) {
                respondError(output, 401, "Sin token para ${ref.provider.displayName}")
                return
            }

            val rangeHeader = headers["range"]
            var start = 0L
            var end = -1L          // -1 → "desde start hasta EOF" (longitud desconocida)
            var status = "200 OK"
            var contentRange: String? = null

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val spec = rangeHeader.removePrefix("bytes=").trim()
                val dash = spec.indexOf('-')
                val startSpec = if (dash > 0) spec.substring(0, dash).toLongOrNull() else null
                if (startSpec != null) {
                    start = startSpec
                    val endSpec = spec.substring(dash + 1).toLongOrNull()
                    end = if (endSpec != null) endSpec else -1L
                    status = "206 Partial Content"
                } else if (dash == 0) {
                    val suffix = spec.substring(1).toLongOrNull() ?: 0L
                    val total = client.length(ref, token)
                    if (total > 0) {
                        start = (total - suffix).coerceAtLeast(0L)
                        end = total - 1
                        status = "206 Partial Content"
                    }
                }
            }

            // Si el rango es "hasta EOF" y podemos conocer el total, acotarlo.
            if (end == -1L) {
                val total = client.length(ref, token)
                if (total > 0) end = total - 1
            }

            val streamResult = if (end >= 0) client.openStream(ref, token, start, end)
                               else client.openStream(ref, token, start, -1L)

            when (streamResult) {
                is CloudResult.Stream -> {
                    val length = streamResult.length
                    contentRange = if (status == "206 Partial Content" && length >= 0) {
                        val total = client.length(ref, token).takeIf { it > 0 } ?: (start + length)
                        "bytes $start-${start + length - 1}/$total"
                    } else null
                    try {
                        val header = buildString {
                            append("HTTP/1.1 $status\r\n")
                            append("Content-Type: video/mp4\r\n")
                            append("Accept-Ranges: bytes\r\n")
                            if (length >= 0) append("Content-Length: $length\r\n")
                            if (contentRange != null) append("Content-Range: $contentRange\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Connection: close\r\n")
                            append("\r\n")
                        }
                        output.write(header.toByteArray(Charsets.UTF_8))
                        output.flush()
                        streamResult.stream.use { s ->
                            val buf = ByteArray(64 * 1024)
                            var remaining = length // -1 → leer hasta EOF
                            while (remaining != 0L) {
                                if (socket.isClosed) break
                                val toRead = if (remaining > 0) minOf(buf.size.toLong(), remaining).toInt() else buf.size
                                val n = s.read(buf, 0, toRead)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                output.flush()
                                if (remaining > 0) remaining -= n
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                is CloudResult.Error -> respondError(output, 500, streamResult.message)
                else -> respondError(output, 500, "Error desconocido")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connection error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Obtiene una URL HTTP local que proxy el archivo de nube referenciado
     * (para pasarla a ExoPlayer, que reproduce HTTP con Range).
     */
    fun proxyUrl(ref: CloudRef): String {
        val port = startIfNotRunning()
        val serialized = serializeRef(ref)
        return "http://127.0.0.1:$port/file?ref=${android.net.Uri.encode(serialized)}"
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        boundPort = 0
        serverThread = null
        Log.i(TAG, "CloudHttpProxy stopped")
    }

    private fun serializeRef(ref: CloudRef): String = ref.toJson().toString()

    private fun parseRef(str: String): CloudRef? {
        return try {
            val decoded = URLDecoder.decode(str, "UTF-8")
            CloudRef.fromJson(JSONObject(decoded))
        } catch (_: Exception) {
            null
        }
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

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Error"
    }
}
