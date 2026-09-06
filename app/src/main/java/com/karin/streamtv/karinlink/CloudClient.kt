package com.karin.streamtv.karinlink

import java.io.InputStream

/**
 * Resultado de las operaciones de un proveedor de nube.
 */
sealed class CloudResult {
    /** Listado de una carpeta. */
    data class Entries(val folders: List<CloudEntry>, val files: List<CloudEntry>) : CloudResult()

    /** Stream abierto para reproducir/descargar un archivo concreto. */
    data class Stream(val stream: InputStream, val length: Long) : CloudResult()

    data class Error(val message: String) : CloudResult()
}

/**
 * Entrada devuelta al listar una carpeta de un proveedor de nube.
 */
data class CloudEntry(
    val name: String,
    val folderId: String,   // id/padre para navegar (carpetas) o parent (archivos)
    val fileId: String,     // id del archivo (para reproducir); "" si es carpeta
    val isDir: Boolean,
    val sizeBytes: Long
)

/**
 * Contrato de un proveedor de almacenamiento en la nube. Implementaciones:
 * [GoogleDriveClient] y [DropboxClient].
 */
interface CloudClient {
    val provider: CloudProvider

    /** Lista las entradas de la carpeta representada por [ref]. */
    fun list(ref: CloudRef, token: String): CloudResult

    /** Longitud total (en bytes) del archivo [ref] para montar cabeceras Range. */
    fun length(ref: CloudRef, token: String): Long

    /**
     * Abre un stream al archivo [ref] (debe ser un archivo, isFolder=false),
     * devolviendo el subrango [start, end] (cerrado) gracias a la cabecera Range
     * enviada al proveedor.
     *
     * Si [end] es -1L, quiere decir "desde [start] hasta el final del archivo"
     * (no se conoce la longitud total). [CloudResult.Stream.length] es el número
     * de bytes del subrango devuelto, o -1L si no se conoce (hasta EOF).
     */
    fun openStream(ref: CloudRef, token: String, start: Long, end: Long): CloudResult
}

/** Despacho por proveedor. */
object CloudClients {
    fun forProvider(provider: CloudProvider): CloudClient? = when (provider) {
        CloudProvider.GOOGLE_DRIVE -> GoogleDriveClient
        CloudProvider.DROPBOX -> DropboxClient
    }
}
