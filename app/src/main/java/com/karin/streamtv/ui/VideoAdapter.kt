package com.karin.streamtv.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
    val sizeBytes: Long = 0L,
    /** True si es audio (música): sin miniatura de video, con carátula. */
    val isAudio: Boolean = false
)

class VideoAdapter(
    private var items: List<VideoItem>,
    private val context: Context,
    private val onItemClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

    private val cr: ContentResolver = context.contentResolver
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val thumbCache = object : androidx.collection.LruCache<String, Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
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
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivThumb: ImageView = view.findViewById(R.id.iv_thumbnail)
        private val tvTitle: TextView = view.findViewById(R.id.tv_title)
        private val tvFolder: TextView = view.findViewById(R.id.tv_folder)
        private val tvDuration: TextView = view.findViewById(R.id.tv_duration)

        fun bind(item: VideoItem) {
            tvTitle.text = if (item.isAudio) "♪ ${item.title}" else item.title
            tvFolder.text = item.folder
            tvDuration.text = formatDuration(item.durationMs)

            ivThumb.tag = item.uri

            val cached = thumbCache.get(item.uri)
            if (cached != null) {
                ivThumb.setImageBitmap(cached)
            } else {
                ivThumb.setImageBitmap(null)
                val uriToLoad = item.uri
                scope.launch(Dispatchers.IO) {
                    val thumb = loadThumbnail(item)
                    if (thumb != null) {
                        thumbCache.put(uriToLoad, thumb)
                        launch(Dispatchers.Main) {
                            if (ivThumb.tag == uriToLoad) {
                                ivThumb.setImageBitmap(thumb)
                            }
                        }
                    }
                }
            }

            itemView.setOnClickListener { onItemClick(item) }
            itemView.onActionKey { onItemClick(item) }
        }

        // Miniatura ligera para gama baja: primero la miniatura indexada de
        // MediaStore (barata), y solo si no hay, un único frame al segundo 1.
        // Se eliminó el barrido multi-posición + detección de frame negro
        // (hasta ~10 getFrameAtTime por celda) que provocaba jank en scroll.
        private fun loadThumbnail(item: VideoItem): Bitmap? {
            // Audio: el id es de MediaStore.Audio (no sirve el banco de
            // miniaturas de video); se intenta la carátula incrustada.
            if (item.isAudio) return loadEmbeddedArt(item)
            if (item.id > 0) {
                try {
                    val thumb = MediaStore.Video.Thumbnails.getThumbnail(
                        cr, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                    if (thumb != null) return scaleBitmap(thumb, 320)
                } catch (_: Exception) {}
            }
            var pfd: android.os.ParcelFileDescriptor? = null
            val retriever = MediaMetadataRetriever()
            try {
                val uri = Uri.parse(item.uri)
                when {
                    uri.scheme == "content" -> {
                        pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) {
                            retriever.setDataSource(pfd.fileDescriptor)
                        } else {
                            retriever.setDataSource(context, uri)
                        }
                    }
                    uri.scheme == "file" -> retriever.setDataSource(uri.path ?: item.relativePath)
                    item.relativePath.isNotBlank() && item.relativePath.startsWith("/") ->
                        retriever.setDataSource(item.relativePath)
                    else -> retriever.setDataSource(item.uri)
                }
                val frame = retriever.getFrameAtTime(
                    1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frame != null) return scaleBitmap(frame, 320)
            } catch (e: Exception) {
                android.util.Log.e("KarinThumb", "extract failed: ${e.message}")
            } finally {
                try { retriever.release() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
            }
            return null
        }

        /** Carátula incrustada (cover art) de un archivo de audio. */
        private fun loadEmbeddedArt(item: VideoItem): Bitmap? {
            var pfd: android.os.ParcelFileDescriptor? = null
            val retriever = MediaMetadataRetriever()
            try {
                val uri = Uri.parse(item.uri)
                when {
                    uri.scheme == "content" -> {
                        pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) {
                            retriever.setDataSource(pfd.fileDescriptor)
                        } else {
                            retriever.setDataSource(context, uri)
                        }
                    }
                    uri.scheme == "file" -> retriever.setDataSource(uri.path ?: item.relativePath)
                    item.relativePath.isNotBlank() && item.relativePath.startsWith("/") ->
                        retriever.setDataSource(item.relativePath)
                    else -> retriever.setDataSource(item.uri)
                }
                val art = retriever.embeddedPicture ?: return null
                val bmp = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                    ?: return null
                return scaleBitmap(bmp, 320)
            } catch (_: Exception) {
                return null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
            }
        }

        private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
            val scale = maxDim.toFloat() / maxOf(src.width, src.height).toFloat()
            if (scale >= 1f) return src
            return Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
            )
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
