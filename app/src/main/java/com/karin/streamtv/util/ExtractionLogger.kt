package com.karin.streamtv.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExtractionLogger {

    private const val TAG = "ExtractionLogger"
    private const val LOG_FILE_NAME = "karinflix_extraction_log.txt"

    private val entries = java.util.Collections.synchronizedList(ArrayList<String>(128))
    private var startTime = 0L
    private var isActive = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(sourceName: String, episodeUrl: String) {
        entries.clear()
        startTime = System.currentTimeMillis()
        isActive = true

        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("[${timestamp()}] EXTRACTION START")
        appendLine("Source: $sourceName")
        appendLine("Episode URL: $episodeUrl")
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("")
    }

    fun logServerStart(serverName: String, host: String, embedUrl: String) {
        appendLine("── Server: $serverName ──────────────────────────────────")
        appendLine("  Host detected: $host")
        appendLine("  Embed URL: $embedUrl")
    }

    fun logMoonGetterMatch(matched: Boolean, pattern: String?, serverName: String?) {
        if (matched) {
            appendLine("  MoonGetter factory match: ✓ (pattern=$pattern, server=$serverName)")
        } else {
            appendLine("  MoonGetter factory match: ✗ (no matching factory)")
        }
    }

    fun logMoonGetterOnExtractCall(callNumber: Int, urlState: String, urlChanged: Boolean, videosFound: Int, error: String? = null) {
        appendLine("  MoonGetter onExtract() call #$callNumber:")
        appendLine("    URL state: $urlState")
        if (callNumber > 1) {
            appendLine("    URL changed from previous call: $urlChanged")
        }
        appendLine("    Videos found: $videosFound")
        if (error != null) {
            appendLine("    Error: $error")
        }
    }

    fun logMoonGetterVideo(index: Int, quality: String?, url: String) {
        appendLine("    Video[$index]: quality=$quality url=${url.take(150)}")
    }

    fun logHttp(method: String, url: String, status: Int, contentType: String?, bodyLength: Int, bodyPreview: String? = null) {
        appendLine("  HTTP $method: $url")
        appendLine("    Status: $status")
        appendLine("    Content-Type: $contentType ?: unknown")
        appendLine("    Body length: $bodyLength chars")
        if (bodyPreview != null) {
            appendLine("    Body preview: ${bodyPreview.take(300)}")
        }
    }

    fun logServerSpecificStart(method: String) {
        appendLine("  Trying server-specific: $method")
    }

    fun logRegexAttempt(patternName: String, matched: Boolean, result: String? = null) {
        if (matched) {
            appendLine("  Regex '$patternName': ✓ → ${result?.take(150)}")
        }
    }

    fun logRhinoEval(inlineScripts: Int, externalScripts: Int, totalChars: Int, urlsFound: Int) {
        appendLine("  Rhino JS eval:")
        appendLine("    Inline scripts: $inlineScripts, External scripts: $externalScripts")
        appendLine("    Total JS chars: $totalChars")
        appendLine("    URLs captured/found: $urlsFound")
    }

    fun logFallbackResult(method: String, candidatesCount: Int, firstCandidate: String?) {
        appendLine("  Fallback ($method): $candidatesCount candidates found")
        if (firstCandidate != null) {
            appendLine("    First candidate: ${firstCandidate.take(150)}")
        }
    }

    fun logServerResult(success: Boolean, videoUrl: String? = null, error: String? = null, method: String? = null) {
        if (success) {
            appendLine("  RESULT: SUCCESS ✓ (method=$method)")
            if (videoUrl != null) {
                appendLine("  Video URL: ${videoUrl.take(200)}")
            }
        } else {
            appendLine("  RESULT: FAILED ✗")
            if (error != null) {
                appendLine("  Error: $error")
            }
        }
        appendLine("────────────────────────────────────────────────────────")
        appendLine("")
    }

    fun logMoonGetterSummary(factoriesLoaded: Int, factoryNames: List<String>) {
        appendLine("  MoonGetter engine:")
        appendLine("    Factories loaded: $factoriesLoaded")
        factoryNames.forEach { appendLine("      - $it") }
    }

    fun logFinalResult(bestSource: String?, serversTried: Int, successful: Int, totalMs: Long) {
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("[${timestamp()}] EXTRACTION COMPLETE (${totalMs}ms)")
        appendLine("Best source: ${bestSource?.take(200) ?: "NONE"}")
        appendLine("Servers tried: $serversTried")
        appendLine("Successful: $successful")
        appendLine("═══════════════════════════════════════════════════════════")
    }

    fun save(context: Context): String? {
        if (entries.isEmpty()) return null

        isActive = false
        val elapsed = System.currentTimeMillis() - startTime
        // Prepend elapsed time to final result if present
        val finalIndex = entries.indexOfFirst { it.contains("EXTRACTION COMPLETE") }
        if (finalIndex >= 0 && finalIndex + 1 < entries.size) {
            entries[finalIndex] = entries[finalIndex].replace("\\d+ms".toRegex(), "${elapsed}ms")
        }

        return try {
            val dir = context.getExternalFilesDir("logs")
            if (dir != null && !dir.exists()) dir.mkdirs()
            val file = File(dir ?: context.filesDir, LOG_FILE_NAME)
            file.writeText(entries.joinToString("\n"), Charsets.UTF_8)
            Log.i(TAG, "Log saved to: ${file.absolutePath} (${file.length()} bytes)")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save log: ${e.message}")
            // Fallback: save to internal storage
            try {
                val file = File(context.filesDir, LOG_FILE_NAME)
                file.writeText(entries.joinToString("\n"), Charsets.UTF_8)
                Log.i(TAG, "Log saved (fallback) to: ${file.absolutePath}")
                file.absolutePath
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback save also failed: ${e2.message}")
                null
            }
        }
    }

    fun getLogContent(): String = entries.joinToString("\n")

    fun clear() {
        entries.clear()
        isActive = false
    }

    private fun appendLine(line: String) {
        if (!isActive) return
        entries.add(line)
    }

    private fun timestamp(): String = dateFormat.format(Date())
}
