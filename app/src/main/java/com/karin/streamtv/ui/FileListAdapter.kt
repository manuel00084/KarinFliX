package com.karin.streamtv.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.karinlink.SmbHttpProxy
import com.karin.streamtv.util.FileViewMode
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileListAdapter(
    private val onItemClick: (FileEntry) -> Unit,
    private val onItemLongClick: (FileEntry) -> Unit
) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

    private var items: List<FileEntry> = emptyList()
    private var viewMode: FileViewMode = FileViewMode.LARGE
    private var homeMode = false
    private var selectedPaths: Set<String> = emptySet()

    companion object {
        private const val TYPE_GRID = 0
        private const val TYPE_LIST = 1
        private const val TYPE_STORAGE = 2
        private const val TYPE_DETAIL = 3
        private const val TYPE_GRID_XL = 4
        private const val TYPE_GRID_MEDIUM = 5
        private const val TYPE_GRID_SMALL = 6
    }

    // Caché en memoria de carátulas de video (patrón TextureCache de Kodi): evita
    // re-decodificar con MediaMetadataRetriever en cada bind del recycler.
    private val thumbCache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pendingJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    fun submitList(newItems: List<FileEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setViewMode(mode: FileViewMode) {
        if (viewMode != mode) {
            viewMode = mode
            notifyDataSetChanged()
        }
    }

    fun setHomeMode(mode: Boolean) {
        if (homeMode != mode) {
            homeMode = mode
            notifyDataSetChanged()
        }
    }

    fun setSelected(paths: Set<String>) {
        selectedPaths = paths
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when {
        homeMode -> TYPE_STORAGE
        viewMode == FileViewMode.EXTRA_LARGE -> TYPE_GRID_XL
        viewMode == FileViewMode.MEDIUM -> TYPE_GRID_MEDIUM
        viewMode == FileViewMode.SMALL -> TYPE_GRID_SMALL
        viewMode == FileViewMode.LIST -> TYPE_LIST
        viewMode == FileViewMode.DETAIL -> TYPE_DETAIL
        else -> TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = when (viewType) {
            TYPE_GRID_XL -> R.layout.item_file_xl
            TYPE_GRID_MEDIUM -> R.layout.item_file_medium
            TYPE_GRID_SMALL -> R.layout.item_file_small
            TYPE_LIST -> R.layout.item_file_list
            TYPE_DETAIL -> R.layout.item_file_detail
            TYPE_STORAGE -> R.layout.item_file_storage
            else -> R.layout.item_file
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view, parent.context)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], items[position].file.absolutePath in selectedPaths)
    }

    override fun getItemCount() = items.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.pendingPath?.let { path ->
            pendingJobs.remove(path)?.cancel()
            holder.pendingPath = null
        }
    }

    fun destroy() {
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        scope.coroutineContext.cancelChildren()
        scope.cancel()
    }

    /** Extrae un frame representativo del video fuera del main thread (sin FFmpeg). */
    private suspend fun loadVideoThumb(path: String, reqW: Int, reqH: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val isNetwork = path.startsWith("http://") || path.startsWith("https://")
            if (!isNetwork) {
                val file = File(path)
                if (!file.exists() || file.length() <= 0L) return@withContext null
            }
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime() ?: return@withContext null
                    scaleToFit(frame, reqW, reqH)
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                android.util.Log.w("FileListAdapter", "No se pudo extraer miniatura: $path", e)
                null
            }
        }

    private fun scaleToFit(src: Bitmap, reqW: Int, reqH: Int): Bitmap {
        if (reqW <= 0 || reqH <= 0) return src
        val scale = minOf(reqW.toFloat() / src.width, reqH.toFloat() / src.height).coerceAtLeast(1f)
        if (scale >= 1f) return src
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (_: Exception) {
            src
        }
    }

    inner class ViewHolder(view: View, private val context: Context) : RecyclerView.ViewHolder(view) {
        private val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
        private val ivThumb: ImageView? = view.findViewById(R.id.iv_thumb)
        private val tvName: TextView = view.findViewById(R.id.tv_name)
        private val tvMeta: TextView = view.findViewById(R.id.tv_meta)
        private val ivCheck: ImageView = view.findViewById(R.id.iv_check)
        private val tvBadge: TextView? = view.findViewById(R.id.tv_badge)
        private val vProgressFill: View? = view.findViewById(R.id.v_progress_fill)
        private val tvStorageUsage: TextView? = view.findViewById(R.id.tv_storage_usage)

        var pendingPath: String? = null

        fun bind(item: FileEntry, selected: Boolean) {
            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
            itemView.onActionKey { onItemClick(item) }
            ivThumb?.visibility = View.GONE
            ivIcon.visibility = View.VISIBLE
            if (homeMode) bindStorage(item, selected) else bindFile(item, selected)
        }

        private fun bindStorage(item: FileEntry, selected: Boolean) {
            val isSmb = item.smb != null
            val remote = item.remote != null
            val accent = androidx.core.content.ContextCompat.getColor(
                context,
                when {
                    isSmb -> R.color.storage_smb
                    remote -> R.color.file_network
                    item.storagePrimary -> R.color.storage_internal
                    else -> R.color.storage_external
                }
            )
            ivIcon.setImageResource(
                when {
                    isSmb -> R.drawable.ic_network_smb
                    remote -> R.drawable.ic_network
                    item.storagePrimary -> R.drawable.ic_phone
                    else -> R.drawable.ic_sd
                }
            )
            ivIcon.imageTintList = ColorStateList.valueOf(accent)

            tvName.text = item.name
            tvMeta.text = item.storageDescription ?: "Almacenamiento"

            tvBadge?.visibility = View.GONE
            ivCheck.visibility = if (selected) View.VISIBLE else View.GONE
            itemView.isSelected = selected

            val total = item.storageTotalBytes
            val used = item.storageUsedBytes
            tvStorageUsage?.text = when {
                total <= 0L -> "Espacio no disponible"
                else -> {
                    val free = (total - used).coerceAtLeast(0L)
                    "${formatSize(used)} usados  •  ${formatSize(free)} libres  •  ${formatSize(total)} en total"
                }
            }

            val fraction = if (total > 0L) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
            vProgressFill?.let { fill ->
                fill.setBackgroundColor(accent)
                fill.post {
                    val parent = fill.parent as? View ?: return@post
                    val width = (parent.width * fraction).toInt().coerceAtLeast(0)
                    val lp = fill.layoutParams
                    lp.width = width
                    fill.layoutParams = lp
                }
            }
        }

        private fun bindFile(item: FileEntry, selected: Boolean) {
            val type = item.fileType
            ivIcon.setImageResource(type.iconRes)
            ivIcon.imageTintList = ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, type.colorRes)
            )

            tvName.text = item.name

            tvMeta.visibility = if (viewMode == FileViewMode.SMALL) View.GONE else View.VISIBLE
            tvMeta.text = if (item.isDirectory) {
                val count = item.itemCount
                if (count > 0) "$count elementos" else "Vacía"
            } else {
                metaForFile(item, viewMode == FileViewMode.DETAIL)
            }

            tvBadge?.let {
                when {
                    item.isAnimeBadge -> {
                        it.visibility = View.VISIBLE
                        it.text = "Anime"
                    }
                    item.isDirectory -> {
                        it.visibility = View.VISIBLE
                        it.text = item.itemCount.toString()
                    }
                    else -> it.visibility = View.GONE
                }
            }

            ivCheck.visibility = if (selected) View.VISIBLE else View.GONE
            itemView.isSelected = selected

            if (type == FileEntry.FileType.VIDEO) {
                loadThumb(item)
            }
        }

        private fun loadThumb(item: FileEntry) {
            val path = when {
                item.remote != null -> item.remote.streamUrl(item.remote.remotePath)
                item.smb != null -> SmbHttpProxy.proxyUrl(item.smb)
                else -> item.file.absolutePath
            }
            val cached = thumbCache.get(path)
            if (cached != null) {
                ivThumb?.setImageBitmap(cached)
                ivThumb?.visibility = View.VISIBLE
                ivIcon.visibility = View.GONE
                return
            }
            pendingPath?.let { pendingJobs.remove(it)?.cancel() }
            pendingPath = path
            pendingJobs[path] = scope.launch {
                val bmp = loadVideoThumb(path, 320, 200)
                if (bmp != null) {
                    thumbCache.put(path, bmp)
                    if (isActive && adapterPosition != RecyclerView.NO_POSITION &&
                        pendingPath == path
                    ) {
                        ivThumb?.setImageBitmap(bmp)
                        ivThumb?.visibility = View.VISIBLE
                        ivIcon.visibility = View.GONE
                    }
                }
                if (pendingPath == path) pendingPath = null
                pendingJobs.remove(path)
            }
        }

        private fun metaForFile(item: FileEntry, detail: Boolean = false): String {
            val parts = mutableListOf<String>()
            if (item.sizeBytes > 0L) parts.add(formatSize(item.sizeBytes))
            if (item.lastModified > 0L) parts.add(formatDate(item.lastModified))
            if (detail && item.extension.isNotBlank()) parts.add(item.extension.uppercase())
            return parts.joinToString("  •  ").ifEmpty { "Archivo" }
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return ""
            val mb = bytes / 1048576.0
            val gb = mb / 1024.0
            return when {
                gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
                mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
                else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            }
        }

        private fun formatDate(ms: Long): String {
            return try {
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))
            } catch (_: Exception) {
                ""
            }
        }
    }
}