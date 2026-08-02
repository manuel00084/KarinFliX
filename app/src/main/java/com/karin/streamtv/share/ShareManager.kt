package com.karin.streamtv.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object ShareManager {

    private const val TAG = "ShareManager"

    data class ShareData(
        val title: String,
        val episodeTitle: String,
        val episodeUrl: String,
        val siteName: String,
        val thumbnailUrl: String = ""
    )

    fun shareToWhatsApp(context: Context, data: ShareData) {
        val shareText = buildShareText(data)
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_SUBJECT, "Mira este episodio en KarinFLiX")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp not installed, opening Play Store")
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp")))
            } catch (_: Exception) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.whatsapp")))
            }
        }
    }

    fun shareToFacebook(context: Context, data: ShareData) {
        val shareText = buildShareText(data)
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.facebook.katana")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Facebook not installed, opening browser")
            try {
                val fbIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/sharer/sharer.php?u=${Uri.encode(data.episodeUrl)}&quote=${Uri.encode(shareText)}"))
                context.startActivity(fbIntent)
            } catch (_: Exception) {
                Log.e(TAG, "Cannot share to Facebook")
            }
        }
    }

    fun shareGeneric(context: Context, data: ShareData) {
        val shareText = buildShareText(data)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, "Mira este episodio en KarinFLiX")
        }
        context.startActivity(Intent.createChooser(intent, "Compartir episodio"))
    }

    private fun buildShareText(data: ShareData): String {
        return buildString {
            appendLine("🎬 ${data.title}")
            appendLine("📺 ${data.episodeTitle}")
            appendLine("🌐 ${data.siteName}")
            appendLine()
            appendLine("Ver en KarinFLiX: ${data.episodeUrl}")
        }
    }

}
