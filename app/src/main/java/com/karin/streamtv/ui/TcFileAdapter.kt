package com.karin.streamtv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TcFileAdapter(
    private val onItemClick: (FileEntry) -> Unit,
    private val onItemLongClick: (FileEntry) -> Unit
) : RecyclerView.Adapter<TcFileAdapter.VH>() {

    private val items = mutableListOf<FileEntry>()
    private val selected = mutableSetOf<String>()
    var searchMode = false
    var basePath: String = ""

    fun submit(list: List<FileEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun getSelected(): List<FileEntry> = items.filter { selected.contains(keyOf(it)) }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

    fun selectionCount(): Int = selected.size

    private fun keyOf(e: FileEntry): String =
        if (e.isDirectory) "d:" + e.file.absolutePath else "f:" + e.file.absolutePath

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_icon)
        val name: TextView = view.findViewById(R.id.tv_name)
        val meta: TextView = view.findViewById(R.id.tv_meta)
        val check: ImageView = view.findViewById(R.id.iv_check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tc_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val type = e.fileType
        holder.icon.setImageResource(type.iconRes)
        holder.icon.setColorFilter(holder.itemView.context.getColor(type.colorRes))
        holder.name.text = e.name

        holder.meta.text = when {
            searchMode && basePath.isNotEmpty() -> {
                val fullPath = e.file.absolutePath
                val relPath = if (fullPath.startsWith(basePath)) {
                    fullPath.removePrefix(basePath).trimStart(File.separatorChar)
                } else {
                    fullPath
                }
                if (e.isDirectory) "Carpeta  •  $relPath" else {
                    val size = if (e.sizeBytes > 0) formatSize(e.sizeBytes) + "  •  " else ""
                    "$size$relPath"
                }
            }
            e.isDirectory -> {
                val count = if (e.itemCount > 0) "$e.itemCount elementos" else "Carpeta"
                count
            }
            e.sizeBytes > 0 -> formatSize(e.sizeBytes) + "  •  " + formatDate(e.lastModified)
            else -> formatDate(e.lastModified)
        }

        val isSel = selected.contains(keyOf(e))
        holder.check.visibility = if (isSel) View.VISIBLE else View.GONE
        holder.itemView.isActivated = isSel

        holder.itemView.setOnClickListener {
            if (selected.isNotEmpty()) {
                toggle(holder, e)
            } else {
                onItemClick(e)
            }
        }
        holder.itemView.setOnLongClickListener {
            toggle(holder, e)
            true
        }
    }

    private fun toggle(holder: VH, e: FileEntry) {
        val k = keyOf(e)
        if (selected.contains(k)) selected.remove(k) else selected.add(k)
        val isSel = selected.contains(k)
        holder.check.visibility = if (isSel) View.VISIBLE else View.GONE
        holder.itemView.isActivated = isSel
    }

    override fun getItemCount(): Int = items.size

    companion object {
        fun formatSize(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var size = bytes.toDouble()
            var i = 0
            while (size >= 1024 && i < units.lastIndex) {
                size /= 1024
                i++
            }
            return "%.1f %s".format(Locale.US, size, units[i])
        }

        fun formatDate(ts: Long): String {
            if (ts <= 0) return ""
            return SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(ts))
        }
    }
}
