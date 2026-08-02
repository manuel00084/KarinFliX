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

    private val SITE_LOGOS = mapOf(
        "JKAnime" to "https://cdn.jkdesa.com/assets3/css/img/jkanimenet.png?v=2.0.184",
        "LatAnime" to "https://latanime.org/img/logito.png",
        "DoramasYT" to "https://www.doramasyt.com/img/logo6.png?v=1718135438",
        "MundoDonghua" to "https://mundodonghua.com/images/favicon.png",
        "RetroTVE" to "https://retrotve.com/wp-content/uploads/2024/11/cropped-android-chrome-512x512-1-192x192.png",
        "FrikiSeries" to "https://www.frikiserie.com/assets/icon/favicon.png",
    )

    private val PLATE_W = 300
    private val PLATE_H = 140

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isClickable = true
            cardType = ImageCardView.CARD_TYPE_INFO_UNDER
            setMainImageDimensions(PLATE_W, PLATE_H)
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
            val logo = generateLogoBitmap(site.name, color)
            DiskImageCache.renderLogoPlate(logo, PLATE_W, PLATE_H, 16)
        }
        cardView.setMainImage(BitmapDrawable(cardView.resources, bmp))

        val faviconUrl = SITE_LOGOS[site.name]
        scope.launch {
            val plate = withContext(Dispatchers.IO) {
                val host = runCatching { java.net.URI(site.url).host }.getOrNull()
                val logo = faviconUrl?.let { DiskImageCache.loadFromNetwork(it, 480, 160) }
                    ?: if (host != null) DiskImageCache.loadBestFavicon(DiskImageCache.faviconCandidates(host)) else null
                logo?.let { DiskImageCache.renderLogoPlate(it, PLATE_W, PLATE_H, 16) }
            }
            if (plate != null) {
                cardView.setMainImage(BitmapDrawable(cardView.resources, plate))
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
