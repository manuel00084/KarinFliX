package com.karin.streamtv.karinlink

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Referencia a un dispositivo remoto de la red KARIN Link y a una ruta dentro
 * de él. [remotePath] es una ruta absoluta del sistema de archivos del peer
 * (p. ej. "/storage/emulated/0/Videos").
 */
data class RemoteRef(
    val deviceName: String,
    val host: String,
    val port: Int,
    val token: String,
    val remotePath: String
) {
    val baseUrl: String get() = "http://$host:$port"

    fun listUrl(path: String = remotePath): String =
        "$baseUrl/fs/list?path=${Uri.encode(path)}&token=$token"

    fun searchUrl(query: String, path: String = remotePath): String =
        "$baseUrl/fs/search?q=${Uri.encode(query)}&path=${Uri.encode(path)}&token=$token"

    /** URL de streaming para ExoPlayer / MediaMetadataRetriever (soporta HTTP Range). */
    fun streamUrl(path: String = remotePath): String =
        "$baseUrl/fs/stream?path=${Uri.encode(path)}&token=$token"
}

/** Una entrada devuelta por el servidor remoto en /fs/list o /fs/search. */
data class RemoteFileInfo(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val count: Int
)

/** Cliente HTTP ligero para el explorador remoto de archivos. */
object RemoteFsClient {

    sealed class FsResult {
        data class Success(val entries: List<RemoteFileInfo>) : FsResult()
        data class Error(val message: String) : FsResult()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    fun list(ref: RemoteRef): FsResult = request(ref.listUrl())

    fun search(ref: RemoteRef, query: String): FsResult = request(ref.searchUrl(query))

    /**
     * Descarga un archivo remoto (vía /fs/stream) al destino local.
     * Devuelve true si la copia terminó correctamente.
     */
    fun download(ref: RemoteRef, dest: java.io.File): Boolean {
        return try {
            val request = Request.Builder()
                .url(ref.streamUrl())
                .header("User-Agent", "KarinFLiX/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun request(url: String): FsResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KarinFLiX/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return FsResult.Error("Error ${response.code} del dispositivo")
                }
                val text = response.body?.string() ?: return FsResult.Error("Respuesta vacía")
                val json = JSONObject(text)
                if (json.optString("status") != "ok") {
                    return FsResult.Error(json.optString("error", "Error desconocido"))
                }
                val array = json.optJSONArray("entries") ?: return FsResult.Success(emptyList())
                val entries = mutableListOf<RemoteFileInfo>()
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    entries.add(
                        RemoteFileInfo(
                            name = o.optString("name", ""),
                            path = o.optString("path", ""),
                            isDir = o.optBoolean("isDir", false),
                            size = o.optLong("size", 0L),
                            modified = o.optLong("modified", 0L),
                            count = o.optInt("count", 0)
                        )
                    )
                }
                FsResult.Success(entries)
            }
        } catch (e: Exception) {
            FsResult.Error("No se pudo conectar: ${e.message ?: "desconocido"}")
        }
    }
}
