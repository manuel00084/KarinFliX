package com.karin.streamtv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.util.onActionKey
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DualPaneFileAdapter(
    private val onItemClick: (FileEntry) -> Unit,
    private val onItemLongClick: (FileEntry) -> Unit
) : RecyclerView.Adapter<DualPaneFileAdapter.VH>() {

    private val items = mutableListOf<FileEntry>()
    private val selected = mutableSetOf<String>()
    var searchMode = false
    var basePath: String = ""

    fun submit(list: List<FileEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun getSelected(): List<FileEntry> =
        items.filter { !it.isParentMarker && selected.contains(keyOf(it)) }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

    fun selectionCount(): Int = selected.size

    fun getItemAt(position: Int): FileEntry? =
        if (position in items.indices) items[position] else null

    /** Marca todo menos el ".." (como "Seleccionar todo" de Kodi). */
    fun selectAll() {
        items.forEach { if (!it.isParentMarker) selected.add(keyOf(it)) }
        notifyDataSetChanged()
    }

    /** Asegura un elemento seleccionado (el menú contextual actúa sobre él
     *  si no había selección previa). Ignora el "..". */
    fun ensureSelected(e: FileEntry) {
        if (e.isParentMarker) return
        val k = keyOf(e)
        if (!selected.contains(k)) {
            selected.add(k)
            val idx = items.indexOfFirst { keyOf(it) == k }
            if (idx >= 0) notifyItemChanged(idx) else notifyDataSetChanged()
        }
    }

    /** Alterna la selección por posición (para mando: tecla MENÚ sobre el
     *  item enfocado). El click largo táctil sigue funcionando igual. */
    fun toggleSelectionAt(position: Int): Boolean {
        if (position !in items.indices) return false
        if (items[position].isParentMarker) return false
        val k = keyOf(items[position])
        if (selected.contains(k)) selected.remove(k) else selected.add(k)
        notifyItemChanged(position)
        return selected.contains(k)
    }

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
            .inflate(R.layout.item_dual_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]

        // ".." para retroceder (Kodi): flecha + ruta destino, sin check.
        if (e.isParentMarker) {
            val ctx = holder.itemView.context
            holder.icon.setImageResource(R.drawable.ic_arrow_back)
            holder.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.accent))
            holder.name.text = ".."
            holder.name.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            holder.meta.text = if (!e.isNetwork) e.file.absolutePath else "Carpeta superior"
            holder.check.visibility = View.GONE
            holder.itemView.isActivated = false
            holder.itemView.setOnClickListener { onItemClick(e) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(e)
                true
            }
            holder.itemView.onActionKey { onItemClick(e) }
            return
        }

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
                val count = if (e.itemCount > 0) "${e.itemCount} elementos" else "Carpeta"
                count
            }
            e.sizeBytes > 0 -> formatSize(e.sizeBytes) + "  •  " + formatDate(e.lastModified)
            else -> formatDate(e.lastModified)
        }

        val isSel = selected.contains(keyOf(e))
        holder.check.visibility = if (isSel) View.VISIBLE else View.GONE
        holder.itemView.isActivated = isSel
        // Kodi muestra la selección en verde: mismo código de color para que
        // se lea a distancia en la TV.
        holder.name.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (isSel) R.color.success else R.color.text_primary
            )
        )

        holder.itemView.setOnClickListener {
            if (selected.isNotEmpty()) {
                toggleSelection(holder)
            } else {
                onItemClick(e)
            }
        }
        holder.itemView.setOnLongClickListener {
            // Click largo = menú contextual (Kodi). La selección se hace
            // desde el menú ("Seleccionar") o con taps si ya hay selección.
            onItemLongClick(e)
            true
        }
        // Sin esto, OK del mando no hace nada en la lista (el click solo
        // llegaba por táctil). Misma lógica que el tap.
        holder.itemView.onActionKey {
            if (selected.isNotEmpty()) {
                toggleSelection(holder)
            } else {
                onItemClick(e)
            }
        }
    }

    /** Alterna por holder (tap / OK con selección activa): una sola vía. */
    private fun toggleSelection(holder: VH) {
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) toggleSelectionAt(pos)
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