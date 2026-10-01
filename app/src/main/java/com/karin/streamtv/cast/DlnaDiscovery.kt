package com.karin.streamtv.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Descubrimiento de receptores DLNA por SSDP.
 *
 * M-SEARCH en multicast a 239.255.255.250:1900 y recolección de las
 * respuestas unicast, que traen una cabecera LOCATION con la URL de la
 * descripción XML del dispositivo. De ahí se lee el controlURL de AVTransport.
 */
object DlnaDiscovery {

    private const val TAG = "DlnaDiscovery"
    private const val MAX_RENDERERS = 12

    /** Cliente propio: el de `Http` espera 30s y aquí todo es de red local. */
    private val lanClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Receptores DLNA disponibles en la red; vacío si no responde nadie. */
    suspend fun discover(timeoutMs: Long = 4000L): List<DlnaProtocol.Renderer> =
        withContext(Dispatchers.IO) {
            val locations = LinkedHashSet<String>()
            runCatching { search(timeoutMs, locations) }
                .onFailure { Log.w(TAG, "SSDP search failed: ${it.message}") }

            val renderers = ArrayList<DlnaProtocol.Renderer>()
            for (location in locations.take(MAX_RENDERERS)) {
                val renderer = fetchRenderer(location) ?: continue
                if (renderers.none { it.controlUrl == renderer.controlUrl }) {
                    renderers.add(renderer)
                }
            }
            Log.d(TAG, "Found ${renderers.size} renderer(s)")
            renderers
        }

    private fun search(timeoutMs: Long, locations: MutableSet<String>) {
        val deadline = System.currentTimeMillis() + timeoutMs
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            val group = InetAddress.getByName(DlnaProtocol.SSDP_HOST)
            // Un MediaRenderer concreto y, por si acaso, la búsqueda general:
            // algunos fabricantes solo responden a ssdp:all.
            val payloads = listOf(
                DlnaProtocol.msearch(DlnaProtocol.MEDIA_RENDERER),
                DlnaProtocol.msearch("ssdp:all"),
            )
            for (payload in payloads) {
                val bytes = payload.toByteArray(Charsets.UTF_8)
                runCatching {
                    socket.send(DatagramPacket(bytes, bytes.size, group, DlnaProtocol.SSDP_PORT))
                }.onFailure { Log.w(TAG, "M-SEND failed: ${it.message}") }
            }

            val buffer = ByteArray(2048)
            var misses = 0
            while (System.currentTimeMillis() < deadline && misses < 12) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    misses = 0
                } catch (_: Exception) {
                    misses++
                    continue
                }
                val text = String(buffer, 0, packet.length, Charsets.UTF_8)
                val location = DlnaProtocol.locationOf(DlnaProtocol.parseSsdpMessage(text))
                if (location != null) locations.add(location)
            }
        }
    }

    /** Descarga la descripción del dispositivo y extrae el AVTransport. */
    private fun fetchRenderer(location: String): DlnaProtocol.Renderer? = try {
        val request = Request.Builder().url(location).get().build()
        lanClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            DlnaProtocol.parseRenderer(body, location)
        }
    } catch (e: Exception) {
        Log.d(TAG, "Cannot read description at $location: ${e.message}")
        null
    }

    /** URL local a la que un receptor puede acceder (para debug). */
    fun describe(renderer: DlnaProtocol.Renderer): String =
        "${renderer.name} (${renderer.manufacturer}) -> ${renderer.controlUrl}"
}

/** Alias corto para usar desde pantallas. */
typealias DlnaDevice = DlnaProtocol.Renderer
