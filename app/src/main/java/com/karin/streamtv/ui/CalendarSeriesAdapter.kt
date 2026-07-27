package com.karin.streamtv.ui

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.model.CalendarItem
import com.karin.streamtv.util.DiskImageCache
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CalendarSeriesAdapter(
    private val items: List<CalendarItem>,
    private val onSeriesClick: (CalendarItem) -> Unit
) : RecyclerView.Adapter<CalendarSeriesAdapter.VH>() {

    private val imageCache = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pendingJobs = mutableMapOf<String, Job>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_calendar_series, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title

        if (item.nextEpisode.isNotBlank()) {
            holder.nextEp.visibility = View.VISIBLE
            holder.nextEp.text = item.nextEpisode
            holder.nextEp.contentDescription = item.nextEpisode
        } else {
            holder.nextEp.visibility = View.GONE
        }

        val cached = imageCache.get(item.thumbnailUrl)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
        } else {
            holder.thumb.setImageResource(android.R.color.darker_gray)
            val job = pendingJobs[item.thumbnailUrl]
            if (job == null || job.isCancelled) {
                pendingJobs[item.thumbnailUrl] = GlobalScope.launch(Dispatchers.Main) {
                    val bmp = withContext(Dispatchers.IO) {
                        if (item.thumbnailUrl.isNotBlank()) {
                            DiskImageCache.loadFromNetwork(item.thumbnailUrl, 400, 240)
                        } else null
                    }
                    if (bmp != null) {
                        imageCache.put(item.thumbnailUrl, bmp)
                        if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                            notifyItemChanged(holder.adapterPosition)
                        }
                    }
                    pendingJobs.remove(item.thumbnailUrl)
                }
            }
        }

        holder.itemView.contentDescription = "${item.title}, ${item.nextEpisode}"
        holder.itemView.setOnClickListener { onSeriesClick(item) }
        holder.itemView.onActionKey { onSeriesClick(item) }
    }

    override fun getItemCount() = items.size

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        val url = holder.thumb.tag as? String
        if (url != null) pendingJobs.remove(url)?.cancel()
    }

    fun clearCache() {
        imageCache.evictAll()
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.iv_thumbnail)
        val title: TextView = view.findViewById(R.id.tv_title)
        val nextEp: TextView = view.findViewById(R.id.tv_next_episode)
    }
}
