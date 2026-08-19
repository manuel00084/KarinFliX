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

    /**
     * Construye un título legible de episodio a partir de su URL,
     * p.ej. "https://sitio/ver/beyblade-x-latino-episodio-99"
     * -> "Beyblade X Latino - Episodio 99".
     */
    fun titleFromEpisodeUrl(url: String): String {
        val clean = url.substringAfter("/ver/").substringAfterLast('/')
            .substringBefore('?').trim()
        if (clean.isBlank()) return "Episodio"
        val episodeMarkers = listOf(
            "-episodio-", "-episode-", "-capitulo-", "-capitulo", "-episodio"
        )
        for (marker in episodeMarkers) {
            val idx = clean.indexOf(marker)
            if (idx > 0) {
                val seriesPart = clean.substring(0, idx)
                val numPart = clean.substring(idx + marker.length)
                val series = seriesPart.replace("-", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                val num = numPart.replace(Regex("""\D"""), "")
                if (num.isNotBlank()) {
                    return "$series - Episodio $num"
                }
                return "$series - Episodio"
            }
        }
        return clean.replace("-", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
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
