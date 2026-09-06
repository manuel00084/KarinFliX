package com.karin.streamtv.karinlink

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cliente de la API de Dropbox.
 *
 * El usuario debe aportar un token de acceso (idealmente un token de larga
 * duración generado en la consola de desarrollador de Dropbox para su propia app).
 * Con ese token la app lista carpetas (files/list_folder) y, para reproducir,
 * obtiene un enlace temporal (files/get_temporary_link) que sirve el archivo por
 * HTTP estándar con soporte de Range (sin necesidad de cabeceras de auth).
 */
object DropboxClient : CloudClient {

    override val provider: CloudProvider = CloudProvider.DROPBOX

    private const val API = "https://api.dropboxapi.com/2"
    private const val CONTENT = "https://content.dropboxapi.com/2"
    private val JSON = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /** Cache de {ruta -> enlace temporal} para no regenerarlo en cada Range. */
    private val tempLinks = ConcurrentHashMap<String, String>()

    private fun folderPath(ref: CloudRef): String {
        val p = ref.folderId.trimEnd('/')
        return if (p.isBlank() || p == "/") "" else "/$p"
    }

    private fun filePath(ref: CloudRef): String {
        val p = ref.fileId.trimStart('/')
        return if (p.startsWith("/")) p else "/$p"
    }

    override fun list(ref: CloudRef, token: String): CloudResult {
        return try {
            val path = folderPath(ref)
            val folders = mutableListOf<CloudEntry>()
            val files = mutableListOf<CloudEntry>()

            var cursor: String? = null

            do {
                val jsonBody: String
                val endpoint: String
                if (cursor == null) {
                    endpoint = "$API/files/list_folder"
                    jsonBody = JSONObject().apply { put("path", path) }.toString()
                } else {
                    endpoint = "$API/files/list_folder/continue"
                    jsonBody = JSONObject().apply { put("cursor", cursor) }.toString()
                }

                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $token")
                    .post(jsonBody.toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return CloudResult.Error("Dropbox: ${resp.code} (${resp.body?.string()?.take(200)})")
                    }
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    cursor = if (json.optBoolean("has_more", false)) {
                        json.optString("cursor", null)
                    } else null

                    val arr = json.optJSONArray("entries") ?: org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("name", "")
                        val pathDisplay = o.optString("path_display", "")
                        if (name.isBlank() || pathDisplay.isBlank()) continue
                        val tag = o.optString(".tag", "file")
                        val isDir = tag == "folder"
                        val entry = CloudEntry(
                            name = name,
                            folderId = if (isDir) pathDisplay else path.trimStart('/'),
                            fileId = if (isDir) "" else pathDisplay,
                            isDir = isDir,
                            sizeBytes = if (isDir) 0L else o.optLong("size", 0L)
                        )
                        if (isDir) folders.add(entry) else files.add(entry)
                    }
                }
            } while (cursor != null)

            CloudResult.Entries(folders, files)
        } catch (e: Exception) {
            CloudResult.Error("Dropbox: ${e.message ?: "error desconocido"}")
        }
    }

    override fun length(ref: CloudRef, token: String): Long {
        return try {
            val link = tempLink(ref, token) ?: return -1L
            val request = Request.Builder()
                .url(link)
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { resp ->
                val cr = resp.header("Content-Range") ?: resp.header("content-range") ?: return -1L
                cr.substringAfter('/').trim().toLongOrNull() ?: -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    override fun openStream(ref: CloudRef, token: String, start: Long, end: Long): CloudResult {
        return try {
            val link = tempLink(ref, token) ?: return CloudResult.Error("Dropbox: no se pudo obtener enlace")
            val range = if (end >= 0) "bytes=$start-$end" else "bytes=$start-"
            val request = Request.Builder()
                .url(link)
                .header("Range", range)
                .build()
            val resp = client.newCall(request).execute()
            if (!resp.isSuccessful) {
                val msg = "Dropbox: ${resp.code}"
                resp.close()
                return CloudResult.Error(msg)
            }
            val body = resp.body
            if (body == null) {
                resp.close()
                return CloudResult.Error("Dropbox: respuesta vacía")
            }
            val cr = resp.header("Content-Range") ?: resp.header("content-range") ?: ""
            val total = cr.substringAfter('/', "").trim().toLongOrNull()
            val length = when {
                total != null && end >= 0 -> (end - start + 1).coerceAtMost(total - start)
                total != null -> total - start
                end >= 0 -> end - start + 1
                else -> -1L
            }
            CloudResult.Stream(body.byteStream(), length)
        } catch (e: Exception) {
            CloudResult.Error("Dropbox: ${e.message ?: "error desconocido"}")
        }
    }

    private fun tempLink(ref: CloudRef, token: String): String? {
        val path = filePath(ref)
        tempLinks[path]?.let { return it }
        return try {
            val body = JSONObject().apply { put("path", path) }.toString()
            val request = Request.Builder()
                .url("$API/files/get_temporary_link")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON))
                .build()
            val link = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string() ?: "{}").optString("link", "")
            }
            if (link.isBlank()) null
            else {
                tempLinks[path] = link
                link
            }
        } catch (e: Exception) {
            null
        }
    }
}
