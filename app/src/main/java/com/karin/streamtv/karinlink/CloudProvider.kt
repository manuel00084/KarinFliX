package com.karin.streamtv.karinlink

import org.json.JSONObject

/**
 * Servicio de almacenamiento en la nube soportado por el explorador de archivos.
 * El acceso es "token propio": el usuario introduce su token/clave de API y la app
 * lo guarda localmente (no se sube a ningún servidor).
 */
enum class CloudProvider(val id: String, val displayName: String, val storageKey: String) {
    GOOGLE_DRIVE("gdrive", "Google Drive", "cloud_token_gdrive"),
    DROPBOX("dropbox", "Dropbox", "cloud_token_dropbox");

    companion object {
        fun fromId(id: String): CloudProvider? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

        fun toJson(p: CloudProvider): String = p.id
        fun fromJson(s: String): CloudProvider? = fromId(s)
    }
}

/**
 * Referencia a un archivo o carpeta dentro de un servicio en la nube.
 *
 * [folderId] identifica la ubicación dependiendo del proveedor:
 *  - Google Drive: el ID de la carpeta ("" = raíz "My Drive").
 *  - Dropbox: la ruta con '/' ("" = raíz "/").
 *
 * [fileId] identifica el archivo concreto para emitir el stream (Drive: file ID,
 * Dropbox: ruta '/ruta/archivo.ext'). Para carpetas [fileId] está vacío.
 */
data class CloudRef(
    val provider: CloudProvider,
    val name: String,       // nombre visible de la ubicación/archivo
    val folderId: String,   // ubicación padre (para listar)
    val fileId: String,     // archivo concreto (para reproducir); "" si es carpeta
    val path: String        // ruta legible para la UI
) {
    val isFolder: Boolean get() = fileId.isBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("provider", CloudProvider.toJson(provider))
        put("name", name)
        put("folderId", folderId)
        put("fileId", fileId)
        put("path", path)
    }

    companion object {
        fun fromJson(o: JSONObject): CloudRef? {
            val provider = CloudProvider.fromJson(o.optString("provider", "")) ?: return null
            return CloudRef(
                provider = provider,
                name = o.optString("name"),
                folderId = o.optString("folderId"),
                fileId = o.optString("fileId"),
                path = o.optString("path")
            )
        }
    }
}
