package com.karin.streamtv.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

object SiteBranding {

    val brandColors: Map<String, Int> = mapOf(
        "JKAnime" to Color.parseColor("#1E88E5"),
        "LatAnime" to Color.parseColor("#43A047"),
        "MundoDonghua" to Color.parseColor("#8E24AA"),
        "RetroTVE" to Color.parseColor("#FF9800"),
        "LaCartoons" to Color.parseColor("#E91E63"),
        "DoramasYT" to Color.parseColor("#00BCD4"),
        "FrikiSeries" to Color.parseColor("#1B5E20"),
    )

    val siteLogos: Map<String, String> = mapOf(
        "JKAnime" to "https://cdn.jkdesa.com/assets3/css/img/jkanimenet.png?v=2.0.184",
        "LatAnime" to "https://latanime.org/img/logito.png",
        "MundoDonghua" to "https://mundodonghua.com/images/favicon.png",
        "RetroTVE" to "https://retrotve.com/wp-content/uploads/2024/11/cropped-android-chrome-512x512-1-192x192.png",
        "LaCartoons" to "https://www.lacartoons.com/wp-content/uploads/2024/01/cropped-lacartoons-favicon-32x32.png",
        "DoramasYT" to "https://www.doramasyt.com/wp-content/uploads/2024/01/cropped-doramasyt-favicon-32x32.png",
        "FrikiSeries" to "https://www.frikiserie.com/assets/icon/favicon.png",
    )

    fun generateLogoBitmap(name: String, color: Int, size: Int = 200): Bitmap {
        val iconText = name.take(2).padEnd(2).take(2)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            c.drawCircle(size / 2f, size / 2f, size / 2f, p)
            p.color = Color.WHITE
            p.textSize = size * 0.38f
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(iconText, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
        }
    }
}
