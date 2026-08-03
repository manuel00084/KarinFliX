package com.karin.streamtv.ui

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.model.Episode
import com.karin.streamtv.util.DiskImageCache
import com.karin.streamtv.util.EpisodeProgress
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EpisodeAdapter(
    private val episodes: List<Episode>,
    private val siteUrl: String = "",
    private val isGrid: Boolean = true,
    private val onEpisodeClick: (Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    private val imageCache = object : LruCache<String, Bitmap>(15 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pendingJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val selectedUrls = LinkedHashSet<String>()
    private var selectionMode = false
    var onSelectionCountChanged: ((Int) -> Unit)? = null

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedUrls.clear()
        notifyDataSetChanged()
        onSelectionCountChanged?.invoke(selectedUrls.size)
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun selectedCount(): Int = selectedUrls.size

    fun toggleSelection(episode: Episode) {
        if (!selectedUrls.add(episode.url)) selectedUrls.remove(episode.url)
        notifyDataSetChanged()
        onSelectionCountChanged?.invoke(selectedUrls.size)
    }

    fun getSelectedEpisodes(): List<Episode> = episodes.filter { selectedUrls.contains(it.url) }

    fun clearSelection() {
        selectedUrls.clear()
        notifyDataSetChanged()
        onSelectionCountChanged?.invoke(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutRes = if (isGrid) R.layout.item_episode_grid else R.layout.item_episode_card
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val episode = episodes[position]
        holder.title.text = episode.title
        holder.date.text = episode.date

        val epNum = episode.episodeNum.trim()
        if (epNum.isNotBlank()) {
            holder.tvEpisodeNumber.visibility = View.VISIBLE
            holder.tvEpisodeNumber.text = if (epNum.length <= 4) "Ep $epNum" else epNum
            holder.tvEpisodeNumber.contentDescription = "Episodio $epNum"
        } else {
            val inferredNum = position + 1
            holder.tvEpisodeNumber.visibility = View.VISIBLE
            holder.tvEpisodeNumber.text = "Ep $inferredNum"
            holder.tvEpisodeNumber.contentDescription = "Episodio $inferredNum"
        }

        val cached = imageCache.get(episode.thumbnailUrl)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
            holder.thumb.tag = null
        } else {
            holder.thumb.setImageResource(android.R.color.darker_gray)
            holder.thumb.tag = episode.thumbnailUrl
            val existingJob = pendingJobs[episode.thumbnailUrl]
            if (existingJob == null || existingJob.isCancelled) {
                pendingJobs[episode.thumbnailUrl] = scope.launch {
                    val bmp = loadImage(episode.thumbnailUrl)
                    if (bmp != null) {
                        imageCache.put(episode.thumbnailUrl, bmp)
                        if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                            notifyItemChanged(holder.adapterPosition)
                        }
                    }
                    pendingJobs.remove(episode.thumbnailUrl)
                }
            }
        }

        val animeId = EpisodeProgress.generateAnimeId(episode.url)
        val isWatched = EpisodeProgress.isWatched(animeId, position + 1)
        val lastPos = EpisodeProgress.getLastPosition(animeId, position + 1)
        val duration = EpisodeProgress.getDuration(animeId, position + 1)

        if (isWatched) {
            holder.watchedBadge.visibility = View.VISIBLE
            holder.partialBadge.visibility = View.GONE
            holder.itemView.contentDescription = "${episode.title} - Visto"
        } else if (lastPos > 0 && duration > 0) {
            val progress = ((lastPos * 100) / duration).toInt().coerceIn(1, 99)
            holder.partialBadge.visibility = View.VISIBLE
            holder.partialProgress.text = "${progress}% visto"
            holder.watchedBadge.visibility = View.GONE
            holder.itemView.contentDescription = "${episode.title} - ${progress}% visto"
        } else {
            holder.watchedBadge.visibility = View.GONE
            holder.partialBadge.visibility = View.GONE
            holder.itemView.contentDescription = episode.title
        }

        holder.itemView.setOnClickListener { onEpisodeClick(episode) }
        holder.itemView.onActionKey { onEpisodeClick(episode) }

        val isSelected = selectionMode && selectedUrls.contains(episode.url)
        holder.selectedCheck?.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.itemView.setBackgroundResource(if (isSelected) R.drawable.bg_selected else R.drawable.bg_card)
        holder.itemView.setOnClickListener { if (selectionMode) toggleSelection(episode) else onEpisodeClick(episode) }
        holder.itemView.onActionKey { if (selectionMode) toggleSelection(episode) else onEpisodeClick(episode) }
    }

    override fun getItemCount() = episodes.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        val url = holder.thumb.tag as? String
        if (url != null) {
            pendingJobs.remove(url)?.cancel()
        }
    }

    fun clearCache() {
        imageCache.evictAll()
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        scope.coroutineContext.cancelChildren()
    }

    fun destroy() {
        clearCache()
        scope.cancel()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.iv_thumbnail)
        val title: TextView = view.findViewById(R.id.tv_episode_title)
        val date: TextView = view.findViewById(R.id.tv_episode_date)
        val watchedBadge: View = view.findViewById(R.id.watched_badge)
        val partialBadge: LinearLayout = view.findViewById(R.id.partial_badge)
        val partialProgress: TextView = view.findViewById(R.id.tv_partial_progress)
        val tvEpisodeNumber: TextView = view.findViewById(R.id.tv_episode_number)
        val selectedCheck: TextView? = view.findViewById(R.id.tv_selected_check)
    }

    private suspend fun loadImage(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        DiskImageCache.loadFromNetwork(url, 800, 600)
    }
}
