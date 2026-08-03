package com.karin.streamtv.util

import android.app.Activity
import android.content.Intent
import android.widget.Toast

object ServerHelper {

    fun extractEpisodeNumber(title: String): Int {
        val patterns = listOf(
            Regex("""(?i)(?:episodio|episode|capitulo|cap|ep\.?|#)\s*(\d+)"""),
            Regex("""(\d+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 0
            }
        }
        return 0
    }

    fun shareUrl(activity: Activity, url: String, label: String, targetPackage: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$label - $url")
                `package` = targetPackage
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "$label - $url")
                }
                activity.startActivity(Intent.createChooser(intent, "Compartir"))
            } catch (_: Exception) {
                Toast.makeText(activity, "App no disponible", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
