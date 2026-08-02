package com.karin.streamtv.util

import android.content.Context
import java.io.File

object CrashLogger {
    private const val FILE_NAME = "crash_log.txt"
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = File(context.cacheDir, FILE_NAME)
                val msg = "${System.currentTimeMillis()}\nThread: ${thread.name}\n${throwable.stackTraceToString()}\n---\n"
                logFile.appendText(msg)
            } catch (_: Exception) {}
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(context: Context, tag: String, msg: String) {
        try {
            val logFile = File(context.cacheDir, FILE_NAME)
            logFile.appendText("$tag: $msg\n")
        } catch (_: Exception) {}
    }

    fun getLog(context: Context): String {
        return try {
            File(context.cacheDir, FILE_NAME).readText()
        } catch (_: Exception) { "No log" }
    }
}
