package com.karin.streamtv.ui.tv

import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.karin.streamtv.model.SiteConfig
import com.karin.streamtv.util.DiskImageCache
import com.karin.streamtv.util.SiteBranding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvSiteCardPresenter : Presenter() {

    private val logoCache = mutableMapOf<String, android.graphics.Bitmap>()
    private val scope = CoroutineScope(Dispatchers.Main)

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
            val color = SiteBranding.brandColors[site.name] ?: android.graphics.Color.parseColor("#555555")
            val logo = SiteBranding.generateLogoBitmap(site.name, color)
            DiskImageCache.renderLogoPlate(logo, PLATE_W, PLATE_H, 16)
        }
        cardView.setMainImage(BitmapDrawable(cardView.resources, bmp))

        val faviconUrl = SiteBranding.siteLogos[site.name]
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
}
