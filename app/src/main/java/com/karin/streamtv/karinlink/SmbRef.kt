package com.karin.streamtv.karinlink

import android.net.Uri
import org.json.JSONObject

/**
 * Referencia a un recurso compartido SMB (Windows network share) o a una
 * carpeta/archivo dentro de él.
 *
 * Ejemplos de uso:
 *  - Comentario raíz:    smb://192.168.1.10/
 *  - Listar shares:      smb://192.168.1.10/<share>/
 *  - Archivo concreto:   smb://192.168.1.10/Users/Public/video.mkv
 */
data class SmbRef(
    val deviceName: String,
    val host: String,
    val port: Int = 445,
    val share: String,        // nombre del share o "" si aún no se ha seleccionado
    val remotePath: String,   // ruta dentro del share (ej. "/Anime/Series/")
    val user: String? = null,
    val password: String? = null,
    val domain: String? = null
) {

    val baseUrl: String get() = "smb://$host:$port"

    /** Ruta SMB completa (para jcifs-ng): smb://host[:port]/share/remotePath */
    val smbUrl: String
        get() {
            val auth = if (user != null && password != null) {
                val cred = if (domain != null) "$domain;$user:$password" else "$user:$password"
                "$cred@"
            } else ""
            val portPart = if (port != 445 && port > 0) ":$port" else ""
            return "smb://$auth$host$portPart/$share${remotePath}"
        }

    /** Host:port legible en la UI */
    val hostPort: String get() = if (port != 0 && port != 445) "$host:$port" else host

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("deviceName", deviceName)
            put("host", host)
            put("port", port)
            put("share", share)
            put("remotePath", remotePath)
            user?.let { put("user", it) }
            password?.let { put("password", it) }
            domain?.let { put("domain", it) }
        }
    }

    companion object {
        fun fromJson(o: JSONObject): SmbRef? {
            val host = o.optString("host", "")
            if (host.isBlank()) return null
            return SmbRef(
                deviceName = o.optString("deviceName", host),
                host = host,
                port = o.optInt("port", 445),
                share = o.optString("share", ""),
                remotePath = o.optString("remotePath", "/"),
                user = if (o.has("user") && !o.isNull("user")) o.optString("user") else null,
                password = if (o.has("password") && !o.isNull("password")) o.optString("password") else null,
                domain = if (o.has("domain") && !o.isNull("domain")) o.optString("domain") else null
            )
        }
    }
}
