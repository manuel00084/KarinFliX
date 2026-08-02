package com.karin.streamtv.ui.tv

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.karin.streamtv.model.Episode
import com.karin.streamtv.util.DiskImageCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvEpisodeCardPresenter : Presenter() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val thumbCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isClickable = true
            cardType = ImageCardView.CARD_TYPE_INFO_UNDER
            setMainImageDimensions(320, 180)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val episode = item as Episode
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = episode.title
        cardView.contentText = episode.episodeNum.ifBlank { episode.date }

        if (episode.thumbnailUrl.isNotBlank()) {
            val cached = thumbCache[episode.thumbnailUrl]
            if (cached != null) {
                cardView.setMainImage(BitmapDrawable(cardView.resources, cached))
            } else {
                cardView.setMainImage(null)
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        DiskImageCache.loadFromNetwork(episode.thumbnailUrl, 320, 180)
                    }
                    if (bmp != null) {
                        thumbCache.put(episode.thumbnailUrl, bmp)
                        cardView.setMainImage(BitmapDrawable(cardView.resources, bmp))
                    }
                }
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
    }
}
