package com.karin.streamtv.cast

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servidor HTTP que sirve UN fichero local hasta que se cierra.
 *
 * Un receptor DLNA no puede abrir `/storage/...`: solo sabe bajar URLs. Para
 * emitir un vídeo del teléfono hay que ponerlo en la red. Es un servidor
 * mínimo con soporte de Range porque las TVs piden el rango antes de empezar
 * (y sin 206 no hacen seek ni a veces arrancan).
 */
class EphemeralMediaServer(private val file: File) {

    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /**
     * Ruta con la que se sirve el fichero, generada en cada arranque. No es
     * una contraseña: es para que la URL no sea un nombre previsible que
     * cualquiera de la red pueda pedir a mano mientras la emisión dura.
     */
    var path: String? = null
        private set

    /** Arranca y devuelve la URL que el receptor debe reproducir, o null. */
    fun start(): String? {
        if (!file.isFile) {
            Log.w(TAG, "Not a file: ${file.path}")
            return null
        }
        val ip = lanAddress() ?: run {
            Log.w(TAG, "No LAN address found")
            return null
        }
        val socket = runCatching {
            ServerSocket().apply { bind(InetSocketAddress(0), 8) }
        }.getOrElse {
            Log.w(TAG, "bind failed: ${it.message}")
            return null
        }
        server = socket
        val route = "/" + randomToken()
        path = route
        running.set(true)
        thread = Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ serve(client, route) }, "cast-media").apply { isDaemon = true }.start()
            }
        }, "cast-media-accept").apply {
            isDaemon = true
            start()
        }
        val url = "http://$ip:${socket.localPort}$route"
        Log.d(TAG, "Serving ${file.name} at $url")
        return url
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        runCatching { thread?.interrupt() }
        server = null
        thread = null
        path = null
        Log.d(TAG, "Stopped")
    }

    private fun serve(socket: Socket, route: String) {
        try {
            socket.use { s ->
                s.soTimeout = 30_000
                val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val sep = line.indexOf(':')
                    if (sep <= 0) continue
                    headers[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
                }
                val method = requestLine.substringBefore(' ')
                val target = requestLine.substringAfter(' ').substringBefore(' ')
                when {
                    target.substringBefore('?') != route -> write(s, 404, "Not Found", null, null, false)
                    method != "GET" && method != "HEAD" ->
                        write(s, 405, "Method Not Allowed", null, null, false)
                    else -> writeMedia(s, headers["range"], method == "HEAD")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "client gone: ${e.message}")
        }
    }

    private fun writeMedia(socket: Socket, rangeHeader: String?, headOnly: Boolean) {
        val total = file.length()
        val wantsRange = !rangeHeader.isNullOrBlank()
        val parsed = if (wantsRange) parseRange(rangeHeader, total) else null
        if (wantsRange && parsed == null) {
            write(
                socket, 416, "Range Not Satisfiable",
                "bytes */$total".toByteArray(Charsets.ISO_8859_1),
                "text/plain; charset=utf-8", headOnly,
                extraHeaders = listOf("Content-Range: bytes */$total"),
            )
            return
        }

        val (start, end, partial) = parsed ?: Triple(0L, total - 1, false)
        val length = end - start + 1
        val status = if (partial) 206 else 200
        val reason = if (partial) "Partial Content" else "OK"
        val extra = mutableListOf(
            "Accept-Ranges: bytes",
            "Content-Type: ${contentType(file.name)}",
        )
        if (partial) extra.add("Content-Range: bytes $start-$end/$total")

        try {
            val out = socket.getOutputStream()
            val header = StringBuilder()
                .append("HTTP/1.1 $status $reason\r\n")
                .append("Content-Length: $length\r\n")
                .append("Connection: close\r\n")
            extra.forEach { header.append(it).append("\r\n") }
            header.append("\r\n")
            out.write(header.toString().toByteArray(Charsets.ISO_8859_1))
            if (headOnly) {
                out.flush()
                return
            }
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(BUFFER)
                var remaining = length
                while (remaining > 0) {
                    val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
            out.flush()
        } catch (e: Exception) {
            Log.d(TAG, "send failed: ${e.message}")
        }
    }

    private fun write(
        socket: Socket,
        status: Int,
        reason: String,
        body: ByteArray?,
        contentType: String?,
        headOnly: Boolean,
        extraHeaders: List<String> = emptyList(),
    ) {
        runCatching {
            val out = socket.getOutputStream()
            val header = StringBuilder("HTTP/1.1 $status $reason\r\n")
            header.append("Connection: close\r\n")
            contentType?.let { header.append("Content-Type: ").append(it).append("\r\n") }
            extraHeaders.forEach { header.append(it).append("\r\n") }
            header.append("Content-Length: ").append(body?.size ?: 0).append("\r\n\r\n")
            out.write(header.toString().toByteArray(Charsets.ISO_8859_1))
            if (!headOnly) body?.let { out.write(it) }
            out.flush()
        }
    }

    companion object {
        private const val TAG = "EphemeralMedia"
        private const val BUFFER = 64 * 1024

        /** 128 bits de aleatoriedad en hexadecimal; el prefijo evita confusiones. */
        private fun randomToken(): String {
            val bytes = ByteArray(16)
            runCatching { java.security.SecureRandom().nextBytes(bytes) }
                .onFailure { repeat(bytes.size) { bytes[it] = (it * 31).toByte() } }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** `bytes=a-b`, `bytes=a-` o `bytes=-n`. Null si no hay Range. */
        internal fun parseRange(header: String?, total: Long): Triple<Long, Long, Boolean>? {
            if (header.isNullOrBlank() || total <= 0) return null
            if (!header.startsWith("bytes=", ignoreCase = true)) return null
            val spec = header.substringAfter('=').split(",").first().trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val rawStart = spec.substring(0, dash).trim()
            val rawEnd = spec.substring(dash + 1).trim()

            val start: Long
            val end: Long
            if (rawStart.isEmpty()) {
                // Suffix: los últimos N bytes.
                val suffix = rawEnd.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                start = (total - suffix).coerceAtLeast(0)
                end = total - 1
            } else {
                start = rawStart.toLongOrNull() ?: return null
                end = if (rawEnd.isEmpty()) {
                    total - 1
                } else {
                    (rawEnd.toLongOrNull() ?: return null).coerceAtMost(total - 1)
                }
            }
            if (start < 0 || start > end || start >= total) return null
            return Triple(start, end, true)
        }

        internal fun contentType(name: String): String = when {
            name.endsWith(".m3u8", true) -> "application/vnd.apple.mpegurl"
            name.endsWith(".mpd", true) -> "application/dash+xml"
            name.endsWith(".webm", true) -> "video/webm"
            name.endsWith(".mkv", true) -> "video/x-matroska"
            name.endsWith(".mp3", true) -> "audio/mpeg"
            name.endsWith(".m4a", true) -> "audio/mp4"
            else -> "video/mp4"
        }

        /** IP IPv4 no local de la red activa; null si no hay (sin Wi-Fi). */
        internal fun lanAddress(): String? {
            val candidates = ArrayList<String>()
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { nif ->
                    if (!nif.isUp || nif.isLoopback) return@forEach
                    nif.inetAddresses?.toList().orEmpty().forEach { addr ->
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            candidates.add(addr.hostAddress ?: return@forEach)
                        }
                    }
                }
            }
            // La primera suele ser la Wi-Fi; descartar la de Docker/Hyper-V si aparece.
            return candidates.firstOrNull { !it.startsWith("172.17.") }
                ?: candidates.firstOrNull()
        }
    }
}
