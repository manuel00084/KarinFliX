package com.karin.streamtv.util

import android.content.Context
import java.io.File

object CrashLogger {
    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_SIZE_BYTES = 512L * 1024L // 512KB
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = File(context.cacheDir, FILE_NAME)
                val msg = "${System.currentTimeMillis()}\nThread: ${thread.name}\n${throwable.stackTraceToString()}\n---\n"
                appendTruncated(logFile, msg)
            } catch (_: Exception) {}
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(context: Context, tag: String, msg: String) {
        try {
            val logFile = File(context.cacheDir, FILE_NAME)
            appendTruncated(logFile, "$tag: $msg\n")
        } catch (_: Exception) {}
    }

    /** Mantiene el log acotado a [MAX_SIZE_BYTES]: si al añadir se supera, se
     *  descarta el tramo más antiguo para que el archivo no crezca sin límite. */
    private fun appendTruncated(file: File, line: String) {
        if (!file.exists()) {
            file.appendText(line)
            return
        }
        if (file.length() + line.length <= MAX_SIZE_BYTES) {
            file.appendText(line)
            return
        }
        val full = file.readText()
        var trimmed = full
        while (trimmed.length > MAX_SIZE_BYTES / 2 && trimmed.length > line.length) {
            trimmed = trimmed.substringAfter('\n', trimmed)
        }
        file.writeText(trimmed + line)
    }

    fun getLog(context: Context): String {
        return try {
            File(context.cacheDir, FILE_NAME).readText()
        } catch (_: Exception) { "No log" }
    }

    /** Último cierre registrado (bloque "timestamp / Thread / stack / ---")
     *  o null si no hubo ninguno todavía. */
    fun latestCrash(context: Context): String? {
        return try {
            val text = File(context.cacheDir, FILE_NAME).readText()
            if (text.isBlank()) return null
            val block = text.split("\n---\n").lastOrNull()?.trim().orEmpty()
            val lines = block.lines()
            if (lines.size >= 2 && lines[1].startsWith("Thread:")) block else null
        } catch (_: Exception) { null }
    }

    fun clear(context: Context) {
        try {
            File(context.cacheDir, FILE_NAME).delete()
        } catch (_: Exception) {}
    }
}
