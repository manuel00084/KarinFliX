package com.karin.streamtv.util

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaExtractor {

    private const val TAG = "MegaExtractor"
    private const val API_URL = "https://g.api.mega.co.nz/cs"
    private val client = Http.client

    data class MegaCredentials(
        val fileId: String,
        val key: String,
        val isFolder: Boolean = false,
        val isOldFormat: Boolean = false
    )

    private data class DownloadInfo(
        val url: String,
        val fileSize: Long,
        val encAttrs: String? = null
    )

    fun extractVideoUrl(megaUrl: String, context: Context? = null): String? {
        try {
            Log.d(TAG, "Parsing Mega URL: $megaUrl")
            val creds = parseMegaUrl(megaUrl) ?: run {
                Log.w(TAG, "Failed to parse Mega URL")
                return null
            }
            Log.d(TAG, "FileID: ${creds.fileId}, isFolder: ${creds.isFolder}, oldFormat: ${creds.isOldFormat}")

            if (creds.isFolder) {
                Log.w(TAG, "Folder links not supported for video playback")
                return null
            }

            val rawKey = decodeBase64Url(creds.key)
            if (rawKey.size < 32) {
                Log.w(TAG, "Key too short: ${rawKey.size} bytes, need 32")
                return null
            }
            Log.d(TAG, "Raw key: ${rawKey.joinToString("") { "%02x".format(it) }}")

            val aesKey = ByteArray(16)
            for (i in 0 until 16) {
                aesKey[i] = (rawKey[i].toInt() xor rawKey[i + 16].toInt()).toByte()
            }
            Log.d(TAG, "AES key: ${aesKey.joinToString("") { "%02x".format(it) }}")

            val apiFileId = if (creds.isOldFormat) {
                val decrypted = decryptOldFormatFileHandle(creds.fileId, rawKey)
                if (decrypted != null) {
                    Log.d(TAG, "Decrypted old-format file handle: $decrypted")
                    decrypted.toString()
                } else {
                    Log.w(TAG, "Failed to decrypt old-format file handle, trying raw ID")
                    creds.fileId
                }
            } else {
                creds.fileId
            }
            Log.d(TAG, "API file ID: $apiFileId")

            val downloadInfo = getDownloadInfo(apiFileId) ?: run {
                Log.w(TAG, "Failed to get download info from Mega API (file may be expired)")
                return null
            }

            Log.d(TAG, "Download URL: ${downloadInfo.url.take(100)}")
            Log.d(TAG, "File size: ${downloadInfo.fileSize}")

            if (downloadInfo.fileSize > 500_000_000) {
                Log.w(TAG, "File too large for direct download: ${downloadInfo.fileSize}")
                return null
            }

            val iv = ByteArray(16)
            for (i in 0 until minOf(8, rawKey.size - 16)) {
                iv[i] = rawKey[i + 16]
            }

            if (downloadInfo.encAttrs != null) {
                try {
                    val decAttrs = aesEcbDecrypt(aesKey, decodeBase64Url(downloadInfo.encAttrs))
                    Log.d(TAG, "Decrypted attrs: ${decAttrs.size} bytes")
                    for (i in 0 until minOf(8, decAttrs.size)) {
                        iv[i + 8] = (iv[i + 8].toInt() xor decAttrs[i].toInt()).toByte()
                    }
                    Log.d(TAG, "IV after attr XOR: ${iv.joinToString("") { "%02x".format(it) }}")
                } catch (e: Exception) {
                    Log.w(TAG, "Attr decrypt failed, using base IV: ${e.message}")
                }
            } else {
                Log.w(TAG, "No encrypted attributes in API response, using base IV")
            }

            val tempFile = downloadAndDecrypt(downloadInfo.url, aesKey, iv, downloadInfo.fileSize, context) ?: run {
                Log.w(TAG, "Failed to download and decrypt Mega file")
                return null
            }

            Log.d(TAG, "Decrypted file saved to: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            return "file://${tempFile.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "Mega extraction failed: ${e.message}", e)
            return null
        }
    }

    private fun decryptOldFormatFileHandle(encryptedId: String, rawKey: ByteArray): Long? {
        try {
            val encIdBytes = decodeBase64Url(encryptedId)
            Log.d(TAG, "Encrypted ID bytes (${encIdBytes.size}): ${encIdBytes.joinToString("") { "%02x".format(it) }}")

            val aesKeyForHandle = ByteArray(16)
            System.arraycopy(rawKey, 0, aesKeyForHandle, 0, 8)

            val paddedInput = ByteArray(16)
            System.arraycopy(encIdBytes, 0, paddedInput, 0, minOf(encIdBytes.size, 16))

            val spec = SecretKeySpec(aesKeyForHandle, "AES")
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, spec)
            val decrypted = cipher.doFinal(paddedInput)

            var handle = 0L
            for (i in 0 until minOf(4, decrypted.size)) {
                handle = handle or ((decrypted[i].toLong() and 0xFF) shl (i * 8))
            }
            Log.d(TAG, "Decrypted handle: $handle (bytes: ${decrypted.take(4).joinToString("") { "%02x".format(it) }})")
            return handle
        } catch (e: Exception) {
            Log.w(TAG, "Old format decrypt failed: ${e.message}")
            return null
        }
    }

    private fun parseMegaUrl(url: String): MegaCredentials? {
        try {
            val cleanUrl = url.trim()

            val folderPattern = Regex("""mega\.nz/(?:folder|file)/([^#]+)(?:#([^!]+))?""")
            val folderMatch = folderPattern.find(cleanUrl)
            if (folderMatch != null) {
                val id = folderMatch.groupValues[1]
                val key = folderMatch.groupValues[2]
                val isFolder = cleanUrl.contains("/folder/")
                return MegaCredentials(fileId = id, key = key, isFolder = isFolder)
            }

            val oldPattern = Regex("""mega\.nz/#!([^!]+)!([^!\s]+)""")
            val oldMatch = oldPattern.find(cleanUrl)
            if (oldMatch != null) {
                return MegaCredentials(
                    fileId = oldMatch.groupValues[1],
                    key = oldMatch.groupValues[2],
                    isOldFormat = true
                )
            }

            val filePattern = Regex("""mega\.nz/file/([^#]+)(?:#(.+))?""")
            val fileMatch = filePattern.find(cleanUrl)
            if (fileMatch != null) {
                return MegaCredentials(fileId = fileMatch.groupValues[1], key = fileMatch.groupValues[2])
            }

            return null
        } catch (e: Exception) {
            Log.w(TAG, "URL parse error: ${e.message}")
            return null
        }
    }

    private fun decodeBase64Url(input: String): ByteArray {
        var padded = input.replace('-', '+').replace('_', '/')
        when (padded.length % 4) {
            2 -> padded += "=="
            3 -> padded += "="
        }
        return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
    }

    private fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun getDownloadInfo(fileId: String): DownloadInfo? {
        try {
            val requestBody = """[{"a":"g","g":1,"p":"$fileId"}]"""
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$API_URL?id=&ak=")
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
                .newCall(request)
                .execute()

            val body = response.body?.string() ?: return null
            response.close()

            Log.d(TAG, "API response: ${body.take(500)}")

            val jsonArray = org.json.JSONArray(body)
            if (jsonArray.length() == 0) return null

            val obj = jsonArray.getJSONObject(0)

            if (obj.has("e")) {
                Log.w(TAG, "Mega API error: ${obj.getInt("e")}")
                return null
            }

            val downloadUrl = obj.getString("g")
            val fileSize = obj.getLong("s")
            val encAttrs = if (obj.has("at")) obj.getString("at") else null

            Log.d(TAG, "File size: $fileSize, hasAttrs: ${encAttrs != null}")
            return DownloadInfo(url = downloadUrl, fileSize = fileSize, encAttrs = encAttrs)
        } catch (e: Exception) {
            Log.e(TAG, "API call failed: ${e.message}")
            return null
        }
    }

    private fun downloadAndDecrypt(
        downloadUrl: String,
        aesKey: ByteArray,
        iv: ByteArray,
        expectedSize: Long,
        context: Context?
    ): File? {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
                .newCall(request)
                .execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Download failed with code: ${response.code}")
                response.close()
                return null
            }

            val inputStream = response.body?.byteStream() ?: run {
                response.close()
                return null
            }

            val tempDir = if (context != null) {
                File(context.cacheDir, "mega_cache")
            } else {
                File("/data/local/tmp", "mega_cache")
            }
            if (!tempDir.exists()) tempDir.mkdirs()
            val tempFile = File(tempDir, "mega_${System.currentTimeMillis()}.mp4")

            val spec = SecretKeySpec(aesKey, "AES")
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, spec, ivSpec)

            val bufferSize = 64 * 1024
            val buffer = ByteArray(bufferSize)
            val outputStream = FileOutputStream(tempFile)

            var totalRead = 0L
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                val decrypted = cipher.update(buffer, 0, read)
                outputStream.write(decrypted)
                totalRead += read
                if (totalRead % (10 * 1024 * 1024) == 0L) {
                    Log.d(TAG, "Downloaded ${totalRead / (1024 * 1024)}MB / ${expectedSize / (1024 * 1024)}MB")
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            response.close()

            Log.d(TAG, "Download complete: ${tempFile.length()} bytes")

            if (tempFile.length() == 0L) {
                tempFile.delete()
                return null
            }

            return tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            return null
        }
    }

    fun cleanupTempFiles(context: Context? = null) {
        try {
            val tempDir = if (context != null) {
                File(context.cacheDir, "mega_cache")
            } else {
                File("/data/local/tmp", "mega_cache")
            }
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("mega_") && file.lastModified() < System.currentTimeMillis() - 3600_000) {
                        file.delete()
                        Log.d(TAG, "Cleaned up old temp file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup failed: ${e.message}")
        }
    }
}
