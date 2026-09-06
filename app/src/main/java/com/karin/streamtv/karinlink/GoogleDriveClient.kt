package com.karin.streamtv.karinlink

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente de Google Drive API v3.
 *
 * El usuario debe aportar un token de acceso de OAuth2 (Authorization: Bearer)
 * obtenido para su propia cuenta. Con ese token la app puede listar "My Drive"
 * y descargar archivos por el endpoint media (que soporta cabecera Range).
 */
object GoogleDriveClient : CloudClient {

    override val provider: CloudProvider = CloudProvider.GOOGLE_DRIVE

    private const val API = "https://www.googleapis.com/drive/v3"
    private const val MEDIA = "https://www.googleapis.com/drive/v3"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private fun rootFolderId(ref: CloudRef): String =
        if (ref.folderId.isBlank() || ref.folderId == "root") "root" else ref.folderId

    override fun list(ref: CloudRef, token: String): CloudResult {
        return try {
            val folderId = rootFolderId(ref)
            val url = buildString {
                append("$API/files")
                append("?q=")
                append(UriQuery.encode("'$folderId' in parents and trashed=false"))
                append("&fields=files(id,name,mimeType,size)%2CnextPageToken")
                append("&orderBy=folder,name")
                append("&pageSize=1000")
                append("&includeItemsFromAllDrives=true")
                append("&supportsAllDrives=true")
            }
            val folders = mutableListOf<CloudEntry>()
            val files = mutableListOf<CloudEntry>()
            var pageToken: String? = ""
            var first = true

            while (first || !pageToken.isNullOrBlank()) {
                first = false
                var u = url
                if (!pageToken.isNullOrBlank()) u += "&pageToken=${UriQuery.encode(pageToken)}"

                val request = Request.Builder()
                    .url(u)
                    .header("Authorization", "Bearer $token")
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return CloudResult.Error("Google Drive: ${resp.code} (${bodyError(resp.body?.string())})")
                    }
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val next = json.optString("nextPageToken", "")
                    pageToken = if (next.isBlank()) null else next
                    val arr = json.optJSONArray("files") ?: org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id", "")
                        val mime = o.optString("mimeType", "")
                        val name = o.optString("name", "")
                        if (id.isBlank() || name.isBlank()) continue
                        val isDir = mime == "application/vnd.google-apps.folder"
                        val entry = CloudEntry(
                            name = name,
                            folderId = folderId,
                            fileId = id,
                            isDir = isDir,
                            sizeBytes = o.optLong("size", 0L)
                        )
                        if (isDir) folders.add(entry) else files.add(entry)
                    }
                }
            }
            CloudResult.Entries(folders, files)
        } catch (e: Exception) {
            CloudResult.Error("Google Drive: ${e.message ?: "error desconocido"}")
        }
    }

    override fun length(ref: CloudRef, token: String): Long {
        return try {
            val url = "$MEDIA/files/${UriQuery.encode(ref.fileId)}?alt=media&supportsAllDrives=true"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { resp ->
                val cr = resp.header("Content-Range") ?: return -1L
                val total = cr.substringAfter('/').trim().toLongOrNull() ?: -1L
                total
            }
        } catch (e: Exception) {
            -1L
        }
    }

    override fun openStream(ref: CloudRef, token: String, start: Long, end: Long): CloudResult {
        return try {
            val url = "$MEDIA/files/${UriQuery.encode(ref.fileId)}?alt=media&supportsAllDrives=true"
            val range = if (end >= 0) "bytes=$start-$end" else "bytes=$start-"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Range", range)
                .build()
            val resp = client.newCall(request).execute()
            if (!resp.isSuccessful) {
                val raw = resp.body?.string()
                val detail = if (raw.isNullOrBlank()) "sin detalle" else bodyError(raw)
                resp.close()
                return CloudResult.Error("Google Drive: ${resp.code} ($detail)")
            }
            val body = resp.body
            if (body == null) {
                resp.close()
                return CloudResult.Error("Google Drive: respuesta vacía")
            }
            val cr = resp.header("Content-Range") ?: ""
            val total = cr.substringAfter('/', "").trim().toLongOrNull()
            val length = when {
                total != null && end >= 0 -> (end - start + 1).coerceAtMost(total - start)
                total != null -> total - start
                end >= 0 -> end - start + 1
                else -> -1L
            }
            CloudResult.Stream(body.byteStream(), length)
        } catch (e: Exception) {
            CloudResult.Error("Google Drive: ${e.message ?: "error desconocido"}")
        }
    }

    private fun bodyError(body: String?): String {
        if (body.isNullOrBlank()) return "sin detalle"
        return try {
            val j = JSONObject(body)
            j.optJSONObject("error")?.optString("message") ?: j.optString("message", body)
        } catch (_: Exception) {
            body
        }
    }

    private object UriQuery {
        fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    }
}
