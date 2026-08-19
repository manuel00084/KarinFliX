package com.karin.streamtv.karinlink

import android.util.Log
import jcifs.CIFSContext
import jcifs.config.BaseConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.InputStream

/**
 * Cliente SMB ligero construido sobre jcifs-ng.
 * Soporta: descubrimiento de shares, listado de directorios,
 * búsqueda recursiva y apertura de streams para streaming HTTP.
 */
object SmbClient {

    private const val TAG = "SmbClient"
    const val SEARCH_LIMIT = 400
    const val SEARCH_SCAN_LIMIT = 50000

    sealed class SmbResult {
        data class Shares(val shares: List<String>) : SmbResult()
        data class Entries(val entries: List<SmbFileInfo>) : SmbResult()
        data class Stream(val stream: InputStream, val length: Long) : SmbResult()
        data class Error(val message: String) : SmbResult()
    }

    data class SmbFileInfo(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val modified: Long
    )

    private fun buildContext(ref: SmbRef): CIFSContext {
        val config = try {
            jcifs.config.PropertyConfiguration(java.util.Properties())
        } catch (e: jcifs.CIFSException) {
            Log.w(TAG, "PropertyConfiguration fallback: ${e.message}")
            BaseConfiguration(true)
        }
        val base = BaseContext(config)
        val cred = when {
            ref.user != null && ref.password != null -> NtlmPasswordAuthenticator(ref.user, ref.password)
            else -> NtlmPasswordAuthenticator()
        }
        return base.withCredentials(cred)
    }

    /**
     * Enumera los shares disponibles en el host indicado.
     */
    fun listShares(
        host: String,
        port: Int = 445,
        user: String? = null,
        password: String? = null
    ): SmbResult {
        val ref = SmbRef(
            deviceName = host,
            host = host,
            port = port,
            share = "",
            remotePath = "/",
            user = user,
            password = password
        )
        return try {
            val ctx = buildContext(ref)
            val url = if (port != 0 && port != 445) "smb://$host:$port/" else "smb://$host/"
            val root = SmbFile(url, ctx)
            val files = root.listFiles()
            if (files == null) {
                SmbResult.Shares(emptyList())
            } else {
                val shares = files.mapNotNull { f ->
                    f.name?.takeIf { it.isNotBlank() }
                }
                SmbResult.Shares(shares)
            }
        } catch (e: Exception) {
            Log.w(TAG, "listShares error: ${e.message}")
            SmbResult.Error(e.message ?: "Error al enumerar shares")
        }
    }

    /**
     * Lista el contenido de [ref.remotePath] dentro del share [ref.share].
     */
    fun list(ref: SmbRef): SmbResult {
        return try {
            val ctx = buildContext(ref)
            val smbFile = SmbFile(ref.smbUrl, ctx)
            if (!smbFile.isDirectory) {
                return SmbResult.Error(
                    "No es un directorio: ${ref.share}${ref.remotePath}"
                )
            }
            val children = smbFile.listFiles()
            if (children == null) {
                return SmbResult.Entries(emptyList())
            }
            val entries = children.mapNotNull { f ->
                val name = f.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val isDir = f.isDirectory
                val childPath =
                    if (ref.remotePath.endsWith("/")) "${ref.remotePath}$name"
                    else "${ref.remotePath}/$name"
                SmbFileInfo(
                    name = name,
                    path = childPath,
                    isDir = isDir,
                    size = if (isDir) 0L else f.length(),
                    modified = f.lastModified()
                )
            }
            SmbResult.Entries(entries)
        } catch (e: Exception) {
            Log.w(TAG, "list error: ${e.message}")
            SmbResult.Error(e.message ?: "Error al listar")
        }
    }

    /**
     * Busca archivos cuyo nombre contiene [query] de forma recursiva.
     */
    fun search(ref: SmbRef, query: String): SmbResult {
        if (query.isBlank()) return SmbResult.Entries(emptyList())
        val result = mutableListOf<SmbFileInfo>()
        val stack = ArrayDeque<SmbRef>()
        stack.addLast(ref)
        var scanned = 0
        return try {
            val ctx = buildContext(ref)
            while (stack.isNotEmpty() &&
                result.size < SEARCH_LIMIT &&
                scanned < SEARCH_SCAN_LIMIT
            ) {
                val current = stack.removeLast()
                scanned++
                val smbFile = SmbFile(current.smbUrl, ctx)
                val children = smbFile.listFiles() ?: continue
                for (c in children) {
                    if (result.size >= SEARCH_LIMIT) break
                    val name = c.name ?: continue
                    val isDir = c.isDirectory
                    val childPath =
                        if (current.remotePath.endsWith("/")) "${current.remotePath}$name"
                        else "${current.remotePath}/$name"
                    if (name.contains(query, ignoreCase = true)) {
                        result.add(
                            SmbFileInfo(
                                name = name,
                                path = childPath,
                                isDir = isDir,
                                size = if (isDir) 0L else c.length(),
                                modified = c.lastModified()
                            )
                        )
                    }
                    if (isDir) {
                        stack.addLast(current.copy(remotePath = childPath))
                    }
                }
            }
            SmbResult.Entries(result)
        } catch (e: Exception) {
            SmbResult.Error(e.message ?: "Error en la búsqueda")
        }
    }

    /**
     * Abre un stream de entrada al archivo remoto para streaming.
     * También devuelve la longitud del archivo para cabeceras HTTP.
     */
    fun openStream(ref: SmbRef): SmbResult {
        return try {
            val ctx = buildContext(ref)
            val smbFile = SmbFile(ref.smbUrl, ctx)
            val input = smbFile.inputStream
            val length = smbFile.length()
            SmbResult.Stream(input, length)
        } catch (e: Exception) {
            Log.w(TAG, "openStream error: ${e.message}")
            SmbResult.Error(e.message ?: "Error al abrir stream")
        }
    }
}
