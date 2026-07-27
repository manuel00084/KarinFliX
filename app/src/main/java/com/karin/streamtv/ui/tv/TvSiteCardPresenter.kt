package com.karin.streamtv.ui.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.karin.streamtv.R
import com.karin.streamtv.model.SiteConfig
import com.karin.streamtv.util.DiskImageCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvSiteCardPresenter(
    private val brandColors: Map<String, Int>
) : Presenter() {

    private val logoCache = mutableMapOf<String, Bitmap>()
    private val scope = CoroutineScope(Dispatchers.Main)

    private val SITE_FAVICONS = mapOf(
        "Cuevana3" to "https://www3.cuevana3.is/static/img/cropped-favicon-1-32x32.png",
        "JKAnime" to "https://icons.duckduckgo.com/ip3/jkanime.net.ico",
        "LatAnime" to "https://icons.duckduckgo.com/ip3/latanime.org.ico",
        "DoramasYT" to "https://icons.duckduckgo.com/ip3/www.doramasyt.com.ico",
        "MundoDonghua" to "https://mundodonghua.com/images/favicon.png",
        "RetroTVE" to "https://retrotve.com/wp-content/uploads/2024/11/cropped-android-chrome-512x512-1-32x32.png",
        "FrikiSeries" to "https://www.frikiserie.com/assets/icon/favicon.png",
    )

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isClickable = true
            cardType = ImageCardView.CARD_TYPE_INFO_UNDER
            setMainImageDimensions(200, 200)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val site = item as SiteConfig
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = site.name
        cardView.contentText = site.url

        val bmp = logoCache.getOrPut(site.name) {
            val color = brandColors[site.name] ?: Color.parseColor("#555555")
            generateLogoBitmap(site.name, color)
        }
        cardView.setMainImage(BitmapDrawable(cardView.resources, bmp))

        val faviconUrl = SITE_FAVICONS[site.name]
        if (faviconUrl != null) {
            scope.launch {
                val favicon = withContext(Dispatchers.IO) {
                    DiskImageCache.loadFromNetwork(faviconUrl, 128, 128)
                }
                if (favicon != null) {
                    cardView.setMainImage(BitmapDrawable(cardView.resources, favicon))
                }
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
    }

    private fun generateLogoBitmap(name: String, color: Int): Bitmap {
        val iconText = name.take(2).padEnd(2).take(2)
        val size = 200
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
