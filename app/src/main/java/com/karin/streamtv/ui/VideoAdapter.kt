package com.karin.streamtv.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: String,
    val durationMs: Long = 0L,
    val folder: String = "",
    val relativePath: String = "",
    val sizeBytes: Long = 0L
)

class VideoAdapter(
    private var items: List<VideoItem>,
    private val contentResolver: ContentResolver,
    private val onItemClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val thumbCache = object : androidx.collection.LruCache<Long, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    fun submitList(newItems: List<VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivThumb: ImageView = view.findViewById(R.id.iv_thumbnail)
        private val tvTitle: TextView = view.findViewById(R.id.tv_title)
        private val tvFolder: TextView = view.findViewById(R.id.tv_folder)
        private val tvDuration: TextView = view.findViewById(R.id.tv_duration)

        fun bind(item: VideoItem) {
            tvTitle.text = item.title
            tvFolder.text = item.folder
            tvDuration.text = formatDuration(item.durationMs)

            ivThumb.tag = item.id

            val cached = thumbCache.get(item.id)
            if (cached != null) {
                ivThumb.setImageBitmap(cached)
            } else {
                ivThumb.setImageResource(android.R.color.darker_gray)
                scope.launch(Dispatchers.IO) {
                    val thumb = loadThumbnail(item.id)
                    if (thumb != null) {
                        thumbCache.put(item.id, thumb)
                        if (adapterPosition != RecyclerView.NO_POSITION &&
                            items.getOrNull(adapterPosition)?.id == item.id
                        ) {
                            launch(Dispatchers.Main) {
                                if (ivThumb.tag == item.id) {
                                    ivThumb.setImageBitmap(thumb)
                                }
                            }
                        }
                    }
                }
            }

            itemView.setOnClickListener { onItemClick(item) }
            itemView.onActionKey { onItemClick(item) }
        }

        private fun loadThumbnail(id: Long): Bitmap? {
            return try {
                MediaStore.Video.Thumbnails.getThumbnail(
                    contentResolver,
                    id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    fun destroy() {
        scope.cancel()
        thumbCache.evictAll()
    }
}
