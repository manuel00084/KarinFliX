package com.karin.streamtv.util

import java.io.File

/**
 * Operaciones de archivos locales (copiar, mover, borrar) con soporte
 * recursivo para carpetas. Usado por el explorador estilo panel dual (exploreKF).
 */
object FileOps {

    /** Copia [src] a [dst] conservando el nombre. Devuelve true si tuvo éxito. */
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
