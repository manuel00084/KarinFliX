package com.karin.streamtv.karinlink

import android.net.Uri

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