package com.karin.streamtv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.util.DiskImageCache
import com.karin.streamtv.util.SearchManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchResultsAdapter(
    private val onClick: (SearchManager.SearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {

    private val results = mutableListOf<SearchManager.SearchResult>()
    private val imageCache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pendingJobs = mutableMapOf<String, Job>()

    private val siteColors = mapOf(
        "Cuevana3" to Color.parseColor("#E53935"),
        "JKAnime" to Color.parseColor("#1E88E5"),
        "LatAnime" to Color.parseColor("#43A047"),
        "DoramasYT" to Color.parseColor("#00BCD4"),
        "MundoDonghua" to Color.parseColor("#8E24AA"),
        "RetroTVE" to Color.parseColor("#FF9800"),
        "LaCartoons" to Color.parseColor("#E91E63"),
        "FrikiSeries" to Color.parseColor("#1B5E20"),
    )

    private val placeholderCache = LruCache<String, Bitmap>(8)

    fun submitList(newResults: List<SearchManager.SearchResult>) {
        results.clear()
        results.addAll(newResults)
        notifyDataSetChanged()
    }

    fun clearCache() {
        imageCache.evictAll()
        placeholderCache.evictAll()
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(results[position])
    }

    override fun getItemCount() = results.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val poster: ImageView = view.findViewById(R.id.iv_search_poster)
        private val title: TextView = view.findViewById(R.id.tv_search_title)
        private val siteBadge: TextView = view.findViewById(R.id.tv_search_site_badge)
        private val episodeNum: TextView = view.findViewById(R.id.tv_search_episode_num)
        private val date: TextView = view.findViewById(R.id.tv_search_date)

        fun bind(result: SearchManager.SearchResult) {
            title.text = result.title

            val siteName = result.site
            siteBadge.text = siteName
            val color = siteColors[siteName] ?: Color.parseColor("#555555")
            siteBadge.setBackgroundColor(color)

            if (result.episodeNum.isNotBlank()) {
                episodeNum.text = "Ep. ${result.episodeNum}"
                episodeNum.visibility = View.VISIBLE
            } else {
                episodeNum.visibility = View.GONE
            }

            if (result.date.isNotBlank()) {
                date.text = result.date
                date.visibility = View.VISIBLE
            } else {
                date.visibility = View.GONE
            }

            if (result.posterUrl.isNotBlank()) {
                val cached = imageCache.get(result.posterUrl)
                if (cached != null) {
                    poster.setImageBitmap(cached)
                } else {
                    poster.setImageBitmap(createPlaceholder(siteName))
                    val existingJob = pendingJobs[result.posterUrl]
                    if (existingJob == null || existingJob.isCancelled) {
                        pendingJobs[result.posterUrl] = GlobalScope.launch(Dispatchers.Main) {
                            val bmp = loadImage(result.posterUrl)
                            if (bmp != null) {
                                imageCache.put(result.posterUrl, bmp)
                                if (adapterPosition != RecyclerView.NO_POSITION) {
                                    notifyItemChanged(adapterPosition)
                                }
                            }
                            pendingJobs.remove(result.posterUrl)
                        }
                    }
                }
            } else {
                poster.setImageBitmap(createPlaceholder(siteName))
            }

            itemView.setOnClickListener { onClick(result) }
        }
    }

    private fun createPlaceholder(siteName: String): Bitmap {
        val cached = placeholderCache.get(siteName)
        if (cached != null) return cached
        val size = 80
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val color = siteColors[siteName] ?: Color.parseColor("#555555")
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.color = Color.WHITE
        p.textSize = 28f
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        val initials = siteName.take(2)
        c.drawText(initials, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
        placeholderCache.put(siteName, bmp)
        return bmp
    }

    private suspend fun loadImage(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        DiskImageCache.loadFromNetwork(url, 300, 200)
    }
}
