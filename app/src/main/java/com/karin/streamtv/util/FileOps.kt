package com.karin.streamtv.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operaciones de archivos locales (copiar, mover, borrar) con soporte
 * recursivo para carpetas. Usado por el explorador estilo panel dual (exploreKF).
 */
object FileOps {

    private const val BUFFER_SIZE = 8192

    /** Resultado detallado de una operación de copia. */
    data class CopyResult(
        val success: Boolean,
        val filesCopied: Int,
        val foldersCopied: Int,
        val bytesCopied: Long,
        val errors: List<String> = emptyList()
    )

    /** Callback de progreso para operaciones de copia. */
    interface CopyProgressListener {
        fun onProgress(currentFile: String, filesProcessed: Int, totalFiles: Int, bytesProcessed: Long, totalBytes: Long)
        fun onComplete(result: CopyResult)
    }

    /**
     * Calcula el tamaño total y número de archivos en un directorio.
     * @return Par de (número de archivos, tamaño total en bytes)
     */
    fun calculateDirStats(dir: File): Pair<Int, Long> {
        var count = 0
        var size = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    val (c, s) = calculateDirStats(child)
                    count += c
                    size += s
                } else {
                    count++
                    size += child.length()
                }
            }
        }
        return Pair(count, size)
    }

    /**
     * Cuenta el número total de archivos (recursivo) en una lista de archivos/carpetas.
     */
    fun countTotalFiles(entries: List<File>): Pair<Int, Long> {
        var totalCount = 0
        var totalSize = 0L
        for (entry in entries) {
            if (entry.isDirectory) {
                val (c, s) = calculateDirStats(entry)
                totalCount += c
                totalSize += s
            } else {
                totalCount++
                totalSize += entry.length()
            }
        }
        return Pair(totalCount, totalSize)
    }

    /**
     * Verifica si el destino ya existe y contiene el archivo/ carpeta.
     */
    fun destinationExists(src: File, dstDir: File): Boolean {
        val dst = File(dstDir, src.name)
        return dst.exists()
    }

    /**
     * Copia [src] a [dst] conservando el nombre con soporte de progreso y cancelación.
     * @param listener Callback de progreso (opcional)
     * @param cancelFlag Flag para cancelar la operación
     * @param overwrite Si es true, sobrescribe archivos existentes
     * @return Resultado detallado de la copia
     */
    fun copyWithProgress(
        src: File,
        dstDir: File,
        listener: CopyProgressListener? = null,
        cancelFlag: AtomicBoolean = AtomicBoolean(false),
        overwrite: Boolean = true
    ): CopyResult {
        val errors = mutableListOf<String>()
        var filesCopied = 0
        var foldersCopied = 0
        var bytesCopied = 0L

        fun copyRecursive(current: File, targetDir: File) {
            if (cancelFlag.get()) return

            val target = File(targetDir, current.name)

            if (current.isDirectory) {
                if (!target.exists()) {
                    target.mkdirs()
                    foldersCopied++
                }
                current.listFiles()?.forEach { child ->
                    if (cancelFlag.get()) return
                    copyRecursive(child, target)
                }
            } else {
                // Verificar si ya existe
                if (target.exists() && !overwrite) {
                    errors.add("Ya existe: ${current.name}")
                    return
                }

                // Crear directorio padre si no existe
                target.parentFile?.mkdirs()

                try {
                    val totalSize = current.length()
                    FileInputStream(current).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            var localBytesCopied = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (cancelFlag.get()) {
                                    target.delete()
                                    return
                                }
                                output.write(buffer, 0, bytesRead)
                                localBytesCopied += bytesRead
                                bytesCopied += bytesRead

                                // Notificar progreso
                                listener?.onProgress(
                                    current.name,
                                    filesCopied,
                                    0, // Se actualizará desde el exterior
                                    bytesCopied,
                                    0  // Se actualizará desde el exterior
                                )
                            }
                        }
                    }
                    filesCopied++
                } catch (e: Exception) {
                    android.util.Log.w("FileOps", "copy error: ${e.message}")
                    errors.add("Error copiando ${current.name}: ${e.message}")
                    target.delete()
                }
            }
        }

        copyRecursive(src, dstDir)

        val result = CopyResult(
            success = errors.isEmpty() && !cancelFlag.get(),
            filesCopied = filesCopied,
            foldersCopied = foldersCopied,
            bytesCopied = bytesCopied,
            errors = errors
        )

        listener?.onComplete(result)
        return result
    }

    /**
     * Copia múltiples archivos/carpetas a un directorio destino.
     */
    fun copyMultipleWithProgress(
        sources: List<File>,
        dstDir: File,
        listener: CopyProgressListener? = null,
        cancelFlag: AtomicBoolean = AtomicBoolean(false),
        overwrite: Boolean = true
    ): CopyResult {
        val errors = mutableListOf<String>()
        var totalFilesCopied = 0
        var totalFoldersCopied = 0
        var totalBytesCopied = 0L

        // Calcular totales
        val (totalFiles, totalBytes) = countTotalFiles(sources)

        for (src in sources) {
            if (cancelFlag.get()) break

            val result = copyWithProgress(
                src = src,
                dstDir = dstDir,
                listener = object : CopyProgressListener {
                    override fun onProgress(currentFile: String, filesProcessed: Int, totalFiles: Int, bytesProcessed: Long, totalBytes: Long) {
                        listener?.onProgress(
                            currentFile,
                            totalFilesCopied + filesProcessed,
                            totalFiles,
                            totalBytesCopied + bytesProcessed,
                            totalBytes
                        )
                    }

                    override fun onComplete(result: CopyResult) {
                        totalFilesCopied += result.filesCopied
                        totalFoldersCopied += result.foldersCopied
                        totalBytesCopied += result.bytesCopied
                        errors.addAll(result.errors)
                    }
                },
                cancelFlag = cancelFlag,
                overwrite = overwrite
            )
        }

        return CopyResult(
            success = errors.isEmpty() && !cancelFlag.get(),
            filesCopied = totalFilesCopied,
            foldersCopied = totalFoldersCopied,
            bytesCopied = totalBytesCopied,
            errors = errors
        )
    }

    /**
     * Copia simple (compatibilidad hacia atrás).
     * Copia [src] a [dst] conservando el nombre. Devuelve true si tuvo éxito.
     */
    fun copy(src: File, dst: File): Boolean {
        return try {
            if (src.isDirectory) {
                dst.mkdirs()
                src.listFiles()?.forEach { child ->
                    copy(child, File(dst, child.name))
                }
            } else {
                dst.parentFile?.mkdirs()
                src.inputStream().use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("FileOps", "copy error: ${e.message}")
            false
        }
    }

    /** Mueve [src] a [dst] (copia + borra origen). */
    fun move(src: File, dst: File): Boolean {
        if (!copy(src, dst)) return false
        return delete(src)
    }

    /** Borra [file] (recursivo para carpetas). */
    fun delete(file: File): Boolean {
        return try {
            if (file.isDirectory) {
                file.listFiles()?.forEach { delete(it) }
            }
            file.delete()
        } catch (e: Exception) {
            android.util.Log.w("FileOps", "delete error: ${e.message}")
            false
        }
    }

    fun mkdir(path: File): Boolean = path.mkdirs()

    /** Renombra/mueve [src] a [targetName] dentro del mismo directorio padre. */
    fun rename(src: File, targetName: String): Boolean {
        val parent = src.parentFile ?: return false
        val dst = File(parent, targetName)
        if (dst.exists()) return false
        return src.renameTo(dst)
    }
}
